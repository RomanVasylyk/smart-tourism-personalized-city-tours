import itertools
import math
from datetime import datetime

from app.schemas.route import RouteGenerateRequest
from app.services.feedback_stats import PlannerFeedbackProfile
from app.services.route_planning.engine import RoutePlannerEngine
from app.services.route_planning.local_search import improve_order
from app.services.route_planning.models import CandidatePoi
from app.services.routing_service import RoutingLeg


def _line_path_cost(positions):
    def cost(order):
        previous = 0.0
        total = 0.0
        for label in order:
            total += abs(positions[label] - previous)
            previous = positions[label]
        return total

    return cost


def test_improve_order_reaches_optimum_on_small_instance():
    positions = {"a": 1.0, "b": 2.0, "c": 5.0, "d": 6.0}
    cost = _line_path_cost(positions)
    initial = ["b", "d", "a", "c"]

    improved = improve_order(initial, cost=cost, max_passes=50, max_evaluations=10000)

    optimum = min(cost(list(order)) for order in itertools.permutations(initial))
    assert cost(improved) == optimum
    assert sorted(improved) == sorted(initial)


def test_improve_order_never_worsens_cost():
    positions = {"a": 4.0, "b": 1.0, "c": 7.0, "d": 2.0, "e": 9.0}
    cost = _line_path_cost(positions)
    initial = ["a", "b", "c", "d", "e"]

    improved = improve_order(initial, cost=cost, max_passes=20, max_evaluations=10000)

    assert cost(improved) <= cost(initial)
    assert sorted(improved) == sorted(initial)


def test_improve_order_rejects_infeasible_orders():
    positions = {"a": 1.0, "b": 2.0, "c": 5.0, "d": 6.0}
    base_cost = _line_path_cost(positions)

    def cost(order):
        # Simulate a time window: "d" must not be visited before "a".
        if order.index("d") < order.index("a"):
            return None
        return base_cost(order)

    initial = ["a", "b", "d", "c"]
    improved = improve_order(initial, cost=cost, max_passes=50, max_evaluations=10000)

    assert cost(improved) is not None
    assert improved.index("a") < improved.index("d")


def test_improve_order_keeps_short_routes_unchanged():
    cost = _line_path_cost({"a": 3.0, "b": 1.0})
    assert improve_order(["a", "b"], cost=cost) == ["a", "b"]


class DistanceRoutingService:
    def route_between(self, start, end, pace):
        distance = math.hypot(end.lat - start.lat, end.lon - start.lon)
        return RoutingLeg(
            duration_seconds=max(60.0, distance * 600.0),
            distance_meters=distance * 1000.0,
            geometry=[
                {"lat": start.lat, "lon": start.lon},
                {"lat": end.lat, "lon": end.lon},
            ],
            source="test_distance",
        )


def _candidate(poi_id, lon):
    return CandidatePoi.from_row(
        {
            "id": poi_id,
            "name": f"P{poi_id}",
            "category": "attraction",
            "lat": 0.0,
            "lon": lon,
            "opening_hours_raw": None,
            "visit_duration_min": 10,
            "base_score": 0.9,
            "short_description": None,
            "wikipedia_url": None,
        }
    )


def _engine(candidates):
    request = RouteGenerateRequest(
        city="nitra",
        start_lat=0.0,
        start_lon=0.0,
        available_minutes=600,
        interests=[],
        pace="normal",
        return_to_start=False,
        respect_opening_hours=False,
    )
    return RoutePlannerEngine(
        request=request,
        start_dt=datetime(2026, 4, 19, 10, 0),
        city_profile={},
        effective_transport_mode="walk",
        feedback_profile=PlannerFeedbackProfile(),
        candidates=candidates,
        routing_service=DistanceRoutingService(),
    )


def test_apply_local_search_untangles_zigzag_order():
    a, b, c, d = _candidate(1, 1.0), _candidate(2, 2.0), _candidate(3, 3.0), _candidate(4, 4.0)
    engine = _engine([a, b, c, d])

    zigzag = [b, d, a, c]
    optimized = engine.apply_local_search(zigzag)

    zigzag_cost = engine.evaluate_order_travel_minutes(zigzag)
    optimized_cost = engine.evaluate_order_travel_minutes(optimized)
    sorted_cost = engine.evaluate_order_travel_minutes([a, b, c, d])

    assert optimized_cost < zigzag_cost
    assert optimized_cost == sorted_cost
    assert sorted(poi.id for poi in optimized) == [1, 2, 3, 4]


def test_local_search_can_be_disabled_via_city_profile():
    a, b, c, d = _candidate(1, 1.0), _candidate(2, 2.0), _candidate(3, 3.0), _candidate(4, 4.0)
    engine = _engine([a, b, c, d])
    engine.local_search_enabled = False

    zigzag = [b, d, a, c]
    assert engine.apply_local_search(zigzag) == zigzag

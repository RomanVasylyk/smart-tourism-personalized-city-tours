import math
from datetime import datetime

from app.schemas.route import RouteGenerateRequest
from app.services.feedback_stats import PlannerFeedbackProfile
from app.services.route_planning.candidate_evaluator import RouteCandidateEvaluator
from app.services.route_planning.models import CandidatePoi
from app.services.routing_service import RoutePoint, RoutingLeg, RoutingService


class TableRoutingService:
    def __init__(self):
        self.duration_matrix_calls = 0
        self.route_between_calls = 0

    def duration_matrix(self, sources, destinations, pace, profile=None):
        self.duration_matrix_calls += 1
        return [
            [max(60.0, math.hypot(dest.lat - source.lat, dest.lon - source.lon) * 600.0) for dest in destinations]
            for source in sources
        ]

    def route_between(self, start, end, pace):
        self.route_between_calls += 1
        return RoutingLeg(
            duration_seconds=600.0,
            distance_meters=800.0,
            geometry=[{"lat": start.lat, "lon": start.lon}, {"lat": end.lat, "lon": end.lon}],
            source="route",
        )


class RouteOnlyService:
    def __init__(self):
        self.route_between_calls = 0

    def route_between(self, start, end, pace):
        self.route_between_calls += 1
        return RoutingLeg(
            duration_seconds=600.0,
            distance_meters=800.0,
            geometry=[{"lat": start.lat, "lon": start.lon}, {"lat": end.lat, "lon": end.lon}],
            source="route",
        )


def _candidate(poi_id, lat, lon):
    return CandidatePoi.from_row(
        {
            "id": poi_id,
            "name": f"P{poi_id}",
            "category": "attraction",
            "lat": lat,
            "lon": lon,
            "opening_hours_raw": None,
            "visit_duration_min": 10,
            "base_score": 0.9,
            "short_description": None,
            "wikipedia_url": None,
        }
    )


def _evaluator(routing_service, *, return_to_start=False):
    request = RouteGenerateRequest(
        city="nitra",
        start_lat=0.0,
        start_lon=0.0,
        available_minutes=600,
        interests=[],
        pace="normal",
        return_to_start=return_to_start,
        respect_opening_hours=False,
    )
    return RouteCandidateEvaluator(
        request=request,
        city_profile={},
        effective_transport_mode="walk",
        feedback_profile=PlannerFeedbackProfile(),
        routing_service=routing_service,
        start_point=RoutePoint(lat=0.0, lon=0.0),
        preferred_poi_ids=set(),
    )


def test_best_candidate_uses_table_and_skips_pairwise_routing():
    service = TableRoutingService()
    evaluator = _evaluator(service)
    candidates = [_candidate(1, 0.0, 3.0), _candidate(2, 0.0, 1.0), _candidate(3, 0.0, 2.0)]

    best = evaluator.best_candidate(
        evaluation_candidates=candidates,
        forced_preferred_poi=None,
        current_point=RoutePoint(lat=0.0, lon=0.0),
        elapsed_minutes=0,
        departure_dt=datetime(2026, 4, 19, 10, 0),
        category_counts={},
    )

    assert best is not None
    assert best.poi.id == 2
    assert service.duration_matrix_calls == 1
    assert service.route_between_calls == 0


def test_best_candidate_batches_return_leg_durations():
    service = TableRoutingService()
    evaluator = _evaluator(service, return_to_start=True)
    candidates = [_candidate(1, 0.0, 1.0), _candidate(2, 0.0, 2.0), _candidate(3, 0.0, 3.0)]

    evaluator.best_candidate(
        evaluation_candidates=candidates,
        forced_preferred_poi=None,
        current_point=RoutePoint(lat=0.0, lon=0.0),
        elapsed_minutes=0,
        departure_dt=datetime(2026, 4, 19, 10, 0),
        category_counts={},
    )

    assert service.duration_matrix_calls == 2
    assert service.route_between_calls == 0


def test_best_candidate_falls_back_without_table_support():
    service = RouteOnlyService()
    evaluator = _evaluator(service)
    candidates = [_candidate(1, 0.0, 1.0), _candidate(2, 0.0, 2.0)]

    best = evaluator.best_candidate(
        evaluation_candidates=candidates,
        forced_preferred_poi=None,
        current_point=RoutePoint(lat=0.0, lon=0.0),
        elapsed_minutes=0,
        departure_dt=datetime(2026, 4, 19, 10, 0),
        category_counts={},
    )

    assert best is not None
    assert service.route_between_calls == 2


def test_duration_matrix_disabled_returns_none():
    service = RoutingService(base_url="http://localhost:5000", enabled=True, table_enabled=False)
    assert service.duration_matrix([RoutePoint(0.0, 0.0)], [RoutePoint(0.0, 1.0)], "normal") is None


def test_duration_matrix_caches_cells_across_calls(monkeypatch):
    RoutingService._shared_duration_cache.clear()
    service = RoutingService(base_url="http://localhost:5000", enabled=True, table_enabled=True)
    fetch_calls = {"count": 0}

    def fake_fetch(sources, destinations, profile):
        fetch_calls["count"] += 1
        return [[123.0 for _ in destinations] for _ in sources]

    monkeypatch.setattr(service, "_fetch_osrm_table", fake_fetch)
    sources = [RoutePoint(0.0, 0.0)]
    destinations = [RoutePoint(0.0, 1.0)]

    first = service.duration_matrix(sources, destinations, "normal")
    second = service.duration_matrix(sources, destinations, "normal")

    assert fetch_calls["count"] == 1
    assert first == second
    RoutingService._shared_duration_cache.clear()

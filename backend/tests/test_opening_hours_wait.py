import math
from datetime import datetime

from app.schemas.route import RouteGenerateRequest
from app.services.feedback_stats import PlannerFeedbackProfile
from app.services.route_planner import cap_available_minutes
from app.services.route_planning.engine import RoutePlannerEngine
from app.services.route_planning.models import CandidatePoi
from app.services.route_planning.opening_hours import resolve_visit_wait, wait_minutes_until_open
from app.services.routing_service import RoutingLeg

ARRIVAL = datetime(2026, 6, 24, 10, 0)
DAYS = ["Mo", "Tu", "We", "Th", "Fr", "Sa", "Su"]
THIS_DAY = DAYS[ARRIVAL.weekday()]
OTHER_DAY = DAYS[(ARRIVAL.weekday() + 1) % 7]


def test_wait_open_now_returns_zero():
    assert wait_minutes_until_open(f"{THIS_DAY} 09:00-18:00", ARRIVAL, 30) == 0


def test_wait_until_opening_soon():
    assert wait_minutes_until_open(f"{THIS_DAY} 10:15-18:00", ARRIVAL, 30) == 15


def test_wait_none_when_closed_all_day():
    assert wait_minutes_until_open(f"{OTHER_DAY} 09:00-18:00", ARRIVAL, 30) is None


def test_wait_none_when_visit_does_not_fit():
    assert wait_minutes_until_open(f"{THIS_DAY} 09:00-10:10", ARRIVAL, 30) is None


def test_wait_no_schedule_returns_zero():
    assert wait_minutes_until_open(None, ARRIVAL, 30) == 0


def test_resolve_respect_disabled_returns_zero():
    value = resolve_visit_wait(
        f"{THIS_DAY} 12:00-18:00", ARRIVAL, 30, respect_opening_hours=False, is_forced=False, max_wait_minutes=20
    )
    assert value == 0


def test_resolve_non_forced_over_limit_skips():
    value = resolve_visit_wait(
        f"{THIS_DAY} 10:30-18:00", ARRIVAL, 30, respect_opening_hours=True, is_forced=False, max_wait_minutes=20
    )
    assert value is None


def test_resolve_forced_over_limit_bypasses():
    value = resolve_visit_wait(
        f"{THIS_DAY} 10:30-18:00", ARRIVAL, 30, respect_opening_hours=True, is_forced=True, max_wait_minutes=20
    )
    assert value == 0


def test_resolve_within_limit_returns_wait():
    value = resolve_visit_wait(
        f"{THIS_DAY} 10:15-18:00", ARRIVAL, 30, respect_opening_hours=True, is_forced=False, max_wait_minutes=20
    )
    assert value == 15


class DistanceRoutingService:
    def route_between(self, start, end, pace):
        distance = math.hypot(end.lat - start.lat, end.lon - start.lon)
        return RoutingLeg(
            duration_seconds=max(60.0, distance * 600.0),
            distance_meters=distance * 1000.0,
            geometry=[{"lat": start.lat, "lon": start.lon}, {"lat": end.lat, "lon": end.lon}],
            source="osrm",
        )


def _candidate(poi_id, lon, opening=None, visit=20):
    return CandidatePoi.from_row(
        {
            "id": poi_id,
            "name": f"P{poi_id}",
            "category": "attraction",
            "lat": 0.0,
            "lon": lon,
            "opening_hours_raw": opening,
            "visit_duration_min": visit,
            "base_score": 0.9,
            "short_description": None,
            "wikipedia_url": None,
        }
    )


def _engine(candidates, *, preferred=None, available=240, respect=True, return_to_start=False):
    request = RouteGenerateRequest(
        city="nitra",
        start_lat=0.0,
        start_lon=0.0,
        available_minutes=available,
        interests=[],
        pace="normal",
        return_to_start=return_to_start,
        respect_opening_hours=respect,
        preferred_poi_ids=preferred or [],
    )
    return RoutePlannerEngine(
        request=request,
        start_dt=datetime(2026, 6, 24, 10, 0),
        city_profile={},
        effective_transport_mode="walk",
        feedback_profile=PlannerFeedbackProfile(),
        candidates=candidates,
        routing_service=DistanceRoutingService(),
    )


def test_route_waits_for_poi_to_open():
    engine = _engine([_candidate(1, 0.5, opening="Mo-Su 10:15-18:00", visit=20)])
    result = engine.plan()
    item = result.route_items[0]
    assert item["poi_id"] == 1
    assert item["wait_minutes"] == 10
    assert result.total_wait_minutes == 10
    assert item["arrival_after_min"] == 15


def test_route_skips_poi_that_opens_too_late():
    engine = _engine([_candidate(1, 0.5, opening="Mo-Su 11:00-18:00", visit=20)])
    result = engine.plan()
    assert result.route_items == []


def test_route_includes_seasonal_poi_in_season():
    opening = "May-Jun Mo-Fr 09:00-18:45; May-Jun Sa,Su 09:00-19:00; Jul-Aug Mo-Su 09:00-19:00"
    engine = _engine([_candidate(1, 0.5, opening=opening, visit=20)])
    result = engine.plan()
    assert [item["poi_id"] for item in result.route_items] == [1]


def test_route_skips_seasonal_poi_out_of_season():
    engine = _engine([_candidate(1, 0.5, opening="Jul-Aug Mo-Su 09:00-19:00", visit=20)])
    result = engine.plan()
    assert result.route_items == []


def test_required_poi_that_opens_late_is_included_and_flagged():
    engine = _engine([_candidate(1, 0.5, opening="Mo-Su 11:00-18:00", visit=20)], preferred=[1])
    result = engine.plan()
    assert [item["poi_id"] for item in result.route_items] == [1]
    assert result.closed_required_poi_ids == [1]
    assert result.route_items[0]["wait_minutes"] == 0


def test_required_pois_over_budget_is_flagged():
    candidates = [_candidate(1, 1.0, visit=20), _candidate(2, 2.0, visit=20)]
    engine = _engine(candidates, preferred=[1, 2], available=30, respect=False)
    result = engine.plan()
    assert [item["poi_id"] for item in result.route_items] == [1, 2]
    assert result.required_pois_over_budget is True
    assert result.used_minutes > result.available_minutes


def test_cap_available_minutes_enforces_city_limit():
    request = RouteGenerateRequest(city="nitra", start_lat=0.0, start_lon=0.0, available_minutes=600, interests=[])
    capped = cap_available_minutes(request, {"routing_limits": {"max_available_minutes": 300}})
    assert capped.available_minutes == 300
    untouched = cap_available_minutes(request, {"routing_limits": {"max_available_minutes": 700}})
    assert untouched.available_minutes == 600
    no_limit = cap_available_minutes(request, {})
    assert no_limit.available_minutes == 600


def test_timeline_rebuild_drops_closed_optional_poi():
    closed = _candidate(1, 0.5, opening="Mo-Su 11:00-18:00", visit=20)
    engine = _engine([closed])
    timeline = engine.build_timeline_for_order([closed])
    assert timeline.route_items == []


def test_timeline_rebuild_keeps_closed_required_poi():
    closed = _candidate(1, 0.5, opening="Mo-Su 11:00-18:00", visit=20)
    engine = _engine([closed], preferred=[1])
    timeline = engine.build_timeline_for_order([closed])
    assert [item["poi_id"] for item in timeline.route_items] == [1]


def test_build_within_budget_trims_optional_stops():
    candidates = [_candidate(1, 0.5, visit=20), _candidate(2, 1.5, visit=20), _candidate(3, 3.0, visit=20)]
    engine = _engine(candidates, available=60, respect=False)
    timeline = engine.build_within_budget(candidates)
    assert timeline.elapsed_minutes <= 60
    assert [item["poi_id"] for item in timeline.route_items] == [1, 2]


def test_build_within_budget_keeps_required_over_budget():
    candidates = [_candidate(1, 1.0, visit=20), _candidate(2, 2.0, visit=20)]
    engine = _engine(candidates, preferred=[1, 2], available=30, respect=False)
    timeline = engine.build_within_budget(candidates)
    assert [item["poi_id"] for item in timeline.route_items] == [1, 2]


def test_plan_never_exceeds_budget_without_required_pois():
    candidates = [_candidate(poi_id, poi_id * 0.5, visit=20) for poi_id in range(1, 6)]
    engine = _engine(candidates, available=60, respect=False)
    result = engine.plan()
    assert result.used_minutes <= result.available_minutes
    assert result.required_pois_over_budget is False


class OptimisticTableRoutingService(DistanceRoutingService):
    def duration_matrix(self, sources, destinations, pace, profile=None):
        return [[60.0 for _ in destinations] for _ in sources]


def test_plan_respects_budget_when_table_underestimates_travel():
    candidates = [_candidate(poi_id, poi_id * 0.5, visit=20) for poi_id in range(1, 6)]
    engine = _engine(candidates, available=90, respect=False)
    engine.evaluator.routing_service = OptimisticTableRoutingService()
    engine.routing_service = OptimisticTableRoutingService()
    result = engine.plan()
    assert result.used_minutes <= result.available_minutes
    assert result.required_pois_over_budget is False

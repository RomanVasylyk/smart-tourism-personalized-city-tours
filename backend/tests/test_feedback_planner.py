from app.services.feedback_stats import PlannerFeedbackProfile, PlannerFeedbackStats
from app.services.route_planner import RouteGenerateRequest, generate_route
from app.services.transport_planner import TravelPlan, TravelSegment


def make_candidate(
    poi_id: int,
    name: str,
    category: str,
    lat: float,
    lon: float,
    base_score: float,
    visit_duration_min: int,
    **feedback_fields,
):
    return {
        "id": poi_id,
        "name": name,
        "category": category,
        "lat": lat,
        "lon": lon,
        "opening_hours_raw": None,
        "visit_duration_min": visit_duration_min,
        "base_score": base_score,
        "wikipedia_url": None,
        **feedback_fields,
    }


def make_travel_plan(mode: str, minutes: int, distance_meters: float, source: str = "test") -> TravelPlan:
    geometry = [
        {"lat": 48.0, "lon": 18.0},
        {"lat": 48.0 + (minutes / 10_000), "lon": 18.0 + (minutes / 10_000)},
    ]
    segment = TravelSegment(
        mode=mode,
        duration_seconds=minutes * 60,
        distance_meters=distance_meters,
        geometry=geometry,
        source=source,
    )
    return TravelPlan(
        mode=mode,
        duration_seconds=minutes * 60,
        distance_meters=distance_meters,
        geometry=geometry,
        source=source,
        segments=[segment],
    )


def run_route(monkeypatch, *, candidates: list[dict], profile: PlannerFeedbackProfile, travel_plans: dict[int, TravelPlan], transport_enabled: bool = False) -> dict:
    plan_by_coords = {(candidate["lat"], candidate["lon"]): travel_plans[candidate["id"]] for candidate in candidates}

    monkeypatch.setattr("app.services.route_planner.get_route_candidates", lambda request: candidates)
    monkeypatch.setattr("app.services.route_planner.load_planner_feedback_profile", lambda city, mode: profile)
    monkeypatch.setattr(
        "app.services.route_planner.city_profile_by_token",
        lambda city: {"transport": {"mhd_enabled": transport_enabled}},
    )
    monkeypatch.setattr("app.services.route_planner.get_routing_service", lambda: object())

    def fake_plan_travel(start, end, pace, routing_service, city_profile, transport_mode, departure_dt=None):
        return plan_by_coords[(end.lat, end.lon)]

    monkeypatch.setattr("app.services.route_planner.plan_travel", fake_plan_travel)

    request = RouteGenerateRequest(
        city="nitra",
        start_lat=48.3076,
        start_lon=18.0845,
        available_minutes=45,
        interests=[],
        pace="normal",
        return_to_start=False,
        start_datetime="2026-04-27T10:00",
        respect_opening_hours=True,
        transport_mode="walk_or_mhd" if transport_enabled else "walk",
    )
    return generate_route(request)


def test_feedback_recompute_can_promote_highly_liked_poi(monkeypatch):
    neutral_profile = PlannerFeedbackProfile()
    liked_poi = make_candidate(
        102,
        "Beloved Gallery",
        "museum",
        48.3200,
        18.0900,
        0.78,
        30,
        poi_feedback_average_rating=4.8,
        poi_feedback_completion_rate=1.0,
        poi_feedback_skip_rate=0.0,
        poi_feedback_interesting_pois_rate=1.0,
        poi_feedback_session_count=7,
        poi_feedback_feedback_count=7,
        poi_feedback_planned_count=7,
        poi_feedback_visited_count=7,
        poi_feedback_skipped_count=0,
        poi_feedback_completed_session_count=7,
        category_feedback_average_rating=4.7,
        category_feedback_completion_rate=0.95,
        category_feedback_skip_rate=0.0,
        category_feedback_interesting_pois_rate=0.9,
        category_feedback_session_count=18,
        category_feedback_feedback_count=18,
        category_feedback_planned_count=18,
        category_feedback_visited_count=17,
        category_feedback_skipped_count=0,
        category_feedback_completed_session_count=17,
    )
    candidates_before = [
        make_candidate(101, "Castle", "historical_site", 48.3172, 18.0861, 0.95, 30),
        make_candidate(102, "Beloved Gallery", "museum", 48.3200, 18.0900, 0.78, 30),
    ]
    candidates_after = [
        make_candidate(101, "Castle", "historical_site", 48.3172, 18.0861, 0.95, 30),
        liked_poi,
    ]
    travel_plans = {
        101: make_travel_plan("walk", 8, 700),
        102: make_travel_plan("walk", 8, 720),
    }

    before = run_route(
        monkeypatch,
        candidates=candidates_before,
        profile=neutral_profile,
        travel_plans=travel_plans,
    )
    after = run_route(
        monkeypatch,
        candidates=candidates_after,
        profile=neutral_profile,
        travel_plans=travel_plans,
    )

    assert before["route"][0]["poi_id"] == 101
    assert after["route"][0]["poi_id"] == 102
    assert after["route"][0]["planner_score_breakdown"]["poi_bonus"] > 0
    assert after["route"][0]["planner_score_breakdown"]["category_bonus"] > 0


def test_feedback_recompute_can_reduce_long_walking_preference(monkeypatch):
    candidates = [
        make_candidate(201, "Far Panorama", "viewpoint", 48.3500, 18.1200, 1.10, 20),
        make_candidate(202, "Close Museum", "museum", 48.3150, 18.0870, 0.60, 20),
    ]
    travel_plans = {
        201: make_travel_plan("walk", 20, 2_200),
        202: make_travel_plan("walk", 7, 650),
    }
    neutral_profile = PlannerFeedbackProfile()
    walking_sensitive_profile = PlannerFeedbackProfile(
        city_stats=PlannerFeedbackStats(
            too_much_walking_rate=1.0,
            completion_rate=0.9,
            session_count=10,
            feedback_count=10,
            planned_count=30,
        )
    )

    before = run_route(
        monkeypatch,
        candidates=candidates,
        profile=neutral_profile,
        travel_plans=travel_plans,
    )
    after = run_route(
        monkeypatch,
        candidates=candidates,
        profile=walking_sensitive_profile,
        travel_plans=travel_plans,
    )

    assert before["route"][0]["poi_id"] == 201
    assert after["route"][0]["poi_id"] == 202
    assert after["total_walk_minutes"] < before["total_walk_minutes"]


def test_feedback_recompute_can_make_walk_or_mhd_more_transit_friendly(monkeypatch):
    candidates = [
        make_candidate(301, "Near Park", "park", 48.3110, 18.0850, 0.86, 20),
        make_candidate(302, "Transit Museum", "museum", 48.3300, 18.1000, 0.82, 20),
    ]
    travel_plans = {
        301: make_travel_plan("walk", 8, 700),
        302: make_travel_plan("transit", 9, 1_900, source="test_mhd"),
    }
    neutral_profile = PlannerFeedbackProfile()
    transit_friendly_profile = PlannerFeedbackProfile(
        transport_mode_stats=PlannerFeedbackStats(
            average_rating=4.8,
            convenient_rate=0.95,
            completion_rate=0.9,
            interesting_pois_rate=0.85,
            session_count=12,
            feedback_count=12,
            planned_count=24,
        )
    )

    before = run_route(
        monkeypatch,
        candidates=candidates,
        profile=neutral_profile,
        travel_plans=travel_plans,
        transport_enabled=True,
    )
    after = run_route(
        monkeypatch,
        candidates=candidates,
        profile=transit_friendly_profile,
        travel_plans=travel_plans,
        transport_enabled=True,
    )

    assert before["route"][0]["poi_id"] == 301
    assert after["route"][0]["poi_id"] == 302
    assert before["legs"][0]["mode"] == "walk"
    assert after["legs"][0]["mode"] == "transit"

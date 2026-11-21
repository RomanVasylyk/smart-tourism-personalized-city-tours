import math
import random
from datetime import datetime, timedelta

import pytest
from app.schemas.route import RouteGenerateRequest
from app.services.feedback_stats import PlannerFeedbackProfile
from app.services.route_planning.engine import RoutePlannerEngine
from app.services.route_planning.exact_search import (
    ExactProblem,
    leg_minutes,
    route_value,
    solve_exact,
    visit_minutes,
)
from app.services.route_planning.models import CandidatePoi
from app.services.route_planning.opening_hours import resolve_visit_wait
from app.services.route_planning.scoring import REPEAT_CATEGORY_PENALTY
from app.services.routing_service import RoutingLeg

START = (48.3076, 18.0845)
OPENING_HOURS_CHOICES = [None, None, None, "Mo-Su 10:30-12:00", "Mo-Su 09:00-09:40,11:00-13:00", "Mo-Su 12:00-18:00"]


def make_poi(poi_id, lat, lon, category, visit, hours=None):
    return CandidatePoi.from_row(
        {
            "id": poi_id,
            "name": f"poi-{poi_id}",
            "category": category,
            "lat": lat,
            "lon": lon,
            "opening_hours_raw": hours,
            "opening_hours_source": "opening_hours" if hours else None,
            "visit_duration_min": visit,
            "base_score": 0.5,
        }
    )


def walking_seconds(a, b):
    dlat = (a[0] - b[0]) * 111_000
    dlon = (a[1] - b[1]) * 111_000 * math.cos(math.radians(a[0]))
    return math.hypot(dlat, dlon) * 1.3 / 1.33


def random_problem(seed, size):
    rng = random.Random(seed)
    candidates = [
        make_poi(
            index + 1,
            START[0] + rng.uniform(-0.012, 0.012),
            START[1] + rng.uniform(-0.016, 0.016),
            rng.choice(["museum", "monument", "religious_site"]),
            rng.choice([10, 15, 20, 30, 40]),
            rng.choice(OPENING_HOURS_CHOICES),
        )
        for index in range(size)
    ]
    points = [START] + [(poi.lat, poi.lon) for poi in candidates]
    matrix = [
        [0.0 if i == j else walking_seconds(points[i], points[j]) for j in range(len(points))]
        for i in range(len(points))
    ]
    preferred = rng.sample([poi.id for poi in candidates], rng.choice([0, 0, 1, 2]))
    return ExactProblem(
        candidates=candidates,
        values=[round(rng.uniform(-1.0, 10.0), 2) for _ in candidates],
        matrix_seconds=matrix,
        start_dt=datetime(2026, 6, 24, 9, 0),
        available_minutes=rng.choice([60, 90, 120, 150]),
        return_to_start=rng.choice([True, False]),
        respect_opening_hours=True,
        max_wait_minutes=20,
        preferred_ids=preferred,
    )


def brute_force_value(problem):
    size = len(problem.candidates)
    forced = {poi.id for poi in problem.candidates if poi.id in problem.preferred_ids}
    best = -float("inf")

    def extend(last, elapsed, sequence):
        nonlocal best
        ids = [problem.candidates[index].id for index in sequence]
        if all(poi_id in ids for poi_id in problem.preferred_ids) and [i for i in ids if i in forced] == [
            i for i in problem.preferred_ids if i in ids
        ]:
            values = {poi.id: problem.values[index] for index, poi in enumerate(problem.candidates)}
            best = max(best, route_value([problem.candidates[index] for index in sequence], values))
        for index in range(size):
            if index in sequence:
                continue
            poi = problem.candidates[index]
            arrival = elapsed + leg_minutes(problem.matrix_seconds[last][index + 1])
            wait = resolve_visit_wait(
                poi.planning_opening_hours,
                problem.start_dt + timedelta(minutes=arrival),
                visit_minutes(poi),
                respect_opening_hours=problem.respect_opening_hours,
                is_forced=poi.id in forced,
                max_wait_minutes=problem.max_wait_minutes,
            )
            if wait is None:
                continue
            new_elapsed = arrival + wait + visit_minutes(poi)
            back = leg_minutes(problem.matrix_seconds[index + 1][0]) if problem.return_to_start else 0
            if poi.id not in forced and new_elapsed + back > problem.available_minutes:
                continue
            extend(index + 1, new_elapsed, sequence + [index])

    extend(0, 0, [])
    return best


@pytest.mark.parametrize("seed", range(40))
def test_exact_search_matches_brute_force(seed):
    problem = random_problem(seed, 7)
    expected = brute_force_value(problem)

    pruned = solve_exact(problem)
    plain = solve_exact(problem, use_bound=False, use_dominance=False)

    assert pruned.optimal and plain.optimal
    if expected == -float("inf"):
        assert pruned.order is None and plain.order is None
        return
    assert pruned.value == pytest.approx(expected)
    assert plain.value == pytest.approx(expected)
    values = {poi.id: problem.values[index] for index, poi in enumerate(problem.candidates)}
    assert route_value(pruned.order, values) == pytest.approx(expected)
    assert pruned.nodes <= plain.nodes


def test_exact_search_keeps_required_order_and_waits_for_opening():
    museum = make_poi(1, START[0] + 0.002, START[1], "museum", 30, "Mo-Su 09:20-17:00")
    church = make_poi(2, START[0] - 0.002, START[1], "religious_site", 15)
    statue = make_poi(3, START[0], START[1] + 0.003, "monument", 10)
    points = [START, (museum.lat, museum.lon), (church.lat, church.lon), (statue.lat, statue.lon)]
    matrix = [[0.0 if i == j else walking_seconds(points[i], points[j]) for j in range(4)] for i in range(4)]
    problem = ExactProblem(
        candidates=[museum, church, statue],
        values=[8.0, 5.0, 4.0],
        matrix_seconds=matrix,
        start_dt=datetime(2026, 6, 24, 9, 0),
        available_minutes=120,
        return_to_start=True,
        respect_opening_hours=True,
        max_wait_minutes=20,
        preferred_ids=[2, 1],
    )

    result = solve_exact(problem)

    assert result.optimal
    ids = [poi.id for poi in result.order]
    assert ids.index(2) < ids.index(1)
    assert set(ids) == {1, 2, 3}
    assert result.value == pytest.approx(8.0 + 5.0 + 4.0)


def test_repeat_penalty_is_part_of_route_value():
    first = make_poi(1, START[0], START[1], "monument", 10)
    second = make_poi(2, START[0], START[1], "monument", 10)
    assert route_value([first, second], {1: 3.0, 2: 2.0}) == pytest.approx(5.0 - REPEAT_CATEGORY_PENALTY)


def test_exact_search_respects_time_limit():
    problem = random_problem(7, 18)
    problem = ExactProblem(**{**problem.__dict__, "available_minutes": 300, "preferred_ids": []})

    result = solve_exact(problem, use_bound=False, use_dominance=False, time_limit_seconds=0.05)

    assert not result.optimal


class MatrixRoutingService:
    def _seconds(self, start, end):
        return walking_seconds((start.lat, start.lon), (end.lat, end.lon))

    def duration_matrix(self, sources, destinations, pace, profile=None):
        return [[self._seconds(source, destination) for destination in destinations] for source in sources]

    def route_between(self, start, end, pace):
        return RoutingLeg(
            duration_seconds=self._seconds(start, end),
            distance_meters=self._seconds(start, end) * 1.33,
            geometry=[{"lat": start.lat, "lon": start.lon}, {"lat": end.lat, "lon": end.lon}],
            source="test_matrix",
        )


def _engine(candidates, *, exact_enabled, available=150):
    request = RouteGenerateRequest(
        city="nitra",
        start_lat=START[0],
        start_lon=START[1],
        available_minutes=available,
        return_to_start=True,
        respect_opening_hours=True,
        start_datetime="2026-06-24T09:00",
    )
    return RoutePlannerEngine(
        request=request,
        start_dt=datetime(2026, 6, 24, 9, 0),
        city_profile={"routing_limits": {"exact_search_enabled": exact_enabled}},
        effective_transport_mode="walk",
        feedback_profile=PlannerFeedbackProfile(),
        candidates=candidates,
        routing_service=MatrixRoutingService(),
    )


def _random_candidates(seed, size):
    rng = random.Random(seed)
    return [
        CandidatePoi.from_row(
            {
                "id": index + 1,
                "name": f"poi-{index + 1}",
                "category": rng.choice(["museum", "monument", "religious_site", "park"]),
                "lat": START[0] + rng.uniform(-0.015, 0.015),
                "lon": START[1] + rng.uniform(-0.02, 0.02),
                "opening_hours_raw": rng.choice(OPENING_HOURS_CHOICES),
                "opening_hours_source": "opening_hours",
                "visit_duration_min": rng.choice([10, 15, 20, 30]),
                "base_score": round(rng.uniform(0.2, 1.0), 2),
            }
        )
        for index in range(size)
    ]


def test_planner_uses_exact_search_for_small_candidate_sets():
    methods = []
    for seed in range(15):
        candidates = _random_candidates(seed, 12)
        heuristic = _engine(candidates, exact_enabled=False)
        heuristic.plan()
        exact = _engine(candidates, exact_enabled=True)
        result = exact.plan()

        assert exact.timeline_value(exact.timeline) >= heuristic.timeline_value(heuristic.timeline) - 1e-9
        assert result.used_minutes <= 150
        methods.append(result.planning_method)

    assert "exact_search" in methods
    assert set(methods) <= {"exact_search", "greedy_local_search_optimal"}


def test_planner_skips_exact_search_for_large_candidate_sets():
    engine = _engine(_random_candidates(3, 25), exact_enabled=True)

    result = engine.plan()

    assert result.planning_method == "greedy_local_search"

from __future__ import annotations

import argparse
import copy
import json
import random
import sys
import time
from functools import partial
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from app.repositories.poi_candidates import get_route_candidates
from app.schemas.route import RouteGenerateRequest
from app.services.city_profiles import city_profile_by_token
from app.services.feedback_stats import load_planner_feedback_profile
from app.services.route_planner import parse_start_datetime
from app.services.route_planning.engine import RoutePlannerEngine
from app.services.route_planning.exact_search import ExactProblem, solve_exact
from app.services.route_planning.models import CandidatePoi
from app.services.routing_service import RoutePoint, RoutingLeg, get_routing_service, haversine_km

CITY_CENTERS = {
    "nitra": (48.3069, 18.0864),
    "trnava": (48.3774, 17.5872),
    "nove-zamky": (47.9857, 18.1620),
    "trencin": (48.8945, 18.0444),
    "zilina": (49.2231, 18.7394),
}


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Greedy, local search, exact search and plain backtracking.")
    parser.add_argument("--cities", nargs="+", default=list(CITY_CENTERS))
    parser.add_argument("--budgets", nargs="+", type=int, default=[120, 240])
    parser.add_argument("--sizes", nargs="+", type=int, default=[8, 10, 12, 14, 16, 18, 20])
    parser.add_argument("--runs", type=int, default=10)
    parser.add_argument("--pool", type=int, default=30)
    parser.add_argument("--start-datetime", default="2026-06-24T10:00")
    parser.add_argument("--seed", type=int, default=42)
    parser.add_argument("--plain-max-size", type=int, default=12)
    parser.add_argument("--exact-time-limit", type=float, default=60.0)
    parser.add_argument("--plain-time-limit", type=float, default=30.0)
    parser.add_argument("--out", required=True)
    return parser.parse_args()


def point_key(point: RoutePoint) -> tuple[float, float]:
    return (round(point.lat, 7), round(point.lon, 7))


class MatrixRouting:
    def __init__(self, points: list[RoutePoint], seconds: list[list[float | None]]):
        self.index = {point_key(point): position for position, point in enumerate(points)}
        self.seconds = seconds

    def _duration(self, start: RoutePoint, end: RoutePoint) -> float:
        value = self.seconds[self.index[point_key(start)]][self.index[point_key(end)]]
        return 10**7 if value is None else value

    def duration_matrix(self, sources, destinations, pace, profile=None):
        return [[self._duration(source, destination) for destination in destinations] for source in sources]

    def route_between(self, start, end, pace):
        return RoutingLeg(
            duration_seconds=self._duration(start, end),
            distance_meters=haversine_km(start.lat, start.lon, end.lat, end.lon) * 1000.0,
            geometry=[{"lat": start.lat, "lon": start.lon}, {"lat": end.lat, "lon": end.lon}],
            source="osrm_table",
        )


def make_engine(request, start_dt, city_profile, feedback, candidates, routing, *, local_search, exact):
    profile = copy.deepcopy(city_profile)
    limits = profile.setdefault("routing_limits", {})
    limits["local_search_enabled"] = local_search
    limits["exact_search_enabled"] = exact
    return RoutePlannerEngine(
        request=request,
        start_dt=start_dt,
        city_profile=profile,
        effective_transport_mode="walk",
        feedback_profile=feedback,
        candidates=candidates,
        routing_service=routing,
    )


def timed_plan(engine):
    started = time.perf_counter()
    result = engine.plan()
    elapsed_ms = (time.perf_counter() - started) * 1000.0
    return result, elapsed_ms


def route_summary(engine, result):
    return {
        "value": round(engine.timeline_value(engine.timeline), 4),
        "stops": len(result.route_items),
        "walk": result.total_walk_minutes,
        "used": result.used_minutes,
        "ids": [int(item["poi_id"]) for item in result.route_items],
    }


def exact_run(engine, candidates, matrix, request, start_dt, *, bound, dominance, limit):
    values = engine.static_values()
    problem = ExactProblem(
        candidates=candidates,
        values=[values[poi.id] for poi in candidates],
        matrix_seconds=matrix,
        start_dt=start_dt,
        available_minutes=request.available_minutes,
        return_to_start=request.return_to_start,
        respect_opening_hours=request.respect_opening_hours,
        max_wait_minutes=engine.max_opening_wait_minutes,
        preferred_ids=[],
    )
    started = time.perf_counter()
    outcome = solve_exact(problem, use_bound=bound, use_dominance=dominance, time_limit_seconds=limit)
    timeline = engine.build_within_budget(outcome.order or [])
    elapsed_ms = (time.perf_counter() - started) * 1000.0
    return {
        "ms": round(elapsed_ms, 3),
        "value": round(outcome.value, 4) if outcome.order is not None else 0.0,
        "rebuilt_value": round(engine.timeline_value(timeline), 4),
        "optimal": outcome.optimal,
        "nodes": outcome.nodes,
        "stops": len(timeline.route_items),
        "used": timeline.elapsed_minutes,
        "ids": [int(item["poi_id"]) for item in timeline.route_items],
    }


def main() -> None:
    args = parse_args()
    rng = random.Random(args.seed)
    routing_service = get_routing_service()
    start_dt = parse_start_datetime(args.start_datetime)
    out = Path(args.out)
    out.parent.mkdir(parents=True, exist_ok=True)

    with out.open("w", encoding="utf-8") as handle:
        for city in args.cities:
            lat, lon = CITY_CENTERS[city]
            city_profile = city_profile_by_token(city) or {}
            base_request = RouteGenerateRequest(
                city=city,
                start_lat=lat,
                start_lon=lon,
                available_minutes=max(args.budgets),
                interests=[],
                pace="normal",
                return_to_start=True,
                respect_opening_hours=True,
                start_datetime=args.start_datetime,
            )
            rows = get_route_candidates(base_request)
            pool = sorted(
                (CandidatePoi.from_row(row) for row in rows),
                key=lambda poi: haversine_km(lat, lon, poi.lat, poi.lon),
            )[: args.pool]
            feedback = load_planner_feedback_profile(city, "walk")
            start = RoutePoint(lat=lat, lon=lon)

            for budget in args.budgets:
                request = base_request.model_copy(update={"available_minutes": budget})
                for size in args.sizes:
                    for run in range(args.runs):
                        candidates = rng.sample(pool, size)
                        points = [start, *(poi.point for poi in candidates)]
                        matrix = routing_service.duration_matrix(points, points, "normal")
                        if not matrix:
                            print(f"{city} {budget} {size} {run}: OSRM table unavailable, skipped")
                            continue
                        routing = MatrixRouting(points, matrix)

                        engine = partial(make_engine, request, start_dt, city_profile, feedback, candidates, routing)

                        greedy_engine = engine(local_search=False, exact=False)
                        greedy_result, greedy_ms = timed_plan(greedy_engine)
                        heuristic_engine = engine(local_search=True, exact=False)
                        heuristic_result, heuristic_ms = timed_plan(heuristic_engine)
                        hybrid_engine = engine(local_search=True, exact=True)
                        hybrid_result, hybrid_ms = timed_plan(hybrid_engine)

                        exact = exact_run(
                            engine(local_search=True, exact=False),
                            candidates,
                            matrix,
                            request,
                            start_dt,
                            bound=True,
                            dominance=True,
                            limit=args.exact_time_limit,
                        )
                        plain = None
                        if size <= args.plain_max_size:
                            plain = exact_run(
                                engine(local_search=True, exact=False),
                                candidates,
                                matrix,
                                request,
                                start_dt,
                                bound=False,
                                dominance=False,
                                limit=args.plain_time_limit,
                            )

                        record = {
                            "city": city,
                            "budget": budget,
                            "size": size,
                            "run": run,
                            "candidate_ids": [poi.id for poi in candidates],
                            "greedy": {"ms": round(greedy_ms, 3), **route_summary(greedy_engine, greedy_result)},
                            "local_search": {
                                "ms": round(heuristic_ms, 3),
                                **route_summary(heuristic_engine, heuristic_result),
                            },
                            "hybrid": {
                                "ms": round(hybrid_ms, 3),
                                "method": hybrid_result.planning_method,
                                **route_summary(hybrid_engine, hybrid_result),
                            },
                            "exact": exact,
                            "plain": plain,
                        }
                        handle.write(json.dumps(record, ensure_ascii=False) + "\n")
                        handle.flush()
                        print(
                            f"{city:<10} {budget:>3} n={size:>2} #{run}: "
                            f"greedy {greedy_ms:7.1f} ms  ls {heuristic_ms:7.1f} ms  "
                            f"exact {exact['ms']:9.1f} ms ({'opt' if exact['optimal'] else 'timeout'}, {exact['nodes']} uzlov)  "
                            + (f"plain {plain['ms']:9.1f} ms  " if plain else "")
                            + f"hodnota ls/opt {record['local_search']['value']:.2f}/{exact['value']:.2f}"
                        )


if __name__ == "__main__":
    main()

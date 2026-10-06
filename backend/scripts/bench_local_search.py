from __future__ import annotations

import argparse
import copy
import json
import random
import statistics
import sys
import time
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from app.repositories.poi_candidates import get_route_candidates
from app.schemas.route import RouteGenerateRequest
from app.services.city_profiles import city_profile_by_token
from app.services.feedback_stats import load_planner_feedback_profile
from app.services.route_planner import cap_available_minutes, parse_start_datetime
from app.services.route_planning.engine import RoutePlannerEngine
from app.services.route_planning.models import CandidatePoi
from app.services.routing_service import get_routing_service
from app.services.transport_planner import normalized_transport_mode

CITY_CENTERS = {
    "nitra": (48.3069, 18.0864),
    "trnava": (48.3774, 17.5872),
    "nove-zamky": (47.9857, 18.1620),
    "trencin": (48.8945, 18.0444),
    "zilina": (49.2231, 18.7394),
}


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Ablation benchmark: greedy vs greedy + local search.")
    parser.add_argument("--cities", nargs="+", default=list(CITY_CENTERS))
    parser.add_argument("--budgets", nargs="+", type=int, default=[120, 240])
    parser.add_argument("--runs", type=int, default=5)
    parser.add_argument("--jitter", type=float, default=0.004)
    parser.add_argument("--start-datetime", default="2026-06-24T10:00")
    parser.add_argument("--seed", type=int, default=42)
    parser.add_argument("--json-out")
    return parser.parse_args()


def build_request(city: str, lat: float, lon: float, budget: int, start_datetime: str) -> RouteGenerateRequest:
    return RouteGenerateRequest(
        city=city,
        start_lat=lat,
        start_lon=lon,
        available_minutes=budget,
        interests=[],
        pace="normal",
        return_to_start=True,
        respect_opening_hours=True,
        start_datetime=start_datetime,
    )


def plan_with(request: RouteGenerateRequest, city_profile: dict, local_search: bool):
    profile = copy.deepcopy(city_profile)
    profile.setdefault("routing_limits", {})["local_search_enabled"] = local_search
    start_dt = parse_start_datetime(request.start_datetime)
    capped = cap_available_minutes(request, profile)
    mode = normalized_transport_mode(capped.transport_mode, profile)
    candidates = [CandidatePoi.from_row(row) for row in get_route_candidates(capped)]
    if not candidates:
        return None, 0.0
    engine = RoutePlannerEngine(
        request=capped,
        start_dt=start_dt,
        city_profile=profile,
        effective_transport_mode=mode,
        feedback_profile=load_planner_feedback_profile(capped.city, mode),
        candidates=candidates,
        routing_service=get_routing_service(),
    )
    started = time.perf_counter()
    result = engine.plan()
    return result, (time.perf_counter() - started) * 1000.0


def main() -> None:
    args = parse_args()
    rng = random.Random(args.seed)
    rows = []

    for city in args.cities:
        center = CITY_CENTERS.get(city)
        if center is None:
            print(f"skipping unknown city: {city}")
            continue
        city_profile = city_profile_by_token(city) or {}

        for budget in args.budgets:
            samples = []
            for _ in range(args.runs):
                lat = center[0] + rng.uniform(-args.jitter, args.jitter)
                lon = center[1] + rng.uniform(-args.jitter, args.jitter)
                request = build_request(city, lat, lon, budget, args.start_datetime)

                baseline, baseline_ms = plan_with(request, city_profile, local_search=False)
                improved, improved_ms = plan_with(request, city_profile, local_search=True)
                if baseline is None or improved is None:
                    continue

                samples.append(
                    {
                        "baseline_walk": baseline.total_walk_minutes,
                        "improved_walk": improved.total_walk_minutes,
                        "baseline_stops": len(baseline.route_items),
                        "improved_stops": len(improved.route_items),
                        "baseline_used": baseline.used_minutes,
                        "improved_used": improved.used_minutes,
                        "baseline_ms": baseline_ms,
                        "improved_ms": improved_ms,
                    }
                )

            if not samples:
                continue

            def mean(key, rows=samples):
                return statistics.mean(sample[key] for sample in rows)

            baseline_walk = mean("baseline_walk")
            improved_walk = mean("improved_walk")
            gain = 0.0 if baseline_walk == 0 else (baseline_walk - improved_walk) / baseline_walk * 100.0
            row = {
                "city": city,
                "budget": budget,
                "runs": len(samples),
                "walk_baseline": round(baseline_walk, 1),
                "walk_local_search": round(improved_walk, 1),
                "walk_gain_pct": round(gain, 1),
                "stops_baseline": round(mean("baseline_stops"), 1),
                "stops_local_search": round(mean("improved_stops"), 1),
                "budget_use_baseline_pct": round(mean("baseline_used") / budget * 100.0, 1),
                "budget_use_local_search_pct": round(mean("improved_used") / budget * 100.0, 1),
                "ms_baseline": round(mean("baseline_ms")),
                "ms_local_search": round(mean("improved_ms")),
            }
            rows.append(row)
            print(
                f"{city:<12} {budget:>4} min  "
                f"chôdza {row['walk_baseline']:>6} → {row['walk_local_search']:>6} min "
                f"({row['walk_gain_pct']:>5} %)  "
                f"zastávky {row['stops_baseline']:>4} → {row['stops_local_search']:<4} "
                f"rozpočet {row['budget_use_local_search_pct']:>5} %  "
                f"{row['ms_baseline']:>5} → {row['ms_local_search']:>5} ms"
            )

    if rows:
        total_gain = statistics.mean(row["walk_gain_pct"] for row in rows)
        print(f"\npriemerné skrátenie chôdze lokálnym zlepšovaním: {total_gain:.1f} %")

    if args.json_out:
        Path(args.json_out).write_text(json.dumps(rows, indent=2, ensure_ascii=False), encoding="utf-8")
        print(f"uložené do {args.json_out}")


if __name__ == "__main__":
    main()

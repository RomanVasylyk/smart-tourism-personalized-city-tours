from __future__ import annotations

import argparse
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
if str(ROOT) not in sys.path:
    sys.path.insert(0, str(ROOT))

from app.services.feedback_stats import recompute_feedback_stats


def main() -> None:
    parser = argparse.ArgumentParser(
        description="Recompute planner feedback aggregates from route sessions and route feedback.",
    )
    parser.add_argument(
        "--city",
        help="Optional city token or city name. When omitted, recomputes all cities.",
    )
    args = parser.parse_args()

    summary = recompute_feedback_stats(city=args.city)
    scope = summary["city_name"] or "all cities"

    print(f"Recomputed feedback aggregates for {scope}.")
    print(f"  poi_feedback_stats: {summary['poi_feedback_stats']}")
    print(f"  category_feedback_stats: {summary['category_feedback_stats']}")
    print(f"  city_feedback_stats: {summary['city_feedback_stats']}")
    print(f"  transport_mode_feedback_stats: {summary['transport_mode_feedback_stats']}")


if __name__ == "__main__":
    main()

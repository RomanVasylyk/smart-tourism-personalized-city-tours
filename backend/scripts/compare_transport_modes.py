from __future__ import annotations

import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
if str(ROOT) not in sys.path:
    sys.path.insert(0, str(ROOT))

from app.services.route_planner import RouteGenerateRequest, generate_route

SCENARIOS = [
    {
        "name": "Historic Core",
        "city": "nitra",
        "start_lat": 48.3084,
        "start_lon": 18.0846,
        "available_minutes": 120,
        "interests": ["historical_site", "museum", "viewpoint"],
        "pace": "normal",
        "return_to_start": True,
        "respect_opening_hours": True,
        "start_datetime": "2026-04-26T10:00",
    },
    {
        "name": "Castle To Zobor",
        "city": "nitra",
        "start_lat": 48.3172,
        "start_lon": 18.0862,
        "available_minutes": 150,
        "interests": ["historical_site", "viewpoint", "park"],
        "pace": "normal",
        "return_to_start": False,
        "respect_opening_hours": True,
        "start_datetime": "2026-04-26T11:00",
    },
    {
        "name": "Agrokomplex Side",
        "city": "nitra",
        "start_lat": 48.3039,
        "start_lon": 18.1088,
        "available_minutes": 180,
        "interests": ["museum", "park", "historical_site", "attraction"],
        "pace": "normal",
        "return_to_start": True,
        "respect_opening_hours": True,
        "start_datetime": "2026-04-26T12:00",
    },
]


def run_mode(scenario: dict, transport_mode: str) -> dict:
    request = RouteGenerateRequest(**scenario, transport_mode=transport_mode)
    return generate_route(request)


def route_travel_minutes(route: dict) -> int:
    return int(route["used_minutes"] - route["total_visit_minutes"])


def transit_leg_count(route: dict) -> int:
    return sum(1 for leg in route.get("legs", []) if leg.get("mode") == "transit")


def main() -> None:
    for scenario in SCENARIOS:
        walking = run_mode(scenario, "walk")
        multimodal = run_mode(scenario, "walk_or_mhd")

        saved_minutes = route_travel_minutes(walking) - route_travel_minutes(multimodal)
        extra_pois = multimodal["poi_count"] - walking["poi_count"]

        print(f"\nScenario: {scenario['name']}")
        print(
            f"  walk         -> travel {route_travel_minutes(walking):3d} min, "
            f"used {walking['used_minutes']:3d} min, POIs {walking['poi_count']}, "
            f"transit legs {transit_leg_count(walking)}"
        )
        print(
            f"  walk_or_mhd  -> travel {route_travel_minutes(multimodal):3d} min, "
            f"used {multimodal['used_minutes']:3d} min, POIs {multimodal['poi_count']}, "
            f"transit legs {transit_leg_count(multimodal)}"
        )
        print(
            f"  delta        -> saved {saved_minutes:+d} travel min, "
            f"extra POIs {extra_pois:+d}"
        )


if __name__ == "__main__":
    main()

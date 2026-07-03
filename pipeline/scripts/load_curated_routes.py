from __future__ import annotations

import argparse
import sys
from pathlib import Path

import yaml
from psycopg.rows import dict_row

ROOT = Path(__file__).resolve().parents[1]
if str(ROOT) not in sys.path:
    sys.path.insert(0, str(ROOT))

from utils.cities import load_city, load_city_profiles
from utils.db import get_connection

CONFIG_FILE = ROOT / "config" / "curated_routes.yaml"


def load_curated_route_config() -> dict[str, list[dict]]:
    config = yaml.safe_load(CONFIG_FILE.read_text(encoding="utf-8")) or {}
    return config.get("curated_routes") or {}


def resolve_city_id(conn, city: dict) -> int:
    city_name = city["name"]
    country = city.get("country") or city.get("country_code") or ""
    with conn.cursor(row_factory=dict_row) as cur:
        cur.execute(
            """
            SELECT id
            FROM cities
            WHERE lower(name) = lower(%s) AND lower(country) = lower(%s)
            ORDER BY id
            LIMIT 1;
            """,
            (city_name, country),
        )
        row = cur.fetchone()
    if row is None:
        raise ValueError(f"City '{city['slug']}' is not loaded. Run load_to_db.py first.")
    return row["id"]


def resolve_poi_id(cur, city_id: int, stop: dict) -> int:
    osm_type = stop.get("osm_type")
    osm_id = stop.get("osm_id")
    if osm_type and osm_id is not None:
        cur.execute(
            """
            SELECT id
            FROM pois
            WHERE city_id = %s AND osm_type = %s AND osm_id = %s
            ORDER BY id
            LIMIT 1;
            """,
            (city_id, str(osm_type), str(osm_id)),
        )
        row = cur.fetchone()
        if row is not None:
            return row["id"]

    name = stop.get("name")
    if name:
        cur.execute(
            """
            SELECT id
            FROM pois
            WHERE city_id = %s AND lower(name) = lower(%s)
            ORDER BY id
            LIMIT 1;
            """,
            (city_id, name),
        )
        row = cur.fetchone()
        if row is not None:
            return row["id"]

    raise ValueError(f"Could not resolve POI for stop {stop!r} in city_id={city_id}.")


def upsert_curated_route(cur, city_id: int, route: dict) -> int:
    start = route.get("start") or {}
    start_lat = start.get("lat")
    start_lon = start.get("lon")
    if start_lat is None or start_lon is None:
        raise ValueError(f"Curated route '{route.get('slug')}' is missing start coordinates.")

    params = (
        route["title"],
        route.get("summary"),
        route.get("theme"),
        start_lat,
        start_lon,
        route.get("recommended_duration_min"),
        route.get("pace", "normal"),
        route.get("transport_mode", "walk"),
        bool(route.get("return_to_start", True)),
    )

    cur.execute(
        """
        UPDATE curated_routes
        SET title = %s, summary = %s, theme = %s, start_lat = %s, start_lon = %s,
            recommended_duration_min = %s, pace = %s, transport_mode = %s,
            return_to_start = %s, is_active = TRUE, updated_at = NOW()
        WHERE city_id = %s AND slug = %s
        RETURNING id;
        """,
        (*params, city_id, route["slug"]),
    )
    row = cur.fetchone()
    if row is not None:
        return row["id"]

    cur.execute(
        """
        INSERT INTO curated_routes (
            city_id, slug, title, summary, theme, start_lat, start_lon,
            recommended_duration_min, pace, transport_mode, return_to_start
        )
        VALUES (%s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s)
        RETURNING id;
        """,
        (city_id, route["slug"], *params),
    )
    return cur.fetchone()["id"]


def replace_curated_route_pois(cur, city_id: int, route_id: int, stops: list[dict]) -> None:
    cur.execute("DELETE FROM curated_route_pois WHERE route_id = %s;", (route_id,))
    for visit_order, stop in enumerate(stops, start=1):
        poi_id = resolve_poi_id(cur, city_id, stop)
        cur.execute(
            """
            INSERT INTO curated_route_pois (route_id, poi_id, visit_order, visit_duration_min, note)
            VALUES (%s, %s, %s, %s, %s);
            """,
            (route_id, poi_id, visit_order, stop.get("visit_duration_min"), stop.get("note")),
        )


def load_curated_routes_for_city(city: dict, routes: list[dict]) -> int:
    with get_connection() as conn:
        city_id = resolve_city_id(conn, city)
        with conn.cursor(row_factory=dict_row) as cur:
            for route in routes:
                stops = route.get("stops") or []
                if not stops:
                    raise ValueError(f"Curated route '{route.get('slug')}' has no stops.")
                route_id = upsert_curated_route(cur, city_id, route)
                replace_curated_route_pois(cur, city_id, route_id, stops)
        conn.commit()
    return len(routes)


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Load hand-curated city tours into Postgres.")
    parser.add_argument("city", nargs="?", default="nove-zamky", help="City slug from pipeline/config/cities.yaml")
    parser.add_argument(
        "--all-cities",
        action="store_true",
        help="Load curated routes for every configured city that has them.",
    )
    return parser.parse_args()


def main() -> None:
    args = parse_args()
    config = load_curated_route_config()
    cities = load_city_profiles() if args.all_cities else [load_city(args.city)]

    for city in cities:
        routes = config.get(city["slug"]) or []
        if not routes:
            print(f"{city['slug']}: no curated routes configured")
            continue
        loaded = load_curated_routes_for_city(city, routes)
        print(f"{city['slug']}: loaded {loaded} curated routes")


if __name__ == "__main__":
    main()

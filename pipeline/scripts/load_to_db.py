from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path

from psycopg.rows import dict_row

ROOT = Path(__file__).resolve().parents[1]
if str(ROOT) not in sys.path:
    sys.path.insert(0, str(ROOT))

from utils.cities import load_city, load_city_profiles
from utils.db import get_connection


def get_center(city: dict) -> tuple[float, float]:
    center = city.get("center") or {}
    lat = center.get("lat")
    lon = center.get("lon")
    if lat is not None and lon is not None:
        return lat, lon

    bbox = city.get("bbox") or {}
    south = bbox.get("south")
    west = bbox.get("west")
    north = bbox.get("north")
    east = bbox.get("east")
    if None in {south, west, north, east}:
        raise ValueError(f"City {city.get('slug', '<unknown>')} is missing center and bbox coordinates")
    return (south + north) / 2, (west + east) / 2


def ensure_city(conn, city: dict) -> int:
    city_name = city["name"]
    country = city.get("country") or city.get("country_code") or ""
    if not country:
        raise ValueError(f"City {city_name} is missing country information")

    center_lat, center_lon = get_center(city)

    with conn.cursor(row_factory=dict_row) as cur:
        cur.execute("LOCK TABLE cities IN SHARE ROW EXCLUSIVE MODE;")
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
        if row:
            return row["id"]

        cur.execute(
            """
            INSERT INTO cities (name, country, center_lat, center_lon)
            VALUES (%s, %s, %s, %s)
                RETURNING id;
            """,
            (city_name, country, center_lat, center_lon),
        )
        row = cur.fetchone()
        return row["id"]


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Load normalized POIs into Postgres.")
    parser.add_argument("city", nargs="?", default="nitra", help="City slug from pipeline/config/cities.yaml")
    parser.add_argument(
        "--all-cities",
        action="store_true",
        help="Load every city configured in pipeline/config/cities.yaml.",
    )
    return parser.parse_args()


def load_pois_for_city(city: dict) -> tuple[int, int]:
    in_file = ROOT / "data" / "processed" / f"{city['slug']}_pois_normalized.json"
    pois = json.loads(in_file.read_text(encoding="utf-8"))

    inserted_count = 0
    updated_count = 0
    with get_connection() as conn:
        city_id = ensure_city(conn, city)

        with conn.cursor() as cur:
            for poi in pois:
                cur.execute(
                    """
                    INSERT INTO pois (
                        city_id, osm_id, osm_type, name, category, subcategory,
                        lat, lon, geom, address, opening_hours_raw, visit_duration_min,
                        base_score, wikidata_id, wikipedia_title, wikipedia_url,
                        short_description, website, image_url, opening_hours_source,
                        source, is_active
                    )
                    VALUES (
                        %(city_id)s, %(osm_id)s, %(osm_type)s, %(name)s, %(category)s, %(subcategory)s,
                        %(lat)s, %(lon)s, ST_SetSRID(ST_MakePoint(%(lon)s, %(lat)s), 4326),
                        %(address)s, %(opening_hours_raw)s, %(visit_duration_min)s,
                        %(base_score)s, %(wikidata_id)s, %(wikipedia_title)s, %(wikipedia_url)s,
                        %(short_description)s, %(website)s, %(image_url)s, %(opening_hours_source)s,
                        %(source)s, %(is_active)s
                    )
                    ON CONFLICT (city_id, osm_id, osm_type) DO UPDATE SET
                        name = EXCLUDED.name,
                        category = EXCLUDED.category,
                        subcategory = EXCLUDED.subcategory,
                        lat = EXCLUDED.lat,
                        lon = EXCLUDED.lon,
                        geom = EXCLUDED.geom,
                        address = EXCLUDED.address,
                        opening_hours_raw = EXCLUDED.opening_hours_raw,
                        visit_duration_min = EXCLUDED.visit_duration_min,
                        base_score = EXCLUDED.base_score,
                        wikidata_id = EXCLUDED.wikidata_id,
                        wikipedia_title = EXCLUDED.wikipedia_title,
                        wikipedia_url = EXCLUDED.wikipedia_url,
                        short_description = EXCLUDED.short_description,
                        website = EXCLUDED.website,
                        image_url = EXCLUDED.image_url,
                        opening_hours_source = EXCLUDED.opening_hours_source,
                        source = EXCLUDED.source,
                        is_active = EXCLUDED.is_active
                    RETURNING (xmax = 0) AS was_inserted
                    """,
                    {
                        "city_id": city_id,
                        "osm_id": poi["osm_id"],
                        "osm_type": poi["osm_type"],
                        "name": poi["name"],
                        "category": poi["category"],
                        "subcategory": poi.get("subcategory"),
                        "lat": poi["lat"],
                        "lon": poi["lon"],
                        "address": poi.get("address"),
                        "opening_hours_raw": poi.get("opening_hours_raw"),
                        "visit_duration_min": poi.get("visit_duration_min"),
                        "base_score": poi.get("base_score"),
                        "wikidata_id": poi.get("wikidata_id"),
                        "wikipedia_title": poi.get("wikipedia_title"),
                        "wikipedia_url": poi.get("wikipedia_url"),
                        "short_description": poi.get("short_description"),
                        "website": poi.get("website"),
                        "image_url": poi.get("image_url"),
                        "opening_hours_source": poi.get("opening_hours_source"),
                        "source": poi.get("source", "osm"),
                        "is_active": poi.get("is_active", True),
                    },
                )
                row = cur.fetchone()
                if row is not None and row[0]:
                    inserted_count += 1
                else:
                    updated_count += 1

        conn.commit()

    return inserted_count, updated_count


def main() -> None:
    args = parse_args()
    cities = load_city_profiles() if args.all_cities else [load_city(args.city)]

    for city in cities:
        inserted_count, updated_count = load_pois_for_city(city)
        print(
            f"{city['slug']}: inserted {inserted_count} POIs and updated {updated_count} POIs in database",
        )


if __name__ == "__main__":
    main()

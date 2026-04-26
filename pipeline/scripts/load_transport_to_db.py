from __future__ import annotations

import json
import sys
from pathlib import Path

from psycopg.rows import dict_row

ROOT = Path(__file__).resolve().parents[1]
if str(ROOT) not in sys.path:
    sys.path.insert(0, str(ROOT))

from utils.cities import load_city
from utils.db import get_connection

TRANSPORT_SCHEMA_SQL = """
CREATE EXTENSION IF NOT EXISTS postgis;

CREATE TABLE IF NOT EXISTS transport_stops (
    id SERIAL PRIMARY KEY,
    city_id INTEGER NOT NULL REFERENCES cities(id) ON DELETE CASCADE,
    provider_stop_code TEXT,
    name TEXT NOT NULL,
    normalized_name TEXT NOT NULL,
    lat DOUBLE PRECISION NOT NULL,
    lon DOUBLE PRECISION NOT NULL,
    geom geometry(Point, 4326) NOT NULL,
    source TEXT NOT NULL,
    source_reference TEXT,
    platform_ref TEXT,
    matched_by TEXT,
    is_active BOOLEAN NOT NULL DEFAULT TRUE
);

CREATE TABLE IF NOT EXISTS transport_lines (
    id SERIAL PRIMARY KEY,
    city_id INTEGER NOT NULL REFERENCES cities(id) ON DELETE CASCADE,
    provider TEXT NOT NULL,
    provider_line_id TEXT NOT NULL,
    name TEXT NOT NULL,
    direction_name TEXT,
    service_bucket TEXT,
    source_url TEXT,
    valid_from DATE,
    valid_to DATE,
    is_active BOOLEAN NOT NULL DEFAULT TRUE
);

CREATE TABLE IF NOT EXISTS transport_line_stops (
    id BIGSERIAL PRIMARY KEY,
    line_id INTEGER NOT NULL REFERENCES transport_lines(id) ON DELETE CASCADE,
    stop_id INTEGER NOT NULL REFERENCES transport_stops(id),
    stop_sequence INTEGER NOT NULL,
    UNIQUE (line_id, stop_sequence)
);

CREATE TABLE IF NOT EXISTS transport_connections (
    id BIGSERIAL PRIMARY KEY,
    city_id INTEGER NOT NULL REFERENCES cities(id) ON DELETE CASCADE,
    line_id INTEGER NOT NULL REFERENCES transport_lines(id) ON DELETE CASCADE,
    from_stop_id INTEGER NOT NULL REFERENCES transport_stops(id),
    to_stop_id INTEGER NOT NULL REFERENCES transport_stops(id),
    from_sequence INTEGER NOT NULL,
    to_sequence INTEGER NOT NULL,
    avg_travel_seconds DOUBLE PRECISION NOT NULL,
    distance_meters DOUBLE PRECISION NOT NULL,
    source_url TEXT,
    is_active BOOLEAN NOT NULL DEFAULT TRUE,
    UNIQUE (line_id, from_stop_id, to_stop_id, from_sequence, to_sequence)
);

CREATE INDEX IF NOT EXISTS idx_transport_stops_city_id ON transport_stops(city_id);
CREATE INDEX IF NOT EXISTS idx_transport_stops_geom ON transport_stops USING GIST (geom);
CREATE INDEX IF NOT EXISTS idx_transport_lines_city_id ON transport_lines(city_id);
CREATE INDEX IF NOT EXISTS idx_transport_connections_city_id ON transport_connections(city_id);
CREATE INDEX IF NOT EXISTS idx_transport_connections_from_stop_id ON transport_connections(from_stop_id);
CREATE INDEX IF NOT EXISTS idx_transport_connections_to_stop_id ON transport_connections(to_stop_id);
CREATE INDEX IF NOT EXISTS idx_transport_connections_line_id ON transport_connections(line_id);
"""


def get_center(city: dict) -> tuple[float, float]:
    center = city.get("center") or {}
    lat = center.get("lat")
    lon = center.get("lon")
    if lat is not None and lon is not None:
        return float(lat), float(lon)

    bbox = city.get("bbox") or {}
    south = bbox.get("south")
    west = bbox.get("west")
    north = bbox.get("north")
    east = bbox.get("east")
    if None in {south, west, north, east}:
        raise ValueError(f"City {city.get('slug', '<unknown>')} is missing center and bbox coordinates")
    return (float(south) + float(north)) / 2, (float(west) + float(east)) / 2


def ensure_city(conn, city: dict) -> int:
    city_name = city["name"]
    country = city.get("country") or city.get("country_code") or ""
    if not country:
        raise ValueError(f"City {city_name} is missing country information")

    center_lat, center_lon = get_center(city)

    with conn.cursor(row_factory=dict_row) as cur:
        cur.execute(
            """
            SELECT id
            FROM cities
            WHERE lower(name) = lower(%s) AND lower(country) = lower(%s)
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


def transport_graph_path(city: dict) -> Path:
    transport = city.get("transport") or {}
    relative_path = str(
        transport.get("processed_graph_path")
        or f"transport/{city['slug']}/processed/transport_graph.json"
    )
    return ROOT / "data" / relative_path.removeprefix("data/")


def ensure_transport_schema(conn) -> None:
    with conn.cursor() as cur:
        cur.execute(TRANSPORT_SCHEMA_SQL)
        cur.execute("ALTER TABLE transport_stops ADD COLUMN IF NOT EXISTS platform_ref TEXT;")
        cur.execute("ALTER TABLE transport_lines ADD COLUMN IF NOT EXISTS service_bucket TEXT;")
        cur.execute("ALTER TABLE transport_stops DROP CONSTRAINT IF EXISTS transport_stops_city_id_normalized_name_key;")
        cur.execute("DROP INDEX IF EXISTS idx_transport_stops_city_source_reference_unique;")
        cur.execute("ALTER TABLE transport_lines DROP CONSTRAINT IF EXISTS transport_lines_city_id_provider_provider_line_id_direction_key;")
        cur.execute("ALTER TABLE transport_line_stops DROP CONSTRAINT IF EXISTS transport_line_stops_line_id_stop_id_key;")


def ensure_transport_unique_indexes(conn) -> None:
    with conn.cursor() as cur:
        cur.execute(
            """
            CREATE UNIQUE INDEX IF NOT EXISTS idx_transport_stops_city_source_reference_unique
                ON transport_stops(city_id, source_reference)
                WHERE source_reference IS NOT NULL;
            """
        )


def delete_existing_transport(conn, city_id: int) -> None:
    with conn.cursor() as cur:
        cur.execute("DELETE FROM transport_connections WHERE city_id = %s;", (city_id,))
        cur.execute("DELETE FROM transport_line_stops WHERE line_id IN (SELECT id FROM transport_lines WHERE city_id = %s);", (city_id,))
        cur.execute("DELETE FROM transport_lines WHERE city_id = %s;", (city_id,))
        cur.execute("DELETE FROM transport_stops WHERE city_id = %s;", (city_id,))


def main() -> None:
    city_slug = sys.argv[1] if len(sys.argv) > 1 else "nitra"
    city = load_city(city_slug)
    graph_file = transport_graph_path(city)
    if not graph_file.exists():
        raise FileNotFoundError(f"Transport graph file not found: {graph_file}")

    graph = json.loads(graph_file.read_text(encoding="utf-8"))
    provider = str(graph.get("provider") or (city.get("transport") or {}).get("provider") or "transport_provider")

    inserted_stops = 0
    inserted_lines = 0
    inserted_connections = 0

    with get_connection() as conn:
        ensure_transport_schema(conn)
        city_id = ensure_city(conn, city)
        delete_existing_transport(conn, city_id)
        ensure_transport_unique_indexes(conn)

        stop_ids_by_graph_key: dict[str, int] = {}
        with conn.cursor(row_factory=dict_row) as cur:
            for stop in graph.get("stops", []):
                cur.execute(
                    """
                    INSERT INTO transport_stops (
                        city_id,
                        provider_stop_code,
                        name,
                        normalized_name,
                        lat,
                        lon,
                        geom,
                        source,
                        source_reference,
                        platform_ref,
                        matched_by,
                        is_active
                    )
                    VALUES (
                        %s, %s, %s, %s, %s, %s,
                        ST_SetSRID(ST_MakePoint(%s, %s), 4326),
                        %s, %s, %s, %s, TRUE
                    )
                    RETURNING id;
                    """,
                    (
                        city_id,
                        stop.get("graph_stop_key"),
                        stop["name"],
                        stop["normalized_name"],
                        stop["lat"],
                        stop["lon"],
                        stop["lon"],
                        stop["lat"],
                        stop.get("source", "osm"),
                        stop.get("source_reference"),
                        stop.get("platform_ref"),
                        stop.get("matched_by"),
                    ),
                )
                row = cur.fetchone()
                stop_ids_by_graph_key[stop["graph_stop_key"]] = row["id"]
                inserted_stops += 1

            line_ids_by_graph_id: dict[str, int] = {}
            for line in graph.get("lines", []):
                cur.execute(
                    """
                    INSERT INTO transport_lines (
                        city_id,
                        provider,
                        provider_line_id,
                        name,
                        direction_name,
                        service_bucket,
                        source_url,
                        valid_from,
                        valid_to,
                        is_active
                    )
                    VALUES (%s, %s, %s, %s, %s, %s, %s, %s, %s, TRUE)
                    RETURNING id;
                    """,
                    (
                        city_id,
                        provider,
                        line["provider_line_id"],
                        line["name"],
                        line.get("direction_name"),
                        line.get("service_bucket"),
                        line.get("source_url"),
                        line.get("valid_from"),
                        line.get("valid_to"),
                    ),
                )
                row = cur.fetchone()
                db_line_id = row["id"]
                line_ids_by_graph_id[line["line_id"]] = db_line_id
                inserted_lines += 1

                for stop in line.get("stops", []):
                    stop_id = stop_ids_by_graph_key.get(stop["graph_stop_key"])
                    if stop_id is None:
                        continue
                    cur.execute(
                        """
                        INSERT INTO transport_line_stops (line_id, stop_id, stop_sequence)
                        VALUES (%s, %s, %s);
                        """,
                        (db_line_id, stop_id, stop["sequence"]),
                    )

            for connection in graph.get("connections", []):
                line_id = line_ids_by_graph_id.get(connection["line_id"])
                from_stop_id = stop_ids_by_graph_key.get(connection["from_stop_key"])
                to_stop_id = stop_ids_by_graph_key.get(connection["to_stop_key"])
                if line_id is None or from_stop_id is None or to_stop_id is None:
                    continue

                cur.execute(
                    """
                    INSERT INTO transport_connections (
                        city_id,
                        line_id,
                        from_stop_id,
                        to_stop_id,
                        from_sequence,
                        to_sequence,
                        avg_travel_seconds,
                        distance_meters,
                        source_url,
                        is_active
                    )
                    VALUES (%s, %s, %s, %s, %s, %s, %s, %s, %s, TRUE);
                    """,
                    (
                        city_id,
                        line_id,
                        from_stop_id,
                        to_stop_id,
                        connection["from_sequence"],
                        connection["to_sequence"],
                        connection["avg_travel_seconds"],
                        connection["distance_meters"],
                        connection.get("source_url"),
                    ),
                )
                inserted_connections += 1

        conn.commit()

    print(
        f"Loaded {inserted_stops} stops, {inserted_lines} lines and "
        f"{inserted_connections} connections into transport tables"
    )


if __name__ == "__main__":
    main()

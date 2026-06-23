from datetime import datetime
from typing import Any
from uuid import UUID

from psycopg.types.json import Jsonb

from app.db.database import get_connection
from app.services.city_lookup import find_city_row

SESSION_UPDATE_FIELDS = {
    "status",
    "finished_at",
    "used_minutes",
    "total_walk_minutes",
    "total_visit_minutes",
    "route_snapshot_json",
}


def find_city_id(city: str) -> int | None:
    with get_connection() as conn:
        with conn.cursor() as cur:
            city_row = find_city_row(cur, city)
            if city_row is None:
                return None
            return int(city_row["id"])


def upsert_route_session(
    *,
    session_id: UUID,
    device_id: str,
    device_token_hash: str,
    city_id: int,
    status: str,
    start_lat: float,
    start_lon: float,
    available_minutes: int,
    pace: str,
    return_to_start: bool,
    opening_hours_enabled: bool,
    started_at: datetime,
    finished_at: datetime | None,
    used_minutes: int | None,
    total_walk_minutes: int | None,
    total_visit_minutes: int | None,
    route_snapshot_json: dict[str, Any],
) -> bool:
    with get_connection() as conn:
        with conn.cursor() as cur:
            cur.execute(
                """
                INSERT INTO route_sessions (
                    id,
                    device_id,
                    device_token_hash,
                    city_id,
                    status,
                    start_lat,
                    start_lon,
                    available_minutes,
                    pace,
                    return_to_start,
                    opening_hours_enabled,
                    started_at,
                    finished_at,
                    used_minutes,
                    total_walk_minutes,
                    total_visit_minutes,
                    route_snapshot_json
                )
                VALUES (
                    %(id)s,
                    %(device_id)s,
                    %(device_token_hash)s,
                    %(city_id)s,
                    %(status)s,
                    %(start_lat)s,
                    %(start_lon)s,
                    %(available_minutes)s,
                    %(pace)s,
                    %(return_to_start)s,
                    %(opening_hours_enabled)s,
                    %(started_at)s,
                    %(finished_at)s,
                    %(used_minutes)s,
                    %(total_walk_minutes)s,
                    %(total_visit_minutes)s,
                    %(route_snapshot_json)s
                )
                ON CONFLICT (id) DO UPDATE SET
                    device_id = EXCLUDED.device_id,
                    device_token_hash = EXCLUDED.device_token_hash,
                    city_id = EXCLUDED.city_id,
                    status = EXCLUDED.status,
                    start_lat = EXCLUDED.start_lat,
                    start_lon = EXCLUDED.start_lon,
                    available_minutes = EXCLUDED.available_minutes,
                    pace = EXCLUDED.pace,
                    return_to_start = EXCLUDED.return_to_start,
                    opening_hours_enabled = EXCLUDED.opening_hours_enabled,
                    started_at = EXCLUDED.started_at,
                    finished_at = EXCLUDED.finished_at,
                    used_minutes = EXCLUDED.used_minutes,
                    total_walk_minutes = EXCLUDED.total_walk_minutes,
                    total_visit_minutes = EXCLUDED.total_visit_minutes,
                    route_snapshot_json = EXCLUDED.route_snapshot_json,
                    updated_at = NOW()
                WHERE route_sessions.device_id = EXCLUDED.device_id
                  AND route_sessions.device_token_hash = EXCLUDED.device_token_hash
                RETURNING id
                """,
                {
                    "id": session_id,
                    "device_id": device_id,
                    "device_token_hash": device_token_hash,
                    "city_id": city_id,
                    "status": status,
                    "start_lat": start_lat,
                    "start_lon": start_lon,
                    "available_minutes": available_minutes,
                    "pace": pace,
                    "return_to_start": return_to_start,
                    "opening_hours_enabled": opening_hours_enabled,
                    "started_at": started_at,
                    "finished_at": finished_at,
                    "used_minutes": used_minutes,
                    "total_walk_minutes": total_walk_minutes,
                    "total_visit_minutes": total_visit_minutes,
                    "route_snapshot_json": Jsonb(route_snapshot_json),
                },
            )
            session_row = cur.fetchone()
            if session_row is None:
                return False

            upsert_session_pois(cur, session_id, route_snapshot_json)
            conn.commit()
            return True


def update_route_session(
    *,
    session_id: UUID,
    device_id: str,
    device_token_hash: str,
    update_values: dict[str, Any],
) -> bool:
    filtered_values = {
        key: value
        for key, value in update_values.items()
        if key in SESSION_UPDATE_FIELDS
    }
    if not filtered_values:
        return True

    assignments = [f"{field} = %({field})s" for field in filtered_values]
    assignments.append("updated_at = NOW()")
    route_snapshot_json = filtered_values.get("route_snapshot_json")
    if route_snapshot_json is not None:
        filtered_values["route_snapshot_json"] = Jsonb(route_snapshot_json)

    params = {
        **filtered_values,
        "id": session_id,
        "device_id": device_id,
        "device_token_hash": device_token_hash,
    }

    with get_connection() as conn:
        with conn.cursor() as cur:
            cur.execute(
                f"""
                UPDATE route_sessions
                SET {", ".join(assignments)}
                WHERE id = %(id)s
                  AND device_id = %(device_id)s
                  AND device_token_hash = %(device_token_hash)s
                RETURNING id
                """,
                params,
            )
            if cur.fetchone() is None:
                return False

            if route_snapshot_json is not None:
                upsert_session_pois(cur, session_id, route_snapshot_json)

            conn.commit()
            return True


def mark_route_session_poi_visited(
    *,
    session_id: UUID,
    poi_id: int,
    device_id: str,
    device_token_hash: str,
    visited_at: datetime,
    skipped: bool,
) -> dict | None:
    with get_connection() as conn:
        with conn.cursor() as cur:
            cur.execute(
                """
                UPDATE route_session_pois rsp
                SET visited = TRUE,
                    visited_at = %(visited_at)s,
                    skipped = %(skipped)s
                WHERE rsp.session_id = %(session_id)s
                  AND rsp.poi_id = %(poi_id)s
                  AND EXISTS (
                      SELECT 1
                      FROM route_sessions rs
                      WHERE rs.id = rsp.session_id
                        AND rs.device_id = %(device_id)s
                        AND rs.device_token_hash = %(device_token_hash)s
                  )
                RETURNING rsp.*
                """,
                {
                    "session_id": session_id,
                    "poi_id": poi_id,
                    "device_id": device_id,
                    "device_token_hash": device_token_hash,
                    "visited_at": visited_at,
                    "skipped": skipped,
                },
            )
            poi_row = cur.fetchone()
            conn.commit()
            return poi_row


def replace_route_feedback(
    *,
    session_id: UUID,
    device_id: str,
    device_token_hash: str,
    rating: int,
    was_convenient: bool,
    too_much_walking: bool,
    pois_were_interesting: bool,
    comment: str | None,
) -> dict | None:
    with get_connection() as conn:
        with conn.cursor() as cur:
            cur.execute(
                """
                DELETE FROM route_feedback rf
                WHERE rf.session_id = %(session_id)s
                  AND EXISTS (
                      SELECT 1
                      FROM route_sessions rs
                      WHERE rs.id = rf.session_id
                        AND rs.device_id = %(device_id)s
                        AND rs.device_token_hash = %(device_token_hash)s
                  )
                """,
                {
                    "session_id": session_id,
                    "device_id": device_id,
                    "device_token_hash": device_token_hash,
                },
            )
            cur.execute(
                """
                INSERT INTO route_feedback (
                    session_id,
                    rating,
                    was_convenient,
                    too_much_walking,
                    pois_were_interesting,
                    comment
                )
                SELECT
                    %(session_id)s,
                    %(rating)s,
                    %(was_convenient)s,
                    %(too_much_walking)s,
                    %(pois_were_interesting)s,
                    %(comment)s
                WHERE EXISTS (
                    SELECT 1
                    FROM route_sessions rs
                    WHERE rs.id = %(session_id)s
                      AND rs.device_id = %(device_id)s
                      AND rs.device_token_hash = %(device_token_hash)s
                )
                RETURNING *
                """,
                {
                    "session_id": session_id,
                    "device_id": device_id,
                    "device_token_hash": device_token_hash,
                    "rating": rating,
                    "was_convenient": was_convenient,
                    "too_much_walking": too_much_walking,
                    "pois_were_interesting": pois_were_interesting,
                    "comment": comment,
                },
            )
            feedback = cur.fetchone()
            conn.commit()
            return feedback


def get_route_session(
    *,
    session_id: UUID,
    device_id: str,
    device_token_hash: str,
) -> dict | None:
    with get_connection() as conn:
        with conn.cursor() as cur:
            cur.execute(
                """
                SELECT
                    rs.*,
                    c.name AS city_name,
                    c.country AS city_country
                FROM route_sessions rs
                         JOIN cities c ON c.id = rs.city_id
                WHERE rs.id = %s
                  AND rs.device_id = %s
                  AND rs.device_token_hash = %s
                """,
                (session_id, device_id, device_token_hash),
            )
            session = cur.fetchone()
            if session is None:
                return None

            return session_with_children(cur, session)


def get_route_sessions_for_device(
    *,
    device_id: str,
    device_token_hash: str,
) -> list[dict]:
    with get_connection() as conn:
        with conn.cursor() as cur:
            cur.execute(
                """
                SELECT
                    rs.*,
                    c.name AS city_name,
                    c.country AS city_country
                FROM route_sessions rs
                         JOIN cities c ON c.id = rs.city_id
                WHERE rs.device_id = %s
                  AND rs.device_token_hash = %s
                ORDER BY rs.started_at DESC, rs.created_at DESC
                """,
                (device_id, device_token_hash),
            )
            return [session_with_children(cur, session) for session in cur.fetchall()]


def session_with_children(cur, session: dict) -> dict:
    cur.execute(
        """
        SELECT *
        FROM route_session_pois
        WHERE session_id = %s
        ORDER BY visit_order
        """,
        (session["id"],),
    )
    pois = cur.fetchall()

    cur.execute(
        """
        SELECT *
        FROM route_feedback
        WHERE session_id = %s
        ORDER BY created_at DESC
        """,
        (session["id"],),
    )
    feedback = cur.fetchall()

    return {
        **session,
        "pois": pois,
        "feedback": feedback,
    }


def upsert_session_pois(cur, session_id: UUID, route_snapshot_json: dict[str, Any]) -> None:
    route_items = route_snapshot_json.get("route") or []

    for item in route_items:
        poi_id = item.get("poi_id")
        if poi_id is None:
            continue

        cur.execute(
            """
            INSERT INTO route_session_pois (
                session_id,
                poi_id,
                visit_order,
                planned_arrival_min,
                planned_departure_min,
                visited,
                skipped
            )
            VALUES (
                %(session_id)s,
                %(poi_id)s,
                %(visit_order)s,
                %(planned_arrival_min)s,
                %(planned_departure_min)s,
                FALSE,
                FALSE
            )
            ON CONFLICT (session_id, poi_id) DO UPDATE SET
                visit_order = EXCLUDED.visit_order,
                planned_arrival_min = EXCLUDED.planned_arrival_min,
                planned_departure_min = EXCLUDED.planned_departure_min
            """,
            {
                "session_id": session_id,
                "poi_id": poi_id,
                "visit_order": item.get("order") or 0,
                "planned_arrival_min": item.get("arrival_after_min"),
                "planned_departure_min": item.get("departure_after_min"),
            },
        )

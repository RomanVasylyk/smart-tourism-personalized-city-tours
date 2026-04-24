from datetime import datetime, timezone
from typing import Any
from uuid import UUID, uuid4

from fastapi import HTTPException
from pydantic import BaseModel, Field
from psycopg.types.json import Jsonb

from app.db.database import get_connection
from app.services.city_profiles import city_profile_by_token

SESSION_STATUSES = {"not_started", "in_progress", "paused", "completed", "cancelled"}


class RouteSessionCreateRequest(BaseModel):
    id: UUID | None = None
    device_id: str = Field(min_length=1)
    city: str = "nitra"
    status: str = "in_progress"
    start_lat: float
    start_lon: float
    available_minutes: int = Field(ge=1)
    pace: str
    return_to_start: bool
    opening_hours_enabled: bool
    started_at: datetime | None = None
    finished_at: datetime | None = None
    used_minutes: int | None = None
    total_walk_minutes: int | None = None
    total_visit_minutes: int | None = None
    route_snapshot_json: dict[str, Any]


class RouteSessionUpdateRequest(BaseModel):
    status: str | None = None
    finished_at: datetime | None = None
    used_minutes: int | None = None
    total_walk_minutes: int | None = None
    total_visit_minutes: int | None = None
    route_snapshot_json: dict[str, Any] | None = None


class RouteSessionPoiVisitRequest(BaseModel):
    visited_at: datetime | None = None
    skipped: bool = False


class RouteFeedbackRequest(BaseModel):
    rating: int = Field(ge=1, le=5)
    was_convenient: bool
    too_much_walking: bool
    pois_were_interesting: bool
    comment: str | None = None


def create_route_session(request: RouteSessionCreateRequest) -> dict:
    validate_status(request.status)
    session_id = request.id or uuid4()
    started_at = request.started_at or now_utc()

    with get_connection() as conn:
        with conn.cursor() as cur:
            city_id = get_city_id(cur, request.city)
            cur.execute(
                """
                INSERT INTO route_sessions (
                    id,
                    device_id,
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
                RETURNING *
                """,
                {
                    "id": session_id,
                    "device_id": request.device_id,
                    "city_id": city_id,
                    "status": request.status,
                    "start_lat": request.start_lat,
                    "start_lon": request.start_lon,
                    "available_minutes": request.available_minutes,
                    "pace": request.pace,
                    "return_to_start": request.return_to_start,
                    "opening_hours_enabled": request.opening_hours_enabled,
                    "started_at": started_at,
                    "finished_at": request.finished_at,
                    "used_minutes": request.used_minutes,
                    "total_walk_minutes": request.total_walk_minutes,
                    "total_visit_minutes": request.total_visit_minutes,
                    "route_snapshot_json": Jsonb(request.route_snapshot_json),
                },
            )
            session = cur.fetchone()
            upsert_session_pois(cur, session_id, request.route_snapshot_json)
            conn.commit()

    return get_route_session(session_id)


def update_route_session(session_id: UUID, request: RouteSessionUpdateRequest) -> dict:
    update_values = request.model_dump(exclude_unset=True)
    if not update_values:
        return get_route_session(session_id)

    if "status" in update_values and update_values["status"] is not None:
        validate_status(update_values["status"])

    assignments = [f"{field} = %({field})s" for field in update_values]
    assignments.append("updated_at = NOW()")
    if "route_snapshot_json" in update_values and update_values["route_snapshot_json"] is not None:
        update_values["route_snapshot_json"] = Jsonb(update_values["route_snapshot_json"])
    params = {**update_values, "id": session_id}

    with get_connection() as conn:
        with conn.cursor() as cur:
            cur.execute(
                f"""
                UPDATE route_sessions
                SET {", ".join(assignments)}
                WHERE id = %(id)s
                RETURNING id
                """,
                params,
            )
            if cur.fetchone() is None:
                raise_not_found()

            if request.route_snapshot_json is not None:
                upsert_session_pois(cur, session_id, request.route_snapshot_json)

            conn.commit()

    return get_route_session(session_id)


def mark_route_session_poi_visited(
    session_id: UUID,
    poi_id: int,
    request: RouteSessionPoiVisitRequest,
) -> dict:
    visited_at = request.visited_at or now_utc()

    with get_connection() as conn:
        with conn.cursor() as cur:
            ensure_session_exists(cur, session_id)
            cur.execute(
                """
                UPDATE route_session_pois
                SET visited = TRUE,
                    visited_at = %(visited_at)s,
                    skipped = %(skipped)s
                WHERE session_id = %(session_id)s
                  AND poi_id = %(poi_id)s
                RETURNING *
                """,
                {
                    "session_id": session_id,
                    "poi_id": poi_id,
                    "visited_at": visited_at,
                    "skipped": request.skipped,
                },
            )
            poi_row = cur.fetchone()
            if poi_row is None:
                raise HTTPException(status_code=404, detail="Route session POI not found.")
            conn.commit()

    return poi_row


def save_route_feedback(session_id: UUID, request: RouteFeedbackRequest) -> dict:
    with get_connection() as conn:
        with conn.cursor() as cur:
            ensure_session_exists(cur, session_id)
            cur.execute(
                "DELETE FROM route_feedback WHERE session_id = %s",
                (session_id,),
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
                VALUES (
                    %(session_id)s,
                    %(rating)s,
                    %(was_convenient)s,
                    %(too_much_walking)s,
                    %(pois_were_interesting)s,
                    %(comment)s
                )
                RETURNING *
                """,
                {
                    "session_id": session_id,
                    "rating": request.rating,
                    "was_convenient": request.was_convenient,
                    "too_much_walking": request.too_much_walking,
                    "pois_were_interesting": request.pois_were_interesting,
                    "comment": request.comment,
                },
            )
            feedback = cur.fetchone()
            conn.commit()

    return feedback


def get_route_session(session_id: UUID) -> dict:
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
                """,
                (session_id,),
            )
            session = cur.fetchone()
            if session is None:
                raise_not_found()

            return session_with_children(cur, session)


def get_route_sessions_for_device(device_id: str) -> list[dict]:
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
                ORDER BY rs.started_at DESC, rs.created_at DESC
                """,
                (device_id,),
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


def get_city_id(cur, city: str) -> int:
    city_profile = city_profile_by_token(city) or {}
    city_name = city_profile.get("name") or city

    cur.execute(
        "SELECT id FROM cities WHERE lower(name) = lower(%s)",
        (city_name,),
    )
    city_row = cur.fetchone()
    if city_row is None:
        raise HTTPException(status_code=404, detail="City not found.")

    return city_row["id"]


def ensure_session_exists(cur, session_id: UUID) -> None:
    cur.execute(
        "SELECT id FROM route_sessions WHERE id = %s",
        (session_id,),
    )
    if cur.fetchone() is None:
        raise_not_found()


def validate_status(status: str) -> None:
    if status not in SESSION_STATUSES:
        raise HTTPException(status_code=400, detail="Invalid route session status.")


def raise_not_found() -> None:
    raise HTTPException(status_code=404, detail="Route session not found.")


def now_utc() -> datetime:
    return datetime.now(timezone.utc)

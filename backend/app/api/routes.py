from uuid import UUID

from fastapi import APIRouter, Header

from app.db.database import get_connection
from app.services.city_lookup import find_city_row
from app.services.city_profiles import city_profile_by_token, load_city_profiles, normalized_city_token
from app.schemas.route import RouteGenerateRequest, RouteLegRequest
from app.services.route_planner import generate_route, generate_route_leg
from app.services.route_sessions import (
    RouteFeedbackRequest,
    RouteSessionCreateRequest,
    RouteSessionPoiVisitRequest,
    RouteSessionUpdateRequest,
    create_route_session,
    get_route_session,
    get_route_sessions_for_device,
    mark_route_session_poi_visited,
    save_route_feedback,
    update_route_session,
)

router = APIRouter()


@router.get("/health")
def health():
    return {"status": "ok"}


@router.get("/cities")
def get_cities():
    with get_connection() as conn:
        with conn.cursor() as cur:
            cur.execute(
                """
                SELECT id, name, country, center_lat, center_lon
                FROM cities
                ORDER BY name, id
                """
            )
            rows = cur.fetchall()

    rows_by_name = {}
    for row in rows:
        rows_by_name.setdefault(normalized_city_token(row["name"]), row)
    city_profiles = load_city_profiles()
    ordered_rows = []

    for profile in city_profiles:
        row = rows_by_name.get(normalized_city_token(str(profile.get("name", ""))))
        if row is None:
            continue

        ordered_rows.append(
            {
                **row,
                "slug": profile.get("slug"),
                "bbox": profile.get("bbox"),
                "available_categories": profile.get("available_categories") or [],
                "default_zoom": profile.get("default_zoom"),
                "routing_limits": profile.get("routing_limits") or {},
                "transport": profile.get("transport") or {},
            }
        )

    return ordered_rows


@router.get("/pois")
def get_pois(city: str = "nitra"):
    city_profile = city_profile_by_token(city) or {}

    with get_connection() as conn:
        with conn.cursor() as cur:
            city_row = find_city_row(cur, city_profile.get("name") or city)
            if city_row is None:
                return []

            cur.execute(
                """
                SELECT
                    p.id,
                    p.name,
                    p.category,
                    p.lat,
                    p.lon,
                    p.opening_hours_raw,
                    p.visit_duration_min,
                    p.base_score,
                    p.wikipedia_url
                FROM pois p
                WHERE p.city_id = %s
                ORDER BY p.base_score DESC NULLS LAST, p.name
                """,
                (city_row["id"],),
            )
            return cur.fetchall()


@router.post("/route/generate")
def generate_route_endpoint(request: RouteGenerateRequest):
    return generate_route(request)


@router.post("/route/leg")
def generate_route_leg_endpoint(request: RouteLegRequest):
    return generate_route_leg(request)


@router.post("/route-sessions")
def create_route_session_endpoint(
    request: RouteSessionCreateRequest,
    x_device_id: str = Header(..., alias="X-Device-Id"),
):
    return create_route_session(request, x_device_id)


@router.patch("/route-sessions/{session_id}")
def update_route_session_endpoint(
    session_id: UUID,
    request: RouteSessionUpdateRequest,
    x_device_id: str = Header(..., alias="X-Device-Id"),
):
    return update_route_session(session_id, request, x_device_id)


@router.post("/route-sessions/{session_id}/pois/{poi_id}/visit")
def mark_route_session_poi_visited_endpoint(
    session_id: UUID,
    poi_id: int,
    request: RouteSessionPoiVisitRequest,
    x_device_id: str = Header(..., alias="X-Device-Id"),
):
    return mark_route_session_poi_visited(session_id, poi_id, request, x_device_id)


@router.post("/route-sessions/{session_id}/feedback")
def save_route_feedback_endpoint(
    session_id: UUID,
    request: RouteFeedbackRequest,
    x_device_id: str = Header(..., alias="X-Device-Id"),
):
    return save_route_feedback(session_id, request, x_device_id)


@router.get("/route-sessions/{session_id}")
def get_route_session_endpoint(
    session_id: UUID,
    x_device_id: str = Header(..., alias="X-Device-Id"),
):
    return get_route_session(session_id, x_device_id)


@router.get("/route-sessions")
def get_route_sessions_endpoint(
    device_id: str,
    x_device_id: str = Header(..., alias="X-Device-Id"),
):
    return get_route_sessions_for_device(device_id, x_device_id)

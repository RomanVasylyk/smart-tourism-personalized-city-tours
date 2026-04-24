from uuid import UUID

from fastapi import APIRouter

from app.db.database import get_connection
from app.services.route_planner import RouteGenerateRequest, generate_route
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
                ORDER BY name
                """
            )
            return cur.fetchall()


@router.get("/pois")
def get_pois(city: str = "nitra"):
    with get_connection() as conn:
        with conn.cursor() as cur:
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
                         JOIN cities c ON c.id = p.city_id
                WHERE lower(c.name) = lower(%s)
                ORDER BY p.base_score DESC NULLS LAST, p.name
                """,
                (city,),
            )
            return cur.fetchall()


@router.post("/route/generate")
def generate_route_endpoint(request: RouteGenerateRequest):
    return generate_route(request)


@router.post("/route-sessions")
def create_route_session_endpoint(request: RouteSessionCreateRequest):
    return create_route_session(request)


@router.patch("/route-sessions/{session_id}")
def update_route_session_endpoint(session_id: UUID, request: RouteSessionUpdateRequest):
    return update_route_session(session_id, request)


@router.post("/route-sessions/{session_id}/pois/{poi_id}/visit")
def mark_route_session_poi_visited_endpoint(
    session_id: UUID,
    poi_id: int,
    request: RouteSessionPoiVisitRequest,
):
    return mark_route_session_poi_visited(session_id, poi_id, request)


@router.post("/route-sessions/{session_id}/feedback")
def save_route_feedback_endpoint(session_id: UUID, request: RouteFeedbackRequest):
    return save_route_feedback(session_id, request)


@router.get("/route-sessions/{session_id}")
def get_route_session_endpoint(session_id: UUID):
    return get_route_session(session_id)


@router.get("/route-sessions")
def get_route_sessions_endpoint(device_id: str):
    return get_route_sessions_for_device(device_id)

from uuid import UUID

from fastapi import APIRouter, Header

from app.repositories.cities import list_city_rows
from app.repositories.pois import list_poi_rows
from app.schemas.responses import (
    CityResponse,
    HealthResponse,
    PoiResponse,
    RouteFeedbackResponse,
    RouteLegResponse,
    RouteResponse,
    RouteSessionPoiResponse,
    RouteSessionResponse,
)
from app.schemas.route import RouteGenerateRequest, RouteLegRequest
from app.schemas.route_sessions import (
    RouteFeedbackRequest,
    RouteSessionCreateRequest,
    RouteSessionPoiVisitRequest,
    RouteSessionUpdateRequest,
)
from app.services.route_planner import generate_route, generate_route_leg
from app.services.route_sessions import (
    create_route_session,
    get_route_session,
    get_route_sessions_for_device,
    mark_route_session_poi_visited,
    save_route_feedback,
    update_route_session,
)

router = APIRouter()


@router.get("/health", response_model=HealthResponse)
def health():
    return {"status": "ok"}


@router.get("/cities", response_model=list[CityResponse])
def get_cities():
    return list_city_rows()


@router.get("/pois", response_model=list[PoiResponse])
def get_pois(city: str = "nitra"):
    return list_poi_rows(city)


@router.post("/route/generate", response_model=RouteResponse)
def generate_route_endpoint(request: RouteGenerateRequest):
    return generate_route(request)


@router.post("/route/leg", response_model=RouteLegResponse)
def generate_route_leg_endpoint(request: RouteLegRequest):
    return generate_route_leg(request)


@router.post("/route-sessions", response_model=RouteSessionResponse)
def create_route_session_endpoint(
    request: RouteSessionCreateRequest,
    x_device_id: str = Header(..., alias="X-Device-Id"),
    x_device_token: str = Header(..., alias="X-Device-Token"),
):
    return create_route_session(request, x_device_id, x_device_token)


@router.patch("/route-sessions/{session_id}", response_model=RouteSessionResponse)
def update_route_session_endpoint(
    session_id: UUID,
    request: RouteSessionUpdateRequest,
    x_device_id: str = Header(..., alias="X-Device-Id"),
    x_device_token: str = Header(..., alias="X-Device-Token"),
):
    return update_route_session(session_id, request, x_device_id, x_device_token)


@router.post("/route-sessions/{session_id}/pois/{poi_id}/visit", response_model=RouteSessionPoiResponse)
def mark_route_session_poi_visited_endpoint(
    session_id: UUID,
    poi_id: int,
    request: RouteSessionPoiVisitRequest,
    x_device_id: str = Header(..., alias="X-Device-Id"),
    x_device_token: str = Header(..., alias="X-Device-Token"),
):
    return mark_route_session_poi_visited(session_id, poi_id, request, x_device_id, x_device_token)


@router.post("/route-sessions/{session_id}/feedback", response_model=RouteFeedbackResponse)
def save_route_feedback_endpoint(
    session_id: UUID,
    request: RouteFeedbackRequest,
    x_device_id: str = Header(..., alias="X-Device-Id"),
    x_device_token: str = Header(..., alias="X-Device-Token"),
):
    return save_route_feedback(session_id, request, x_device_id, x_device_token)


@router.get("/route-sessions/{session_id}", response_model=RouteSessionResponse)
def get_route_session_endpoint(
    session_id: UUID,
    x_device_id: str = Header(..., alias="X-Device-Id"),
    x_device_token: str = Header(..., alias="X-Device-Token"),
):
    return get_route_session(session_id, x_device_id, x_device_token)


@router.get("/route-sessions", response_model=list[RouteSessionResponse])
def get_route_sessions_endpoint(
    device_id: str,
    x_device_id: str = Header(..., alias="X-Device-Id"),
    x_device_token: str = Header(..., alias="X-Device-Token"),
):
    return get_route_sessions_for_device(device_id, x_device_id, x_device_token)

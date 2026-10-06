from uuid import UUID

from app.core.rate_limit import (
    CATALOG_RATE_LIMIT,
    ROUTE_GENERATE_RATE_LIMIT,
    ROUTE_LEG_RATE_LIMIT,
    limiter,
)
from app.db.database import get_connection
from app.repositories.cities import list_city_rows
from app.repositories.pois import list_poi_rows
from app.schemas.responses import (
    CityResponse,
    CuratedRouteDetailResponse,
    CuratedRouteSummaryResponse,
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
from app.services.curated_routes import get_curated_route, list_curated_routes
from app.services.route_planner import generate_route, generate_route_leg
from app.services.route_sessions import (
    create_route_session,
    get_route_session,
    get_route_sessions_for_device,
    mark_route_session_poi_visited,
    save_route_feedback,
    update_route_session,
)
from fastapi import APIRouter, Header, HTTPException, Request

router = APIRouter()

MAX_POI_PAGE_SIZE = 5000


@router.get("/health", response_model=HealthResponse)
def health():
    return {"status": "ok"}


@router.get("/health/ready", response_model=HealthResponse)
def health_ready():
    try:
        with get_connection() as conn:
            with conn.cursor() as cur:
                cur.execute("SELECT 1")
                cur.fetchone()
    except Exception as exc:
        raise HTTPException(status_code=503, detail="Database is not reachable.") from exc
    return {"status": "ready"}


@router.get("/cities", response_model=list[CityResponse])
@limiter.limit(CATALOG_RATE_LIMIT)
def get_cities(request: Request):
    return list_city_rows()


@router.get("/pois", response_model=list[PoiResponse])
@limiter.limit(CATALOG_RATE_LIMIT)
def get_pois(request: Request, city: str = "nitra", limit: int = MAX_POI_PAGE_SIZE):
    return list_poi_rows(city, max(1, min(limit, MAX_POI_PAGE_SIZE)))


@router.get("/cities/{slug}/curated-routes", response_model=list[CuratedRouteSummaryResponse])
@limiter.limit(CATALOG_RATE_LIMIT)
def get_curated_routes_endpoint(request: Request, slug: str):
    return list_curated_routes(slug)


@router.get("/curated-routes/{route_id}", response_model=CuratedRouteDetailResponse)
def get_curated_route_endpoint(route_id: int, start_datetime: str | None = None):
    return get_curated_route(route_id, start_datetime)


@router.post("/route/generate", response_model=RouteResponse)
@limiter.limit(ROUTE_GENERATE_RATE_LIMIT)
def generate_route_endpoint(request: Request, payload: RouteGenerateRequest):
    return generate_route(payload)


@router.post("/route/leg", response_model=RouteLegResponse)
@limiter.limit(ROUTE_LEG_RATE_LIMIT)
def generate_route_leg_endpoint(request: Request, payload: RouteLegRequest):
    return generate_route_leg(payload)


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
    limit: int = 100,
    offset: int = 0,
    x_device_id: str = Header(..., alias="X-Device-Id"),
    x_device_token: str = Header(..., alias="X-Device-Token"),
):
    return get_route_sessions_for_device(device_id, x_device_id, x_device_token, limit, offset)

from datetime import datetime

from app.repositories.poi_candidates import get_route_candidates
from app.schemas.route import RouteGenerateRequest, RouteLegRequest
from app.services.city_profiles import city_profile_by_token
from app.services.feedback_stats import load_planner_feedback_profile
from app.services.route_planning.engine import RoutePlannerEngine
from app.services.route_planning.models import CandidatePoi
from app.services.route_planning.response import leg_dict, point_dict, route_response_dict
from app.services.route_planning.result import RoutePlanningResult
from app.services.routing_service import RoutePoint, get_routing_service
from app.services.transport_planner import normalized_transport_mode, plan_travel
from fastapi import HTTPException


def parse_start_datetime(raw_value: str | None) -> datetime:
    if raw_value is None:
        return datetime.now().replace(second=0, microsecond=0)

    try:
        return datetime.fromisoformat(raw_value).replace(second=0, microsecond=0)
    except ValueError as exc:
        raise HTTPException(
            status_code=400,
            detail="Invalid start_datetime. Use ISO local datetime, for example 2026-04-19T14:30.",
        ) from exc


def generate_route_leg(request: RouteLegRequest) -> dict:
    city_profile = city_profile_by_token(request.city) or {}
    effective_transport_mode = normalized_transport_mode(request.transport_mode, city_profile)
    departure_dt = parse_start_datetime(request.start_datetime)
    routing_service = get_routing_service()

    start = RoutePoint(lat=request.start_lat, lon=request.start_lon)
    end = RoutePoint(lat=request.end_lat, lon=request.end_lon)
    travel_plan = plan_travel(
        start=start,
        end=end,
        pace=request.pace,
        routing_service=routing_service,
        city_profile=city_profile,
        transport_mode=effective_transport_mode,
        departure_dt=departure_dt,
    )

    return leg_dict(
        order=1,
        from_point=point_dict("start", request.start_lat, request.start_lon),
        to_point=point_dict(
            "poi",
            request.end_lat,
            request.end_lon,
            {
                "id": request.end_poi_id,
                "name": request.end_name,
            },
        ),
        travel_plan=travel_plan,
    )


def generate_route(request: RouteGenerateRequest) -> dict:
    return route_response_dict(plan_route(request))


def cap_available_minutes(request: RouteGenerateRequest, city_profile: dict) -> RouteGenerateRequest:
    max_minutes = (city_profile.get("routing_limits") or {}).get("max_available_minutes")
    if max_minutes is None:
        return request
    capped = min(request.available_minutes, int(max_minutes))
    if capped == request.available_minutes:
        return request
    return request.model_copy(update={"available_minutes": capped})


def plan_route(request: RouteGenerateRequest) -> RoutePlanningResult:
    start_dt = parse_start_datetime(request.start_datetime)
    city_profile = city_profile_by_token(request.city) or {}
    request = cap_available_minutes(request, city_profile)
    effective_transport_mode = normalized_transport_mode(request.transport_mode, city_profile)
    feedback_profile = load_planner_feedback_profile(request.city, effective_transport_mode)
    candidates = [CandidatePoi.from_row(row) for row in get_route_candidates(request)]

    if not candidates:
        raise HTTPException(
            status_code=404,
            detail="No POIs found for the selected city/interests.",
        )

    engine = RoutePlannerEngine(
        request=request,
        start_dt=start_dt,
        city_profile=city_profile,
        effective_transport_mode=effective_transport_mode,
        feedback_profile=feedback_profile,
        candidates=candidates,
        routing_service=get_routing_service(),
    )
    return engine.plan()

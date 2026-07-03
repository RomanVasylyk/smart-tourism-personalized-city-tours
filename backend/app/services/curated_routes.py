from __future__ import annotations

from datetime import timedelta

from app.repositories.curated_routes import (
    get_curated_route_row,
    list_curated_route_poi_rows,
    list_curated_route_rows,
)
from app.services.city_profiles import city_profile_by_token
from app.services.route_planner import parse_start_datetime
from app.services.route_planning.models import CandidateEvaluation, CandidatePoi
from app.services.route_planning.response import route_response_dict
from app.services.route_planning.result import RoutePlanningResult
from app.services.route_planning.timeline import RouteTimelineBuilder
from app.services.routing_service import RoutePoint, get_routing_service
from app.services.transport_planner import normalized_transport_mode, plan_travel
from fastapi import HTTPException

DEFAULT_VISIT_DURATION_MIN = 20


def list_curated_routes(city: str) -> list[dict]:
    return [curated_route_summary_dict(row) for row in list_curated_route_rows(city)]


def get_curated_route(route_id: int, start_datetime: str | None = None) -> dict:
    route_row = get_curated_route_row(route_id)
    if route_row is None:
        raise HTTPException(status_code=404, detail="Curated route not found.")

    poi_rows = list_curated_route_poi_rows(route_id)
    if not poi_rows:
        raise HTTPException(
            status_code=409,
            detail="Curated route has no active points of interest.",
        )

    route_response = materialize_curated_route(route_row, poi_rows, start_datetime)
    return {
        "id": int(route_row["id"]),
        "slug": route_row.get("slug"),
        "title": route_row["title"],
        "summary": route_row.get("summary"),
        "theme": route_row.get("theme"),
        "city": route_response["city"],
        "recommended_duration_min": route_row.get("recommended_duration_min"),
        "pace": route_response["pace"],
        "transport_mode": route_response["transport_mode"],
        "return_to_start": route_response["return_to_start"],
        "poi_count": route_response["poi_count"],
        "route": route_response,
    }


def curated_route_summary_dict(row: dict) -> dict:
    return {
        "id": int(row["id"]),
        "slug": row.get("slug"),
        "title": row["title"],
        "summary": row.get("summary"),
        "theme": row.get("theme"),
        "city": row.get("city_name"),
        "recommended_duration_min": row.get("recommended_duration_min"),
        "pace": row.get("pace"),
        "transport_mode": row.get("transport_mode"),
        "return_to_start": row.get("return_to_start"),
        "poi_count": int(row.get("poi_count") or 0),
        "stop_names": list(row.get("stop_names") or []),
    }


def materialize_curated_route(route_row: dict, poi_rows: list[dict], start_datetime: str | None) -> dict:
    city_profile = city_profile_by_token(route_row.get("city_name")) or {}
    city_token = city_profile.get("slug") or route_row.get("city_name") or ""
    pace = route_row.get("pace") or "normal"
    effective_transport_mode = normalized_transport_mode(route_row.get("transport_mode") or "walk", city_profile)
    start_dt = parse_start_datetime(start_datetime)
    routing_service = get_routing_service()

    start_point = RoutePoint(lat=float(route_row["start_lat"]), lon=float(route_row["start_lon"]))
    timeline = RouteTimelineBuilder(start_point=start_point)

    for poi_row in poi_rows:
        candidate = CandidatePoi.from_row(poi_row)
        departure_dt = start_dt + timedelta(seconds=timeline.elapsed_actual_seconds)
        travel_plan = plan_travel(
            start=timeline.current_point,
            end=candidate.point,
            pace=pace,
            routing_service=routing_service,
            city_profile=city_profile,
            transport_mode=effective_transport_mode,
            departure_dt=departure_dt,
        )
        visit_minutes = int(
            poi_row.get("override_visit_duration_min") or candidate.visit_duration_min or DEFAULT_VISIT_DURATION_MIN
        )
        timeline.add_stop(
            CandidateEvaluation(
                poi=candidate,
                travel_plan=travel_plan,
                visit_minutes=visit_minutes,
                utility=0.0,
                score_breakdown={},
            )
        )

    if route_row.get("return_to_start") and timeline.route_items:
        return_departure_dt = start_dt + timedelta(seconds=timeline.elapsed_actual_seconds)
        return_plan = plan_travel(
            start=timeline.current_point,
            end=start_point,
            pace=pace,
            routing_service=routing_service,
            city_profile=city_profile,
            transport_mode=effective_transport_mode,
            departure_dt=return_departure_dt,
        )
        timeline.add_return_to_start(return_plan)

    total_visit_minutes = sum(item["visit_duration_min"] for item in timeline.route_items)
    total_walk_minutes = timeline.elapsed_minutes - total_visit_minutes
    recommended_minutes = route_row.get("recommended_duration_min")
    available_minutes = int(recommended_minutes) if recommended_minutes else timeline.elapsed_minutes

    result = RoutePlanningResult(
        city=city_token,
        start_lat=start_point.lat,
        start_lon=start_point.lon,
        start_datetime=start_dt,
        pace=pace,
        interests=[],
        transport_mode=effective_transport_mode,
        return_to_start=bool(route_row.get("return_to_start")),
        respect_opening_hours=False,
        available_minutes=max(available_minutes, timeline.elapsed_minutes),
        used_minutes=timeline.elapsed_minutes,
        total_visit_minutes=total_visit_minutes,
        total_walk_minutes=total_walk_minutes,
        return_to_start_minutes=timeline.return_to_start_minutes,
        route_items=timeline.route_items,
        legs=timeline.legs,
        full_geometry=timeline.full_geometry,
    )
    return route_response_dict(result)

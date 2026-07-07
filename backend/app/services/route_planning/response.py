from __future__ import annotations

from datetime import datetime

from app.services.route_planning.result import RoutePlanningResult
from app.services.transport_planner import TravelPlan, TravelSegment


def point_dict(point_type: str, lat: float, lon: float, poi: dict | None = None) -> dict:
    return {
        "type": point_type,
        "poi_id": poi["id"] if poi else None,
        "name": poi["name"] if poi else None,
        "lat": lat,
        "lon": lon,
    }


def leg_dict(
    order: int,
    from_point: dict,
    to_point: dict,
    travel_plan: TravelPlan,
) -> dict:
    first_segment = travel_plan.segments[0] if travel_plan.segments else None
    last_segment = travel_plan.segments[-1] if travel_plan.segments else None
    return {
        "order": order,
        "mode": travel_plan.mode,
        "from": from_point,
        "to": to_point,
        "duration_seconds": round(travel_plan.duration_seconds, 1),
        "duration_minutes": travel_plan.duration_minutes,
        "distance_meters": round(travel_plan.distance_meters, 1),
        "geometry": travel_plan.geometry,
        "routing_source": travel_plan.source,
        "departure_time": format_optional_datetime(first_segment.departure_time if first_segment else None),
        "arrival_time": format_optional_datetime(last_segment.arrival_time if last_segment else None),
        "segments": [segment_dict(index + 1, segment) for index, segment in enumerate(travel_plan.segments)],
    }


def segment_dict(order: int, segment: TravelSegment) -> dict:
    return {
        "order": order,
        "mode": segment.mode,
        "duration_seconds": round(segment.duration_seconds, 1),
        "duration_minutes": segment.duration_minutes,
        "distance_meters": round(segment.distance_meters, 1),
        "geometry": segment.geometry,
        "source": segment.source,
        "line_name": segment.line_name,
        "from_stop_name": segment.from_stop_name,
        "to_stop_name": segment.to_stop_name,
        "departure_time": format_optional_datetime(segment.departure_time),
        "arrival_time": format_optional_datetime(segment.arrival_time),
        "wait_minutes_before_departure": segment.wait_minutes_before_departure,
        "in_vehicle_minutes": segment.in_vehicle_minutes,
    }


def format_optional_datetime(value: datetime | None) -> str | None:
    if value is None:
        return None
    return value.isoformat(timespec="minutes")


def append_geometry(full_geometry: list[dict], leg_geometry: list[dict]) -> None:
    if not leg_geometry:
        return

    if not full_geometry:
        full_geometry.extend(leg_geometry)
        return

    full_geometry.extend(leg_geometry[1:])


FALLBACK_ROUTING_SOURCE = "haversine_fallback"


def routing_fallback_stats(legs: list[dict]) -> tuple[int, int]:

    fallback_count = 0
    for leg in legs:
        sources = {leg.get("routing_source")}
        for segment in leg.get("segments") or []:
            sources.add(segment.get("source"))
        if FALLBACK_ROUTING_SOURCE in sources:
            fallback_count += 1
    return fallback_count, len(legs)


def route_response_dict(result: RoutePlanningResult) -> dict:
    fallback_leg_count, total_leg_count = routing_fallback_stats(result.legs)
    return {
        "city": result.city,
        "start": {"lat": result.start_lat, "lon": result.start_lon},
        "start_datetime": result.start_datetime.isoformat(timespec="minutes"),
        "pace": result.pace,
        "interests": result.interests,
        "transport_mode": result.transport_mode,
        "return_to_start": result.return_to_start,
        "respect_opening_hours": result.respect_opening_hours,
        "available_minutes": result.available_minutes,
        "used_minutes": result.used_minutes,
        "remaining_minutes": max(0, result.available_minutes - result.used_minutes),
        "total_visit_minutes": result.total_visit_minutes,
        "total_walk_minutes": result.total_walk_minutes,
        "return_to_start_minutes": result.return_to_start_minutes,
        "poi_count": len(result.route_items),
        "routing_leg_count": total_leg_count,
        "routing_fallback_leg_count": fallback_leg_count,
        "routing_degraded": fallback_leg_count > 0,
        "route": result.route_items,
        "legs": result.legs,
        "full_geometry": result.full_geometry,
    }

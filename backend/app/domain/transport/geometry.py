from datetime import date, datetime, timedelta

from app.services.routing_service import RoutePoint, RoutingLeg, RoutingService, haversine_km


def route_geometry_leg(
    routing_service: RoutingService,
    start: RoutePoint,
    end: RoutePoint,
    profile: str,
) -> RoutingLeg:
    if hasattr(routing_service, "route_geometry_between"):
        return routing_service.route_geometry_between(start, end, profile=profile)
    return routing_service.route_between(start, end, "normal")


def merged_geometry(geometries) -> list[dict]:
    merged: list[dict] = []

    for geometry in geometries:
        if not geometry:
            continue
        if not merged:
            merged.extend(geometry)
            continue
        merged.extend(geometry[1:])

    return merged


def polyline_distance_meters(geometry: list[dict]) -> float:
    if len(geometry) < 2:
        return 0.0

    total_meters = 0.0
    for current, nxt in zip(geometry, geometry[1:], strict=False):
        total_meters += haversine_km(current["lat"], current["lon"], nxt["lat"], nxt["lon"]) * 1_000
    return total_meters


def service_datetime(service_date: date, time_minutes: int) -> datetime:
    midnight = datetime.combine(service_date, datetime.min.time())
    return midnight + timedelta(minutes=time_minutes)

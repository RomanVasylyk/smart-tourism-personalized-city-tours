from datetime import datetime

from app.domain.transport.estimated_transit import build_estimated_transit_segments, find_estimated_transit_solution
from app.domain.transport.exact_transit import build_exact_transit_plan
from app.domain.transport.models import (
    STOP_CANDIDATE_LIMIT,
    TRANSPORT_MODE_WALK,
    TRANSPORT_MODE_WALK_OR_MHD,
    TravelPlan,
    TravelSegment,
)
from app.domain.transport.segments import (
    build_transit_plan_from_segments,
    build_walk_transfer_segment,
    travel_plan_from_walk_leg,
)
from app.services.routing_service import RoutePoint, RoutingLeg, RoutingService, haversine_km
from app.services.transport_graph import GraphStop, TransportGraph, load_transport_graph


def normalized_transport_mode(requested_mode: str | None, city_profile: dict | None = None) -> str:
    requested_mode = (requested_mode or TRANSPORT_MODE_WALK).strip().lower()
    if requested_mode != TRANSPORT_MODE_WALK_OR_MHD:
        return TRANSPORT_MODE_WALK

    transport_profile = (city_profile or {}).get("transport") or {}
    if transport_profile.get("mhd_enabled") is True:
        return TRANSPORT_MODE_WALK_OR_MHD

    return TRANSPORT_MODE_WALK


def plan_travel(
    start: RoutePoint,
    end: RoutePoint,
    pace: str,
    routing_service: RoutingService,
    city_profile: dict | None,
    transport_mode: str,
    departure_dt: datetime | None = None,
) -> TravelPlan:
    walk_leg = routing_service.route_between(start, end, pace)
    walk_plan = travel_plan_from_walk_leg(walk_leg, departure_dt=departure_dt)

    if normalized_transport_mode(transport_mode, city_profile) != TRANSPORT_MODE_WALK_OR_MHD:
        return walk_plan

    transport_profile = (city_profile or {}).get("transport") or {}
    best_transit_plan = best_transit_plan_for_leg(
        start=start,
        end=end,
        pace=pace,
        walk_leg=walk_leg,
        routing_service=routing_service,
        city_profile=city_profile or {},
        transport_profile=transport_profile,
        departure_dt=departure_dt,
    )
    if best_transit_plan is None:
        return walk_plan

    return best_transit_plan


def best_transit_plan_for_leg(
    start: RoutePoint,
    end: RoutePoint,
    pace: str,
    walk_leg: RoutingLeg,
    routing_service: RoutingService,
    city_profile: dict,
    transport_profile: dict,
    departure_dt: datetime | None,
) -> TravelPlan | None:
    graph = load_transport_graph(city_profile)
    if graph is None:
        return None

    min_direct_distance = float(transport_profile.get("min_direct_distance_meters") or 1_200)
    if walk_leg.distance_meters < min_direct_distance:
        return None

    max_first_mile = float(transport_profile.get("max_first_mile_meters") or 650)
    max_last_mile = float(transport_profile.get("max_last_mile_meters") or 650)
    average_wait_minutes = float(transport_profile.get("average_wait_minutes") or 4.0)
    max_total_duration_multiplier = float(transport_profile.get("max_total_duration_multiplier") or 1.15)
    min_walking_distance_savings = float(transport_profile.get("min_walking_distance_savings_meters") or 500)

    start_candidates = candidate_stops_for_point(start, graph, pace, max_first_mile)
    end_candidates = candidate_stops_for_point(end, graph, pace, max_last_mile)
    if not start_candidates or not end_candidates:
        return None

    exact_plan = build_exact_transit_plan(
        graph=graph,
        start=start,
        end=end,
        pace=pace,
        routing_service=routing_service,
        departure_dt=departure_dt,
        start_candidates=start_candidates,
        end_candidates=end_candidates,
        walk_leg=walk_leg,
        max_total_duration_multiplier=max_total_duration_multiplier,
        min_walking_distance_savings=min_walking_distance_savings,
    )
    if exact_plan is not None:
        return exact_plan

    estimated_solution = find_estimated_transit_solution(
        graph=graph,
        start_candidates=start_candidates,
        end_candidates=end_candidates,
        average_wait_minutes=average_wait_minutes,
    )
    if estimated_solution is None:
        return None

    _, path_steps, _ = estimated_solution
    transit_segments = build_estimated_transit_segments(graph, path_steps, routing_service)
    if not transit_segments:
        return None

    return build_transit_plan_from_segments(
        start=start,
        end=end,
        pace=pace,
        routing_service=routing_service,
        walk_leg=walk_leg,
        graph=graph,
        path_start_stop_id=path_steps[0][0].from_stop_id,
        path_end_stop_id=path_steps[-1][0].to_stop_id,
        transit_segments=transit_segments,
        departure_dt=departure_dt,
        max_total_duration_multiplier=max_total_duration_multiplier,
        min_walking_distance_savings=min_walking_distance_savings,
        source=graph.provider,
    )


def candidate_stops_for_point(
    point: RoutePoint,
    graph: TransportGraph,
    pace: str,
    max_distance_meters: float,
) -> list[tuple[GraphStop, TravelSegment]]:
    candidates: list[tuple[float, GraphStop, TravelSegment]] = []
    for stop in graph.stops_by_id.values():
        distance_meters = haversine_km(point.lat, point.lon, stop.point.lat, stop.point.lon) * 1_000
        if distance_meters > max_distance_meters:
            continue
        walk_segment = build_walk_transfer_segment(point, stop.point, pace, "walk_transfer")
        candidates.append((distance_meters, stop, walk_segment))

    candidates.sort(key=lambda item: (item[0], item[1].name))
    return [(stop, segment) for _, stop, segment in candidates[:STOP_CANDIDATE_LIMIT]]

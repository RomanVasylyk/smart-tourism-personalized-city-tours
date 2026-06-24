from dataclasses import replace
from datetime import datetime, timedelta

from app.domain.transport.geometry import merged_geometry, polyline_distance_meters, route_geometry_leg
from app.domain.transport.models import ScheduledRide, TravelPlan, TravelSegment
from app.services.routing_service import RoutePoint, RoutingLeg, RoutingService, haversine_km, walking_speed_kmh
from app.services.transport_graph import TransportGraph


def travel_plan_from_walk_leg(walk_leg: RoutingLeg, departure_dt: datetime | None = None) -> TravelPlan:
    walk_segment = TravelSegment(
        mode="walk",
        duration_seconds=walk_leg.duration_seconds,
        distance_meters=walk_leg.distance_meters,
        geometry=walk_leg.geometry,
        source=walk_leg.source,
        departure_time=departure_dt,
        arrival_time=departure_dt + timedelta(seconds=walk_leg.duration_seconds) if departure_dt else None,
    )
    return TravelPlan(
        mode="walk",
        duration_seconds=walk_leg.duration_seconds,
        distance_meters=walk_leg.distance_meters,
        geometry=walk_leg.geometry,
        source=walk_leg.source,
        segments=[walk_segment],
    )


def build_exact_transit_segments(
    graph: TransportGraph,
    rides: list[ScheduledRide],
    routing_service: RoutingService,
    initial_stop_arrival_dt: datetime,
) -> list[TravelSegment]:
    segments: list[TravelSegment] = []
    current_arrival_dt = initial_stop_arrival_dt

    for ride in rides:
        trip = graph.trips_by_id.get(ride.trip_id)
        if trip is None:
            continue
        wait_seconds = max(0.0, (ride.board_time - current_arrival_dt).total_seconds())
        path_stop_times = trip.stop_times[ride.board_index : ride.alight_index + 1]
        geometry_legs = []
        for from_stop_time, to_stop_time in zip(path_stop_times, path_stop_times[1:], strict=False):
            from_stop = graph.stops_by_id[from_stop_time.stop_id]
            to_stop = graph.stops_by_id[to_stop_time.stop_id]
            geometry_legs.append(route_geometry_leg(routing_service, from_stop.point, to_stop.point, "driving"))

        geometry = merged_geometry(route_leg.geometry for route_leg in geometry_legs)
        if len(geometry) < 2:
            first_stop = graph.stops_by_id[path_stop_times[0].stop_id]
            last_stop = graph.stops_by_id[path_stop_times[-1].stop_id]
            geometry = [
                {"lat": first_stop.point.lat, "lon": first_stop.point.lon},
                {"lat": last_stop.point.lat, "lon": last_stop.point.lon},
            ]

        in_vehicle_seconds = max(0.0, (ride.alight_time - ride.board_time).total_seconds())
        distance_meters = sum(route_leg.distance_meters for route_leg in geometry_legs) or polyline_distance_meters(
            geometry
        )
        segments.append(
            TravelSegment(
                mode="transit",
                duration_seconds=wait_seconds + in_vehicle_seconds,
                distance_meters=distance_meters,
                geometry=geometry,
                source=trip.source,
                line_name=trip.line_name,
                from_stop_name=path_stop_times[0].stop_name,
                to_stop_name=path_stop_times[-1].stop_name,
                departure_time=ride.board_time,
                arrival_time=ride.alight_time,
                wait_seconds_before_departure=wait_seconds,
                in_vehicle_seconds=in_vehicle_seconds,
            )
        )
        current_arrival_dt = ride.alight_time

    return segments


def build_transit_plan_from_segments(
    start: RoutePoint,
    end: RoutePoint,
    pace: str,
    routing_service: RoutingService,
    walk_leg: RoutingLeg,
    graph: TransportGraph,
    path_start_stop_id: int,
    path_end_stop_id: int,
    transit_segments: list[TravelSegment],
    departure_dt: datetime | None,
    max_total_duration_multiplier: float,
    min_walking_distance_savings: float,
    source: str,
) -> TravelPlan | None:
    if not transit_segments:
        return None

    first_stop = graph.stops_by_id.get(path_start_stop_id)
    last_stop = graph.stops_by_id.get(path_end_stop_id)
    if first_stop is None or last_stop is None:
        return None

    refined_start_segment = refine_walk_segment(
        start=start,
        end=first_stop.point,
        pace=pace,
        routing_service=routing_service,
        source="walk_transfer",
    )
    refined_end_segment = refine_walk_segment(
        start=last_stop.point,
        end=end,
        pace=pace,
        routing_service=routing_service,
        source="walk_transfer",
    )

    walking_savings = walk_leg.distance_meters - (
        refined_start_segment.distance_meters + refined_end_segment.distance_meters
    )
    if walking_savings < min_walking_distance_savings:
        return None

    segments = [
        segment
        for segment in [refined_start_segment, *transit_segments, refined_end_segment]
        if segment.mode == "transit" or segment.distance_meters > 20
    ]
    if not any(segment.mode == "transit" for segment in segments):
        return None

    if departure_dt is not None:
        segments = with_segment_timings(segments, departure_dt)

    total_duration_seconds = sum(segment.duration_seconds for segment in segments)
    if total_duration_seconds <= 0:
        return None
    if total_duration_seconds > walk_leg.duration_seconds * max_total_duration_multiplier:
        return None

    total_distance_meters = sum(segment.distance_meters for segment in segments)
    geometry = merged_geometry(segment.geometry for segment in segments)
    return TravelPlan(
        mode="transit",
        duration_seconds=total_duration_seconds,
        distance_meters=total_distance_meters,
        geometry=geometry,
        source=source,
        segments=segments,
    )


def refine_walk_segment(
    start: RoutePoint,
    end: RoutePoint,
    pace: str,
    routing_service: RoutingService,
    source: str,
) -> TravelSegment:
    walk_leg = routing_service.route_between(start, end, pace)
    return TravelSegment(
        mode="walk",
        duration_seconds=walk_leg.duration_seconds,
        distance_meters=walk_leg.distance_meters,
        geometry=walk_leg.geometry,
        source=source if walk_leg.source == "haversine_fallback" else walk_leg.source,
    )


def with_segment_timings(segments: list[TravelSegment], departure_dt: datetime) -> list[TravelSegment]:
    current_dt = departure_dt
    timed_segments: list[TravelSegment] = []

    for segment in segments:
        if segment.mode == "transit":
            actual_departure = current_dt + timedelta(seconds=segment.wait_seconds_before_departure)
            arrival_dt = current_dt + timedelta(seconds=segment.duration_seconds)
            timed_segments.append(
                replace(
                    segment,
                    departure_time=actual_departure,
                    arrival_time=arrival_dt,
                )
            )
            current_dt = arrival_dt
            continue

        arrival_dt = current_dt + timedelta(seconds=segment.duration_seconds)
        timed_segments.append(
            replace(
                segment,
                departure_time=current_dt,
                arrival_time=arrival_dt,
            )
        )
        current_dt = arrival_dt

    return timed_segments


def build_walk_transfer_segment(
    start: RoutePoint,
    end: RoutePoint,
    pace: str,
    source: str,
) -> TravelSegment:
    distance_meters = haversine_km(start.lat, start.lon, end.lat, end.lon) * 1_000
    if distance_meters < 15:
        duration_seconds = 0.0
    else:
        duration_seconds = (distance_meters / 1_000) / walking_speed_kmh(pace) * 60 * 60

    return TravelSegment(
        mode="walk",
        duration_seconds=duration_seconds,
        distance_meters=distance_meters,
        geometry=[
            {"lat": start.lat, "lon": start.lon},
            {"lat": end.lat, "lon": end.lon},
        ],
        source=source,
    )

from __future__ import annotations

import math
from dataclasses import dataclass, replace
from datetime import date, datetime, timedelta
from heapq import heappop, heappush
from itertools import count

from psycopg import Error as PsycopgError

from app.db.database import get_connection
from app.services.routing_service import RoutePoint, RoutingLeg, RoutingService, haversine_km, walking_speed_kmh

TRANSPORT_MODE_WALK = "walk"
TRANSPORT_MODE_WALK_OR_MHD = "walk_or_mhd"
STOP_CANDIDATE_LIMIT = 12


@dataclass(frozen=True)
class TravelSegment:
    mode: str
    duration_seconds: float
    distance_meters: float
    geometry: list[dict]
    source: str
    line_name: str | None = None
    from_stop_name: str | None = None
    to_stop_name: str | None = None
    departure_time: datetime | None = None
    arrival_time: datetime | None = None
    wait_seconds_before_departure: float = 0.0
    in_vehicle_seconds: float = 0.0

    @property
    def duration_minutes(self) -> int:
        if self.duration_seconds <= 0:
            return 0
        return max(1, round(self.duration_seconds / 60))

    @property
    def wait_minutes_before_departure(self) -> int:
        if self.wait_seconds_before_departure <= 0:
            return 0
        return max(1, round(self.wait_seconds_before_departure / 60))

    @property
    def in_vehicle_minutes(self) -> int:
        if self.in_vehicle_seconds <= 0:
            return 0
        return max(1, round(self.in_vehicle_seconds / 60))


@dataclass(frozen=True)
class TravelPlan:
    mode: str
    duration_seconds: float
    distance_meters: float
    geometry: list[dict]
    source: str
    segments: list[TravelSegment]

    @property
    def duration_minutes(self) -> int:
        if self.duration_seconds <= 0:
            return 0
        return max(1, round(self.duration_seconds / 60))


@dataclass(frozen=True)
class GraphStop:
    stop_id: int
    name: str
    point: RoutePoint


@dataclass(frozen=True)
class GraphEdge:
    line_id: int
    line_name: str
    source: str
    from_stop_id: int
    to_stop_id: int
    from_stop_name: str
    to_stop_name: str
    from_sequence: int
    to_sequence: int
    avg_travel_seconds: float
    distance_meters: float


@dataclass(frozen=True)
class GraphTripStopTime:
    stop_id: int
    stop_name: str
    stop_sequence: int
    time_minutes: int


@dataclass(frozen=True)
class GraphTrip:
    trip_id: int
    line_id: int
    line_name: str
    source: str
    service_bucket: str
    valid_from: date | None
    valid_to: date | None
    stop_times: list[GraphTripStopTime]


@dataclass(frozen=True)
class TripBoardOption:
    trip_id: int
    stop_index: int


@dataclass(frozen=True)
class ScheduledRide:
    trip_id: int
    board_index: int
    alight_index: int
    board_time: datetime
    alight_time: datetime


@dataclass(frozen=True)
class ExactTransitSolution:
    rides: list[ScheduledRide]
    start_stop_id: int
    end_stop_id: int


@dataclass(frozen=True)
class TransportGraph:
    provider: str
    stops_by_id: dict[int, GraphStop]
    outgoing_edges: dict[int, list[GraphEdge]]
    trips_by_id: dict[int, GraphTrip]
    board_options_by_stop: dict[int, list[TripBoardOption]]


SERVICE_BUCKET_WEEKDAYS = {
    "workdays": {0, 1, 2, 3, 4},
    "weekends_holidays": {5, 6},
    "all_days": {0, 1, 2, 3, 4, 5, 6},
}


_TRANSPORT_GRAPH_CACHE: dict[str, TransportGraph | None] = {}


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


def build_exact_transit_plan(
    graph: TransportGraph,
    start: RoutePoint,
    end: RoutePoint,
    pace: str,
    routing_service: RoutingService,
    departure_dt: datetime | None,
    start_candidates: list[tuple[GraphStop, TravelSegment]],
    end_candidates: list[tuple[GraphStop, TravelSegment]],
    walk_leg: RoutingLeg,
    max_total_duration_multiplier: float,
    min_walking_distance_savings: float,
) -> TravelPlan | None:
    if departure_dt is None or not graph.trips_by_id:
        return None

    exact_solution = find_exact_transit_solution(
        graph=graph,
        start_candidates=start_candidates,
        end_candidates=end_candidates,
        departure_dt=departure_dt,
    )
    if exact_solution is None:
        return None

    start_stop = graph.stops_by_id.get(exact_solution.start_stop_id)
    end_stop = graph.stops_by_id.get(exact_solution.end_stop_id)
    if start_stop is None or end_stop is None:
        return None

    refined_start_segment = refine_walk_segment(
        start=start,
        end=start_stop.point,
        pace=pace,
        routing_service=routing_service,
        source="walk_transfer",
    )
    refined_end_segment = refine_walk_segment(
        start=end_stop.point,
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

    first_ride = exact_solution.rides[0]
    start_walk_arrival_dt = departure_dt + timedelta(seconds=refined_start_segment.duration_seconds)
    if start_walk_arrival_dt > first_ride.board_time:
        return None

    transit_segments = build_exact_transit_segments(
        graph=graph,
        rides=exact_solution.rides,
        routing_service=routing_service,
        initial_stop_arrival_dt=start_walk_arrival_dt,
    )
    if not transit_segments:
        return None

    if transit_segments[-1].arrival_time is None:
        return None

    timed_start_segment = replace(
        refined_start_segment,
        departure_time=departure_dt,
        arrival_time=start_walk_arrival_dt,
    )
    timed_end_segment = replace(
        refined_end_segment,
        departure_time=transit_segments[-1].arrival_time,
        arrival_time=transit_segments[-1].arrival_time + timedelta(seconds=refined_end_segment.duration_seconds),
    )

    segments = [
        segment
        for segment in [timed_start_segment, *transit_segments, timed_end_segment]
        if segment.mode == "transit" or segment.distance_meters > 20
    ]
    if not any(segment.mode == "transit" for segment in segments):
        return None

    total_duration_seconds = (
        segments[-1].arrival_time - departure_dt
    ).total_seconds() if segments[-1].arrival_time else 0.0
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
        source=graph.provider,
        segments=segments,
    )


def find_exact_transit_solution(
    graph: TransportGraph,
    start_candidates: list[tuple[GraphStop, TravelSegment]],
    end_candidates: list[tuple[GraphStop, TravelSegment]],
    departure_dt: datetime,
) -> ExactTransitSolution | None:
    arrival_by_stop: dict[int, datetime] = {}
    parent_by_stop: dict[int, tuple[int, ScheduledRide] | None] = {}
    end_walk_by_stop = {stop.stop_id: segment for stop, segment in end_candidates}
    heap: list[tuple[datetime, int, int]] = []
    sequence = count()

    for stop, walk_segment in start_candidates:
        arrival_dt = departure_dt + timedelta(seconds=walk_segment.duration_seconds)
        current_best = arrival_by_stop.get(stop.stop_id)
        if current_best is not None and current_best <= arrival_dt:
            continue
        arrival_by_stop[stop.stop_id] = arrival_dt
        parent_by_stop[stop.stop_id] = None
        heappush(heap, (arrival_dt, next(sequence), stop.stop_id))

    best_final_arrival: datetime | None = None
    best_end_stop_id: int | None = None

    while heap:
        current_arrival_dt, _, stop_id = heappop(heap)
        if current_arrival_dt > arrival_by_stop.get(stop_id, datetime.max):
            continue
        if best_final_arrival is not None and current_arrival_dt >= best_final_arrival:
            continue

        if stop_id in end_walk_by_stop:
            final_arrival_dt = current_arrival_dt + timedelta(seconds=end_walk_by_stop[stop_id].duration_seconds)
            if best_final_arrival is None or final_arrival_dt < best_final_arrival:
                best_final_arrival = final_arrival_dt
                best_end_stop_id = stop_id

        for board_option in graph.board_options_by_stop.get(stop_id, []):
            trip = graph.trips_by_id.get(board_option.trip_id)
            if trip is None or board_option.stop_index >= len(trip.stop_times):
                continue
            if not trip_operates_on_date(trip, current_arrival_dt.date()):
                continue

            board_stop_time = trip.stop_times[board_option.stop_index]
            board_dt = service_datetime(current_arrival_dt.date(), board_stop_time.time_minutes)
            if board_dt < current_arrival_dt:
                continue

            for alight_index in range(board_option.stop_index + 1, len(trip.stop_times)):
                alight_stop_time = trip.stop_times[alight_index]
                if alight_stop_time.time_minutes < board_stop_time.time_minutes:
                    continue
                alight_dt = service_datetime(current_arrival_dt.date(), alight_stop_time.time_minutes)
                next_stop_id = alight_stop_time.stop_id
                if alight_dt >= arrival_by_stop.get(next_stop_id, datetime.max):
                    continue

                arrival_by_stop[next_stop_id] = alight_dt
                parent_by_stop[next_stop_id] = (
                    stop_id,
                    ScheduledRide(
                        trip_id=trip.trip_id,
                        board_index=board_option.stop_index,
                        alight_index=alight_index,
                        board_time=board_dt,
                        alight_time=alight_dt,
                    ),
                )
                heappush(heap, (alight_dt, next(sequence), next_stop_id))

    if best_end_stop_id is None:
        return None

    rides: list[ScheduledRide] = []
    current_stop_id = best_end_stop_id
    while True:
        parent_entry = parent_by_stop.get(current_stop_id)
        if parent_entry is None:
            break
        previous_stop_id, ride = parent_entry
        rides.append(ride)
        current_stop_id = previous_stop_id
    rides.reverse()
    if not rides:
        return None

    return ExactTransitSolution(
        rides=rides,
        start_stop_id=current_stop_id,
        end_stop_id=best_end_stop_id,
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
        for from_stop_time, to_stop_time in zip(path_stop_times, path_stop_times[1:]):
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
        distance_meters = sum(route_leg.distance_meters for route_leg in geometry_legs) or polyline_distance_meters(geometry)
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


def trip_operates_on_date(trip: GraphTrip, service_date: date) -> bool:
    if trip.valid_from is not None and service_date < trip.valid_from:
        return False
    if trip.valid_to is not None and service_date > trip.valid_to:
        return False

    allowed_weekdays = SERVICE_BUCKET_WEEKDAYS.get(trip.service_bucket, SERVICE_BUCKET_WEEKDAYS["all_days"])
    return service_date.weekday() in allowed_weekdays


def find_estimated_transit_solution(
    graph: TransportGraph,
    start_candidates: list[tuple[GraphStop, TravelSegment]],
    end_candidates: list[tuple[GraphStop, TravelSegment]],
    average_wait_minutes: float,
) -> tuple[TravelSegment, list[tuple[GraphEdge, float]], TravelSegment] | None:
    start_states: dict[tuple[int, int | None], TravelSegment] = {}
    end_segments = {stop.stop_id: segment for stop, segment in end_candidates}

    distances: dict[tuple[int, int | None], float] = {}
    parents: dict[tuple[int, int | None], tuple[tuple[int, int | None], GraphEdge, float] | None] = {}
    heap: list[tuple[float, int, int, int | None]] = []
    sequence = count()

    for stop, walk_segment in start_candidates:
        state = (stop.stop_id, None)
        current_best = distances.get(state)
        if current_best is not None and current_best <= walk_segment.duration_seconds:
            continue
        distances[state] = walk_segment.duration_seconds
        parents[state] = None
        start_states[state] = walk_segment
        heappush(heap, (walk_segment.duration_seconds, next(sequence), stop.stop_id, None))

    best_total_seconds: float | None = None
    best_state: tuple[int, int | None] | None = None

    while heap:
        current_seconds, _, stop_id, active_line = heappop(heap)
        state = (stop_id, active_line)
        if current_seconds > distances.get(state, math.inf):
            continue
        if best_total_seconds is not None and current_seconds >= best_total_seconds:
            continue

        if active_line is not None and stop_id in end_segments:
            final_seconds = current_seconds + end_segments[stop_id].duration_seconds
            if best_total_seconds is None or final_seconds < best_total_seconds:
                best_total_seconds = final_seconds
                best_state = state

        for edge in graph.outgoing_edges.get(stop_id, []):
            extra_wait_seconds = 0.0
            if active_line != edge.line_id:
                extra_wait_seconds = average_wait_minutes * 60
            next_state = (edge.to_stop_id, edge.line_id)
            next_seconds = current_seconds + extra_wait_seconds + edge.avg_travel_seconds
            if next_seconds >= distances.get(next_state, math.inf):
                continue
            if best_total_seconds is not None and next_seconds >= best_total_seconds:
                continue

            distances[next_state] = next_seconds
            parents[next_state] = (state, edge, extra_wait_seconds)
            heappush(heap, (next_seconds, next(sequence), edge.to_stop_id, edge.line_id))

    if best_state is None:
        return None

    path_steps: list[tuple[GraphEdge, float]] = []
    current_state = best_state
    while True:
        parent_entry = parents.get(current_state)
        if parent_entry is None:
            break
        previous_state, edge, extra_wait_seconds = parent_entry
        path_steps.append((edge, extra_wait_seconds))
        current_state = previous_state
    path_steps.reverse()

    start_segment = start_states[current_state]
    end_segment = end_segments[best_state[0]]
    return start_segment, path_steps, end_segment


def build_estimated_transit_segments(
    graph: TransportGraph,
    path_steps: list[tuple[GraphEdge, float]],
    routing_service: RoutingService,
) -> list[TravelSegment]:
    if not path_steps:
        return []

    groups: list[dict] = []
    for edge, extra_wait_seconds in path_steps:
        if not groups or groups[-1]["line_id"] != edge.line_id:
            groups.append(
                {
                    "line_id": edge.line_id,
                    "line_name": edge.line_name,
                    "source": edge.source,
                    "extra_wait_seconds": extra_wait_seconds,
                    "edges": [edge],
                }
            )
        else:
            groups[-1]["edges"].append(edge)

    segments: list[TravelSegment] = []
    for group in groups:
        edges = group["edges"]
        route_legs = []
        for edge in edges:
            from_stop = graph.stops_by_id[edge.from_stop_id]
            to_stop = graph.stops_by_id[edge.to_stop_id]
            route_legs.append(route_geometry_leg(routing_service, from_stop.point, to_stop.point, "driving"))

        geometry = merged_geometry(route_leg.geometry for route_leg in route_legs)
        if len(geometry) < 2:
            geometry = [
                {"lat": graph.stops_by_id[edges[0].from_stop_id].point.lat, "lon": graph.stops_by_id[edges[0].from_stop_id].point.lon},
                {"lat": graph.stops_by_id[edges[-1].to_stop_id].point.lat, "lon": graph.stops_by_id[edges[-1].to_stop_id].point.lon},
            ]

        distance_meters = sum(route_leg.distance_meters for route_leg in route_legs) or sum(edge.distance_meters for edge in edges)
        in_vehicle_seconds = sum(edge.avg_travel_seconds for edge in edges)
        wait_seconds = group["extra_wait_seconds"]
        segments.append(
            TravelSegment(
                mode="transit",
                duration_seconds=wait_seconds + in_vehicle_seconds,
                distance_meters=distance_meters,
                geometry=geometry,
                source=group["source"],
                line_name=group["line_name"],
                from_stop_name=edges[0].from_stop_name,
                to_stop_name=edges[-1].to_stop_name,
                wait_seconds_before_departure=wait_seconds,
                in_vehicle_seconds=in_vehicle_seconds,
            )
        )

    return segments


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


def transport_cache_key(city_profile: dict) -> str | None:
    transport_profile = city_profile.get("transport") or {}
    provider = transport_profile.get("provider")
    city_name = city_profile.get("name")
    country = city_profile.get("country")
    if not provider or not city_name:
        return None
    return f"{str(city_name).strip().lower()}|{str(country or '').strip().lower()}|{str(provider).strip().lower()}"


def stop_display_name(name: str, platform_ref: str | None) -> str:
    if platform_ref:
        return f"{name} ({platform_ref})"
    return name


def load_transport_graph(city_profile: dict) -> TransportGraph | None:
    cache_key = transport_cache_key(city_profile)
    if cache_key is None:
        return None
    if cache_key in _TRANSPORT_GRAPH_CACHE:
        return _TRANSPORT_GRAPH_CACHE[cache_key]

    city_name = str(city_profile.get("name") or "").strip()
    country = str(city_profile.get("country") or "").strip()
    graph: TransportGraph | None = None

    try:
        with get_connection() as conn:
            with conn.cursor() as cur:
                cur.execute(
                    """
                    SELECT id
                    FROM cities
                    WHERE lower(name) = lower(%s)
                      AND lower(country) = lower(%s)
                    LIMIT 1;
                    """,
                    (city_name, country),
                )
                city_row = cur.fetchone()
                if not city_row:
                    return None

                city_id = city_row["id"]
                cur.execute(
                    """
                    SELECT id, name, platform_ref, lat, lon
                    FROM transport_stops
                    WHERE city_id = %s
                      AND is_active = TRUE;
                    """,
                    (city_id,),
                )
                stop_rows = cur.fetchall()
                stops_by_id = {
                    row["id"]: GraphStop(
                        stop_id=row["id"],
                        name=stop_display_name(row["name"], row.get("platform_ref")),
                        point=RoutePoint(lat=float(row["lat"]), lon=float(row["lon"])),
                    )
                    for row in stop_rows
                }
                if not stops_by_id:
                    return None

                cur.execute(
                    """
                    SELECT
                        tc.line_id,
                        tl.name AS line_name,
                        tl.provider AS provider,
                        tc.from_stop_id,
                        tc.to_stop_id,
                        tc.from_sequence,
                        tc.to_sequence,
                        tc.avg_travel_seconds,
                        tc.distance_meters
                    FROM transport_connections tc
                    JOIN transport_lines tl ON tl.id = tc.line_id
                    WHERE tc.city_id = %s
                      AND tc.is_active = TRUE
                      AND tl.is_active = TRUE
                    ORDER BY tc.line_id, tc.from_sequence, tc.to_sequence;
                    """,
                    (city_id,),
                )
                connection_rows = cur.fetchall()

                cur.execute(
                    """
                    SELECT
                        tt.id AS trip_id,
                        tt.service_bucket,
                        tt.source_url,
                        tt.valid_from,
                        tt.valid_to,
                        tl.id AS line_id,
                        tl.name AS line_name,
                        tl.provider AS provider,
                        ttst.stop_id,
                        ttst.stop_sequence,
                        ttst.time_minutes
                    FROM transport_trips tt
                    JOIN transport_lines tl ON tl.id = tt.line_id
                    JOIN transport_trip_stop_times ttst ON ttst.trip_id = tt.id
                    WHERE tl.city_id = %s
                      AND tl.is_active = TRUE
                      AND tt.is_active = TRUE
                    ORDER BY tt.id, ttst.stop_sequence;
                    """,
                    (city_id,),
                )
                trip_rows = cur.fetchall()

        outgoing_edges: dict[int, list[GraphEdge]] = {}
        provider = None
        for row in connection_rows:
            if row["from_stop_id"] not in stops_by_id or row["to_stop_id"] not in stops_by_id:
                continue

            from_stop = stops_by_id[row["from_stop_id"]]
            to_stop = stops_by_id[row["to_stop_id"]]
            edge = GraphEdge(
                line_id=int(row["line_id"]),
                line_name=row["line_name"],
                source=row["provider"],
                from_stop_id=int(row["from_stop_id"]),
                to_stop_id=int(row["to_stop_id"]),
                from_stop_name=from_stop.name,
                to_stop_name=to_stop.name,
                from_sequence=int(row["from_sequence"]),
                to_sequence=int(row["to_sequence"]),
                avg_travel_seconds=float(row["avg_travel_seconds"]),
                distance_meters=float(row["distance_meters"]),
            )
            outgoing_edges.setdefault(edge.from_stop_id, []).append(edge)
            provider = provider or edge.source

        trips_by_id: dict[int, GraphTrip] = {}
        trip_stop_times_by_id: dict[int, list[GraphTripStopTime]] = {}
        for row in trip_rows:
            stop_id = int(row["stop_id"])
            if stop_id not in stops_by_id:
                continue

            trip_id = int(row["trip_id"])
            trip_stop_times_by_id.setdefault(trip_id, []).append(
                GraphTripStopTime(
                    stop_id=stop_id,
                    stop_name=stops_by_id[stop_id].name,
                    stop_sequence=int(row["stop_sequence"]),
                    time_minutes=int(row["time_minutes"]),
                )
            )
            if trip_id not in trips_by_id:
                trips_by_id[trip_id] = GraphTrip(
                    trip_id=trip_id,
                    line_id=int(row["line_id"]),
                    line_name=row["line_name"],
                    source=row["source_url"] or row["provider"],
                    service_bucket=row["service_bucket"],
                    valid_from=row["valid_from"],
                    valid_to=row["valid_to"],
                    stop_times=[],
                )
                provider = provider or row["provider"]

        board_options_by_stop: dict[int, list[TripBoardOption]] = {}
        for trip_id, trip in list(trips_by_id.items()):
            stop_times = trip_stop_times_by_id.get(trip_id, [])
            stop_times.sort(key=lambda item: item.stop_sequence)
            if len(stop_times) < 2:
                del trips_by_id[trip_id]
                continue

            trips_by_id[trip_id] = replace(trip, stop_times=stop_times)
            for index, stop_time in enumerate(stop_times[:-1]):
                board_options_by_stop.setdefault(stop_time.stop_id, []).append(
                    TripBoardOption(
                        trip_id=trip_id,
                        stop_index=index,
                    )
                )

        for stop_id, options in board_options_by_stop.items():
            options.sort(
                key=lambda option: trips_by_id[option.trip_id].stop_times[option.stop_index].time_minutes,
            )

        if outgoing_edges or trips_by_id:
            graph = TransportGraph(
                provider=provider or str((city_profile.get("transport") or {}).get("provider") or "transport_provider"),
                stops_by_id=stops_by_id,
                outgoing_edges=outgoing_edges,
                trips_by_id=trips_by_id,
                board_options_by_stop=board_options_by_stop,
            )
    except PsycopgError:
        graph = None

    if graph is not None:
        _TRANSPORT_GRAPH_CACHE[cache_key] = graph
    return graph


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
    for current, nxt in zip(geometry, geometry[1:]):
        total_meters += haversine_km(current["lat"], current["lon"], nxt["lat"], nxt["lon"]) * 1_000
    return total_meters


def service_datetime(service_date: date, time_minutes: int) -> datetime:
    midnight = datetime.combine(service_date, datetime.min.time())
    return midnight + timedelta(minutes=time_minutes)

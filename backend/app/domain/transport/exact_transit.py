from dataclasses import replace
from datetime import date, datetime, timedelta
from heapq import heappop, heappush
from itertools import count

from app.domain.transport.geometry import merged_geometry, service_datetime
from app.domain.transport.models import (
    SERVICE_BUCKET_WEEKDAYS,
    ExactTransitSolution,
    ScheduledRide,
    TravelPlan,
    TravelSegment,
)
from app.domain.transport.segments import build_exact_transit_segments, refine_walk_segment
from app.services.routing_service import RoutePoint, RoutingLeg, RoutingService
from app.services.transport_graph import GraphStop, GraphTrip, TransportGraph


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
    min_transfer_seconds: float = 120.0,
) -> TravelPlan | None:
    if departure_dt is None or not graph.trips_by_id:
        return None

    exact_solution = find_exact_transit_solution(
        graph=graph,
        start_candidates=start_candidates,
        end_candidates=end_candidates,
        departure_dt=departure_dt,
        min_transfer_seconds=min_transfer_seconds,
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
        (segments[-1].arrival_time - departure_dt).total_seconds() if segments[-1].arrival_time else 0.0
    )
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
    min_transfer_seconds: float = 120.0,
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

        arrived_by_ride = parent_by_stop.get(stop_id) is not None
        earliest_board_dt = (
            current_arrival_dt + timedelta(seconds=min_transfer_seconds) if arrived_by_ride else current_arrival_dt
        )

        for board_option in graph.board_options_by_stop.get(stop_id, []):
            trip = graph.trips_by_id.get(board_option.trip_id)
            if trip is None or board_option.stop_index >= len(trip.stop_times):
                continue
            if not trip_operates_on_date(trip, current_arrival_dt.date()):
                continue

            board_stop_time = trip.stop_times[board_option.stop_index]
            board_dt = service_datetime(current_arrival_dt.date(), board_stop_time.time_minutes)
            if board_dt < earliest_board_dt:
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


def trip_operates_on_date(trip: GraphTrip, service_date: date) -> bool:
    if trip.valid_from is not None and service_date < trip.valid_from:
        return False
    if trip.valid_to is not None and service_date > trip.valid_to:
        return False

    allowed_weekdays = SERVICE_BUCKET_WEEKDAYS.get(trip.service_bucket, SERVICE_BUCKET_WEEKDAYS["all_days"])
    return service_date.weekday() in allowed_weekdays

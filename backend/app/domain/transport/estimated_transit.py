import math
from heapq import heappop, heappush
from itertools import count

from app.domain.transport.geometry import merged_geometry, route_geometry_leg
from app.domain.transport.models import TravelSegment
from app.services.routing_service import RoutingService
from app.services.transport_graph import GraphEdge, GraphStop, TransportGraph


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
                {
                    "lat": graph.stops_by_id[edges[0].from_stop_id].point.lat,
                    "lon": graph.stops_by_id[edges[0].from_stop_id].point.lon,
                },
                {
                    "lat": graph.stops_by_id[edges[-1].to_stop_id].point.lat,
                    "lon": graph.stops_by_id[edges[-1].to_stop_id].point.lon,
                },
            ]

        distance_meters = sum(route_leg.distance_meters for route_leg in route_legs) or sum(
            edge.distance_meters for edge in edges
        )
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

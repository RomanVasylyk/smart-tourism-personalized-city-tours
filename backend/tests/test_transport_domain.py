from datetime import date, datetime

from app.domain.transport.estimated_transit import (
    build_estimated_transit_segments,
    find_estimated_transit_solution,
)
from app.domain.transport.exact_transit import find_exact_transit_solution, trip_operates_on_date
from app.domain.transport.geometry import merged_geometry, polyline_distance_meters, service_datetime
from app.domain.transport.models import TravelSegment
from app.domain.transport.nearest_stops import candidate_stops_for_point, normalized_transport_mode
from app.services.routing_service import RoutePoint, RoutingLeg
from app.services.transport_graph import (
    GraphEdge,
    GraphStop,
    GraphTrip,
    GraphTripStopTime,
    TransportGraph,
    TripBoardOption,
)

MONDAY = date(2024, 1, 1)
SATURDAY = date(2024, 1, 6)


def _stop(stop_id, lat, lon, name=None):
    return GraphStop(stop_id=stop_id, name=name or f"S{stop_id}", point=RoutePoint(lat=lat, lon=lon))


def _walk(duration_seconds, distance_meters=50.0):
    return TravelSegment(
        mode="walk",
        duration_seconds=duration_seconds,
        distance_meters=distance_meters,
        geometry=[],
        source="walk_transfer",
    )


def _edge(line_id, from_id, to_id, avg_seconds, distance=300.0):
    return GraphEdge(
        line_id=line_id,
        line_name=f"Bus {line_id}",
        source="test_mhd",
        from_stop_id=from_id,
        to_stop_id=to_id,
        from_stop_name=f"S{from_id}",
        to_stop_name=f"S{to_id}",
        from_sequence=from_id,
        to_sequence=to_id,
        avg_travel_seconds=avg_seconds,
        distance_meters=distance,
    )


class FakeGeometryRouting:
    def route_between(self, start, end, pace):
        return RoutingLeg(
            duration_seconds=0.0,
            distance_meters=100.0,
            geometry=[{"lat": start.lat, "lon": start.lon}, {"lat": end.lat, "lon": end.lon}],
            source="osrm",
        )


def test_normalized_transport_mode():
    assert normalized_transport_mode("walk", {}) == "walk"
    assert normalized_transport_mode(None, {}) == "walk"
    assert normalized_transport_mode("walk_or_mhd", {"transport": {"mhd_enabled": True}}) == "walk_or_mhd"
    assert normalized_transport_mode("walk_or_mhd", {"transport": {"mhd_enabled": False}}) == "walk"
    assert normalized_transport_mode("walk_or_mhd", None) == "walk"


def test_merged_geometry_drops_shared_points():
    a = {"lat": 0.0, "lon": 0.0}
    b = {"lat": 0.0, "lon": 1.0}
    c = {"lat": 0.0, "lon": 2.0}
    assert merged_geometry([[a, b], [b, c]]) == [a, b, c]
    assert merged_geometry([[], [a, b]]) == [a, b]
    assert merged_geometry([]) == []


def test_polyline_distance_meters():
    assert polyline_distance_meters([{"lat": 0.0, "lon": 0.0}]) == 0.0
    distance = polyline_distance_meters([{"lat": 48.30, "lon": 18.08}, {"lat": 48.31, "lon": 18.08}])
    assert 1000 < distance < 1200


def test_service_datetime():
    assert service_datetime(date(2026, 4, 27), 10 * 60 + 5) == datetime(2026, 4, 27, 10, 5)


def test_trip_operates_on_date_respects_bucket_and_validity():
    workdays = GraphTrip(1, 9, "Bus 9", "src", "workdays", None, None, [])
    weekend = GraphTrip(2, 9, "Bus 9", "src", "weekends_holidays", None, None, [])
    all_days = GraphTrip(3, 9, "Bus 9", "src", "all_days", None, None, [])

    assert trip_operates_on_date(workdays, MONDAY) is True
    assert trip_operates_on_date(workdays, SATURDAY) is False
    assert trip_operates_on_date(weekend, SATURDAY) is True
    assert trip_operates_on_date(weekend, MONDAY) is False
    assert trip_operates_on_date(all_days, MONDAY) is True
    assert trip_operates_on_date(all_days, SATURDAY) is True

    bounded = GraphTrip(4, 9, "Bus 9", "src", "all_days", date(2024, 2, 1), date(2024, 3, 1), [])
    assert trip_operates_on_date(bounded, MONDAY) is False
    assert trip_operates_on_date(bounded, date(2024, 2, 15)) is True


def test_candidate_stops_for_point_filters_and_sorts():
    graph = TransportGraph(
        provider="test_mhd",
        stops_by_id={
            1: _stop(1, 0.0, 0.001),
            2: _stop(2, 0.0, 0.005),
            3: _stop(3, 0.0, 0.020),
        },
        outgoing_edges={},
        trips_by_id={},
        board_options_by_stop={},
    )

    candidates = candidate_stops_for_point(RoutePoint(0.0, 0.0), graph, "normal", max_distance_meters=650.0)

    assert [stop.stop_id for stop, _ in candidates] == [1, 2]


def test_find_exact_transit_solution_picks_direct_ride():
    stops = {1: _stop(1, 48.31, 18.08), 2: _stop(2, 48.32, 18.09), 3: _stop(3, 48.33, 18.10)}
    trip = GraphTrip(
        trip_id=501,
        line_id=9,
        line_name="Bus 9",
        source="test_mhd",
        service_bucket="all_days",
        valid_from=None,
        valid_to=None,
        stop_times=[
            GraphTripStopTime(1, "S1", 1, 10 * 60 + 5),
            GraphTripStopTime(2, "S2", 2, 10 * 60 + 10),
            GraphTripStopTime(3, "S3", 3, 10 * 60 + 18),
        ],
    )
    graph = TransportGraph(
        provider="test_mhd",
        stops_by_id=stops,
        outgoing_edges={},
        trips_by_id={501: trip},
        board_options_by_stop={1: [TripBoardOption(501, 0)], 2: [TripBoardOption(501, 1)]},
    )

    solution = find_exact_transit_solution(
        graph=graph,
        start_candidates=[(stops[1], _walk(60))],
        end_candidates=[(stops[3], _walk(60))],
        departure_dt=datetime(2024, 1, 1, 10, 0),
    )

    assert solution is not None
    assert solution.start_stop_id == 1
    assert solution.end_stop_id == 3
    assert len(solution.rides) == 1
    ride = solution.rides[0]
    assert ride.board_index == 0
    assert ride.alight_index == 2
    assert ride.board_time == datetime(2024, 1, 1, 10, 5)
    assert ride.alight_time == datetime(2024, 1, 1, 10, 18)


def test_find_exact_transit_solution_applies_min_transfer_buffer():
    stops = {1: _stop(1, 48.31, 18.08), 2: _stop(2, 48.32, 18.09), 3: _stop(3, 48.33, 18.10)}
    first_leg = GraphTrip(
        trip_id=501,
        line_id=9,
        line_name="Bus 9",
        source="test_mhd",
        service_bucket="all_days",
        valid_from=None,
        valid_to=None,
        stop_times=[
            GraphTripStopTime(1, "S1", 1, 10 * 60 + 5),
            GraphTripStopTime(2, "S2", 2, 10 * 60 + 10),
        ],
    )
    tight_connection = GraphTrip(
        trip_id=601,
        line_id=12,
        line_name="Bus 12",
        source="test_mhd",
        service_bucket="all_days",
        valid_from=None,
        valid_to=None,
        stop_times=[
            GraphTripStopTime(2, "S2", 1, 10 * 60 + 11),
            GraphTripStopTime(3, "S3", 2, 10 * 60 + 20),
        ],
    )
    later_connection = GraphTrip(
        trip_id=602,
        line_id=12,
        line_name="Bus 12",
        source="test_mhd",
        service_bucket="all_days",
        valid_from=None,
        valid_to=None,
        stop_times=[
            GraphTripStopTime(2, "S2", 1, 10 * 60 + 14),
            GraphTripStopTime(3, "S3", 2, 10 * 60 + 23),
        ],
    )
    graph = TransportGraph(
        provider="test_mhd",
        stops_by_id=stops,
        outgoing_edges={},
        trips_by_id={501: first_leg, 601: tight_connection, 602: later_connection},
        board_options_by_stop={
            1: [TripBoardOption(501, 0)],
            2: [TripBoardOption(601, 0), TripBoardOption(602, 0)],
        },
    )

    solution = find_exact_transit_solution(
        graph=graph,
        start_candidates=[(stops[1], _walk(60))],
        end_candidates=[(stops[3], _walk(60))],
        departure_dt=datetime(2024, 1, 1, 10, 0),
        min_transfer_seconds=120.0,
    )

    assert solution is not None
    assert len(solution.rides) == 2
    assert solution.rides[1].trip_id == 602
    assert solution.rides[1].board_time == datetime(2024, 1, 1, 10, 14)


def test_find_estimated_transit_solution_charges_wait_on_first_boarding():
    stops = {1: _stop(1, 48.31, 18.08), 2: _stop(2, 48.32, 18.09), 3: _stop(3, 48.33, 18.10)}
    graph = TransportGraph(
        provider="test_mhd",
        stops_by_id=stops,
        outgoing_edges={
            1: [_edge(9, 1, 2, 240.0)],
            2: [_edge(9, 2, 3, 420.0)],
        },
        trips_by_id={},
        board_options_by_stop={},
    )

    solution = find_estimated_transit_solution(
        graph=graph,
        start_candidates=[(stops[1], _walk(60))],
        end_candidates=[(stops[3], _walk(60))],
        average_wait_minutes=2.0,
    )

    assert solution is not None
    _, path_steps, _ = solution
    assert [edge.to_stop_id for edge, _ in path_steps] == [2, 3]
    assert path_steps[0][1] == 120.0
    assert path_steps[1][1] == 0.0


def test_build_estimated_transit_segments_groups_same_line():
    stops = {1: _stop(1, 48.31, 18.08), 2: _stop(2, 48.32, 18.09), 3: _stop(3, 48.33, 18.10)}
    graph = TransportGraph(
        provider="test_mhd",
        stops_by_id=stops,
        outgoing_edges={},
        trips_by_id={},
        board_options_by_stop={},
    )
    path_steps = [(_edge(9, 1, 2, 240.0), 120.0), (_edge(9, 2, 3, 420.0), 0.0)]

    segments = build_estimated_transit_segments(graph, path_steps, FakeGeometryRouting())

    assert len(segments) == 1
    segment = segments[0]
    assert segment.mode == "transit"
    assert segment.line_name == "Bus 9"
    assert segment.in_vehicle_seconds == 660.0
    assert segment.wait_seconds_before_departure == 120.0
    assert segment.duration_seconds == 780.0
    assert segment.from_stop_name == "S1"
    assert segment.to_stop_name == "S3"

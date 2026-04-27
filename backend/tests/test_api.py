from fastapi.testclient import TestClient

from app.main import app
from app.services.routing_service import RoutePoint, RoutingLeg
from app.services.transport_planner import (
    GraphEdge,
    GraphStop,
    GraphTrip,
    GraphTripStopTime,
    TransportGraph,
    TripBoardOption,
)

client = TestClient(app)


class FakeCursor:
    def __init__(self, rows):
        self.rows = rows

    def __enter__(self):
        return self

    def __exit__(self, exc_type, exc, traceback):
        return False

    def execute(self, query, params=None):
        self.query = query
        self.params = params

    def fetchall(self):
        return self.rows


class FakeConnection:
    def __init__(self, rows):
        self.rows = rows

    def __enter__(self):
        return self

    def __exit__(self, exc_type, exc, traceback):
        return False

    def cursor(self):
        return FakeCursor(self.rows)


class FakeRoutingService:
    def route_between(self, start, end, pace):
        return RoutingLeg(
            duration_seconds=600,
            distance_meters=800,
            geometry=[
                {"lat": start.lat, "lon": start.lon},
                {"lat": end.lat, "lon": end.lon},
            ],
            source="test",
        )


class SlowWalkingRoutingService:
    def route_between(self, start, end, pace):
        return RoutingLeg(
            duration_seconds=1800,
            distance_meters=3200,
            geometry=[
                {"lat": start.lat, "lon": start.lon},
                {"lat": end.lat, "lon": end.lon},
            ],
            source="test_walk",
        )


class MixedRoutingService:
    def route_between(self, start, end, pace):
        lat_delta = abs(start.lat - end.lat)
        lon_delta = abs(start.lon - end.lon)
        if max(lat_delta, lon_delta) < 0.0035:
            duration_seconds = 120
            distance_meters = 250
        else:
            duration_seconds = 1800
            distance_meters = 3200

        return RoutingLeg(
            duration_seconds=duration_seconds,
            distance_meters=distance_meters,
            geometry=[
                {"lat": start.lat, "lon": start.lon},
                {"lat": end.lat, "lon": end.lon},
            ],
            source="test_mixed",
        )


def test_health_returns_ok():
    response = client.get("/health")

    assert response.status_code == 200
    assert response.json() == {"status": "ok"}


def test_get_pois_returns_database_rows(monkeypatch):
    rows = [
        {
            "id": 1,
            "name": "Ponitrianske muzeum",
            "category": "museum",
            "lat": 48.3132523,
            "lon": 18.0881342,
            "opening_hours_raw": None,
            "visit_duration_min": 60,
            "base_score": 0.9,
            "wikipedia_url": "https://sk.wikipedia.org/wiki/Ponitrianske_muzeum",
        }
    ]

    monkeypatch.setattr("app.api.routes.get_connection", lambda: FakeConnection(rows))

    response = client.get("/pois", params={"city": "nitra"})

    assert response.status_code == 200
    assert response.json() == rows


def test_generate_route_returns_planned_stop(monkeypatch):
    rows = [
        {
            "id": 10,
            "name": "Nitra Castle",
            "category": "historical_site",
            "lat": 48.3172,
            "lon": 18.0861,
            "opening_hours_raw": None,
            "visit_duration_min": 30,
            "base_score": 0.95,
            "wikipedia_url": "https://en.wikipedia.org/wiki/Nitra_Castle",
        }
    ]

    monkeypatch.setattr("app.services.route_planner.get_connection", lambda: FakeConnection(rows))
    monkeypatch.setattr("app.services.route_planner.get_routing_service", lambda: FakeRoutingService())

    response = client.post(
        "/route/generate",
        json={
            "city": "nitra",
            "start_lat": 48.3076,
            "start_lon": 18.0845,
            "available_minutes": 120,
            "interests": ["historical_site"],
            "pace": "normal",
            "return_to_start": True,
            "start_datetime": "2026-04-19T10:00",
            "respect_opening_hours": True,
        },
    )

    body = response.json()

    assert response.status_code == 200
    assert body["city"] == "nitra"
    assert body["transport_mode"] == "walk"
    assert body["poi_count"] == 1
    assert body["used_minutes"] <= body["available_minutes"]
    assert body["route"][0]["poi_id"] == 10
    assert body["route"][0]["name"] == "Nitra Castle"
    assert len(body["legs"]) == 2
    assert body["legs"][0]["duration_minutes"] == 10
    assert body["legs"][0]["distance_meters"] == 800
    assert body["legs"][0]["routing_source"] == "test"
    assert body["full_geometry"][0] == {"lat": 48.3076, "lon": 18.0845}


def test_generate_route_can_return_transit_segments(monkeypatch):
    rows = [
        {
            "id": 11,
            "name": "Pyramida",
            "category": "viewpoint",
            "lat": 48.3425,
            "lon": 18.1049,
            "opening_hours_raw": None,
            "visit_duration_min": 20,
            "base_score": 0.95,
            "wikipedia_url": None,
        }
    ]
    city_profile = {
        "transport": {
            "mhd_enabled": True,
            "provider": "test_mhd",
            "mode": "static",
            "min_direct_distance_meters": 1000,
            "max_first_mile_meters": 500,
            "max_last_mile_meters": 500,
            "average_wait_minutes": 2,
            "max_total_duration_multiplier": 1.2,
            "min_walking_distance_savings_meters": 300,
        }
    }
    fake_graph = TransportGraph(
        provider="test_mhd",
        stops_by_id={
            101: GraphStop(stop_id=101, name="Centrum", point=RoutePoint(lat=48.3084, lon=18.0845)),
            102: GraphStop(stop_id=102, name="Hrad", point=RoutePoint(lat=48.3180, lon=18.0868)),
            103: GraphStop(stop_id=103, name="Pyramida", point=RoutePoint(lat=48.3422, lon=18.1045)),
        },
        outgoing_edges={
            101: [
                GraphEdge(
                    line_id=9,
                    line_name="Bus 9",
                    source="test_mhd",
                    from_stop_id=101,
                    to_stop_id=102,
                    from_stop_name="Centrum",
                    to_stop_name="Hrad",
                    from_sequence=1,
                    to_sequence=2,
                    avg_travel_seconds=240,
                    distance_meters=1100,
                )
            ],
            102: [
                GraphEdge(
                    line_id=9,
                    line_name="Bus 9",
                    source="test_mhd",
                    from_stop_id=102,
                    to_stop_id=103,
                    from_stop_name="Hrad",
                    to_stop_name="Pyramida",
                    from_sequence=2,
                    to_sequence=3,
                    avg_travel_seconds=420,
                    distance_meters=2200,
                )
            ],
        },
        trips_by_id={},
        board_options_by_stop={},
    )

    monkeypatch.setattr("app.services.route_planner.get_connection", lambda: FakeConnection(rows))
    monkeypatch.setattr("app.services.route_planner.get_routing_service", lambda: MixedRoutingService())
    monkeypatch.setattr("app.services.route_planner.city_profile_by_token", lambda city: city_profile)
    monkeypatch.setattr("app.services.transport_planner.load_transport_graph", lambda city: fake_graph)

    response = client.post(
        "/route/generate",
        json={
            "city": "nitra",
            "start_lat": 48.3076,
            "start_lon": 18.0845,
            "available_minutes": 120,
            "interests": ["viewpoint"],
            "pace": "normal",
            "return_to_start": False,
            "start_datetime": "2026-04-19T10:00",
            "respect_opening_hours": True,
            "transport_mode": "walk_or_mhd",
        },
    )

    body = response.json()

    assert response.status_code == 200
    assert body["transport_mode"] == "walk_or_mhd"
    assert body["legs"][0]["mode"] == "transit"
    assert [segment["mode"] for segment in body["legs"][0]["segments"]] == ["walk", "transit", "walk"]
    assert body["legs"][0]["segments"][1]["line_name"] == "Bus 9"


def test_generate_route_prefers_exact_trip_times_when_available(monkeypatch):
    rows = [
        {
            "id": 12,
            "name": "Pyramida",
            "category": "viewpoint",
            "lat": 48.3184,
            "lon": 18.0869,
            "opening_hours_raw": None,
            "visit_duration_min": 20,
            "base_score": 0.95,
            "wikipedia_url": None,
        }
    ]
    city_profile = {
        "transport": {
            "mhd_enabled": True,
            "provider": "test_mhd",
            "mode": "static",
            "min_direct_distance_meters": 1000,
            "max_first_mile_meters": 500,
            "max_last_mile_meters": 500,
            "average_wait_minutes": 2,
            "max_total_duration_multiplier": 1.4,
            "min_walking_distance_savings_meters": 300,
        }
    }
    trip = GraphTrip(
        trip_id=501,
        line_id=9,
        line_name="Bus 9",
        source="test_mhd",
        service_bucket="workdays",
        valid_from=None,
        valid_to=None,
        stop_times=[
            GraphTripStopTime(stop_id=101, stop_name="Centrum", stop_sequence=1, time_minutes=10 * 60 + 5),
            GraphTripStopTime(stop_id=102, stop_name="Hrad", stop_sequence=2, time_minutes=10 * 60 + 10),
            GraphTripStopTime(stop_id=103, stop_name="Pyramida", stop_sequence=3, time_minutes=10 * 60 + 18),
        ],
    )
    fake_graph = TransportGraph(
        provider="test_mhd",
        stops_by_id={
            101: GraphStop(stop_id=101, name="Centrum", point=RoutePoint(lat=48.3084, lon=18.0845)),
            102: GraphStop(stop_id=102, name="Hrad", point=RoutePoint(lat=48.3130, lon=18.0858)),
            103: GraphStop(stop_id=103, name="Pyramida", point=RoutePoint(lat=48.3179, lon=18.0868)),
        },
        outgoing_edges={
            101: [
                GraphEdge(
                    line_id=9,
                    line_name="Bus 9",
                    source="test_mhd",
                    from_stop_id=101,
                    to_stop_id=102,
                    from_stop_name="Centrum",
                    to_stop_name="Hrad",
                    from_sequence=1,
                    to_sequence=2,
                    avg_travel_seconds=300,
                    distance_meters=900,
                )
            ],
            102: [
                GraphEdge(
                    line_id=9,
                    line_name="Bus 9",
                    source="test_mhd",
                    from_stop_id=102,
                    to_stop_id=103,
                    from_stop_name="Hrad",
                    to_stop_name="Pyramida",
                    from_sequence=2,
                    to_sequence=3,
                    avg_travel_seconds=480,
                    distance_meters=1400,
                )
            ],
        },
        trips_by_id={501: trip},
        board_options_by_stop={
            101: [TripBoardOption(trip_id=501, stop_index=0)],
            102: [TripBoardOption(trip_id=501, stop_index=1)],
        },
    )

    monkeypatch.setattr("app.services.route_planner.get_connection", lambda: FakeConnection(rows))
    monkeypatch.setattr("app.services.route_planner.get_routing_service", lambda: MixedRoutingService())
    monkeypatch.setattr("app.services.route_planner.city_profile_by_token", lambda city: city_profile)
    monkeypatch.setattr("app.services.transport_planner.load_transport_graph", lambda city: fake_graph)

    response = client.post(
        "/route/generate",
        json={
            "city": "nitra",
            "start_lat": 48.3076,
            "start_lon": 18.0845,
            "available_minutes": 120,
            "interests": ["viewpoint"],
            "pace": "normal",
            "return_to_start": False,
            "start_datetime": "2026-04-27T10:00",
            "respect_opening_hours": True,
            "transport_mode": "walk_or_mhd",
        },
    )

    body = response.json()

    assert response.status_code == 200
    assert body["legs"][0]["mode"] == "transit"
    assert body["legs"][0]["segments"][1]["departure_time"] == "2026-04-27T10:05"
    assert body["legs"][0]["segments"][1]["arrival_time"] == "2026-04-27T10:18"
    assert body["legs"][0]["segments"][1]["wait_minutes_before_departure"] == 3
    assert body["legs"][0]["segments"][1]["in_vehicle_minutes"] == 13

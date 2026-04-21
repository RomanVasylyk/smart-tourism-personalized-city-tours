from fastapi.testclient import TestClient

from app.main import app
from app.services.routing_service import RoutingLeg

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
    assert body["poi_count"] == 1
    assert body["used_minutes"] <= body["available_minutes"]
    assert body["route"][0]["poi_id"] == 10
    assert body["route"][0]["name"] == "Nitra Castle"
    assert len(body["legs"]) == 2
    assert body["legs"][0]["duration_minutes"] == 10
    assert body["legs"][0]["distance_meters"] == 800
    assert body["legs"][0]["routing_source"] == "test"
    assert body["full_geometry"][0] == {"lat": 48.3076, "lon": 18.0845}

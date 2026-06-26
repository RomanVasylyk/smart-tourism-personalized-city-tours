import sys
from pathlib import Path

import pytest
from fastapi.testclient import TestClient

BACKEND_ROOT = Path(__file__).resolve().parents[1]

if str(BACKEND_ROOT) not in sys.path:
    sys.path.insert(0, str(BACKEND_ROOT))

from app.main import app  # noqa: E402
from app.services.routing_service import RoutingLeg  # noqa: E402


@pytest.fixture
def client() -> TestClient:
    return TestClient(app)


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

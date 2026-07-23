from types import SimpleNamespace

import pytest
from app.core.config import get_settings
from app.core.rate_limit import limiter, rate_limit_client_key
from app.services.feedback_stats import PlannerFeedbackProfile
from conftest import FakeConnection, FakeRoutingService

ROWS = [
    {
        "id": 10,
        "name": "Nitra Castle",
        "category": "historical_site",
        "lat": 48.3172,
        "lon": 18.0861,
        "opening_hours_raw": None,
        "visit_duration_min": 30,
        "base_score": 0.95,
        "short_description": None,
        "wikipedia_url": None,
    }
]

PAYLOAD = {
    "city": "nitra",
    "start_lat": 48.3076,
    "start_lon": 18.0845,
    "available_minutes": 120,
    "interests": ["historical_site"],
    "pace": "normal",
    "return_to_start": True,
    "start_datetime": "2026-04-19T10:00",
    "respect_opening_hours": True,
}


@pytest.fixture
def rate_limited(monkeypatch):
    monkeypatch.setattr("app.repositories.poi_candidates.get_connection", lambda: FakeConnection(ROWS))
    monkeypatch.setattr("app.services.route_planner.get_routing_service", lambda: FakeRoutingService())
    monkeypatch.setattr(
        "app.services.route_planner.load_planner_feedback_profile",
        lambda city, mode: PlannerFeedbackProfile(),
    )
    limiter.enabled = True
    try:
        yield
    finally:
        limiter.enabled = False


def test_limiter_is_attached_to_app(client):
    assert client.app.state.limiter is limiter


def test_rate_limit_key_uses_forwarded_header_only_when_trusted(monkeypatch):
    request = SimpleNamespace(
        headers={"X-Forwarded-For": "203.0.113.7, 10.0.0.2"},
        client=SimpleNamespace(host="10.0.0.9"),
    )

    assert rate_limit_client_key(request) == "10.0.0.9"

    monkeypatch.setattr(
        "app.core.rate_limit.get_settings",
        lambda: SimpleNamespace(rate_limit_trust_proxy_headers=True),
    )
    assert rate_limit_client_key(request) == "203.0.113.7"

    request_without_header = SimpleNamespace(headers={}, client=SimpleNamespace(host="10.0.0.9"))
    assert rate_limit_client_key(request_without_header) == "10.0.0.9"


def test_route_generate_is_rate_limited(client, rate_limited):
    limit = int(get_settings().rate_limit_route_generate.split("/")[0])

    statuses = [client.post("/route/generate", json=PAYLOAD).status_code for _ in range(limit + 1)]

    assert statuses[:limit] == [200] * limit
    assert statuses[-1] == 429

from datetime import datetime

import httpx
import pytest
from app.services import routing_service as rs
from app.services.route_planning.response import route_response_dict, routing_fallback_stats
from app.services.route_planning.result import RoutePlanningResult
from app.services.routing_service import RoutePoint, RoutingService


def _ok_route_response():
    return {
        "code": "Ok",
        "routes": [
            {
                "duration": 600.0,
                "distance": 800.0,
                "geometry": {"coordinates": [[18.0, 48.0], [18.01, 48.01]]},
            }
        ],
    }


def _no_route_response():
    return {"code": "NoRoute", "routes": []}


class _Resp:
    def __init__(self, data, status=200):
        self._data = data
        self.status_code = status

    def raise_for_status(self):
        if self.status_code >= 400:
            raise httpx.HTTPError(f"status {self.status_code}")

    def json(self):
        return self._data


@pytest.fixture(autouse=True)
def _reset_circuit():
    RoutingService.reset_circuit()
    yield
    RoutingService.reset_circuit()


def _service(monkeypatch, responses):
    calls = {"count": 0}
    sequence = list(responses)

    def fake_get(url, params=None, timeout=None):
        calls["count"] += 1
        item = sequence[min(calls["count"] - 1, len(sequence) - 1)]
        if isinstance(item, Exception):
            raise item
        return item

    monkeypatch.setattr(rs.httpx, "get", fake_get)
    service = RoutingService(base_url="http://osrm", enabled=True, table_enabled=False)
    service.OSRM_RETRY_BACKOFF_SECONDS = 0.0
    return service, calls


def test_route_retries_transient_error_then_succeeds(monkeypatch):
    service, calls = _service(monkeypatch, [httpx.ConnectError("boom"), _Resp(_ok_route_response())])

    leg = service.route_between(RoutePoint(48.0, 18.0), RoutePoint(48.01, 18.01), "normal")

    assert leg.source == "osrm"
    assert calls["count"] == 2


def test_no_route_falls_back_without_tripping_circuit(monkeypatch):
    service, _ = _service(monkeypatch, [_Resp(_no_route_response())])

    leg = service.route_between(RoutePoint(48.0, 18.0), RoutePoint(48.9, 18.9), "normal")

    assert leg.source == "haversine_fallback"
    assert RoutingService._circuit_closed()
    assert RoutingService._circuit_consecutive_failures == 0


def test_circuit_opens_after_repeated_failures_then_skips_osrm(monkeypatch):
    service, calls = _service(monkeypatch, [httpx.ConnectError("down")])
    service.OSRM_MAX_ATTEMPTS = 1

    for index in range(RoutingService.CIRCUIT_FAILURE_THRESHOLD):
        leg = service.route_between(RoutePoint(48.0 + index * 0.01, 18.0), RoutePoint(48.5, 18.5), "normal")
        assert leg.source == "haversine_fallback"

    assert not RoutingService._circuit_closed()
    calls_before = calls["count"]
    leg = service.route_between(RoutePoint(49.0, 19.0), RoutePoint(48.5, 18.5), "normal")
    assert leg.source == "haversine_fallback"
    assert calls["count"] == calls_before  


def test_circuit_resets_after_a_success(monkeypatch):
    service, _ = _service(monkeypatch, [httpx.ConnectError("blip"), _Resp(_ok_route_response())])
    service.OSRM_MAX_ATTEMPTS = 1

    first = service.route_between(RoutePoint(48.0, 18.0), RoutePoint(48.5, 18.5), "normal")
    assert first.source == "haversine_fallback"
    assert RoutingService._circuit_consecutive_failures == 1

    second = service.route_between(RoutePoint(48.1, 18.0), RoutePoint(48.6, 18.6), "normal")
    assert second.source == "osrm"
    assert RoutingService._circuit_consecutive_failures == 0


def test_routing_fallback_stats_counts_degraded_legs():
    legs = [
        {"routing_source": "osrm", "segments": []},
        {"routing_source": "haversine_fallback", "segments": []},
        {"routing_source": "transit", "segments": [{"source": "osrm"}, {"source": "haversine_fallback"}]},
    ]
    assert routing_fallback_stats(legs) == (2, 3)


def test_route_response_dict_exposes_routing_degradation():
    result = RoutePlanningResult(
        city="nitra",
        start_lat=0.0,
        start_lon=0.0,
        start_datetime=datetime(2026, 4, 19, 10, 0),
        pace="normal",
        interests=[],
        transport_mode="walk",
        return_to_start=False,
        respect_opening_hours=True,
        available_minutes=120,
        used_minutes=30,
        total_visit_minutes=20,
        total_walk_minutes=10,
        return_to_start_minutes=0,
        route_items=[{"poi_id": 1, "visit_duration_min": 20}],
        legs=[{"routing_source": "haversine_fallback", "segments": []}],
        full_geometry=[],
    )

    body = route_response_dict(result)

    assert body["routing_leg_count"] == 1
    assert body["routing_fallback_leg_count"] == 1
    assert body["routing_degraded"] is True

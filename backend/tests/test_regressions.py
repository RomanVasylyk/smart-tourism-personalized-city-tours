from datetime import UTC, datetime, timedelta, timezone

import httpx
import pytest
from app.repositories import route_sessions as route_sessions_repository
from app.services import route_planner
from app.services import routing_service as rs
from app.services.feedback_stats import PlannerFeedbackProfile, PlannerFeedbackStats
from app.services.route_planner import parse_start_datetime
from app.services.route_planning.models import CandidatePoi
from app.services.route_planning.opening_hours import resolve_visit_wait
from app.services.route_planning.scoring import score_candidate, session_confidence, stats_confidence
from app.services.route_sessions import aware_datetime
from app.services.routing_service import RoutePoint, RoutingService
from app.services.transport_planner import TravelPlan


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


def _routing_service(monkeypatch, response):
    monkeypatch.setattr(rs._http_client(), "get", lambda url, params=None, timeout=None: response)
    service = RoutingService(base_url="http://osrm", enabled=True, table_enabled=False)
    service.OSRM_RETRY_BACKOFF_SECONDS = 0.0
    return service


def _walk_plan(minutes: int) -> TravelPlan:
    return TravelPlan(
        mode="walk",
        duration_seconds=minutes * 60,
        distance_meters=minutes * 80,
        geometry=[],
        source="test",
        segments=[],
    )


def _poi_row(**overrides) -> dict:
    row = {
        "id": 1,
        "name": "Chapel",
        "category": "religious_site",
        "lat": 47.9861,
        "lon": 18.1631,
        "opening_hours_raw": None,
        "opening_hours_source": None,
        "visit_duration_min": 20,
        "base_score": 0.7,
    }
    row.update(overrides)
    return row


def test_timezone_aware_start_datetime_becomes_naive_local_time():
    parsed = parse_start_datetime("2026-11-13T10:00:00+01:00")

    expected = datetime(2026, 11, 13, 9, 0, tzinfo=UTC).astimezone().replace(tzinfo=None)
    assert parsed.tzinfo is None
    assert parsed == expected


def test_naive_start_datetime_is_kept_as_local_time():
    assert parse_start_datetime("2026-11-13T10:00:42") == datetime(2026, 11, 13, 10, 0)


def test_rating_confidence_counts_ratings_not_planned_stops():
    stats = PlannerFeedbackStats(average_rating=1.0, planned_count=10, session_count=10, feedback_count=2)

    assert stats_confidence(stats, 4) == 0.5
    assert session_confidence(stats, 4) == 1.0


def test_single_city_session_does_not_get_full_weight():
    one_bad_session = PlannerFeedbackProfile(
        city_stats=PlannerFeedbackStats(
            average_rating=1.0,
            too_much_walking_rate=1.0,
            completion_rate=0.0,
            skip_rate=0.0,
            planned_count=6,
            session_count=1,
            feedback_count=1,
        )
    )
    poi = _poi_row(category="museum", base_score=0.9)

    neutral_score, _ = score_candidate(poi, _walk_plan(28), {}, PlannerFeedbackProfile(), "walk")
    score, breakdown = score_candidate(poi, _walk_plan(28), {}, one_bad_session, "walk")

    assert breakdown["walking_penalty"] == pytest.approx(10.01 / 6, abs=0.01)
    assert neutral_score - score < 2.0


def test_service_times_are_not_used_as_opening_hours():
    chapel = CandidatePoi.from_row(
        _poi_row(opening_hours_raw="Tu-Su 16:00; Sa off", opening_hours_source="service_times")
    )
    friday_morning = datetime(2026, 10, 23, 10, 0)

    assert chapel.opening_hours_raw == "Tu-Su 16:00; Sa off"
    assert chapel.planning_opening_hours is None
    assert (
        resolve_visit_wait(
            chapel.planning_opening_hours,
            friday_morning,
            20,
            respect_opening_hours=True,
            is_forced=False,
            max_wait_minutes=20,
        )
        == 0
    )


def test_regular_opening_hours_still_apply():
    museum = CandidatePoi.from_row(
        _poi_row(opening_hours_raw="Tu-Fr 09:00-17:00", opening_hours_source="opening_hours")
    )

    assert museum.planning_opening_hours == "Tu-Fr 09:00-17:00"


def test_zero_length_osrm_route_between_adjacent_points_is_not_a_fallback(monkeypatch):
    zero_route = {
        "code": "Ok",
        "routes": [{"duration": 0, "distance": 0, "geometry": {"coordinates": [[18.0, 48.0], [18.0, 48.0]]}}],
    }
    service = _routing_service(monkeypatch, _Resp(zero_route))

    leg = service.route_between(RoutePoint(48.0, 18.0), RoutePoint(48.00003, 18.00002), "normal")

    assert leg.source == "osrm"
    assert 0 < leg.distance_meters < 10
    assert RoutingService._circuit_consecutive_failures == 0


def test_zero_length_osrm_route_between_distant_points_stays_a_fallback(monkeypatch):
    zero_route = {
        "code": "Ok",
        "routes": [{"duration": 0, "distance": 0, "geometry": {"coordinates": [[18.0, 48.0], [18.0, 48.0]]}}],
    }
    service = _routing_service(monkeypatch, _Resp(zero_route))

    leg = service.route_between(RoutePoint(48.0, 18.0), RoutePoint(48.01, 18.01), "normal")

    assert leg.source == "haversine_fallback"


def test_osrm_bad_request_does_not_trip_circuit(monkeypatch):
    service = _routing_service(monkeypatch, _Resp({"code": "NoRoute", "message": "Impossible route"}, status=400))

    for index in range(RoutingService.CIRCUIT_FAILURE_THRESHOLD + 1):
        leg = service.route_between(RoutePoint(48.0 + index * 0.01, 18.0), RoutePoint(48.9, 18.9), "normal")
        assert leg.source == "haversine_fallback"

    assert RoutingService._circuit_closed()
    assert RoutingService._circuit_consecutive_failures == 0


def test_naive_session_timestamp_gets_local_timezone():
    naive = datetime(2026, 10, 6, 15, 3)
    aware = datetime(2026, 10, 6, 15, 3, tzinfo=timezone(timedelta(hours=2)))

    converted = aware_datetime(naive)

    assert converted.tzinfo is not None
    assert converted.replace(tzinfo=None) == naive
    assert aware_datetime(aware) is aware
    assert aware_datetime(None) is None


def test_malformed_route_snapshot_is_rejected(client):
    payload = {
        "id": "00000000-0000-4000-8000-000000000002",
        "device_id": "device-1",
        "city": "nitra",
        "status": "in_progress",
        "start_lat": 48.3076,
        "start_lon": 18.0845,
        "available_minutes": 120,
        "pace": "normal",
        "return_to_start": True,
        "opening_hours_enabled": True,
        "route_snapshot_json": {"route": [1, 2, 3]},
    }
    headers = {"X-Device-Id": "device-1", "X-Device-Token": "token-1"}

    response = client.post("/route-sessions", json=payload, headers=headers)
    payload["route_snapshot_json"] = {"route": [{"poi_id": "7"}]}
    string_id_response = client.post("/route-sessions", json=payload, headers=headers)

    assert response.status_code == 422
    assert string_id_response.status_code == 422


def test_session_pois_are_limited_to_pois_of_the_session_city():
    statements = []

    class RecordingCursor:
        def execute(self, sql, params=None):
            statements.append((" ".join(sql.split()), params))

    route_sessions_repository.upsert_session_pois(
        RecordingCursor(),
        "11111111-1111-1111-1111-111111111111",
        {"route": [{"poi_id": 7, "order": 1}, {"poi_id": None}, "broken"]},
    )

    insert_statements = [statement for statement in statements if "INSERT INTO route_session_pois" in statement[0]]
    assert len(insert_statements) == 1
    assert "JOIN route_sessions rs ON rs.city_id = p.city_id" in insert_statements[0][0]
    assert insert_statements[0][1]["poi_id"] == 7


def test_route_check_reports_stops_outside_opening_hours(client, monkeypatch):
    rows = [
        {"id": 1, "opening_hours_raw": "Mo-Fr 09:00-12:00", "opening_hours_source": "opening_hours"},
        {"id": 2, "opening_hours_raw": "Mo-Fr 09:00-17:00", "opening_hours_source": "opening_hours"},
        {"id": 3, "opening_hours_raw": "Sa off", "opening_hours_source": "service_times"},
        {"id": 4, "opening_hours_raw": "Mo-Fr 11:10-17:00", "opening_hours_source": "opening_hours"},
    ]
    monkeypatch.setattr(route_planner, "get_poi_opening_hours", lambda city, poi_ids: rows)
    payload = {
        "city": "nitra",
        "start_datetime": "2026-10-23T10:00",
        "stops": [
            {"poi_id": 2, "arrival_after_min": 10, "visit_duration_min": 30},
            {"poi_id": 4, "arrival_after_min": 60, "visit_duration_min": 20},
            {"poi_id": 3, "arrival_after_min": 90, "visit_duration_min": 20},
            {"poi_id": 1, "arrival_after_min": 115, "visit_duration_min": 30},
            {"poi_id": 9, "arrival_after_min": 150, "visit_duration_min": 10},
        ],
    }

    response = client.post("/route/check", json=payload)
    payload["respect_opening_hours"] = False
    ignored_response = client.post("/route/check", json=payload)

    assert response.status_code == 200
    assert response.json() == {"closed_poi_ids": [1]}
    assert ignored_response.json() == {"closed_poi_ids": []}

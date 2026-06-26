from hashlib import sha256


def route_session_payload(device_id="device-1"):
    return {
        "id": "00000000-0000-4000-8000-000000000001",
        "device_id": device_id,
        "city": "nitra",
        "status": "in_progress",
        "start_lat": 48.3076,
        "start_lon": 18.0845,
        "available_minutes": 120,
        "pace": "normal",
        "return_to_start": True,
        "opening_hours_enabled": True,
        "started_at": "2026-04-19T10:00:00Z",
        "finished_at": None,
        "used_minutes": 0,
        "total_walk_minutes": 0,
        "total_visit_minutes": 0,
        "route_snapshot_json": {"route": []},
    }


def route_session_headers(device_id="device-1", device_token="token-1"):
    return {
        "X-Device-Id": device_id,
        "X-Device-Token": device_token,
    }


def test_create_route_session_requires_device_header(client):
    response = client.post("/route-sessions", json=route_session_payload())

    assert response.status_code == 422


def test_create_route_session_requires_device_token_header(client):
    response = client.post(
        "/route-sessions",
        json=route_session_payload(),
        headers={"X-Device-Id": "device-1"},
    )

    assert response.status_code == 422


def test_create_route_session_rejects_mismatched_device_header(client):
    response = client.post(
        "/route-sessions",
        json=route_session_payload(device_id="device-1"),
        headers=route_session_headers(device_id="device-2"),
    )

    assert response.status_code == 403
    assert response.json()["detail"] == "Device id does not match authenticated device."


def test_create_route_session_passes_authenticated_device(client, monkeypatch):
    def fake_create_route_session(request, authenticated_device_id, authenticated_device_token):
        assert request.device_id == "device-1"
        assert authenticated_device_id == "device-1"
        assert authenticated_device_token == "token-1"
        return {
            "id": str(request.id),
            "device_id": request.device_id,
            "status": request.status,
        }

    monkeypatch.setattr("app.api.routes.create_route_session", fake_create_route_session)

    response = client.post(
        "/route-sessions",
        json=route_session_payload(device_id="device-1"),
        headers=route_session_headers(device_id="device-1", device_token="token-1"),
    )

    assert response.status_code == 200
    assert response.json()["device_id"] == "device-1"


def test_get_route_sessions_rejects_mismatched_device_header(client):
    response = client.get(
        "/route-sessions",
        params={"device_id": "device-1"},
        headers=route_session_headers(device_id="device-2"),
    )

    assert response.status_code == 403
    assert response.json()["detail"] == "Device id does not match authenticated device."


def test_get_route_session_requires_matching_device_token(client, monkeypatch):
    session_id = "00000000-0000-4000-8000-000000000001"
    owner_token_hash = sha256(b"owner-token").hexdigest()

    def fake_get_route_session(*, session_id, device_id, device_token_hash):
        if device_id != "device-1" or device_token_hash != owner_token_hash:
            return None
        return {
            "id": session_id,
            "device_id": device_id,
            "status": "in_progress",
        }

    monkeypatch.setattr("app.repositories.route_sessions.get_route_session", fake_get_route_session)

    rejected_response = client.get(
        f"/route-sessions/{session_id}",
        headers=route_session_headers(device_id="device-1", device_token="wrong-token"),
    )
    accepted_response = client.get(
        f"/route-sessions/{session_id}",
        headers=route_session_headers(device_id="device-1", device_token="owner-token"),
    )

    assert rejected_response.status_code == 404
    assert accepted_response.status_code == 200
    assert accepted_response.json()["device_id"] == "device-1"

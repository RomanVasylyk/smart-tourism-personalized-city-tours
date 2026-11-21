from types import SimpleNamespace

import pytest
from app.admin import api as admin_api
from app.admin import crud
from app.admin import pipeline as data_pipeline
from app.admin.resources import RESOURCES_BY_NAME
from app.services import auth

ADMIN = {"id": 3, "email": "spravca@example.com", "display_name": "Správca", "is_admin": True, "is_active": True}


@pytest.fixture
def admin_session(client, monkeypatch):
    monkeypatch.setattr(auth, "user_for_token", lambda token: dict(ADMIN) if token == "token" else None)
    client.cookies.set("admin_session", "token")
    return {"X-Admin-Request": "1"}


def fake_module(approval, digest):
    return SimpleNamespace(
        approval=SimpleNamespace(load_approval=lambda path: approval, file_sha256=lambda path: digest),
    )


def test_approval_and_load_states(tmp_path):
    data_file = tmp_path / "graph.json"
    data_file.write_text("{}")

    assert data_pipeline.approval_state(fake_module(None, "a"), data_file)["status"] == "missing"
    approved = {"sha256": "a", "reviewer": "Správca", "approved_at": "2026-11-21T15:00:00+00:00"}
    assert data_pipeline.approval_state(fake_module(approved, "a"), data_file)["status"] == "approved"
    data_pipeline._file_cache.clear()
    assert data_pipeline.approval_state(fake_module(approved, "b"), data_file)["status"] == "changed"
    data_pipeline._file_cache.clear()
    assert data_pipeline.load_state(fake_module(None, "a"), data_file, None)["in_sync"] is None
    loaded = {"sha256": "a", "loaded_by": "Správca", "loaded_at": None}
    assert data_pipeline.load_state(fake_module(None, "a"), data_file, loaded)["in_sync"] is True
    data_pipeline._file_cache.clear()
    assert data_pipeline.load_state(fake_module(None, "c"), data_file, loaded)["in_sync"] is False
    data_pipeline._file_cache.clear()


def test_reviewer_name_uses_display_name_and_email():
    assert data_pipeline.reviewer_name(ADMIN) == "Správca <spravca@example.com>"
    assert data_pipeline.reviewer_name({"email": "a@b.sk", "display_name": " "}) == "a@b.sk"


def test_pipeline_actions_are_signed_by_the_logged_in_admin(client, admin_session, monkeypatch):
    calls = []
    monkeypatch.setattr(data_pipeline, "city_slugs", lambda: {"nitra"})
    monkeypatch.setattr(
        data_pipeline, "approve_transport", lambda slug, admin: calls.append((slug, admin["email"])) or {}
    )

    assert client.post("/admin/api/pipeline/nitra/transport/approve").status_code == 403
    response = client.post("/admin/api/pipeline/nitra/transport/approve", json={}, headers=admin_session)
    assert response.status_code == 200
    assert calls == [("nitra", "spravca@example.com")]
    assert client.post("/admin/api/pipeline/levoca/transport/approve", headers=admin_session).status_code == 404
    assert client.post("/admin/api/pipeline/nitra/routes/load", headers=admin_session).status_code == 422


def test_pipeline_errors_become_readable_responses(client, admin_session, monkeypatch):
    class ApprovalError(RuntimeError):
        pass

    class ReviewBlockedError(ValueError):
        pass

    def not_approved(slug, admin):
        raise ApprovalError("not approved")

    def blocked(slug, admin, allow_errors):
        raise ReviewBlockedError("errors")

    monkeypatch.setattr(data_pipeline, "city_slugs", lambda: {"nitra"})
    monkeypatch.setattr(data_pipeline, "load_transport", not_approved)
    monkeypatch.setattr(data_pipeline, "approve_pois", blocked)

    loaded = client.post("/admin/api/pipeline/nitra/transport/load", headers=admin_session)
    reviewed = client.post(
        "/admin/api/pipeline/nitra/pois/approve", json={"allow_errors": False}, headers=admin_session
    )
    assert loaded.status_code == 409
    assert "schválené" in loaded.json()["detail"]
    assert reviewed.status_code == 422


def test_editing_pipeline_fields_protects_poi_from_import():
    pois = RESOURCES_BY_NAME["pois"]
    existing = {"osm_type": "node", "name": "Hrad", "visit_duration_min": 60, "admin_locked": False}

    edited = admin_api.prepare_values(pois, {"name": "Nitriansky hrad"}, {}, admin=ADMIN, existing=existing)
    untouched = admin_api.prepare_values(pois, {"name": "Hrad"}, {}, admin=ADMIN, existing=existing)
    unlocked = admin_api.prepare_values(
        pois,
        {"name": "Nitriansky hrad", "admin_locked": False},
        {},
        admin=ADMIN,
        existing={**existing, "admin_locked": True},
    )
    manual = admin_api.prepare_values(
        pois, {"name": "Nový bod"}, {}, admin=ADMIN, existing={**existing, "osm_type": "manual"}
    )

    assert edited["admin_locked"] is True
    assert "admin_locked" not in untouched
    assert unlocked["admin_locked"] is False
    assert "admin_locked" not in manual


def test_pipeline_status_reports_missing_folder(monkeypatch, tmp_path):
    monkeypatch.setattr(data_pipeline, "PIPELINE_ROOT", tmp_path / "missing")

    result = data_pipeline.status()

    assert result["available"] is False
    with pytest.raises(data_pipeline.PipelineUnavailableError):
        data_pipeline.modules()
    assert isinstance(crud.AdminError(503, "x"), Exception)


def test_decisions_flow_through_review(tmp_path, monkeypatch):
    import json

    module = data_pipeline.modules()
    processed = tmp_path / "test_pois_normalized.json"
    pois = [
        {
            "osm_type": "way",
            "osm_id": "7",
            "name": "Kaštieľsky park",
            "category": "park",
            "lat": 48.2,
            "lon": 18.1,
            "base_score": 0.5,
            "visit_duration_min": 20,
            "short_description": "x",
        },
        {
            "osm_type": "node",
            "osm_id": "8",
            "name": "Hrad",
            "category": "attraction",
            "lat": 47.99,
            "lon": 18.16,
            "base_score": 0.5,
            "visit_duration_min": 20,
            "short_description": "x",
        },
    ]
    processed.write_text(json.dumps(pois, ensure_ascii=False, indent=2), encoding="utf-8")
    city = {
        "slug": "test",
        "name": "Test",
        "bbox": {"south": 47.9, "west": 18.0, "north": 48.1, "east": 18.3},
        "available_categories": ["park", "attraction"],
    }
    monkeypatch.setattr(module.poi_review, "processed_file", lambda profile: processed)
    monkeypatch.setattr(module.poi_loader, "processed_file", lambda profile: processed)
    monkeypatch.setattr(data_pipeline, "city_profile", lambda loaded, slug: city)

    def park(listing):
        return next(item for item in listing["items"] if item["key"] == "way/7")

    listing = data_pipeline.poi_items("test")
    assert park(listing)["issues"][0]["code"] == "outside_city"
    assert listing["summary"]["error_count"] == 1

    listing = data_pipeline.save_decision("test", "way/7", "accept", {}, "Park patrí k mestu.", ADMIN)
    assert park(listing)["decision"]["action"] == "accept"
    assert park(listing)["issues"][0]["level"] == "accepted"
    assert listing["summary"]["error_count"] == 0

    listing = data_pipeline.save_decision("test", "way/7", "include", {"lat": "48,05"}, None, ADMIN)
    assert park(listing)["decision"]["edits"] == {"lat": 48.05}
    assert park(listing)["issues"] == []

    with pytest.raises(data_pipeline.InputError):
        data_pipeline.save_decision("test", "way/7", "include", {"base_score": 3}, None, ADMIN)
    with pytest.raises(data_pipeline.InputError):
        data_pipeline.save_decision("test", "way/7", "include", {"osm_id": "9"}, None, ADMIN)
    with pytest.raises(KeyError):
        data_pipeline.save_decision("test", "node/999", "exclude", {}, None, ADMIN)
    listing = data_pipeline.save_decision("test", "way/7", "include", {}, None, ADMIN)
    assert park(listing)["decision"] is None


def test_city_config_validation():
    city = {
        "slug": "nitra",
        "name": "Nitra",
        "country": "Slovakia",
        "center": {"lat": 48.3, "lon": 18.08},
        "bbox": {"south": 48.28, "west": 18.03, "north": 48.36, "east": 18.13},
    }
    data_pipeline.validate_config({"cities": [city]})
    with pytest.raises(data_pipeline.InputError, match="prehodené"):
        data_pipeline.validate_config({"cities": [{**city, "bbox": {**city["bbox"], "south": 48.5}}]})
    with pytest.raises(data_pipeline.InputError, match="opakovať"):
        data_pipeline.validate_config({"cities": [city, city]})
    with pytest.raises(data_pipeline.InputError, match="skratku"):
        data_pipeline.validate_config({"cities": [{**city, "slug": "Nitra mesto"}]})
    with pytest.raises(data_pipeline.InputError):
        data_pipeline.validate_config({"towns": []})


def test_city_profiles_prefer_mounted_pipeline(monkeypatch, tmp_path):
    from app.services import city_profiles

    config = tmp_path / "config"
    config.mkdir()
    (config / "cities.yaml").write_text("cities: []", encoding="utf-8")
    monkeypatch.setenv("PIPELINE_ROOT", str(tmp_path))

    assert city_profiles.resolve_config_file() == config / "cities.yaml"


def test_job_commands_and_single_running_job(monkeypatch):
    from app.admin import jobs

    steps = jobs.commands("transport_process", "nitra")
    assert steps[0][2:] == ["scripts/normalize_transport.py", "nitra"]
    assert steps[1][2:] == ["scripts/validate_transport_graph.py", "nitra", "--write"]
    with pytest.raises(KeyError):
        jobs.start_job("format_disk", "nitra", "Správca")
    monkeypatch.setitem(jobs._running, 99, [])
    with pytest.raises(jobs.JobBusyError):
        jobs.start_job("pois_process", "nitra", "Správca")


def test_job_runner_stops_after_failed_step(monkeypatch):
    import sys

    from app.admin import jobs

    finished = {}
    monkeypatch.setattr(
        jobs,
        "commands",
        lambda kind, slug: [
            [sys.executable, "-c", "print('ahoj')"],
            [sys.executable, "-c", "import sys; sys.exit(3)"],
            [sys.executable, "-c", "print('nikdy')"],
        ],
    )
    monkeypatch.setattr(jobs, "finish_job", lambda job_id, code, lines: finished.update(code=code, lines=list(lines)))
    jobs._running[5] = []

    jobs.run_job(5, "pois_process", "nitra")

    assert finished["code"] == 3
    assert "ahoj" in finished["lines"]
    assert "nikdy" not in finished["lines"]
    assert 5 not in jobs._running


def test_specific_pipeline_routes_win_over_generic_action(client, admin_session, monkeypatch):
    from app.admin import jobs

    decided = {}

    def missing_file(slug, kind):
        raise KeyError(kind)

    monkeypatch.setattr(data_pipeline, "city_slugs", lambda: {"nitra"})
    monkeypatch.setattr(data_pipeline, "upload_pois", lambda slug, payload, admin: {"count": len(payload)})
    monkeypatch.setattr(
        data_pipeline,
        "save_decision",
        lambda slug, key, action, edits, note, admin: decided.update(key=key, action=action) or {"items": []},
    )
    monkeypatch.setattr(data_pipeline, "data_file", missing_file)
    monkeypatch.setattr(jobs, "start_job", lambda kind, slug, by: 7)

    uploaded = client.post("/admin/api/pipeline/nitra/pois/upload", json=[{"osm_type": "node"}], headers=admin_session)
    started = client.post("/admin/api/pipeline/nitra/jobs/pois_process", headers=admin_session)
    decision = client.put(
        "/admin/api/pipeline/nitra/pois/decisions/way/176370538", json={"action": "exclude"}, headers=admin_session
    )

    assert uploaded.json() == {"count": 1}
    assert started.json() == {"job_id": 7}
    assert decision.status_code == 200
    assert decided == {"key": "way/176370538", "action": "exclude"}
    assert client.get("/admin/api/pipeline/nitra/files/secrets").status_code == 404

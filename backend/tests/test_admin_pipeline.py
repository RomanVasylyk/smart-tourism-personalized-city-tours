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

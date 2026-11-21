import json
from pathlib import Path

import pytest
from etl.poi.review import review_pois, snapshot_of
from utils.approval import ApprovalError, require_approval, write_approval

CITY = {
    "slug": "testovo",
    "bbox": {"south": 48.28, "west": 18.03, "north": 48.36, "east": 18.13},
    "available_categories": ["museum", "monument"],
}


def poi(osm_id, **overrides):
    record = {
        "osm_type": "node",
        "osm_id": str(osm_id),
        "name": f"Bod {osm_id}",
        "category": "monument",
        "lat": 48.31,
        "lon": 18.08 + osm_id * 0.001,
        "base_score": 0.6,
        "visit_duration_min": 20,
        "short_description": "Opis.",
        "is_active": True,
    }
    record.update(overrides)
    return record


def codes(items):
    return sorted({item["code"] for item in items})


def test_review_reports_data_errors():
    pois = [
        poi(1),
        poi(2, name=" "),
        poi(3, lat=48.9),
        poi(4, category="zoo"),
        poi(5, base_score=1.4),
        poi(6, visit_duration_min=0),
        poi(1, name="Kópia"),
        poi(7, name="", is_active=False),
    ]

    report = review_pois(pois, CITY)

    assert codes(report["errors"]) == [
        "duplicate_key",
        "invalid_base_score",
        "invalid_visit_duration",
        "missing_name",
        "outside_city",
        "unknown_category",
    ]
    assert report["summary"]["active"] == 7
    assert report["summary"]["error_count"] == 6
    assert report["changes"] is None


def test_review_warns_about_near_duplicates_and_missing_descriptions():
    pois = [
        poi(1, name="Kostol sv. Martina"),
        poi(2, name="kostol sv. martina", lon=18.0815),
        poi(3, short_description=None),
    ]

    report = review_pois(pois, CITY)

    assert report["errors"] == []
    assert codes(report["warnings"]) == ["missing_description", "possible_duplicate"]


def test_review_lists_changes_since_last_approval():
    approved = snapshot_of([poi(1), poi(2), poi(3)])
    current = [poi(1, name="Nový názov"), poi(2, lat=48.315), poi(4)]

    changes = review_pois(current, CITY, approved)["changes"]

    assert [item["key"] for item in changes["added"]] == ["node/4"]
    assert [item["key"] for item in changes["removed"]] == ["node/3"]
    assert {item["key"]: item["fields"] for item in changes["changed"]} == {
        "node/1": ["name"],
        "node/2": ["position"],
    }


def test_loading_requires_approval_of_the_exact_file(tmp_path: Path):
    data_file = tmp_path / "testovo_pois_normalized.json"
    data_file.write_text(json.dumps([poi(1)]), encoding="utf-8")

    with pytest.raises(ApprovalError):
        require_approval(data_file)

    write_approval(data_file, "Správca", {"active": 1}, snapshot_of([poi(1)]))
    assert require_approval(data_file)["reviewer"] == "Správca"

    data_file.write_text(json.dumps([poi(1), poi(2)]), encoding="utf-8")
    with pytest.raises(ApprovalError):
        require_approval(data_file)


def test_approval_is_blocked_by_errors_unless_explicitly_allowed(tmp_path):
    from scripts.review_pois import ReviewBlockedError, approve_reviewed

    data_file = tmp_path / "nitra_pois_normalized.json"
    pois = [{"osm_type": "node", "osm_id": 1, "name": "Hrad", "lat": 48.31, "lon": 18.08}]
    data_file.write_text(json.dumps(pois))
    report = {"errors": [{"code": "outside_city"}], "summary": {"active": 1}}

    with pytest.raises(ReviewBlockedError):
        approve_reviewed(data_file, pois, report, "Správca")
    approval_file = approve_reviewed(data_file, pois, report, "Správca", allow_errors=True)
    assert json.loads(approval_file.read_text())["reviewer"] == "Správca"


def test_decisions_exclude_edit_and_accept_errors():
    from etl.poi.review import apply_decisions

    city = {"slug": "test", "bbox": {"south": 48.0, "west": 18.0, "north": 48.1, "east": 18.1}}
    pois = [
        {
            "osm_type": "node",
            "osm_id": 1,
            "name": "Hrad",
            "category": "attraction",
            "lat": 48.05,
            "lon": 18.05,
            "base_score": 0.6,
            "visit_duration_min": 30,
            "short_description": "x",
        },
        {
            "osm_type": "node",
            "osm_id": 2,
            "name": "Park",
            "category": "park",
            "lat": 49.0,
            "lon": 18.05,
            "base_score": 0.6,
            "visit_duration_min": 30,
            "short_description": "x",
        },
        {
            "osm_type": "node",
            "osm_id": 3,
            "name": "Kostol",
            "category": "religious_site",
            "lat": 49.0,
            "lon": 18.05,
            "base_score": 0.6,
            "visit_duration_min": 30,
            "short_description": "x",
        },
        {
            "osm_type": "node",
            "osm_id": 4,
            "name": "Socha",
            "category": "monument",
            "lat": 49.0,
            "lon": 18.05,
            "base_score": 0.6,
            "visit_duration_min": 30,
            "short_description": "x",
        },
    ]
    decisions = {
        "node/2": {"action": "exclude"},
        "node/3": {"action": "include", "edits": {"lat": 48.06, "unknown_field": "x"}},
        "node/4": {"action": "accept", "note": "Leží tesne za hranicou mesta."},
    }

    reviewed = apply_decisions(pois, decisions)
    report = review_pois(reviewed, city, None, decisions)

    assert reviewed[1]["is_active"] is False
    assert reviewed[2]["lat"] == 48.06 and "unknown_field" not in reviewed[2]
    assert pois[2]["lat"] == 49.0
    assert report["errors"] == []
    assert [item["key"] for item in report["accepted"]] == ["node/4"]
    assert report["summary"]["excluded_count"] == 1
    assert report["summary"]["edited_count"] == 1


def test_review_writes_reviewed_file_and_keeps_identical_approval(tmp_path, monkeypatch):
    from scripts import review_pois as review_script

    processed = tmp_path / "test_pois_normalized.json"
    pois = [
        {
            "osm_type": "node",
            "osm_id": 1,
            "name": "Hrad",
            "category": "attraction",
            "lat": 48.05,
            "lon": 18.05,
            "base_score": 0.6,
            "visit_duration_min": 30,
            "short_description": "x",
        }
    ]
    processed.write_text(json.dumps(pois, ensure_ascii=False, indent=2), encoding="utf-8")
    write_approval(processed, "Správca", {"active": 1})
    monkeypatch.setattr(review_script, "processed_file", lambda city: processed)
    city = {"slug": "test", "bbox": {}}

    reviewed, _, report = review_script.review_city_data(city)
    assert reviewed.read_bytes() == processed.read_bytes()
    assert require_approval(reviewed)["carried_over_from"] == processed.name

    review_script.save_decisions(city, {"node/1": {"action": "exclude"}})
    reviewed, items, _ = review_script.review_city_data(city)
    assert items[0]["is_active"] is False
    with pytest.raises(ApprovalError):
        require_approval(reviewed)

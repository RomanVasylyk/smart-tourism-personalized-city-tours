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

from __future__ import annotations

import json
import os
import sys
from collections import Counter
from collections.abc import Callable
from importlib import import_module
from pathlib import Path
from threading import Lock
from types import SimpleNamespace
from typing import Any

from app.db.database import get_connection
from app.services.service_calendar import invalidate_service_calendar
from app.services.transport_graph import clear_transport_graph_cache

PIPELINE_ROOT = Path(os.getenv("PIPELINE_ROOT", "/pipeline"))
MAX_ISSUES = 8
QUALITY_KEYS = (
    "warnings_count",
    "validation_warning_count",
    "invalid_trip_count",
    "unmatched_stop_count",
    "coverage_ratio",
)

_lock = Lock()
_file_cache: dict[tuple[str, float, int, str], Any] = {}


class PipelineUnavailableError(RuntimeError):
    pass


def modules() -> SimpleNamespace:
    if not (PIPELINE_ROOT / "config" / "cities.yaml").exists():
        raise PipelineUnavailableError(f"Priečinok dátovej pipeline {PIPELINE_ROOT} nie je pripojený k serveru.")
    root = str(PIPELINE_ROOT)
    if root not in sys.path:
        sys.path.insert(0, root)
    try:
        return SimpleNamespace(
            approval=import_module("utils.approval"),
            cities=import_module("utils.cities"),
            validator=import_module("scripts.validate_transport_graph"),
            transport_loader=import_module("scripts.load_transport_to_db"),
            poi_review=import_module("scripts.review_pois"),
            poi_loader=import_module("scripts.load_to_db"),
        )
    except ImportError as exc:
        raise PipelineUnavailableError(f"Moduly dátovej pipeline sa nepodarilo načítať: {exc}") from exc


def cached(path: Path, kind: str, compute: Callable[[Path], Any]) -> Any:
    stat = path.stat()
    key = (str(path), stat.st_mtime, stat.st_size, kind)
    with _lock:
        if key in _file_cache:
            return _file_cache[key]
    value = compute(path)
    with _lock:
        _file_cache[key] = value
    return value


def read_json(path: Path) -> Any:
    return json.loads(path.read_text(encoding="utf-8"))


def graph_summary(path: Path) -> dict:
    graph = read_json(path)
    trips = graph.get("trips") or []
    return {
        "generated_at": graph.get("generated_at"),
        "stops": len(graph.get("stops") or []),
        "lines": len(graph.get("lines") or []),
        "trips": len(trips),
        "buckets": dict(Counter(trip.get("service_bucket") or "all_days" for trip in trips)),
    }


def poi_summary(path: Path) -> dict:
    pois = read_json(path)
    active = [poi for poi in pois if poi.get("is_active", True)]
    return {
        "total": len(pois),
        "active": len(active),
        "by_category": dict(sorted(Counter(poi.get("category") for poi in active).items())),
    }


def latest_loads() -> dict[tuple[str, str], dict]:
    with get_connection() as conn:
        with conn.cursor() as cur:
            cur.execute(
                """
                SELECT DISTINCT ON (dataset, city_slug) dataset, city_slug, sha256, loaded_by, loaded_at
                FROM data_loads
                ORDER BY dataset, city_slug, loaded_at DESC
                """
            )
            return {(row["dataset"], row["city_slug"]): row for row in cur.fetchall()}


def load_state(module: SimpleNamespace, data_file: Path, last_load: dict | None) -> dict:
    if last_load is None:
        return {"in_sync": None, "loaded_by": None, "loaded_at": None}
    digest = cached(data_file, "sha256", module.approval.file_sha256)
    return {
        "in_sync": last_load["sha256"] == digest,
        "loaded_by": last_load["loaded_by"],
        "loaded_at": last_load["loaded_at"],
    }


def approval_state(module: SimpleNamespace, data_file: Path) -> dict:
    approval = module.approval.load_approval(data_file)
    if approval is None:
        return {"status": "missing", "reviewer": None, "approved_at": None}
    digest = cached(data_file, "sha256", module.approval.file_sha256)
    return {
        "status": "approved" if approval.get("sha256") == digest else "changed",
        "reviewer": approval.get("reviewer"),
        "approved_at": approval.get("approved_at"),
    }


def is_newer_or_same(report_file: Path, data_file: Path) -> bool:
    return report_file.exists() and report_file.stat().st_mtime >= data_file.stat().st_mtime


def database_state(city_name: str) -> dict:
    empty = {"city_id": None, "buckets": {}, "trips": 0, "lines": 0, "stops": 0, "pois_active": 0, "pois_locked": 0}
    with get_connection() as conn:
        with conn.cursor() as cur:
            cur.execute("SELECT id FROM cities WHERE lower(name) = lower(%s) ORDER BY id LIMIT 1", (city_name,))
            row = cur.fetchone()
            if row is None:
                return empty
            city_id = row["id"]
            cur.execute(
                """
                SELECT t.service_bucket, COUNT(*) AS trips
                FROM transport_trips t JOIN transport_lines l ON l.id = t.line_id
                WHERE l.city_id = %s
                GROUP BY t.service_bucket
                """,
                (city_id,),
            )
            buckets = {item["service_bucket"]: item["trips"] for item in cur.fetchall()}
            cur.execute(
                """
                SELECT
                    (SELECT COUNT(*) FROM transport_lines WHERE city_id = %(city)s) AS lines,
                    (SELECT COUNT(*) FROM transport_stops WHERE city_id = %(city)s) AS stops,
                    (SELECT COUNT(*) FROM pois WHERE city_id = %(city)s AND is_active AND osm_type <> 'manual') AS pois_active,
                    (SELECT COUNT(*) FROM pois WHERE city_id = %(city)s AND admin_locked) AS pois_locked
                """,
                {"city": city_id},
            )
            counts = cur.fetchone()
    return {"city_id": city_id, "buckets": buckets, "trips": sum(buckets.values()), **counts}


def transport_state(module: SimpleNamespace, city: dict, database: dict, last_load: dict | None) -> dict | None:
    graph_file = module.transport_loader.transport_graph_path(city)
    if not graph_file.exists():
        return None
    summary = cached(graph_file, "graph", graph_summary)
    report_file, _ = module.validator.report_paths(graph_file)
    report = read_json(report_file) if report_file.exists() else None
    return {
        **summary,
        "report_current": is_newer_or_same(report_file, graph_file),
        "quality": {key: report.get(key) for key in QUALITY_KEYS} if report else None,
        "issues": [
            {"code": item.get("code"), "message": item.get("message")}
            for item in (report or {}).get("validation_warnings", [])[:MAX_ISSUES]
        ],
        "approval": approval_state(module, graph_file),
        "load": load_state(module, graph_file, last_load),
        "database": {
            "trips": database["trips"],
            "lines": database["lines"],
            "stops": database["stops"],
            "buckets": database["buckets"],
        },
    }


def poi_state(module: SimpleNamespace, city: dict, database: dict, last_load: dict | None) -> dict | None:
    data_file = module.poi_loader.processed_file(city)
    if not data_file.exists():
        return None
    summary = cached(data_file, "pois", poi_summary)
    review_path = module.poi_review.review_file(city)
    review = read_json(review_path) if review_path.exists() else None
    review_state = None
    if review is not None:
        changes = review.get("changes") or {}
        review_state = {
            "errors": len(review.get("errors") or []),
            "warnings": len(review.get("warnings") or []),
            "warning_codes": dict(Counter(item.get("code") for item in review.get("warnings") or [])),
            "error_items": (review.get("errors") or [])[:MAX_ISSUES],
            "warning_items": [
                item for item in review.get("warnings") or [] if item.get("code") != "missing_description"
            ][:MAX_ISSUES],
            "changes": (
                {key: len(changes.get(key) or []) for key in ("added", "removed", "changed")} if changes else None
            ),
        }
    return {
        **summary,
        "review": review_state,
        "review_current": is_newer_or_same(review_path, data_file),
        "approval": approval_state(module, data_file),
        "load": load_state(module, data_file, last_load),
        "database": {"active": database["pois_active"], "locked": database["pois_locked"]},
    }


def status() -> dict:
    try:
        module = modules()
    except PipelineUnavailableError as exc:
        return {"available": False, "message": str(exc), "cities": []}
    loads = latest_loads()
    cities = []
    for city in module.cities.load_city_profiles():
        database = database_state(city["name"])
        cities.append(
            {
                "slug": city["slug"],
                "name": city["name"],
                "transport": transport_state(module, city, database, loads.get(("transport", city["slug"]))),
                "pois": poi_state(module, city, database, loads.get(("pois", city["slug"]))),
            }
        )
    return {"available": True, "message": None, "cities": cities}


def city_slugs() -> set[str]:
    return {city["slug"] for city in modules().cities.load_city_profiles()}


def city_profile(module: SimpleNamespace, slug: str) -> dict:
    return module.cities.load_city(slug)


def reviewer_name(admin: dict) -> str:
    name = (admin.get("display_name") or "").strip()
    return f"{name} <{admin['email']}>" if name else admin["email"]


def check_transport(slug: str) -> dict:
    module = modules()
    graph_file, report_file, report = module.validator.build_report(city_profile(module, slug))
    module.validator.write_report(report_file, report)
    return {key: report.get(key) for key in QUALITY_KEYS}


def approve_transport(slug: str, admin: dict) -> dict:
    module = modules()
    graph_file, report_file, report = module.validator.build_report(city_profile(module, slug))
    module.validator.write_report(report_file, report)
    module.validator.approve_graph(graph_file, report, reviewer_name(admin))
    return {"reviewer": reviewer_name(admin)}


def load_transport(slug: str, admin: dict) -> dict:
    module = modules()
    counts = module.transport_loader.load_transport(
        city_profile(module, slug), migrate=False, loaded_by=reviewer_name(admin)
    )
    clear_transport_graph_cache()
    invalidate_service_calendar()
    return counts


def check_pois(slug: str) -> dict:
    module = modules()
    _, _, report = module.poi_review.review_city_data(city_profile(module, slug))
    return report["summary"]


def approve_pois(slug: str, admin: dict, allow_errors: bool) -> dict:
    module = modules()
    data_file, pois, report = module.poi_review.review_city_data(city_profile(module, slug))
    module.poi_review.approve_reviewed(data_file, pois, report, reviewer_name(admin), allow_errors)
    return {"reviewer": reviewer_name(admin)}


def load_pois(slug: str, admin: dict) -> dict:
    module = modules()
    inserted, updated, kept = module.poi_loader.load_pois_for_city(city_profile(module, slug), reviewer_name(admin))
    return {"inserted": inserted, "updated": updated, "kept": kept}


def error_kind(exc: Exception) -> str | None:
    name = type(exc).__name__
    if name == "ApprovalError":
        return "approval"
    if name == "ReviewBlockedError":
        return "review"
    return None

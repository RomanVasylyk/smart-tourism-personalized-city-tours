from __future__ import annotations

import json
import os
import sys
from collections import Counter
from collections.abc import Callable
from datetime import UTC, datetime
from importlib import import_module
from pathlib import Path
from threading import Lock
from types import SimpleNamespace
from typing import Any

import yaml
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
    source_file = module.poi_loader.processed_file(city)
    if not source_file.exists():
        return None
    reviewed = module.poi_review.reviewed_file(city)
    decisions_path = module.poi_review.decisions_file(city)
    data_file = reviewed if reviewed.exists() else source_file
    summary = cached(data_file, "pois", poi_summary)
    review_path = module.poi_review.review_file(city)
    review = read_json(review_path) if review_path.exists() else None
    review_state = None
    if review is not None:
        changes = review.get("changes") or {}
        review_state = {
            "errors": len(review.get("errors") or []),
            "accepted": len(review.get("accepted") or []),
            "warnings": len(review.get("warnings") or []),
            "warning_codes": dict(Counter(item.get("code") for item in review.get("warnings") or [])),
            "error_items": (review.get("errors") or [])[:MAX_ISSUES],
            "warning_items": [
                item for item in review.get("warnings") or [] if item.get("code") != "missing_description"
            ][:MAX_ISSUES],
            "changes": (
                {key: len(changes.get(key) or []) for key in ("added", "removed", "changed")} if changes else None
            ),
            "excluded": (review.get("summary") or {}).get("excluded_count", 0),
            "edited": (review.get("summary") or {}).get("edited_count", 0),
        }
    inputs = [source_file, module.cities.CONFIG_FILE, *([decisions_path] if decisions_path.exists() else [])]
    newest_input = max(path.stat().st_mtime for path in inputs)
    review_current = reviewed.exists() and review_path.exists() and review_path.stat().st_mtime >= newest_input
    missing = {"status": "missing", "reviewer": None, "approved_at": None}
    return {
        **summary,
        "source_total": cached(source_file, "pois", poi_summary)["total"],
        "review": review_state,
        "review_current": review_current,
        "approval": approval_state(module, reviewed) if reviewed.exists() else missing,
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


class InputError(ValueError):
    pass


ITEM_FIELDS = (
    "name",
    "category",
    "subcategory",
    "lat",
    "lon",
    "address",
    "visit_duration_min",
    "base_score",
    "short_description",
    "website",
    "image_url",
    "wikidata_id",
    "wikipedia_title",
    "wikipedia_url",
    "opening_hours_raw",
)
ISSUE_SECTIONS = (("errors", "error"), ("accepted", "accepted"), ("warnings", "warning"))
REQUIRED_POI_FIELDS = ("osm_type", "osm_id", "name", "category", "lat", "lon")
SLUG_CHARACTERS = set("abcdefghijklmnopqrstuvwxyz0123456789-")


def clear_caches() -> None:
    with _lock:
        _file_cache.clear()


def review_module() -> Any:
    modules()
    return import_module("etl.poi.review")


def ensure_reviewed(module: SimpleNamespace, city: dict) -> None:
    if not module.poi_review.reviewed_file(city).exists() or not module.poi_review.review_file(city).exists():
        module.poi_review.review_city_data(city)


def poi_items(slug: str) -> dict:
    module = modules()
    city = city_profile(module, slug)
    source_file = module.poi_loader.processed_file(city)
    if not source_file.exists():
        raise FileNotFoundError(source_file)
    ensure_reviewed(module, city)
    review = read_json(module.poi_review.review_file(city))
    decisions = module.poi_review.load_decisions(city)
    issues: dict[str, list[dict]] = {}
    for section, level in ISSUE_SECTIONS:
        for item in review.get(section) or []:
            issues.setdefault(item["key"], []).append(
                {"code": item.get("code"), "level": level, "message": item.get("message")}
            )
    reviewer = review_module()
    items = []
    for poi in read_json(source_file):
        key = reviewer.poi_key(poi)
        items.append(
            {
                "key": key,
                "values": {field: poi.get(field) for field in ITEM_FIELDS},
                "is_active": poi.get("is_active", True),
                "decision": decisions.get(key),
                "issues": issues.get(key, []),
            }
        )
    return {
        "city": {"slug": city["slug"], "name": city["name"], "bbox": city.get("bbox")},
        "categories": city.get("available_categories") or [],
        "editable_fields": list(reviewer.EDITABLE_FIELDS),
        "summary": review.get("summary") or {},
        "items": items,
    }


def coerce_edit(field: str, value: Any, categories: list[str]) -> Any:
    if isinstance(value, str):
        value = value.strip()
    if value in ("", None):
        if field in ("name", "category", "lat", "lon"):
            raise InputError("Názov, kategória a súradnice nemôžu byť prázdne.")
        return None
    try:
        if field == "lat":
            number = float(str(value).replace(",", "."))
            if not -90 <= number <= 90:
                raise InputError("Zemepisná šírka musí byť od -90 do 90.")
            return number
        if field == "lon":
            number = float(str(value).replace(",", "."))
            if not -180 <= number <= 180:
                raise InputError("Zemepisná dĺžka musí byť od -180 do 180.")
            return number
        if field == "visit_duration_min":
            number = int(value)
            if not 1 <= number <= 600:
                raise InputError("Dĺžka návštevy musí byť od 1 do 600 minút.")
            return number
        if field == "base_score":
            number = float(str(value).replace(",", "."))
            if not 0 <= number <= 1:
                raise InputError("Základné skóre musí byť od 0 do 1.")
            return number
    except (TypeError, ValueError) as exc:
        if isinstance(exc, InputError):
            raise
        raise InputError("Číselné pole má nesprávny formát.") from exc
    if field == "category" and categories and value not in categories:
        raise InputError("Kategória nie je pre mesto povolená.")
    return str(value)


def save_decision(slug: str, key: str, action: str, edits: dict | None, note: str | None, admin: dict) -> dict:
    module = modules()
    city = city_profile(module, slug)
    reviewer = review_module()
    if action not in reviewer.DECISION_ACTIONS:
        raise InputError("Neznáme rozhodnutie.")
    pois = read_json(module.poi_loader.processed_file(city))
    if key not in {reviewer.poi_key(poi) for poi in pois}:
        raise KeyError(key)
    categories = city.get("available_categories") or []
    clean_edits = {}
    for field, value in (edits or {}).items():
        if field not in reviewer.EDITABLE_FIELDS:
            raise InputError("Toto pole nemožno upraviť.")
        clean_edits[field] = coerce_edit(field, value, categories)
    decisions = module.poi_review.load_decisions(city)
    note = (note or "").strip() or None
    if action == "include" and not clean_edits and not note:
        decisions.pop(key, None)
    else:
        decisions[key] = {
            "action": action,
            "edits": clean_edits,
            "note": note,
            "by": reviewer_name(admin),
            "at": datetime.now(UTC).isoformat(timespec="seconds"),
        }
    module.poi_review.save_decisions(city, decisions)
    module.poi_review.review_city_data(city)
    return poi_items(slug)


def upload_pois(slug: str, payload: list, admin: dict) -> dict:
    module = modules()
    city = city_profile(module, slug)
    if not isinstance(payload, list) or not payload:
        raise InputError("Súbor musí obsahovať neprázdny zoznam bodov záujmu.")
    reviewer = review_module()
    seen = set()
    for index, poi in enumerate(payload, start=1):
        if not isinstance(poi, dict) or any(poi.get(field) in (None, "") for field in REQUIRED_POI_FIELDS):
            raise InputError(f"Záznam č. {index} nemá všetky povinné polia: {', '.join(REQUIRED_POI_FIELDS)}.")
        if not isinstance(poi["lat"], int | float) or not isinstance(poi["lon"], int | float):
            raise InputError(f"Záznam č. {index} má súradnice v nesprávnom formáte.")
        key = reviewer.poi_key(poi)
        if key in seen:
            raise InputError(f"Objekt {key} je v súbore viackrát.")
        seen.add(key)
    source_file = module.poi_loader.processed_file(city)
    if source_file.exists():
        source_file.with_name(f"{city['slug']}_pois_normalized.backup.json").write_bytes(source_file.read_bytes())
    source_file.write_text(json.dumps(payload, ensure_ascii=False, indent=2), encoding="utf-8")
    module.poi_review.review_city_data(city)
    return {"count": len(payload), "uploaded_by": reviewer_name(admin)}


def lines_summary(path: Path) -> list[dict]:
    graph = read_json(path)
    trips = Counter(trip.get("line_id") for trip in graph.get("trips") or [])
    return [
        {
            "line_id": line.get("line_id"),
            "name": line.get("name"),
            "direction": line.get("direction_name"),
            "service_bucket": line.get("service_bucket"),
            "stops": len(line.get("stops") or []),
            "trips": trips.get(line.get("line_id"), 0),
            "valid_from": line.get("valid_from"),
            "source_url": line.get("source_url"),
        }
        for line in graph.get("lines") or []
    ]


def transport_lines(slug: str) -> dict:
    module = modules()
    city = city_profile(module, slug)
    graph_file = module.transport_loader.transport_graph_path(city)
    if not graph_file.exists():
        raise FileNotFoundError(graph_file)
    return {"city": {"slug": city["slug"], "name": city["name"]}, "lines": cached(graph_file, "lines", lines_summary)}


def data_file(slug: str, kind: str) -> tuple[Path, str]:
    module = modules()
    city = city_profile(module, slug)
    graph_file = module.transport_loader.transport_graph_path(city)
    paths = {
        "pois_normalized": module.poi_loader.processed_file(city),
        "pois_reviewed": module.poi_review.reviewed_file(city),
        "pois_review": module.poi_review.review_file(city),
        "pois_decisions": module.poi_review.decisions_file(city),
        "transport_graph": graph_file,
        "transport_report": module.validator.report_paths(graph_file)[0],
    }
    path = paths.get(kind)
    if path is None:
        raise KeyError(kind)
    if not path.exists():
        raise FileNotFoundError(path)
    name = path.name if path.name.startswith(city["slug"]) else f"{city['slug']}_{path.name}"
    return path, name


def city_settings(city: dict) -> dict:
    transport = city.get("transport") or {}
    limits = city.get("routing_limits") or {}
    return {
        "slug": city.get("slug"),
        "name": city.get("name"),
        "country": city.get("country"),
        "center": city.get("center") or {},
        "bbox": city.get("bbox") or {},
        "available_categories": city.get("available_categories") or [],
        "default_zoom": city.get("default_zoom"),
        "routing_limits": {
            "max_available_minutes": limits.get("max_available_minutes"),
            "max_poi_candidates": limits.get("max_poi_candidates"),
        },
        "transport": {
            "mhd_enabled": bool(transport.get("mhd_enabled")),
            "provider": transport.get("provider"),
            "source_index_url": transport.get("source_index_url"),
        },
    }


def number(value: Any, label: str, low: float, high: float) -> float:
    try:
        result = float(str(value).replace(",", "."))
    except (TypeError, ValueError) as exc:
        raise InputError(f"{label} musí byť číslo.") from exc
    if not low <= result <= high:
        raise InputError(f"{label} musí byť od {low:g} do {high:g}.")
    return result


def validate_city(city: Any, position: int) -> None:
    if not isinstance(city, dict):
        raise InputError(f"Mesto č. {position} nie je správne zapísané.")
    slug = str(city.get("slug") or "")
    if not slug or not set(slug) <= SLUG_CHARACTERS:
        raise InputError(f"Mesto č. {position} potrebuje skratku z malých písmen, číslic a pomlčiek.")
    if not city.get("name") or not city.get("country"):
        raise InputError(f"Mesto {slug} potrebuje názov a krajinu.")
    center = city.get("center") or {}
    bbox = city.get("bbox") or {}
    number(center.get("lat"), f"Stred mesta {slug} – šírka", -90, 90)
    number(center.get("lon"), f"Stred mesta {slug} – dĺžka", -180, 180)
    south = number(bbox.get("south"), f"Hranica mesta {slug} – juh", -90, 90)
    north = number(bbox.get("north"), f"Hranica mesta {slug} – sever", -90, 90)
    west = number(bbox.get("west"), f"Hranica mesta {slug} – západ", -180, 180)
    east = number(bbox.get("east"), f"Hranica mesta {slug} – východ", -180, 180)
    if south >= north or west >= east:
        raise InputError(f"Hranice mesta {slug} sú prehodené: juh musí byť menší ako sever a západ menší ako východ.")


def validate_config(config: Any) -> None:
    cities = config.get("cities") if isinstance(config, dict) else None
    if not isinstance(cities, list) or not cities:
        raise InputError("Konfigurácia musí obsahovať zoznam miest pod kľúčom cities.")
    for position, city in enumerate(cities, start=1):
        validate_city(city, position)
    slugs = [city["slug"] for city in cities]
    if len(slugs) != len(set(slugs)):
        raise InputError("Skratky miest sa nesmú opakovať.")


def config_state() -> dict:
    module = modules()
    return {
        "yaml": module.cities.CONFIG_FILE.read_text(encoding="utf-8"),
        "cities": [city_settings(city) for city in module.cities.load_city_profiles()],
    }


def write_config(text: str) -> None:
    module = modules()
    try:
        parsed = yaml.safe_load(text)
    except yaml.YAMLError as exc:
        raise InputError(f"Súbor nie je platný YAML: {exc}") from exc
    validate_config(parsed)
    path = module.cities.CONFIG_FILE
    path.with_name("cities.backup.yaml").write_bytes(path.read_bytes())
    path.write_text(text, encoding="utf-8")
    clear_caches()


def update_city_settings(slug: str, data: dict) -> dict:
    module = modules()
    config = yaml.safe_load(module.cities.CONFIG_FILE.read_text(encoding="utf-8"))
    city = next((item for item in config.get("cities") or [] if item.get("slug") == slug), None)
    if city is None:
        raise KeyError(slug)
    center = data.get("center") or {}
    bbox = data.get("bbox") or {}
    city["center"] = {"lat": center.get("lat"), "lon": center.get("lon")}
    city["bbox"] = {key: bbox.get(key) for key in ("south", "west", "north", "east")}
    for section in (city["center"], city["bbox"]):
        for key, value in section.items():
            section[key] = number(value, "Súradnica", -180, 180)
    categories = [item for item in data.get("available_categories") or [] if isinstance(item, str)]
    if not categories:
        raise InputError("Vyberte aspoň jednu kategóriu.")
    city["available_categories"] = categories
    if data.get("default_zoom") not in (None, ""):
        city["default_zoom"] = number(data["default_zoom"], "Priblíženie mapy", 3, 20)
    limits = data.get("routing_limits") or {}
    routing = city.setdefault("routing_limits", {})
    if limits.get("max_available_minutes") not in (None, ""):
        routing["max_available_minutes"] = int(number(limits["max_available_minutes"], "Najdlhšia trasa", 30, 1440))
    if limits.get("max_poi_candidates") not in (None, ""):
        routing["max_poi_candidates"] = int(number(limits["max_poi_candidates"], "Počet kandidátov", 10, 2000))
    if isinstance(data.get("transport"), dict) and "mhd_enabled" in data["transport"]:
        city.setdefault("transport", {})["mhd_enabled"] = bool(data["transport"]["mhd_enabled"])
    write_config(yaml.safe_dump(config, sort_keys=False, allow_unicode=True, default_flow_style=False))
    profile = city_profile(module, slug)
    if module.poi_loader.processed_file(profile).exists():
        module.poi_review.review_city_data(profile)
    return city_settings(profile)

from __future__ import annotations

import math
import unicodedata
from collections import Counter

BBOX_TOLERANCE_DEGREES = 0.002
DUPLICATE_DISTANCE_METERS = 150.0
MOVED_DISTANCE_METERS = 50.0
MAX_VISIT_MINUTES = 600
EDITABLE_FIELDS = (
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
)
DECISION_ACTIONS = ("include", "exclude", "accept")


def poi_key(poi: dict) -> str:
    return f"{poi.get('osm_type')}/{poi.get('osm_id')}"


def distance_meters(a_lat: float, a_lon: float, b_lat: float, b_lon: float) -> float:
    radius = 6_371_000.0
    phi1 = math.radians(a_lat)
    phi2 = math.radians(b_lat)
    d_phi = math.radians(b_lat - a_lat)
    d_lambda = math.radians(b_lon - a_lon)
    h = math.sin(d_phi / 2) ** 2 + math.cos(phi1) * math.cos(phi2) * math.sin(d_lambda / 2) ** 2
    return 2 * radius * math.asin(math.sqrt(h))


def normalized_name(name: str | None) -> str:
    decomposed = unicodedata.normalize("NFKD", (name or "").casefold())
    without_marks = "".join(char for char in decomposed if not unicodedata.combining(char))
    return " ".join(without_marks.split())


def has_coordinates(poi: dict) -> bool:
    return isinstance(poi.get("lat"), int | float) and isinstance(poi.get("lon"), int | float)


def inside_bbox(poi: dict, bbox: dict) -> bool:
    return (
        bbox["south"] - BBOX_TOLERANCE_DEGREES <= poi["lat"] <= bbox["north"] + BBOX_TOLERANCE_DEGREES
        and bbox["west"] - BBOX_TOLERANCE_DEGREES <= poi["lon"] <= bbox["east"] + BBOX_TOLERANCE_DEGREES
    )


def issue(code: str, poi: dict, message: str) -> dict:
    return {"code": code, "key": poi_key(poi), "name": poi.get("name"), "message": message}


def snapshot_of(pois: list[dict]) -> dict:
    return {
        "pois": {
            poi_key(poi): {
                "name": poi.get("name"),
                "category": poi.get("category"),
                "lat": poi.get("lat"),
                "lon": poi.get("lon"),
            }
            for poi in pois
            if poi.get("is_active", True)
        }
    }


def compare_with_snapshot(pois: list[dict], snapshot: dict) -> dict:
    previous = snapshot.get("pois") or {}
    current = snapshot_of(pois)["pois"]
    added = sorted(key for key in current if key not in previous)
    removed = sorted(key for key in previous if key not in current)
    changed = []
    for key in sorted(set(current) & set(previous)):
        before = previous[key]
        after = current[key]
        differences = [field for field in ("name", "category") if before.get(field) != after.get(field)]
        if None not in (before.get("lat"), before.get("lon"), after.get("lat"), after.get("lon")):
            moved = distance_meters(before["lat"], before["lon"], after["lat"], after["lon"])
            if moved > MOVED_DISTANCE_METERS:
                differences.append("position")
        if differences:
            changed.append({"key": key, "name": after.get("name"), "fields": differences})
    return {
        "added": [{"key": key, "name": current[key].get("name")} for key in added],
        "removed": [{"key": key, "name": previous[key].get("name")} for key in removed],
        "changed": changed,
    }


def apply_decisions(pois: list[dict], decisions: dict | None) -> list[dict]:
    if not decisions:
        return [dict(poi) for poi in pois]
    reviewed = []
    for poi in pois:
        decision = decisions.get(poi_key(poi)) or {}
        item = dict(poi)
        for field, value in (decision.get("edits") or {}).items():
            if field in EDITABLE_FIELDS:
                item[field] = value
        if decision.get("action") == "exclude":
            item["is_active"] = False
        reviewed.append(item)
    return reviewed


def review_pois(
    pois: list[dict],
    city: dict,
    approved_snapshot: dict | None = None,
    decisions: dict | None = None,
) -> dict:
    bbox = city.get("bbox") or {}
    categories = set(city.get("available_categories") or [])
    errors: list[dict] = []
    warnings: list[dict] = []
    seen_keys: set[str] = set()
    active = [poi for poi in pois if poi.get("is_active", True)]

    for poi in active:
        key = poi_key(poi)
        if key in seen_keys:
            errors.append(issue("duplicate_key", poi, "The same OSM object appears more than once."))
        seen_keys.add(key)
        if not (poi.get("name") or "").strip():
            errors.append(issue("missing_name", poi, "POI has no name."))
        if not has_coordinates(poi):
            errors.append(issue("missing_coordinates", poi, "POI has no valid coordinates."))
        elif bbox and not inside_bbox(poi, bbox):
            errors.append(issue("outside_city", poi, "POI lies outside the city bounding box."))
        if categories and poi.get("category") not in categories:
            errors.append(
                issue("unknown_category", poi, f"Category {poi.get('category')!r} is not enabled for the city.")
            )
        score = poi.get("base_score")
        if not isinstance(score, int | float) or not 0.0 <= score <= 1.0:
            errors.append(issue("invalid_base_score", poi, f"Base score {score!r} is outside 0 to 1."))
        visit = poi.get("visit_duration_min")
        if not isinstance(visit, int) or not 0 < visit <= MAX_VISIT_MINUTES:
            errors.append(
                issue("invalid_visit_duration", poi, f"Visit duration {visit!r} is not a positive number of minutes.")
            )
        if not poi.get("short_description"):
            warnings.append(issue("missing_description", poi, "POI has no description."))

    by_name: dict[str, list[dict]] = {}
    for poi in active:
        if has_coordinates(poi) and normalized_name(poi.get("name")):
            by_name.setdefault(normalized_name(poi.get("name")), []).append(poi)
    for group in by_name.values():
        for index, first in enumerate(group):
            for second in group[index + 1 :]:
                gap = distance_meters(first["lat"], first["lon"], second["lat"], second["lon"])
                if gap < DUPLICATE_DISTANCE_METERS:
                    warnings.append(
                        issue(
                            "possible_duplicate",
                            second,
                            f"Same name as {poi_key(first)} at {gap:.0f} m.",
                        )
                    )

    decisions = decisions or {}
    accepted = [item for item in errors if (decisions.get(item["key"]) or {}).get("action") == "accept"]
    errors = [item for item in errors if (decisions.get(item["key"]) or {}).get("action") != "accept"]

    summary = {
        "total": len(pois),
        "active": len(active),
        "by_category": dict(sorted(Counter(poi.get("category") for poi in active).items())),
        "with_description": sum(1 for poi in active if poi.get("short_description")),
        "with_opening_hours": sum(1 for poi in active if poi.get("opening_hours_raw")),
        "with_wikidata": sum(1 for poi in active if poi.get("wikidata_id")),
        "error_count": len(errors),
        "warning_count": len(warnings),
        "accepted_count": len(accepted),
        "excluded_count": sum(1 for decision in decisions.values() if decision.get("action") == "exclude"),
        "edited_count": sum(1 for decision in decisions.values() if decision.get("edits")),
    }
    changes = compare_with_snapshot(pois, approved_snapshot) if approved_snapshot else None
    return {
        "city": city.get("slug"),
        "summary": summary,
        "errors": errors,
        "accepted": accepted,
        "warnings": warnings,
        "changes": changes,
    }

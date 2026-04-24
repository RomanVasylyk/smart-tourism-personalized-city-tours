from __future__ import annotations

import json
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
if str(ROOT) not in sys.path:
    sys.path.insert(0, str(ROOT))

from utils.cities import load_city


def load_city_profile() -> dict:
    city_slug = sys.argv[1] if len(sys.argv) > 1 else "nitra"
    return load_city(city_slug)


def map_category(tags: dict) -> str | None:
    if tags.get("tourism") == "attraction":
        return "attraction"
    if tags.get("tourism") == "museum":
        return "museum"
    if tags.get("tourism") == "gallery":
        return "gallery"
    if tags.get("tourism") == "viewpoint":
        return "viewpoint"
    if tags.get("historic") == "monument":
        return "monument"
    if tags.get("historic") == "memorial":
        return "monument"
    if tags.get("historic") in {"castle", "ruins"}:
        return "historical_site"
    if tags.get("leisure") in {"park", "garden"}:
        return "park"
    if tags.get("amenity") == "place_of_worship":
        return "religious_site"
    return None


def visit_duration_for(category: str) -> int:
    mapping = {
        "attraction": 30,
        "museum": 60,
        "gallery": 45,
        "viewpoint": 20,
        "monument": 15,
        "historical_site": 30,
        "park": 25,
        "religious_site": 20,
    }
    return mapping[category]


def base_score_for(category: str, city: dict) -> float:
    mapping = {
        "attraction": 0.8,
        "museum": 0.9,
        "gallery": 0.75,
        "viewpoint": 0.75,
        "monument": 0.6,
        "historical_site": 0.85,
        "park": 0.65,
        "religious_site": 0.7,
    }
    base_score = mapping[category]
    adjustment = (city.get("default_score_adjustments") or {}).get(category, 0.0)
    return round(base_score + adjustment, 3)


def parse_wikipedia(value: str | None) -> tuple[str | None, str | None]:
    if not value or ":" not in value:
        return None, None
    lang, title = value.split(":", 1)
    return title, f"https://{lang}.wikipedia.org/wiki/{title.replace(' ', '_')}"


def normalize_element(element: dict, city: dict) -> dict | None:
    tags = element.get("tags", {})
    name = tags.get("name")
    category = map_category(tags)

    if not name or not category:
        return None

    lat = element.get("lat") or element.get("center", {}).get("lat")
    lon = element.get("lon") or element.get("center", {}).get("lon")
    if lat is None or lon is None:
        return None

    if is_excluded(name, city):
        return None

    wikipedia_title, wikipedia_url = parse_wikipedia(tags.get("wikipedia"))

    return {
        "osm_id": str(element["id"]),
        "osm_type": element["type"],
        "name": name.strip(),
        "category": category,
        "subcategory": None,
        "lat": lat,
        "lon": lon,
        "address": None,
        "opening_hours_raw": tags.get("opening_hours"),
        "visit_duration_min": visit_duration_for(category),
        "base_score": base_score_for(category, city),
        "wikidata_id": tags.get("wikidata"),
        "wikipedia_title": wikipedia_title,
        "wikipedia_url": wikipedia_url,
        "short_description": None,
        "source": "osm",
        "is_active": True,
    }


def deduplicate(items: list[dict]) -> list[dict]:
    seen = set()
    result = []

    for item in items:
        key = (item["osm_type"], item["osm_id"])
        if key in seen:
            continue
        seen.add(key)
        result.append(item)

    return result


def is_excluded(name: str, city: dict) -> bool:
    excluded_patterns = ((city.get("city_specific_exclusions") or {}).get("excluded_name_patterns")) or []
    return any(re.search(pattern, name, flags=re.IGNORECASE) for pattern in excluded_patterns)


def main() -> None:
    city = load_city_profile()
    slug = city["slug"]
    raw_file = ROOT / "data" / "raw" / f"{slug}_osm_raw.json"
    out_file = ROOT / "data" / "processed" / f"{slug}_pois_normalized.json"

    data = json.loads(raw_file.read_text(encoding="utf-8"))
    normalized = [item for item in (normalize_element(el, city) for el in data.get("elements", [])) if item]
    normalized = deduplicate(normalized)

    out_file.parent.mkdir(parents=True, exist_ok=True)
    out_file.write_text(json.dumps(normalized, ensure_ascii=False, indent=2), encoding="utf-8")
    print(f"Saved {len(normalized)} normalized POIs to {out_file}")


if __name__ == "__main__":
    main()

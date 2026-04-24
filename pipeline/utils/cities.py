from __future__ import annotations

from pathlib import Path

import yaml

ROOT = Path(__file__).resolve().parents[1]
CONFIG_FILE = ROOT / "config" / "cities.yaml"


def load_city_profiles() -> list[dict]:
    config = yaml.safe_load(CONFIG_FILE.read_text(encoding="utf-8")) or {}
    cities = config.get("cities") or []
    if not cities:
        raise ValueError(f"No cities configured in {CONFIG_FILE}")
    return cities


def load_city(slug: str) -> dict:
    normalized_slug = slug.strip().lower()
    for city in load_city_profiles():
        if city.get("slug", "").lower() == normalized_slug:
            return city
    raise ValueError(f"City '{slug}' not found in {CONFIG_FILE}")

from __future__ import annotations

from pathlib import Path

import yaml


def resolve_config_file() -> Path:
    for parent in Path(__file__).resolve().parents:
        candidate = parent / "pipeline" / "config" / "cities.yaml"
        if candidate.exists():
            return candidate

    raise FileNotFoundError("cities.yaml was not found at pipeline/config/cities.yaml.")


CONFIG_FILE = resolve_config_file()


def load_city_profiles() -> list[dict]:
    config = yaml.safe_load(CONFIG_FILE.read_text(encoding="utf-8")) or {}
    cities = config.get("cities") or []
    if not cities:
        raise ValueError(f"No cities configured in {CONFIG_FILE}")
    return cities


def city_profile_by_token(city_token: str | None) -> dict | None:
    if not city_token:
        return None

    normalized_token = city_token.strip().lower()
    for city in load_city_profiles():
        if normalized_token in {
            str(city.get("slug", "")).lower(),
            str(city.get("name", "")).lower(),
        }:
            return city
    return None


def city_profile_by_name(city_name: str | None) -> dict | None:
    if not city_name:
        return None

    normalized_name = city_name.strip().lower()
    for city in load_city_profiles():
        if str(city.get("name", "")).lower() == normalized_name:
            return city
    return None

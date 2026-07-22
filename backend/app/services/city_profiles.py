from __future__ import annotations

import re
import unicodedata
from pathlib import Path

import yaml


def resolve_config_file() -> Path:
    for parent in Path(__file__).resolve().parents:
        candidate = parent / "pipeline" / "config" / "cities.yaml"
        if candidate.exists():
            return candidate

    raise FileNotFoundError("cities.yaml was not found at pipeline/config/cities.yaml.")


CONFIG_FILE = resolve_config_file()

_PROFILES_CACHE: tuple[float, list[dict]] | None = None


def load_city_profiles() -> list[dict]:
    global _PROFILES_CACHE
    mtime = CONFIG_FILE.stat().st_mtime
    if _PROFILES_CACHE is not None and _PROFILES_CACHE[0] == mtime:
        return _PROFILES_CACHE[1]

    config = yaml.safe_load(CONFIG_FILE.read_text(encoding="utf-8")) or {}
    cities = config.get("cities") or []
    if not cities:
        raise ValueError(f"No cities configured in {CONFIG_FILE}")
    _PROFILES_CACHE = (mtime, cities)
    return cities


def normalized_city_token(value: str | None) -> str:
    if not value:
        return ""

    ascii_value = unicodedata.normalize("NFKD", value).encode("ascii", "ignore").decode("ascii")
    return re.sub(r"[^a-z0-9]+", "-", ascii_value.strip().lower()).strip("-")


def city_profile_tokens(city: dict) -> set[str]:
    return {
        token
        for token in {
            normalized_city_token(str(city.get("slug", ""))),
            normalized_city_token(str(city.get("name", ""))),
        }
        if token
    }


def city_profile_by_token(city_token: str | None) -> dict | None:
    normalized_token = normalized_city_token(city_token)
    if not normalized_token:
        return None

    for city in load_city_profiles():
        if normalized_token in city_profile_tokens(city):
            return city
    return None


def city_profile_by_name(city_name: str | None) -> dict | None:
    normalized_name = normalized_city_token(city_name)
    if not normalized_name:
        return None

    for city in load_city_profiles():
        if normalized_city_token(str(city.get("name", ""))) == normalized_name:
            return city
    return None

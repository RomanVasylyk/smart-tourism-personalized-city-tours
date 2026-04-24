from __future__ import annotations

import json
import sys
import time
from pathlib import Path

import requests

ROOT = Path(__file__).resolve().parents[1]
if str(ROOT) not in sys.path:
    sys.path.insert(0, str(ROOT))

from utils.cities import load_city

OUTPUT_DIR = ROOT / "data" / "raw"
OVERPASS_URLS = (
    "https://overpass-api.de/api/interpreter",
    "https://overpass.kumi.systems/api/interpreter",
    "https://overpass.private.coffee/api/interpreter",
)
OVERPASS_HEADERS = {
    "User-Agent": "smart-tourism-starter/1.0 (educational project)",
}
TAG_FILTERS = (
    ("tourism", "attraction"),
    ("tourism", "viewpoint"),
    ("tourism", "museum"),
    ("tourism", "gallery"),
    ("historic", "monument"),
    ("historic", "memorial"),
    ("historic", "castle"),
    ("historic", "ruins"),
    ("leisure", "park"),
    ("leisure", "garden"),
    ("amenity", "place_of_worship"),
)
RETRYABLE_STATUS_CODES = {429, 504}
QUERY_TIMEOUT_SECONDS = 90
HTTP_TIMEOUT_SECONDS = 180
MAX_ATTEMPTS_PER_URL = 2
PAUSE_BETWEEN_BATCHES_SECONDS = 1

def build_query(city: dict, element_type: str) -> str:
    bbox = city.get("bbox") or {}
    south = bbox.get("south")
    west = bbox.get("west")
    north = bbox.get("north")
    east = bbox.get("east")
    if None in {south, west, north, east}:
        raise ValueError(f"City {city.get('slug', '<unknown>')} is missing bbox coordinates")

    query_lines = [f"[out:json][timeout:{QUERY_TIMEOUT_SECONDS}];", "("]
    for key, value in TAG_FILTERS:
        query_lines.append(
            f'  {element_type}["{key}"="{value}"]({south},{west},{north},{east});'
        )
    query_lines.extend((");", "out center tags;"))
    return "\n".join(query_lines)


def summarize_response_body(response: requests.Response) -> str:
    return " ".join(response.text.strip().split())[:240]


def fetch_query(session: requests.Session, label: str, query: str) -> dict:
    errors: list[str] = []
    for url in OVERPASS_URLS:
        for attempt in range(1, MAX_ATTEMPTS_PER_URL + 1):
            try:
                response = session.get(
                    url,
                    params={"data": query},
                    timeout=HTTP_TIMEOUT_SECONDS,
                )
            except requests.RequestException as exc:
                errors.append(f"{url} [{label}] attempt {attempt}/{MAX_ATTEMPTS_PER_URL}: {exc}")
                break

            if response.ok:
                try:
                    return response.json()
                except ValueError as exc:
                    errors.append(
                        f"{url} [{label}] attempt {attempt}/{MAX_ATTEMPTS_PER_URL}: invalid JSON: {exc}"
                    )
                    break

            body = summarize_response_body(response)
            error = (
                f"{url} [{label}] attempt {attempt}/{MAX_ATTEMPTS_PER_URL}: "
                f"HTTP {response.status_code}: {body}"
            )
            if response.status_code in RETRYABLE_STATUS_CODES and attempt < MAX_ATTEMPTS_PER_URL:
                retry_after = response.headers.get("Retry-After")
                wait_seconds = int(retry_after) if retry_after and retry_after.isdigit() else attempt * 2
                errors.append(f"{error} Retrying in {wait_seconds}s.")
                time.sleep(wait_seconds)
                continue

            errors.append(error)
            break

    joined_errors = "\n".join(f"- {error}" for error in errors)
    raise RuntimeError(f"Overpass request failed for {label}.\n{joined_errors}")


def merge_payloads(payloads: list[dict]) -> dict:
    merged_elements: dict[tuple[str, int], dict] = {}
    for payload in payloads:
        for element in payload.get("elements", []):
            key = (element["type"], element["id"])
            merged_elements.setdefault(key, element)

    base_payload = payloads[0] if payloads else {}
    return {
        "version": base_payload.get("version"),
        "generator": base_payload.get("generator"),
        "osm3s": base_payload.get("osm3s"),
        "elements": list(merged_elements.values()),
    }


def main() -> None:
    city_slug = sys.argv[1] if len(sys.argv) > 1 else "nitra"
    city = load_city(city_slug)
    session = requests.Session()
    session.headers.update(OVERPASS_HEADERS)

    payloads = []
    for index, element_type in enumerate(("node", "way")):
        payloads.append(fetch_query(session, element_type, build_query(city, element_type)))
        if index == 0:
            time.sleep(PAUSE_BETWEEN_BATCHES_SECONDS)

    data = merge_payloads(payloads)

    OUTPUT_DIR.mkdir(parents=True, exist_ok=True)
    out_file = OUTPUT_DIR / f"{city['slug']}_osm_raw.json"
    out_file.write_text(
        json.dumps(data, ensure_ascii=False, indent=2),
        encoding="utf-8",
    )

    print(f"Saved {len(data.get('elements', []))} elements to {out_file}")


if __name__ == "__main__":
    main()

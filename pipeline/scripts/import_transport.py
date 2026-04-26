from __future__ import annotations

import json
import re
import sys
import time
from pathlib import Path
from urllib.parse import urljoin

import requests

ROOT = Path(__file__).resolve().parents[1]
if str(ROOT) not in sys.path:
    sys.path.insert(0, str(ROOT))

from utils.cities import load_city

OVERPASS_URLS = (
    "https://overpass-api.de/api/interpreter",
    "https://overpass.kumi.systems/api/interpreter",
    "https://overpass.private.coffee/api/interpreter",
)
HTTP_TIMEOUT_SECONDS = 180
QUERY_TIMEOUT_SECONDS = 90
MAX_ATTEMPTS_PER_URL = 2
RETRYABLE_STATUS_CODES = {429, 504}
PAUSE_BETWEEN_BATCHES_SECONDS = 1
PROVIDER_HEADERS = {
    "User-Agent": "smart-tourism-starter/1.0 (educational project)",
}
PDF_LINK_PATTERN = re.compile(r"""href=["']([^"']+\.pdf)["']""", re.IGNORECASE)
UPLOADS_DATE_PATTERN = re.compile(r"/uploads/(\d{4})/(\d{2})/")
LINE_ID_PATTERN = re.compile(r"(\d+)")


def summarize_response_body(response: requests.Response) -> str:
    return " ".join(response.text.strip().split())[:240]


def fetch_overpass_query(session: requests.Session, label: str, query: str) -> dict:
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


def build_stop_query(city: dict) -> str:
    bbox = city.get("bbox") or {}
    south = bbox.get("south")
    west = bbox.get("west")
    north = bbox.get("north")
    east = bbox.get("east")
    if None in {south, west, north, east}:
        raise ValueError(f"City {city.get('slug', '<unknown>')} is missing bbox coordinates")

    return "\n".join(
        (
            f"[out:json][timeout:{QUERY_TIMEOUT_SECONDS}];",
            "(",
            f'  node["highway"="bus_stop"]({south},{west},{north},{east});',
            f'  node["public_transport"="platform"]({south},{west},{north},{east});',
            f'  way["highway"="bus_stop"]({south},{west},{north},{east});',
            f'  way["public_transport"="platform"]({south},{west},{north},{east});',
            ");",
            "out center tags;",
        )
    )


def transport_raw_dir(city: dict) -> Path:
    transport = city.get("transport") or {}
    relative_subdir = str(transport.get("raw_data_subdir") or f"transport/{city['slug']}/raw")
    path = ROOT / "data" / relative_subdir.removeprefix("data/")
    path.mkdir(parents=True, exist_ok=True)
    (path / "timetables").mkdir(parents=True, exist_ok=True)
    return path


def line_key_from_url(url: str) -> str | None:
    stem = Path(url.split("?", 1)[0]).stem
    match = LINE_ID_PATTERN.search(stem)
    if not match:
        return None
    return str(int(match.group(1)))


def provider_link_rank(url: str) -> tuple[int, int, int]:
    match = UPLOADS_DATE_PATTERN.search(url)
    if not match:
        return (0, 0, 0)
    year = int(match.group(1))
    month = int(match.group(2))
    https_preferred = 1 if url.startswith("https://") else 0
    return (year, month, https_preferred)


def discover_provider_documents(index_url: str, session: requests.Session) -> tuple[str, list[dict]]:
    response = session.get(index_url, timeout=HTTP_TIMEOUT_SECONDS)
    response.raise_for_status()
    html = response.text

    documents_by_line: dict[str, dict] = {}
    for match in PDF_LINK_PATTERN.finditer(html):
        absolute_url = urljoin(index_url, match.group(1).replace("&amp;", "&")).replace("http://", "https://")
        line_key = line_key_from_url(absolute_url)
        if line_key is None:
            continue

        candidate = {
            "line_id": line_key,
            "source_url": absolute_url,
        }
        current = documents_by_line.get(line_key)
        if current is None or provider_link_rank(absolute_url) >= provider_link_rank(current["source_url"]):
            documents_by_line[line_key] = candidate

    documents = sorted(documents_by_line.values(), key=lambda item: int(item["line_id"]))
    if not documents:
        raise RuntimeError(f"No provider PDF documents found at {index_url}")
    return html, documents


def download_documents(documents: list[dict], output_dir: Path, session: requests.Session) -> list[dict]:
    downloaded: list[dict] = []
    for document in documents:
        response = session.get(document["source_url"], timeout=HTTP_TIMEOUT_SECONDS)
        response.raise_for_status()

        filename = f"{document['line_id'].zfill(2)}.pdf"
        path = output_dir / "timetables" / filename
        path.write_bytes(response.content)
        downloaded.append(
            {
                **document,
                "filename": filename,
                "size_bytes": path.stat().st_size,
            }
        )
    return downloaded


def main() -> None:
    city_slug = sys.argv[1] if len(sys.argv) > 1 else "nitra"
    city = load_city(city_slug)
    transport = city.get("transport") or {}
    if transport.get("mhd_enabled") is not True:
        raise ValueError(f"City '{city_slug}' does not have MHD enabled in cities.yaml")

    index_url = transport.get("source_index_url")
    if not index_url:
        raise ValueError(f"City '{city_slug}' is missing transport.source_index_url in cities.yaml")

    raw_dir = transport_raw_dir(city)
    session = requests.Session()
    session.headers.update(PROVIDER_HEADERS)

    provider_html, documents = discover_provider_documents(str(index_url), session)
    downloaded_documents = download_documents(documents, raw_dir, session)
    (raw_dir / "provider_index.html").write_text(provider_html, encoding="utf-8")
    (raw_dir / "provider_manifest.json").write_text(
        json.dumps(
            {
                "city": city_slug,
                "provider": transport.get("provider"),
                "source_index_url": index_url,
                "downloaded_at": time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime()),
                "documents": downloaded_documents,
            },
            ensure_ascii=False,
            indent=2,
        ),
        encoding="utf-8",
    )

    stop_payload = fetch_overpass_query(session, "transport_stops", build_stop_query(city))
    (raw_dir / "osm_stops_raw.json").write_text(
        json.dumps(stop_payload, ensure_ascii=False, indent=2),
        encoding="utf-8",
    )

    print(
        f"Saved {len(downloaded_documents)} transport PDFs and "
        f"{len(stop_payload.get('elements', []))} raw OSM stops under {raw_dir}"
    )


if __name__ == "__main__":
    main()

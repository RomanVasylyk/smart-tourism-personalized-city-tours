from __future__ import annotations

import json
import re
import sys
import time
from pathlib import Path
from urllib.parse import unquote, urljoin, urlparse

import requests
from bs4 import BeautifulSoup

ROOT = Path(__file__).resolve().parents[1]
if str(ROOT) not in sys.path:
    sys.path.insert(0, str(ROOT))

from etl.transport.text import apply_stop_alias, load_stop_aliases
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
NOMINATIM_SEARCH_URL = "https://nominatim.openstreetmap.org/search"
GEOCODE_PAUSE_SECONDS = 1.1
PROVIDER_HEADERS = {
    "User-Agent": "smart-tourism-starter/1.0 (educational project)",
}
PDF_LINK_PATTERN = re.compile(r"""href=["']([^"']+\.pdf)["']""", re.IGNORECASE)
UPLOADS_DATE_PATTERN = re.compile(r"/uploads/(\d{4})/(\d{2})/")
LINE_ID_PATTERN = re.compile(r"(\d+)")
IMHD_LINE_PATH_PATTERN = re.compile(r"^/(?P<city>[a-z0-9-]+)/linka/(?P<line_id>[^/]+)/", re.IGNORECASE)
IMHD_SCHEDULE_PATH_PATTERN = re.compile(
    r"^/(?P<city>[a-z0-9-]+)/cestovny-poriadok/linka/(?P<line_id>[^/]+)/",
    re.IGNORECASE,
)


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
                    errors.append(f"{url} [{label}] attempt {attempt}/{MAX_ATTEMPTS_PER_URL}: invalid JSON: {exc}")
                    break

            body = summarize_response_body(response)
            error = f"{url} [{label}] attempt {attempt}/{MAX_ATTEMPTS_PER_URL}: " f"HTTP {response.status_code}: {body}"
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


def stop_query_areas(city: dict) -> list[dict]:
    transport = city.get("transport") or {}
    configured_areas = transport.get("stop_query_areas")
    if configured_areas:
        return list(configured_areas)

    transport_bbox = transport.get("stop_bbox")
    if transport_bbox:
        return [transport_bbox]
    return [city.get("bbox") or {}]


def validate_query_area(city_slug: str, area: dict, index: int) -> tuple[float, float, float, float]:
    south = area.get("south")
    west = area.get("west")
    north = area.get("north")
    east = area.get("east")
    if None in {south, west, north, east}:
        raise ValueError(f"Transport stop query area #{index} for city '{city_slug}' is missing bbox coordinates")
    return float(south), float(west), float(north), float(east)


def build_stop_query(city: dict) -> str:
    city_slug = str(city.get("slug") or "<unknown>")
    query_lines = [f"[out:json][timeout:{QUERY_TIMEOUT_SECONDS}];", "("]
    for index, area in enumerate(stop_query_areas(city), start=1):
        south, west, north, east = validate_query_area(city_slug, area, index)
        query_lines.extend(
            (
                f'  node["highway"="bus_stop"]({south},{west},{north},{east});',
                f'  node["public_transport"="platform"]({south},{west},{north},{east});',
                f'  node["public_transport"="stop_position"]["bus"="yes"]({south},{west},{north},{east});',
                f'  node["amenity"="bus_station"]({south},{west},{north},{east});',
                f'  node["public_transport"="station"]["bus"="yes"]({south},{west},{north},{east});',
                f'  way["highway"="bus_stop"]({south},{west},{north},{east});',
                f'  way["public_transport"="platform"]({south},{west},{north},{east});',
                f'  way["amenity"="bus_station"]({south},{west},{north},{east});',
                f'  way["public_transport"="station"]["bus"="yes"]({south},{west},{north},{east});',
            )
        )
    query_lines.extend((");", "out center tags;"))
    return "\n".join(query_lines)


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


def normalize_whitespace(value: str) -> str:
    return " ".join(str(value or "").split())


def sanitize_line_id_for_filename(line_id: str) -> str:
    return re.sub(r"[^A-Za-z0-9_-]+", "_", str(line_id or "").strip()) or "line"


def slugify_stop_name(value: str) -> str:
    slug = re.sub(r"[^A-Za-z0-9]+", "-", normalize_whitespace(value)).strip("-").lower()
    return slug or "stop"


def provider_link_rank(url: str) -> tuple[int, int, int]:
    match = UPLOADS_DATE_PATTERN.search(url)
    if not match:
        return (0, 0, 0)
    year = int(match.group(1))
    month = int(match.group(2))
    https_preferred = 1 if url.startswith("https://") else 0
    return (year, month, https_preferred)


def discover_imhd_line_links(
    index_html: str,
    index_url: str,
    city_code: str,
    allowed_line_ids: set[str] | None = None,
) -> list[dict]:
    soup = BeautifulSoup(index_html, "html.parser")
    documents_by_line: dict[str, dict] = {}

    for link in soup.find_all("a", href=True):
        absolute_url = urljoin(index_url, link["href"])
        parsed_url = urlparse(absolute_url)
        match = IMHD_LINE_PATH_PATTERN.match(parsed_url.path)
        if match is None or match.group("city").casefold() != city_code.casefold():
            continue

        line_id = normalize_whitespace(unquote(match.group("line_id"))).upper()
        if not line_id:
            continue
        if allowed_line_ids is not None and line_id not in allowed_line_ids:
            continue

        candidate = {
            "line_id": line_id,
            "source_url": absolute_url,
            "line_label": normalize_whitespace(link.get_text(" ", strip=True)) or line_id,
        }
        documents_by_line.setdefault(line_id, candidate)

    if not documents_by_line:
        raise RuntimeError(f"No imhd.sk line pages found for city code '{city_code}' at {index_url}")

    def sort_key(item: dict) -> tuple[int, str]:
        line_id = str(item["line_id"])
        numeric_match = re.fullmatch(r"(\d+)", line_id)
        if numeric_match:
            return int(numeric_match.group(1)), ""
        prefixed_numeric_match = re.fullmatch(r"([A-Za-z]+)(\d+)", line_id)
        if prefixed_numeric_match:
            return int(prefixed_numeric_match.group(2)), prefixed_numeric_match.group(1)
        return 999_999, line_id

    return sorted(documents_by_line.values(), key=sort_key)


def discover_imhd_direction_documents(line_html: str, line_url: str, city_code: str, line_id: str) -> list[dict]:
    soup = BeautifulSoup(line_html, "html.parser")
    discovered_documents: list[dict] = []
    seen_route_keys: set[tuple[str, ...]] = set()
    seen_schedule_urls: set[str] = set()

    timetable_section = soup.find("section", class_="Timetable") or soup
    for table in timetable_section.find_all("table"):
        table_classes = set(table.get("class") or [])
        if "d-none" in table_classes or "table-hover" not in table_classes:
            continue

        stop_links: list[tuple[str, str]] = []
        for link in table.find_all("a", href=True):
            absolute_url = urljoin(line_url, link["href"])
            parsed_url = urlparse(absolute_url)
            match = IMHD_SCHEDULE_PATH_PATTERN.match(parsed_url.path)
            if match is None or match.group("city").casefold() != city_code.casefold():
                continue
            if normalize_whitespace(unquote(match.group("line_id"))).upper() != line_id.upper():
                continue

            stop_name = normalize_whitespace(link.get_text(" ", strip=True))
            if not stop_name:
                continue
            stop_links.append((stop_name, absolute_url))

        route_key = tuple(schedule_url for _, schedule_url in stop_links)
        if len(stop_links) < 2 or route_key in seen_route_keys:
            continue

        seen_route_keys.add(route_key)
        seen_schedule_urls.update(schedule_url for _, schedule_url in stop_links)
        origin_stop_name, origin_schedule_url = stop_links[0]
        destination_stop_name = stop_links[-1][0]
        document_number = len(discovered_documents) + 1
        discovered_documents.append(
            {
                "line_id": line_id,
                "source_url": origin_schedule_url,
                "origin_stop_name": origin_stop_name,
                "destination_stop_name": destination_stop_name,
                "route_stop_names": [stop_name for stop_name, _ in stop_links],
                "filename": f"{sanitize_line_id_for_filename(line_id)}_{document_number:02d}.html",
                "document_format": "imhd_html",
            }
        )

    if not discovered_documents:
        for link in timetable_section.find_all("a", href=True):
            absolute_url = urljoin(line_url, link["href"])
            parsed_url = urlparse(absolute_url)
            match = IMHD_SCHEDULE_PATH_PATTERN.match(parsed_url.path)
            if match is None or match.group("city").casefold() != city_code.casefold():
                continue
            if normalize_whitespace(unquote(match.group("line_id"))).upper() != line_id.upper():
                continue
            if absolute_url in seen_schedule_urls:
                continue

            stop_name = normalize_whitespace(link.get_text(" ", strip=True))
            if not stop_name:
                stop_name = normalize_whitespace(unquote(parsed_url.path.rstrip("/").split("/")[-3]))
            seen_schedule_urls.add(absolute_url)
            document_number = len(discovered_documents) + 1
            discovered_documents.append(
                {
                    "line_id": line_id,
                    "source_url": absolute_url,
                    "origin_stop_name": stop_name,
                    "destination_stop_name": None,
                    "route_stop_names": [],
                    "filename": f"{sanitize_line_id_for_filename(line_id)}_{document_number:02d}.html",
                    "document_format": "imhd_html",
                }
            )

    if not discovered_documents:
        raise RuntimeError(f"No direction timetable pages found on imhd.sk line page {line_url}")

    return discovered_documents


def city_viewbox(city: dict) -> str | None:
    bbox = city.get("bbox") or {}
    south = bbox.get("south")
    west = bbox.get("west")
    north = bbox.get("north")
    east = bbox.get("east")
    if None in {south, west, north, east}:
        return None
    return f"{west},{north},{east},{south}"


def geocode_query_candidates(stop_name: str, city: dict, stop_aliases: dict[str, str]) -> list[str]:
    city_name = str(city.get("name") or city.get("slug") or "").strip()
    country = str(city.get("country") or "").strip()
    canonical_name = apply_stop_alias(stop_name, stop_aliases)

    candidates = [
        f"{canonical_name}, {city_name}, {country}",
        f"{canonical_name} {city_name} {country}",
    ]
    if canonical_name != stop_name:
        candidates.extend(
            (
                f"{stop_name}, {city_name}, {country}",
                f"{stop_name} {city_name} {country}",
            )
        )
    return [candidate for candidate in dict.fromkeys(candidates) if normalize_whitespace(candidate)]


def geocode_provider_stop_names(
    city: dict,
    stop_names: set[str],
    session: requests.Session,
    stop_aliases: dict[str, str],
) -> list[dict]:
    viewbox = city_viewbox(city)
    if not viewbox:
        return []

    geocoded_stops: list[dict] = []
    for stop_name in sorted(stop_names):
        result: dict | None = None
        for query in geocode_query_candidates(stop_name, city, stop_aliases):
            response = session.get(
                NOMINATIM_SEARCH_URL,
                params={
                    "q": query,
                    "format": "jsonv2",
                    "limit": 1,
                    "viewbox": viewbox,
                    "bounded": 1,
                },
                timeout=HTTP_TIMEOUT_SECONDS,
            )
            if response.ok:
                try:
                    payload = response.json()
                except ValueError:
                    payload = []
            else:
                payload = []

            if payload:
                first_result = payload[0]
                result = {
                    "stop_name": stop_name,
                    "query": query,
                    "display_name": str(first_result.get("display_name") or stop_name),
                    "lat": float(first_result["lat"]),
                    "lon": float(first_result["lon"]),
                    "category": first_result.get("category"),
                    "type": first_result.get("type"),
                }
                break
            time.sleep(GEOCODE_PAUSE_SECONDS)

        if result is not None:
            geocoded_stops.append(result)
        time.sleep(GEOCODE_PAUSE_SECONDS)

    return geocoded_stops


def discover_imhd_documents(
    index_url: str,
    session: requests.Session,
    city_code: str,
    allowed_line_ids: set[str] | None = None,
) -> tuple[str, list[dict]]:
    response = session.get(index_url, timeout=HTTP_TIMEOUT_SECONDS)
    response.raise_for_status()
    index_html = response.text

    line_pages = discover_imhd_line_links(index_html, index_url, city_code, allowed_line_ids=allowed_line_ids)
    documents: list[dict] = []
    for line_page in line_pages:
        line_response = session.get(line_page["source_url"], timeout=HTTP_TIMEOUT_SECONDS)
        line_response.raise_for_status()
        documents.extend(
            discover_imhd_direction_documents(
                line_response.text,
                str(line_page["source_url"]),
                city_code,
                str(line_page["line_id"]),
            )
        )

    return index_html, documents


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

        filename = str(document.get("filename") or "").strip()
        if not filename:
            parsed_url = urlparse(str(document["source_url"]))
            suffix = Path(parsed_url.path).suffix or ".pdf"
            safe_line_id = sanitize_line_id_for_filename(str(document["line_id"]))
            filename = f"{safe_line_id.zfill(2) if safe_line_id.isdigit() else safe_line_id}{suffix}"
        path = output_dir / "timetables" / filename
        path.write_bytes(response.content)
        downloaded.append(
            {
                **document,
                "filename": filename,
                "document_format": document.get("document_format")
                or ("pdf" if filename.lower().endswith(".pdf") else "html"),
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
    datasource = str(transport.get("datasource") or "provider_pdf")

    raw_dir = transport_raw_dir(city)
    session = requests.Session()
    session.headers.update(PROVIDER_HEADERS)

    if datasource == "provider_pdf":
        provider_html, documents = discover_provider_documents(str(index_url), session)
    elif datasource == "imhd_html":
        city_code = str(transport.get("imhd_city_code") or "").strip()
        if not city_code:
            raise ValueError(f"City '{city_slug}' is missing transport.imhd_city_code in cities.yaml")
        allowed_line_ids = {
            normalize_whitespace(str(line_id)).upper()
            for line_id in (transport.get("line_id_allowlist") or [])
            if normalize_whitespace(str(line_id))
        } or None
        provider_html, documents = discover_imhd_documents(
            str(index_url),
            session,
            city_code,
            allowed_line_ids=allowed_line_ids,
        )
    else:
        raise ValueError(f"Unsupported transport datasource '{datasource}' for city '{city_slug}'")

    downloaded_documents = download_documents(documents, raw_dir, session)
    (raw_dir / "provider_index.html").write_text(provider_html, encoding="utf-8")
    (raw_dir / "provider_manifest.json").write_text(
        json.dumps(
            {
                "city": city_slug,
                "provider": transport.get("provider"),
                "datasource": datasource,
                "source_index_url": index_url,
                "stop_query_areas": stop_query_areas(city),
                "downloaded_at": time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime()),
                "documents": downloaded_documents,
            },
            ensure_ascii=False,
            indent=2,
        ),
        encoding="utf-8",
    )

    if datasource == "imhd_html" and transport.get("provider_stop_geocodes_enabled") is True:
        stop_aliases = load_stop_aliases(city_slug)
        provider_stop_names = {
            normalize_whitespace(stop_name)
            for document in downloaded_documents
            for stop_name in (document.get("route_stop_names") or [])
            if normalize_whitespace(stop_name)
        }
        provider_stop_geocodes = geocode_provider_stop_names(city, provider_stop_names, session, stop_aliases)
        (raw_dir / "provider_stop_geocodes.json").write_text(
            json.dumps(provider_stop_geocodes, ensure_ascii=False, indent=2),
            encoding="utf-8",
        )

    stop_payload = fetch_overpass_query(session, "transport_stops", build_stop_query(city))
    (raw_dir / "osm_stops_raw.json").write_text(
        json.dumps(stop_payload, ensure_ascii=False, indent=2),
        encoding="utf-8",
    )

    print(
        f"Saved {len(downloaded_documents)} transport documents and "
        f"{len(stop_payload.get('elements', []))} raw OSM stops under {raw_dir}"
    )


if __name__ == "__main__":
    main()

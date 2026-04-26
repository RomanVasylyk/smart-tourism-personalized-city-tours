from __future__ import annotations

import json
import math
import re
import sys
import unicodedata
from dataclasses import dataclass, field
from datetime import UTC, datetime
from difflib import get_close_matches
from pathlib import Path

from pypdf import PdfReader

ROOT = Path(__file__).resolve().parents[1]
if str(ROOT) not in sys.path:
    sys.path.insert(0, str(ROOT))

from utils.cities import load_city

STOP_ROW_PATTERN = re.compile(r"^[wW]?[DA]\s+(.+?)\s+(?:odch|prích|prich)?\s*(.+)$")
TIME_TOKEN_PATTERN = re.compile(r"\bK\b|(?<!\d)(\d{1,2})\s?(\d{2})(?!\d)")
VALIDITY_PATTERN = re.compile(r"Platí od\s+(\d{1,2}\.\d{1,2}\.\d{4})\s+do\s+(\d{1,2}\.\d{1,2}\.\d{4})")
LINE_NUMBER_PATTERN = re.compile(r"^\d{1,3}$")


@dataclass
class StopRow:
    name: str
    times: list[int | None]


@dataclass
class VariantAccumulator:
    line_number: str
    service_bucket: str
    source_urls: set[str] = field(default_factory=set)
    valid_from: str | None = None
    valid_to: str | None = None
    stop_names: list[str] = field(default_factory=list)
    edge_samples: list[list[float]] = field(default_factory=list)


def strip_accents(value: str) -> str:
    return "".join(
        char for char in unicodedata.normalize("NFKD", value)
        if not unicodedata.combining(char)
    )


def normalize_stop_name(value: str) -> str:
    value = strip_accents(value).lower()
    value = value.replace("štúrova", "sturova")
    value = re.sub(r"[^\w\s]", " ", value)
    value = re.sub(r"\s+", " ", value)
    return value.strip()


def detect_service_bucket(page_text: str) -> str:
    normalized_text = strip_accents(page_text).upper()
    if "PRACOVNE DNI" in normalized_text:
        return "workdays"
    if "SOBOTY, NEDELE, SVIATKY" in normalized_text:
        return "weekends_holidays"
    if "SOBOTY" in normalized_text or "NEDELE" in normalized_text:
        return "weekends_holidays"
    return "all_days"


def transport_paths(city: dict) -> tuple[Path, Path]:
    transport = city.get("transport") or {}
    raw_subdir = str(transport.get("raw_data_subdir") or f"transport/{city['slug']}/raw")
    processed_path = str(
        transport.get("processed_graph_path")
        or f"transport/{city['slug']}/processed/transport_graph.json"
    )
    raw_dir = ROOT / "data" / raw_subdir.removeprefix("data/")
    processed_file = ROOT / "data" / processed_path.removeprefix("data/")
    processed_file.parent.mkdir(parents=True, exist_ok=True)
    return raw_dir, processed_file


def parse_date(raw_value: str | None) -> str | None:
    if not raw_value:
        return None
    return datetime.strptime(raw_value, "%d.%m.%Y").date().isoformat()


def parse_validity(text: str) -> tuple[str | None, str | None]:
    match = VALIDITY_PATTERN.search(text)
    if not match:
        return None, None
    return parse_date(match.group(1)), parse_date(match.group(2))


def extract_line_number(lines: list[str], fallback_line_number: str) -> str:
    for line in lines[:5]:
        if LINE_NUMBER_PATTERN.fullmatch(line):
            return str(int(line))
    return fallback_line_number


def extract_time_columns(raw_text: str) -> list[int | None]:
    columns: list[int | None] = []
    for match in TIME_TOKEN_PATTERN.finditer(raw_text):
        token = match.group(0)
        if token == "K":
            columns.append(None)
            continue
        hour = int(match.group(1))
        minute = int(match.group(2))
        if hour > 24 or minute > 59:
            continue
        total_minutes = (hour * 60) + minute
        columns.append(total_minutes)
    return columns


def parse_stop_rows(page_text: str) -> list[StopRow]:
    rows: list[StopRow] = []
    for raw_line in page_text.splitlines():
        line = " ".join(raw_line.split())
        if not line:
            continue
        match = STOP_ROW_PATTERN.match(line)
        if not match:
            continue
        stop_name = match.group(1).strip()
        times = extract_time_columns(match.group(2))
        if not stop_name or not times:
            continue
        rows.append(StopRow(name=stop_name, times=times))
    return rows


def compact_repeated_stop_rows(rows: list[StopRow]) -> list[StopRow]:
    total_rows = len(rows)
    if total_rows < 4:
        return rows

    stop_names = [row.name for row in rows]
    for chunk_size in range(2, (total_rows // 2) + 1):
        if total_rows % chunk_size != 0:
            continue
        base = stop_names[:chunk_size]
        if all(stop_names[index : index + chunk_size] == base for index in range(0, total_rows, chunk_size)):
            return rows[:chunk_size]

    return rows


def parse_pdf_variants(pdf_path: Path, source_url: str, fallback_line_number: str) -> list[VariantAccumulator]:
    reader = PdfReader(str(pdf_path))
    variants: dict[tuple[str, tuple[str, ...]], VariantAccumulator] = {}

    for page in reader.pages:
        page_text = page.extract_text(extraction_mode="plain") or ""
        lines = [line.strip() for line in page_text.splitlines() if line.strip()]
        stop_rows = compact_repeated_stop_rows(parse_stop_rows(page_text))
        if len(stop_rows) < 2:
            continue

        line_number = extract_line_number(lines, fallback_line_number)
        service_bucket = detect_service_bucket(page_text)
        stop_names = [row.name for row in stop_rows]
        max_columns = max(len(row.times) for row in stop_rows)
        padded_rows = [
            StopRow(name=row.name, times=row.times + [None] * (max_columns - len(row.times)))
            for row in stop_rows
        ]

        edge_samples: list[list[float]] = [[] for _ in range(len(stop_names) - 1)]
        for index in range(len(padded_rows) - 1):
            current_row = padded_rows[index]
            next_row = padded_rows[index + 1]
            for column in range(max_columns):
                current_minutes = current_row.times[column]
                next_minutes = next_row.times[column]
                if current_minutes is None or next_minutes is None:
                    continue
                delta_minutes = next_minutes - current_minutes
                if 0 < delta_minutes <= 90:
                    edge_samples[index].append(delta_minutes * 60.0)

        if not any(samples for samples in edge_samples):
            continue

        valid_from, valid_to = parse_validity(page_text)
        key = (line_number, service_bucket, tuple(stop_names))
        accumulator = variants.get(key)
        if accumulator is None:
            accumulator = VariantAccumulator(
                line_number=line_number,
                service_bucket=service_bucket,
                stop_names=stop_names,
                edge_samples=[list(samples) for samples in edge_samples],
                valid_from=valid_from,
                valid_to=valid_to,
            )
            variants[key] = accumulator
        else:
            for index, samples in enumerate(edge_samples):
                accumulator.edge_samples[index].extend(samples)
            accumulator.valid_from = min(filter(None, [accumulator.valid_from, valid_from]), default=None)
            accumulator.valid_to = max(filter(None, [accumulator.valid_to, valid_to]), default=None)

        accumulator.source_urls.add(source_url)

    return list(variants.values())


def load_osm_stops(raw_dir: Path, city: dict) -> list[dict]:
    raw_file = raw_dir / "osm_stops_raw.json"
    payload = json.loads(raw_file.read_text(encoding="utf-8"))
    center = city.get("center") or {}
    center_lat = float(center.get("lat") or 0.0)
    center_lon = float(center.get("lon") or 0.0)

    stops: list[dict] = []
    for element in payload.get("elements", []):
        tags = element.get("tags") or {}
        name = str(tags.get("name") or "").strip()
        if not name:
            continue
        point = element.get("center") or element
        lat = point.get("lat")
        lon = point.get("lon")
        if lat is None or lon is None:
            continue

        stops.append(
            {
                "osm_id": f"{element['type']}/{element['id']}",
                "name": name,
                "normalized_name": normalize_stop_name(name),
                "lat": float(lat),
                "lon": float(lon),
                "ref": str(tags.get("local_ref") or tags.get("ref") or "").strip() or None,
                "distance_to_center_meters": haversine_km(center_lat, center_lon, float(lat), float(lon)) * 1_000,
            }
        )
    return stops


def build_osm_stop_index(stops: list[dict]) -> dict[str, list[dict]]:
    index: dict[str, list[dict]] = {}
    for stop in stops:
        index.setdefault(stop["normalized_name"], []).append(stop)
    for candidates in index.values():
        candidates.sort(key=lambda item: (item["distance_to_center_meters"], item["osm_id"]))
    return index


def match_provider_stop_candidates(
    stop_name: str,
    osm_index: dict[str, list[dict]],
) -> tuple[list[dict], str | None]:
    normalized_name = normalize_stop_name(stop_name)
    exact_candidates = osm_index.get(normalized_name)
    if exact_candidates:
        matched_by = "exact" if len(exact_candidates) == 1 else "exact_multi"
        return list(exact_candidates), matched_by

    close_matches = get_close_matches(normalized_name, osm_index.keys(), n=1, cutoff=0.86)
    if close_matches:
        matched_key = close_matches[0]
        fuzzy_candidates = list(osm_index[matched_key])
        matched_by = "fuzzy" if len(fuzzy_candidates) == 1 else "fuzzy_multi"
        return fuzzy_candidates, matched_by

    return [], None


def transition_cost(previous_stop: dict, current_stop: dict) -> float:
    distance_cost = haversine_km(
        previous_stop["lat"],
        previous_stop["lon"],
        current_stop["lat"],
        current_stop["lon"],
    ) * 1_000
    if previous_stop["osm_id"] == current_stop["osm_id"]:
        distance_cost += 150
    return distance_cost


def choose_variant_stop_assignments(
    stop_names: list[str],
    osm_index: dict[str, list[dict]],
) -> list[tuple[int, dict, str]]:
    matched_entries = []
    for index, stop_name in enumerate(stop_names):
        candidates, matched_by = match_provider_stop_candidates(stop_name, osm_index)
        if not candidates:
            continue
        matched_entries.append(
            {
                "index": index,
                "stop_name": stop_name,
                "matched_by": matched_by or "unknown",
                "candidates": candidates,
            }
        )

    if not matched_entries:
        return []
    if len(matched_entries) == 1:
        entry = matched_entries[0]
        return [(entry["index"], entry["candidates"][0], entry["matched_by"])]

    costs: list[list[float]] = []
    parents: list[list[int | None]] = []

    first_candidates = matched_entries[0]["candidates"]
    costs.append([0.0 for _ in first_candidates])
    parents.append([None for _ in first_candidates])

    for entry_index in range(1, len(matched_entries)):
        current_candidates = matched_entries[entry_index]["candidates"]
        previous_candidates = matched_entries[entry_index - 1]["candidates"]
        current_costs: list[float] = []
        current_parents: list[int | None] = []

        for current_candidate in current_candidates:
            best_cost = math.inf
            best_parent: int | None = None
            for previous_index, previous_candidate in enumerate(previous_candidates):
                cost = costs[entry_index - 1][previous_index] + transition_cost(previous_candidate, current_candidate)
                if cost < best_cost:
                    best_cost = cost
                    best_parent = previous_index
            current_costs.append(best_cost)
            current_parents.append(best_parent)

        costs.append(current_costs)
        parents.append(current_parents)

    final_entry_index = len(matched_entries) - 1
    final_candidate_index = min(
        range(len(costs[final_entry_index])),
        key=lambda candidate_index: costs[final_entry_index][candidate_index],
    )

    assignments: list[tuple[int, dict, str]] = []
    current_candidate_index: int | None = final_candidate_index
    for entry_index in range(final_entry_index, -1, -1):
        if current_candidate_index is None:
            break
        entry = matched_entries[entry_index]
        assignments.append(
            (
                entry["index"],
                entry["candidates"][current_candidate_index],
                entry["matched_by"],
            )
        )
        current_candidate_index = parents[entry_index][current_candidate_index]

    assignments.reverse()
    return assignments


def haversine_km(lat1: float, lon1: float, lat2: float, lon2: float) -> float:
    radius_km = 6_371.0088
    lat1_rad, lon1_rad = math.radians(lat1), math.radians(lon1)
    lat2_rad, lon2_rad = math.radians(lat2), math.radians(lon2)
    delta_lat = lat2_rad - lat1_rad
    delta_lon = lon2_rad - lon1_rad
    a = (
        math.sin(delta_lat / 2) ** 2
        + math.cos(lat1_rad) * math.cos(lat2_rad) * math.sin(delta_lon / 2) ** 2
    )
    return 2 * radius_km * math.asin(math.sqrt(a))


def mean(values: list[float]) -> float:
    return sum(values) / len(values)


def build_processed_graph(
    city: dict,
    variants: list[VariantAccumulator],
    osm_index: dict[str, list[dict]],
) -> dict:
    provider = str((city.get("transport") or {}).get("provider") or "transport_provider")
    stops_by_graph_key: dict[str, dict] = {}
    unmatched_stops: set[str] = set()
    lines: list[dict] = []
    connections: list[dict] = []
    line_counter = 0

    for variant in sorted(variants, key=lambda item: (int(item.line_number), item.service_bucket, tuple(item.stop_names))):
        matched_stops_with_indices: list[tuple[int, dict, str]] = []
        for stop_name in variant.stop_names:
            if not match_provider_stop_candidates(stop_name, osm_index)[0]:
                unmatched_stops.add(stop_name)

        for index, osm_stop, matched_by in choose_variant_stop_assignments(variant.stop_names, osm_index):
            graph_stop_key = osm_stop["osm_id"]
            stop_record = stops_by_graph_key.get(graph_stop_key)
            if stop_record is None:
                stop_record = {
                    "graph_stop_key": graph_stop_key,
                    "name": osm_stop["name"],
                    "normalized_name": osm_stop["normalized_name"],
                    "lat": osm_stop["lat"],
                    "lon": osm_stop["lon"],
                    "platform_ref": osm_stop.get("ref"),
                    "source": "osm",
                    "source_reference": osm_stop["osm_id"],
                    "matched_by": matched_by,
                }
                stops_by_graph_key[graph_stop_key] = stop_record

            matched_stops_with_indices.append((index, stop_record, variant.stop_names[index]))

        if len(matched_stops_with_indices) < 2:
            continue

        line_counter += 1
        line_id = f"{variant.line_number}:{line_counter}"
        ordered_stops: list[dict] = []
        line_connections: list[dict] = []

        for sequence, (original_index, stop_record, provider_stop_name) in enumerate(matched_stops_with_indices, start=1):
            ordered_stops.append(
                {
                    "sequence": sequence,
                    "provider_stop_name": provider_stop_name,
                    "graph_stop_key": stop_record["graph_stop_key"],
                }
            )

        for index in range(len(matched_stops_with_indices) - 1):
            original_from_index, from_stop, _ = matched_stops_with_indices[index]
            original_to_index, to_stop, _ = matched_stops_with_indices[index + 1]
            edge_duration_samples = []
            for edge_index in range(original_from_index, original_to_index):
                edge_duration_samples.extend(variant.edge_samples[edge_index])
            if not edge_duration_samples:
                continue

            line_connections.append(
                {
                    "from_sequence": index + 1,
                    "to_sequence": index + 2,
                    "from_stop_key": from_stop["graph_stop_key"],
                    "to_stop_key": to_stop["graph_stop_key"],
                    "avg_travel_seconds": round(mean(edge_duration_samples), 1),
                    "distance_meters": round(
                        haversine_km(from_stop["lat"], from_stop["lon"], to_stop["lat"], to_stop["lon"]) * 1_000,
                        1,
                    ),
                }
            )

        if not line_connections:
            continue

        direction_name = f"{ordered_stops[0]['provider_stop_name']} -> {ordered_stops[-1]['provider_stop_name']}"
        source_url = sorted(variant.source_urls)[-1]
        lines.append(
            {
                "line_id": line_id,
                "provider_line_id": variant.line_number,
                "name": f"Bus {variant.line_number}",
                "direction_name": direction_name,
                "service_bucket": variant.service_bucket,
                "source_url": source_url,
                "valid_from": variant.valid_from,
                "valid_to": variant.valid_to,
                "stops": ordered_stops,
            }
        )

        for connection in line_connections:
            connections.append(
                {
                    "line_id": line_id,
                    "source_url": source_url,
                    **connection,
                }
            )

    return {
        "city": city["slug"],
        "provider": provider,
        "generated_at": datetime.now(UTC).replace(microsecond=0).isoformat().replace("+00:00", "Z"),
        "stops": sorted(stops_by_graph_key.values(), key=lambda item: (item["name"], item["source_reference"])),
        "lines": lines,
        "connections": connections,
        "unmatched_stops": sorted(unmatched_stops),
    }


def load_manifest(raw_dir: Path) -> dict:
    manifest_file = raw_dir / "provider_manifest.json"
    if not manifest_file.exists():
        raise FileNotFoundError(f"Transport manifest not found: {manifest_file}")
    return json.loads(manifest_file.read_text(encoding="utf-8"))


def main() -> None:
    city_slug = sys.argv[1] if len(sys.argv) > 1 else "nitra"
    city = load_city(city_slug)
    raw_dir, processed_file = transport_paths(city)

    manifest = load_manifest(raw_dir)
    osm_stops = load_osm_stops(raw_dir, city)
    osm_index = build_osm_stop_index(osm_stops)

    variants: list[VariantAccumulator] = []
    for document in manifest.get("documents", []):
        filename = document.get("filename")
        source_url = document.get("source_url")
        line_id = str(document.get("line_id") or "")
        if not filename or not source_url or not line_id:
            continue

        pdf_path = raw_dir / "timetables" / filename
        if not pdf_path.exists():
            raise FileNotFoundError(f"Transport PDF is missing: {pdf_path}")
        variants.extend(parse_pdf_variants(pdf_path, str(source_url), line_id))

    graph = build_processed_graph(city, variants, osm_index)
    processed_file.write_text(
        json.dumps(graph, ensure_ascii=False, indent=2),
        encoding="utf-8",
    )
    print(
        f"Saved {len(graph['stops'])} matched stops, {len(graph['lines'])} lines and "
        f"{len(graph['connections'])} connections to {processed_file}"
    )
    if graph["unmatched_stops"]:
        print(f"Unmatched stops: {len(graph['unmatched_stops'])}")


if __name__ == "__main__":
    main()

from __future__ import annotations

import json
import re
from pathlib import Path
from typing import Any

from bs4 import BeautifulSoup
from pypdf import PdfReader

from .matching import haversine_km
from .models import StopRow, TransportIssue, VariantAccumulator
from .text import (
    build_city_prefixed_stop_match_keys,
    build_stop_match_keys,
    collapse_identical_consecutive_rows,
    collapse_zero_delta_duplicate_name_rows,
    compact_repeated_stop_rows,
    extract_line_number,
    normalize_display_text,
    normalize_stop_base_name,
    normalize_stop_name,
    normalized_platform_token,
    parse_date,
    parse_stop_rows,
    parse_validity,
    strip_accents,
    split_page_sections,
    split_stop_name_components,
    split_stop_row_blocks,
)

IMHD_VALID_FROM_PATTERN = re.compile(r"Plat[ií]\s+od\s+(\d{1,2}\.\d{1,2}\.\d{4})", re.IGNORECASE)
IMHD_LINE_NUMBER_PATTERN = re.compile(r"Cestovn[ýy]\s+poriadok\s+linky\s+([A-Za-z0-9]+)", re.IGNORECASE)
IMHD_CONTINUATION_NOTE_PATTERN = re.compile(
    r"Zo\s+zast[aá]vky\s+(?P<stop>.+?)\s+pokra[cč]uje\s+v\s+smere\s+(?P<direction>.+?)(?:[.;]|$)",
    re.IGNORECASE,
)

def add_issue(
    issues: list[TransportIssue],
    *,
    code: str,
    message: str,
    severity: str = "warning",
    document: str | None = None,
    line_number: str | None = None,
    page: int | None = None,
    stop_name: str | None = None,
    trip_id: str | None = None,
    **details: Any,
) -> None:
    issues.append(
        TransportIssue(
            code=code,
            message=message,
            severity=severity,
            document=document,
            line_number=line_number,
            page=page,
            stop_name=stop_name,
            trip_id=trip_id,
            details=details,
        )
    )

def sanitize_trip_column(column_times: list[int | None]) -> list[int | None]:
    return list(column_times)


def parse_document_variants(
    document_path: Path,
    source_url: str,
    fallback_line_number: str,
    issues: list[TransportIssue],
    document_format: str = "pdf",
) -> list[VariantAccumulator]:
    if document_format == "imhd_html":
        return parse_imhd_html_variants(document_path, source_url, fallback_line_number, issues)
    return parse_pdf_variants(document_path, source_url, fallback_line_number, issues)

def parse_pdf_variants(
    pdf_path: Path,
    source_url: str,
    fallback_line_number: str,
    issues: list[TransportIssue],
) -> list[VariantAccumulator]:
    reader = PdfReader(str(pdf_path))
    variants: dict[tuple[str, str, tuple[str, ...]], VariantAccumulator] = {}

    for page_number, page in enumerate(reader.pages, start=1):
        try:
            page_text = page.extract_text(extraction_mode="plain") or ""
        except Exception as exc:  # pragma: no cover - depends on pdf internals
            add_issue(
                issues,
                code="pdf_page_extract_failed",
                message="Skipping page because text extraction failed.",
                document=pdf_path.name,
                line_number=fallback_line_number,
                page=page_number,
                error=str(exc),
            )
            continue

        lines = [normalize_display_text(line) for line in page_text.splitlines() if normalize_display_text(line)]
        line_number = extract_line_number(lines, fallback_line_number)
        valid_from, valid_to = parse_validity(page_text)
        if valid_from is not None and valid_to is not None and valid_from > valid_to:
            add_issue(
                issues,
                code="invalid_validity_range",
                message="Ignoring invalid validity range where valid_from is after valid_to.",
                document=pdf_path.name,
                line_number=line_number,
                page=page_number,
                valid_from=valid_from,
                valid_to=valid_to,
            )
            valid_from, valid_to = None, None

        section_count = 0
        for service_bucket, section_text in split_page_sections(page_text):
            parsed_rows = collapse_zero_delta_duplicate_name_rows(
                collapse_identical_consecutive_rows(compact_repeated_stop_rows(parse_stop_rows(section_text)))
            )
            stop_row_blocks = split_stop_row_blocks(parsed_rows)
            if not stop_row_blocks:
                continue

            for block_index, stop_rows in enumerate(stop_row_blocks, start=1):
                if len(stop_rows) < 2:
                    continue

                stop_names = [row.name for row in stop_rows]
                max_columns = max(len(row.times) for row in stop_rows)
                padded_rows = [
                    StopRow(name=row.name, times=sanitize_trip_column(row.times + [None] * (max_columns - len(row.times))))
                    for row in stop_rows
                ]

                edge_samples: list[list[float]] = [[] for _ in range(len(stop_names) - 1)]
                trip_columns: list[list[int | None]] = []
                descending_column_count = 0
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

                for column_index in range(max_columns):
                    column_times = [row.times[column_index] for row in padded_rows]
                    non_empty_times = [minutes for minutes in column_times if minutes is not None]
                    if len(non_empty_times) < 2:
                        continue
                    if any(
                        later < earlier
                        for earlier, later in zip(non_empty_times, non_empty_times[1:])
                    ):
                        descending_column_count += 1
                        continue
                    trip_columns.append(column_times)

                if descending_column_count:
                    add_issue(
                        issues,
                        code="descending_column_times",
                        message="Skipping malformed timetable columns because times go backwards.",
                        document=pdf_path.name,
                        line_number=line_number,
                        page=page_number,
                        service_bucket=service_bucket,
                        block_index=block_index,
                        column_count=descending_column_count,
                    )

                if not any(samples for samples in edge_samples):
                    add_issue(
                        issues,
                        code="section_skipped_missing_edge_samples",
                        message="Skipping timetable section because no usable edge duration samples were derived.",
                        document=pdf_path.name,
                        line_number=line_number,
                        page=page_number,
                        service_bucket=service_bucket,
                        block_index=block_index,
                    )
                    continue

                section_count += 1
                variant_key = (line_number, service_bucket, tuple(stop_names))
                accumulator = variants.get(variant_key)
                if accumulator is None:
                    accumulator = VariantAccumulator(
                        line_number=line_number,
                        service_bucket=service_bucket,
                        stop_names=stop_names,
                        edge_samples=[list(samples) for samples in edge_samples],
                        trip_columns=[list(column) for column in trip_columns],
                        valid_from=valid_from,
                        valid_to=valid_to,
                    )
                    variants[variant_key] = accumulator
                else:
                    for index, samples in enumerate(edge_samples):
                        accumulator.edge_samples[index].extend(samples)
                    accumulator.trip_columns.extend(list(column) for column in trip_columns)
                    accumulator.valid_from = min(filter(None, [accumulator.valid_from, valid_from]), default=None)
                    accumulator.valid_to = max(filter(None, [accumulator.valid_to, valid_to]), default=None)

                accumulator.source_urls.add(source_url)

        if section_count == 0:
            add_issue(
                issues,
                code="page_skipped_insufficient_rows",
                message="Skipping page because no timetable section produced at least two stop rows.",
                document=pdf_path.name,
                line_number=fallback_line_number,
                page=page_number,
            )

    if not variants:
        add_issue(
            issues,
            code="document_parsed_without_variants",
            message="No valid transport variants were produced from this PDF.",
            document=pdf_path.name,
            line_number=fallback_line_number,
        )

    return list(variants.values())


def parse_imhd_valid_from(page_text: str) -> str | None:
    match = IMHD_VALID_FROM_PATTERN.search(page_text)
    if not match:
        return None
    return parse_date(match.group(1))


def parse_imhd_line_number(page_text: str, fallback_line_number: str) -> str:
    match = IMHD_LINE_NUMBER_PATTERN.search(page_text)
    if not match:
        return fallback_line_number
    return normalize_display_text(match.group(1)).upper()


def parse_imhd_stop_rows(soup: BeautifulSoup) -> tuple[list[str], list[int]]:
    stop_table = soup.find("table", class_=lambda classes: classes and "stopsList" in classes)
    if stop_table is None:
        return [], []

    stop_names: list[str] = []
    offsets: list[int] = []
    for row in stop_table.find_all("tr"):
        stop_name_cell = row.find("td", class_=lambda classes: classes and "stopName" in classes)
        if stop_name_cell is None:
            continue

        stop_name = normalize_display_text(stop_name_cell.get_text(" ", strip=True))
        if not stop_name:
            continue

        stop_time_cell = row.find("td", class_=lambda classes: classes and "stopTime" in classes)
        offset_minutes = 0
        if stop_time_cell is not None:
            time_candidates: list[int] = []
            for class_name in ("timemax", "timemin"):
                span = stop_time_cell.find("span", class_=class_name)
                if span is None:
                    continue
                time_candidates.extend(int(value) for value in re.findall(r"\d{1,3}", span.get_text(" ", strip=True)))
            if not time_candidates:
                time_candidates.extend(int(value) for value in re.findall(r"\d{1,3}", stop_time_cell.get_text(" ", strip=True)))
            if time_candidates:
                offset_minutes = max(time_candidates)

        if offsets and offset_minutes < offsets[-1]:
            offset_minutes = offsets[-1]

        stop_names.append(stop_name)
        offsets.append(offset_minutes)

    return stop_names, offsets


def continuation_stop_indexes(stop_names: list[str], notes_by_code: dict[str, str]) -> dict[str, int]:
    indexes: dict[str, int] = {}
    normalized_stop_indexes: dict[str, int] = {}
    for index, stop_name in enumerate(stop_names):
        base_name, _ = split_stop_name_components(stop_name)
        normalized_stop_indexes.setdefault(normalize_stop_name(stop_name), index)
        normalized_stop_indexes.setdefault(normalize_stop_base_name(base_name), index)
    for code, note_text in notes_by_code.items():
        match = IMHD_CONTINUATION_NOTE_PATTERN.search(note_text)
        if match is None:
            continue
        stop_index = normalized_stop_indexes.get(normalize_stop_name(match.group("stop")))
        if stop_index is None:
            note_base_name, _ = split_stop_name_components(match.group("stop"))
            stop_index = normalized_stop_indexes.get(normalize_stop_base_name(note_base_name))
        if stop_index is not None:
            indexes[code] = stop_index
    return indexes


def parse_imhd_notes(table: BeautifulSoup | None) -> dict[str, str]:
    if table is None:
        return {}

    notes: dict[str, str] = {}
    for row in table.find_all("tr"):
        cells = row.find_all(["th", "td"])
        if len(cells) < 2:
            continue
        code = normalize_display_text(cells[0].get_text(" ", strip=True)).upper()
        text = normalize_display_text(cells[1].get_text(" ", strip=True))
        if not code or not text:
            continue
        notes[code] = text
    return notes


def parse_imhd_trip_columns(
    table: BeautifulSoup,
    offsets: list[int],
    continuation_indexes: dict[str, int],
) -> list[list[int | None]]:
    trip_columns: list[list[int | None]] = []

    for row in table.find_all("tr"):
        row_header = row.find(["th", "td"])
        if row_header is None:
            continue

        hour_text = normalize_display_text(row_header.get_text(" ", strip=True))
        if not hour_text.isdigit():
            continue

        hour = int(hour_text)
        if hour < 0 or hour > 24:
            continue

        for cell in row.find_all("td"):
            cell_text = normalize_display_text(cell.get_text(" ", strip=True))
            if not cell_text:
                continue

            minute_matches = [int(value) for value in re.findall(r"(?<!\d)(\d{1,2})(?!\d)", cell_text)]
            valid_minutes = [minute for minute in minute_matches if 0 <= minute <= 59]
            if not valid_minutes:
                continue

            note_codes = {token.upper() for token in re.findall(r"[A-Za-z]+", cell_text)}
            trip_end_index = len(offsets) - 1
            for note_code, continuation_index in continuation_indexes.items():
                if note_code not in note_codes:
                    trip_end_index = min(trip_end_index, continuation_index)

            for minute in valid_minutes:
                base_minutes = (hour * 60) + minute
                trip_columns.append(
                    [
                        base_minutes + offset if stop_index <= trip_end_index else None
                        for stop_index, offset in enumerate(offsets)
                    ]
                )

    return trip_columns


def map_imhd_service_bucket(heading_text: str) -> str:
    normalized_heading = strip_accents(normalize_display_text(heading_text)).upper()
    if "VOLNE DNI" in normalized_heading:
        return "weekends_holidays"
    if "PRACOVNE DNI" in normalized_heading:
        return "workdays"
    return "all_days"


def parse_imhd_html_variants(
    html_path: Path,
    source_url: str,
    fallback_line_number: str,
    issues: list[TransportIssue],
) -> list[VariantAccumulator]:
    page_text = html_path.read_text(encoding="utf-8")
    soup = BeautifulSoup(page_text, "html.parser")

    line_number = parse_imhd_line_number(page_text, fallback_line_number)
    valid_from = parse_imhd_valid_from(page_text)
    stop_names, offsets = parse_imhd_stop_rows(soup)
    if len(stop_names) < 2:
        add_issue(
            issues,
            code="imhd_document_without_stop_rows",
            message="Skipping HTML timetable because fewer than two stop rows were parsed.",
            document=html_path.name,
            line_number=line_number,
        )
        return []

    notes_by_code = parse_imhd_notes(soup.find("table", class_=lambda classes: classes and "notesTable" in classes))
    continuation_indexes = continuation_stop_indexes(stop_names, notes_by_code)
    variants: dict[tuple[str, str, tuple[str, ...]], VariantAccumulator] = {}

    for heading in soup.find_all("h2", class_=lambda classes: classes and "TimetableTabHeading" in classes):
        heading_text = normalize_display_text(heading.get_text(" ", strip=True))
        if not heading_text:
            continue

        timetable_table = heading.find_next("table")
        if timetable_table is None:
            continue
        timetable_classes = set(timetable_table.get("class") or [])
        if "notesTable" in timetable_classes or "stopsList" in timetable_classes:
            continue

        service_bucket = map_imhd_service_bucket(heading_text)
        trip_columns = parse_imhd_trip_columns(timetable_table, offsets, continuation_indexes)
        if not trip_columns:
            add_issue(
                issues,
                code="imhd_service_bucket_without_trips",
                message="Skipping HTML timetable service bucket because no trips were parsed.",
                document=html_path.name,
                line_number=line_number,
                service_bucket=service_bucket,
            )
            continue

        edge_samples: list[list[float]] = []
        for current_offset, next_offset in zip(offsets, offsets[1:]):
            delta_minutes = next_offset - current_offset
            edge_samples.append([delta_minutes * 60.0] if delta_minutes > 0 else [])

        variant_key = (line_number, service_bucket, tuple(stop_names))
        accumulator = variants.get(variant_key)
        if accumulator is None:
            accumulator = VariantAccumulator(
                line_number=line_number,
                service_bucket=service_bucket,
                stop_names=stop_names,
                edge_samples=edge_samples,
                trip_columns=list(trip_columns),
                valid_from=valid_from,
                valid_to=None,
            )
            accumulator.source_urls.add(source_url)
            variants[variant_key] = accumulator
            continue

        accumulator.trip_columns.extend(trip_columns)
        accumulator.source_urls.add(source_url)
        if accumulator.valid_from is None:
            accumulator.valid_from = valid_from

    if not variants:
        add_issue(
            issues,
            code="imhd_document_without_variants",
            message="No valid transport variants were produced from this HTML timetable.",
            document=html_path.name,
            line_number=line_number,
        )

    return list(variants.values())

def load_osm_stops(raw_dir: Path, city: dict, stop_aliases: dict[str, str]) -> list[dict]:
    raw_file = raw_dir / "osm_stops_raw.json"
    payload = json.loads(raw_file.read_text(encoding="utf-8"))
    center = city.get("center") or {}
    city_name = str(city.get("name") or "").strip()
    center_lat = float(center.get("lat") or 0.0)
    center_lon = float(center.get("lon") or 0.0)

    stops: list[dict] = []
    for element in payload.get("elements", []):
        tags = element.get("tags") or {}
        name = str(tags.get("name") or "").strip()
        if not name:
            continue
        alternate_names = [
            str(tags.get(key) or "").strip()
            for key in ("name:sk", "name:hu")
            if str(tags.get(key) or "").strip()
        ]

        point = element.get("center") or element
        lat = point.get("lat")
        lon = point.get("lon")
        if lat is None or lon is None:
            continue

        ref = str(tags.get("local_ref") or tags.get("ref") or "").strip() or None
        base_name, platform_token = split_stop_name_components(name, stop_aliases)
        normalized_name = normalize_stop_name(name, stop_aliases)
        match_keys: set[str] = set(build_stop_match_keys(name, ref, stop_aliases))
        if city_name:
            match_keys.update(build_city_prefixed_stop_match_keys(name, city_name, ref, stop_aliases))
        for alternate_name in alternate_names:
            match_keys.update(build_stop_match_keys(alternate_name, ref, stop_aliases))
            if city_name:
                match_keys.update(
                    build_city_prefixed_stop_match_keys(alternate_name, city_name, ref, stop_aliases)
                )
        stops.append(
            {
                "osm_id": f"{element['type']}/{element['id']}",
                "name": normalize_display_text(name),
                "normalized_name": normalized_name,
                "normalized_base_name": normalize_stop_base_name(base_name),
                "platform_token": platform_token or normalized_platform_token(ref),
                "match_keys": sorted(match_keys),
                "lat": float(lat),
                "lon": float(lon),
                "ref": ref,
                "distance_to_center_meters": haversine_km(center_lat, center_lon, float(lat), float(lon)) * 1_000,
            }
        )

    provider_geocodes_file = raw_dir / "provider_stop_geocodes.json"
    if provider_geocodes_file.exists():
        for entry in json.loads(provider_geocodes_file.read_text(encoding="utf-8")):
            stop_name = normalize_display_text(str(entry.get("stop_name") or ""))
            lat = entry.get("lat")
            lon = entry.get("lon")
            if not stop_name or lat is None or lon is None:
                continue

            ref = str(entry.get("ref") or "").strip() or None
            base_name, platform_token = split_stop_name_components(stop_name, stop_aliases)
            stops.append(
                {
                    "osm_id": f"provider_geocode/{city.get('slug')}/{normalize_stop_base_name(stop_name).replace(' ', '-')}",
                    "name": stop_name,
                    "normalized_name": normalize_stop_name(stop_name, stop_aliases),
                    "normalized_base_name": normalize_stop_base_name(base_name),
                    "platform_token": platform_token or normalized_platform_token(ref),
                    "match_keys": sorted(build_stop_match_keys(stop_name, ref, stop_aliases)),
                    "lat": float(lat),
                    "lon": float(lon),
                    "ref": ref,
                    "distance_to_center_meters": haversine_km(center_lat, center_lon, float(lat), float(lon)) * 1_000,
                }
            )
    return stops

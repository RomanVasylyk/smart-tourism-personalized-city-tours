from __future__ import annotations

import json
from pathlib import Path
from typing import Any

from pypdf import PdfReader

from .matching import haversine_km
from .models import StopRow, TransportIssue, VariantAccumulator
from .text import (
    build_stop_match_keys,
    collapse_identical_consecutive_rows,
    collapse_zero_delta_duplicate_name_rows,
    compact_repeated_stop_rows,
    extract_line_number,
    normalize_display_text,
    normalize_stop_base_name,
    normalize_stop_name,
    normalized_platform_token,
    parse_stop_rows,
    parse_validity,
    split_page_sections,
    split_stop_name_components,
    split_stop_row_blocks,
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

def load_osm_stops(raw_dir: Path, city: dict, stop_aliases: dict[str, str]) -> list[dict]:
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

        ref = str(tags.get("local_ref") or tags.get("ref") or "").strip() or None
        base_name, platform_token = split_stop_name_components(name, stop_aliases)
        normalized_name = normalize_stop_name(name, stop_aliases)
        stops.append(
            {
                "osm_id": f"{element['type']}/{element['id']}",
                "name": normalize_display_text(name),
                "normalized_name": normalized_name,
                "normalized_base_name": normalize_stop_base_name(base_name),
                "platform_token": platform_token or normalized_platform_token(ref),
                "match_keys": sorted(build_stop_match_keys(name, ref, stop_aliases)),
                "lat": float(lat),
                "lon": float(lon),
                "ref": ref,
                "distance_to_center_meters": haversine_km(center_lat, center_lon, float(lat), float(lon)) * 1_000,
            }
        )
    return stops

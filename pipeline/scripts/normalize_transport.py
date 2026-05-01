from __future__ import annotations

import argparse
import json
import math
import re
import sys
import unicodedata
from dataclasses import dataclass, field
from datetime import UTC, datetime
from difflib import get_close_matches
from pathlib import Path
from typing import Any

import yaml
from pypdf import PdfReader

ROOT = Path(__file__).resolve().parents[1]
if str(ROOT) not in sys.path:
    sys.path.insert(0, str(ROOT))

from utils.cities import load_city

STOP_ALIAS_CONFIG_FILE = ROOT / "config" / "transport_stop_aliases.yaml"
VALIDITY_PATTERN = re.compile(r"Plati od\s+(\d{1,2}\.\d{1,2}\.\d{4})\s+do\s+(\d{1,2}\.\d{1,2}\.\d{4})", re.IGNORECASE)
LINE_NUMBER_PATTERN = re.compile(r"^\d{1,3}$")
TIME_TOKEN_PATTERN = re.compile(r"\bK\b|(?<!\w)(?:--?|x|X|…)(?!\w)|(?<!\d)(\d{1,2})\s?(\d{2})(?!\d)")
TIME_VALUE_PATTERN = re.compile(r"(?<!\d)(\d{1,2})\s?(\d{2})(?!\d)")
STOP_ROW_PATTERN = re.compile(r"^(?P<prefix>[wWxX]{0,2}[DASZ])\s+(?P<body>.+)$", re.IGNORECASE)
PLATFORM_SUFFIX_PATTERNS = (
    re.compile(r"\(\s*(?P<token>[A-Z]|\d{1,2}|[A-Z]\d|\d[A-Z])\s*\)\s*$"),
    re.compile(r"[,/-]\s*(?P<token>[A-Z]|\d{1,2}|[A-Z]\d|\d[A-Z])\s*$"),
    re.compile(r"\b(?:NAST|NASTUPISTE|NASTUPISTE|PLATF|PLATFORM|STANOVISTE)\.?\s*(?P<token>[A-Z0-9]{1,4})\s*$"),
    re.compile(r"\s+(?P<token>[A-Z])\s*$"),
)
METADATA_CONTAINS_MARKERS = (
    "PLATI OD",
    "PREPRAVU ZABEZPECUJE",
    "ZOZNAM ZASTAVOK",
    "VSETKY SPOJE",
    "S BEZBARIEROVYM PRISTUPOM",
    "ZASTAVKA JE LEN NA ZNAMENIE",
    "PRESTUP NA VLAK",
)
METADATA_EXACT_MARKERS = {
    "POKRACOVANIE",
    "OPACNY SMER",
    "OPACNY SMER - POKRACOVANIE",
}
FOOTNOTE_LINE_PATTERN = re.compile(
    r"^(?:\+|[a-z]{1,3}\s+(?:premava|nepremava|spoj)|[HDJpwx]\s+zastavka|p\s+spoj)\b",
    re.IGNORECASE,
)
STOP_NOTE_TAIL_PATTERN = re.compile(
    r"\s+\+\s+premava\b.*$|\s+[a-z]{1,3}\s+(?:premava|nepremava)\b.*$|\s+p\s+spoj\b.*$",
    re.IGNORECASE,
)
SERVICE_BUCKETS = {"workdays", "weekends_holidays", "all_days"}
FULL_KEY_FUZZY_CUTOFF = 0.88
BASE_KEY_FUZZY_CUTOFF = 0.92
STOP_NAME_ABBREVIATIONS: tuple[tuple[re.Pattern[str], str], ...] = (
    (re.compile(r"\bUL\.\b", re.IGNORECASE), "ULICA"),
    (re.compile(r"\bNAM\.\b", re.IGNORECASE), "NAMESTIE"),
    (re.compile(r"\bNABR\.\b", re.IGNORECASE), "NABREZIE"),
    (re.compile(r"\bSV\.\b", re.IGNORECASE), "SVATEHO"),
    (re.compile(r"\bZEL\.\s*ST\.\b", re.IGNORECASE), "ZELEZNICNA STANICA"),
    (re.compile(r"\bAUT\.\s*ST\.\b", re.IGNORECASE), "AUTOBUSOVA STANICA"),
)


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
    trip_columns: list[list[int | None]] = field(default_factory=list)


@dataclass
class TransportIssue:
    code: str
    message: str
    severity: str = "warning"
    document: str | None = None
    line_number: str | None = None
    page: int | None = None
    stop_name: str | None = None
    trip_id: str | None = None
    details: dict[str, Any] = field(default_factory=dict)

    def to_dict(self) -> dict[str, Any]:
        payload = {
            "severity": self.severity,
            "code": self.code,
            "message": self.message,
        }
        if self.document is not None:
            payload["document"] = self.document
        if self.line_number is not None:
            payload["line_number"] = self.line_number
        if self.page is not None:
            payload["page"] = self.page
        if self.stop_name is not None:
            payload["stop_name"] = self.stop_name
        if self.trip_id is not None:
            payload["trip_id"] = self.trip_id
        if self.details:
            payload["details"] = self.details
        return payload


def strip_accents(value: str) -> str:
    return "".join(
        char for char in unicodedata.normalize("NFKD", value)
        if not unicodedata.combining(char)
    )


def normalize_display_text(value: str) -> str:
    value = str(value or "")
    value = value.replace("\u00A0", " ").replace("–", "-").replace("—", "-").replace("−", "-")
    value = " ".join(value.split())
    value = re.sub(r"\s*([(),/;-])\s*", r" \1 ", value)
    value = re.sub(r"\s+", " ", value)
    return value.strip(" ,;/")


def alias_lookup_key(value: str) -> str:
    return normalize_display_text(value).casefold()


def load_stop_aliases(city_slug: str) -> dict[str, str]:
    if not STOP_ALIAS_CONFIG_FILE.exists():
        return {}

    payload = yaml.safe_load(STOP_ALIAS_CONFIG_FILE.read_text(encoding="utf-8")) or {}
    merged_aliases: dict[str, str] = {}
    for aliases in (
        payload.get("stop_aliases") or {},
        (((payload.get("cities") or {}).get(city_slug) or {}).get("stop_aliases") or {}),
    ):
        for alias, canonical in aliases.items():
            if not alias or not canonical:
                continue
            merged_aliases[alias_lookup_key(str(alias))] = normalize_display_text(str(canonical))
    return merged_aliases


def apply_stop_alias(value: str, stop_aliases: dict[str, str]) -> str:
    normalized_value = normalize_display_text(value)
    return stop_aliases.get(alias_lookup_key(normalized_value), normalized_value)


def normalized_platform_token(value: str | None) -> str | None:
    if not value:
        return None
    token = strip_accents(normalize_display_text(value)).upper()
    token = re.sub(r"[^A-Z0-9]", "", token)
    return token or None


def strip_row_prefix(value: str) -> str:
    normalized_value = normalize_display_text(value)
    match = STOP_ROW_PATTERN.match(normalized_value)
    if match is None:
        return normalized_value.strip()
    return match.group("body").strip()


def split_stop_name_components(value: str, stop_aliases: dict[str, str] | None = None) -> tuple[str, str | None]:
    stop_aliases = stop_aliases or {}
    canonical_value = strip_accents(strip_row_prefix(apply_stop_alias(value, stop_aliases))).upper()
    platform_token = None
    for pattern in PLATFORM_SUFFIX_PATTERNS:
        match = pattern.search(canonical_value)
        if not match:
            continue
        platform_token = normalized_platform_token(match.group("token"))
        canonical_value = canonical_value[: match.start()].strip(" ,;/()-")
        break
    return canonical_value.strip(), platform_token


def normalize_stop_base_name(value: str) -> str:
    normalized_value = strip_accents(normalize_display_text(value)).upper()
    for pattern, replacement in STOP_NAME_ABBREVIATIONS:
        normalized_value = pattern.sub(replacement, normalized_value)
    normalized_value = normalized_value.replace("STUROVA", "STUROVA")
    normalized_value = re.sub(r"[^\w\s]", " ", normalized_value)
    normalized_value = re.sub(r"\s+", " ", normalized_value)
    return normalized_value.strip().lower()


def normalize_stop_name(value: str, stop_aliases: dict[str, str] | None = None) -> str:
    base_name, platform_token = split_stop_name_components(value, stop_aliases)
    normalized_base_name = normalize_stop_base_name(base_name)
    if platform_token:
        return f"{normalized_base_name} platform {platform_token.lower()}"
    return normalized_base_name


def build_stop_match_keys(
    stop_name: str,
    ref: str | None = None,
    stop_aliases: dict[str, str] | None = None,
) -> set[str]:
    stop_aliases = stop_aliases or {}
    base_name, platform_token = split_stop_name_components(stop_name, stop_aliases)
    normalized_base_name = normalize_stop_base_name(base_name)
    ref_token = normalized_platform_token(ref)
    keys = {normalized_base_name}
    if platform_token:
        keys.add(f"{normalized_base_name} platform {platform_token.lower()}")
    if ref_token:
        keys.add(f"{normalized_base_name} platform {ref_token.lower()}")

    locality_parts = [part.strip() for part in base_name.split(",") if part.strip()]
    if len(locality_parts) >= 2:
        locality_stripped_base_name = normalize_stop_base_name(" ".join(locality_parts[1:]))
        if len(locality_stripped_base_name.split()) >= 2:
            keys.add(locality_stripped_base_name)
            if platform_token:
                keys.add(f"{locality_stripped_base_name} platform {platform_token.lower()}")
            if ref_token:
                keys.add(f"{locality_stripped_base_name} platform {ref_token.lower()}")
    return {key for key in keys if key}


def detect_service_bucket(page_text: str) -> str:
    normalized_text = strip_accents(page_text).upper()
    if "PRACOVNE DNI" in normalized_text:
        return "workdays"
    if "SOBOTY, NEDELE, SVIATKY" in normalized_text:
        return "weekends_holidays"
    if "SOBOTY" in normalized_text or "NEDELE" in normalized_text:
        return "weekends_holidays"
    return "all_days"


def split_page_sections(page_text: str) -> list[tuple[str, str]]:
    sections: list[tuple[str, str]] = []
    current_bucket: str | None = None
    current_lines: list[str] = []

    for raw_line in page_text.splitlines():
        normalized_line = strip_accents(normalize_display_text(raw_line)).upper()
        if "ZOZNAM ZASTAVOK" in normalized_line:
            if current_bucket is not None and current_lines:
                sections.append((current_bucket, "\n".join(current_lines)))
            current_bucket = detect_service_bucket(normalized_line)
            current_lines = [raw_line]
            continue

        if current_bucket is not None:
            current_lines.append(raw_line)

    if current_bucket is not None and current_lines:
        sections.append((current_bucket, "\n".join(current_lines)))

    if sections:
        return sections
    return [(detect_service_bucket(page_text), page_text)]


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


def report_paths(processed_file: Path) -> tuple[Path, Path]:
    report_file = processed_file.with_name(f"{processed_file.stem}_report.json")
    unmatched_file = processed_file.with_name(f"{processed_file.stem}_unmatched_stops.json")
    return report_file, unmatched_file


def parse_date(raw_value: str | None) -> str | None:
    if not raw_value:
        return None
    try:
        return datetime.strptime(raw_value, "%d.%m.%Y").date().isoformat()
    except ValueError:
        return None


def parse_validity(text: str) -> tuple[str | None, str | None]:
    match = VALIDITY_PATTERN.search(strip_accents(text))
    if not match:
        return None, None
    return parse_date(match.group(1)), parse_date(match.group(2))


def extract_line_number(lines: list[str], fallback_line_number: str) -> str:
    for line in lines[:5]:
        if LINE_NUMBER_PATTERN.fullmatch(strip_accents(line).strip()):
            return str(int(strip_accents(line).strip()))
    return fallback_line_number


def extract_time_columns(raw_text: str) -> list[int | None]:
    columns: list[int | None] = []
    for match in TIME_TOKEN_PATTERN.finditer(raw_text):
        token = match.group(0).strip()
        if token.casefold() in {"k", "-", "--", "x", "…"}:
            columns.append(None)
            continue
        time_match = TIME_VALUE_PATTERN.fullmatch(token)
        if not time_match:
            continue
        hour = int(time_match.group(1))
        minute = int(time_match.group(2))
        if hour > 24 or minute > 59:
            continue
        columns.append((hour * 60) + minute)
    return columns


def contains_time_tokens(value: str) -> bool:
    return TIME_TOKEN_PATTERN.search(value) is not None


def looks_like_stop_row_start(value: str) -> bool:
    return STOP_ROW_PATTERN.match(normalize_display_text(value)) is not None


def is_metadata_line(value: str) -> bool:
    normalized_value = strip_accents(normalize_display_text(value)).upper()
    if normalized_value in METADATA_EXACT_MARKERS:
        return True
    if FOOTNOTE_LINE_PATTERN.match(normalize_display_text(value)):
        return True
    if any(marker in normalized_value for marker in METADATA_CONTAINS_MARKERS):
        return True
    if re.fullmatch(r"[0-9+\s]+", normalized_value):
        return True
    if re.fullmatch(r"[A-Z]{1,3}(?:\s+[A-Z]{1,3})+", normalized_value):
        return True
    return False


def combine_wrapped_row_lines(page_text: str) -> list[str]:
    logical_rows: list[str] = []
    buffered_row = ""

    for raw_line in page_text.splitlines():
        line = normalize_display_text(raw_line)
        if not line:
            continue
        if is_metadata_line(line):
            continue

        if looks_like_stop_row_start(line):
            if buffered_row:
                logical_rows.append(buffered_row)
            buffered_row = line
            continue

        if not buffered_row:
            continue

        if re.fullmatch(r"[0-9+\s]+", line):
            continue

        if re.fullmatch(r"[A-Za-z]{1,3}(?:\s+[A-Za-z]{1,3})+", line):
            continue

        buffered_row = f"{buffered_row} {line}".strip()

    if buffered_row:
        logical_rows.append(buffered_row)

    return logical_rows


def parse_stop_row_text(row_text: str) -> StopRow | None:
    match = STOP_ROW_PATTERN.match(normalize_display_text(row_text))
    if match is None:
        return None

    stripped_row_text = re.sub(r"\b(?:odch|prích|prich)\b", " ", match.group("body"), flags=re.IGNORECASE)
    first_time_match = TIME_TOKEN_PATTERN.search(stripped_row_text)
    if first_time_match is None:
        return None

    stop_name = STOP_NOTE_TAIL_PATTERN.sub("", stripped_row_text[: first_time_match.start()]).strip(" ,;/:-")
    times = extract_time_columns(stripped_row_text[first_time_match.start() :])
    if not stop_name or not times or not any(time_value is not None for time_value in times):
        return None

    return StopRow(name=normalize_display_text(stop_name), times=times)


def parse_stop_rows(page_text: str) -> list[StopRow]:
    rows: list[StopRow] = []
    for row_text in combine_wrapped_row_lines(page_text):
        parsed_row = parse_stop_row_text(row_text)
        if parsed_row is not None:
            rows.append(parsed_row)
    return rows


def compact_repeated_stop_rows(rows: list[StopRow]) -> list[StopRow]:
    total_rows = len(rows)
    if total_rows < 4:
        return rows

    stop_names = [row.name for row in rows]
    for chunk_size in range(2, (total_rows // 2) + 1):
        if total_rows % chunk_size != 0:
            continue
        base_stop_names = stop_names[:chunk_size]
        if all(stop_names[index : index + chunk_size] == base_stop_names for index in range(0, total_rows, chunk_size)):
            return rows[:chunk_size]

    return rows


def collapse_identical_consecutive_rows(rows: list[StopRow]) -> list[StopRow]:
    collapsed_rows: list[StopRow] = []
    for row in rows:
        if collapsed_rows and row.name == collapsed_rows[-1].name and row.times == collapsed_rows[-1].times:
            continue
        collapsed_rows.append(row)
    return collapsed_rows


def collapse_zero_delta_duplicate_name_rows(rows: list[StopRow]) -> list[StopRow]:
    collapsed_rows: list[StopRow] = []
    for row in rows:
        if not collapsed_rows:
            collapsed_rows.append(row)
            continue

        previous_row = collapsed_rows[-1]
        if normalize_stop_base_name(previous_row.name) != normalize_stop_base_name(row.name):
            collapsed_rows.append(row)
            continue

        comparable_deltas = [
            current_time - previous_time
            for previous_time, current_time in zip(previous_row.times, row.times)
            if previous_time is not None and current_time is not None
        ]
        if comparable_deltas and all(delta == 0 for delta in comparable_deltas):
            continue

        collapsed_rows.append(row)

    return collapsed_rows


def should_split_row_block(previous_row: StopRow, current_row: StopRow) -> bool:
    comparable_deltas = [
        current_time - previous_time
        for previous_time, current_time in zip(previous_row.times, current_row.times)
        if previous_time is not None and current_time is not None
    ]
    if len(comparable_deltas) < 2:
        return False

    negative_deltas = [delta for delta in comparable_deltas if delta < 0]
    if len(negative_deltas) == len(comparable_deltas):
        return True
    if len(comparable_deltas) >= 4 and len(negative_deltas) * 4 >= len(comparable_deltas) * 3:
        return True
    return False


def split_stop_row_blocks(rows: list[StopRow]) -> list[list[StopRow]]:
    if len(rows) < 2:
        return [rows] if rows else []

    blocks: list[list[StopRow]] = []
    current_block: list[StopRow] = [rows[0]]
    for row in rows[1:]:
        previous_row = current_block[-1]
        if should_split_row_block(previous_row, row):
            if current_block:
                blocks.append(current_block)
            current_block = [row]
            continue
        current_block.append(row)

    if current_block:
        blocks.append(current_block)
    return [block for block in blocks if block]


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


def build_osm_stop_index(stops: list[dict]) -> dict[str, list[dict]]:
    index: dict[str, list[dict]] = {}
    for stop in stops:
        for key in stop["match_keys"]:
            index.setdefault(key, []).append(stop)
    for candidates in index.values():
        candidates.sort(key=lambda item: (item["distance_to_center_meters"], item["osm_id"]))
    return index


def deduplicate_candidates(candidates: list[dict]) -> list[dict]:
    deduplicated_candidates: list[dict] = []
    seen_osm_ids: set[str] = set()
    for candidate in candidates:
        osm_id = candidate["osm_id"]
        if osm_id in seen_osm_ids:
            continue
        seen_osm_ids.add(osm_id)
        deduplicated_candidates.append(candidate)
    return deduplicated_candidates


def fuzzy_match_key(target_key: str, available_keys: list[str], cutoff: float) -> str | None:
    if not target_key or not available_keys:
        return None
    matches = get_close_matches(target_key, available_keys, n=1, cutoff=cutoff)
    return matches[0] if matches else None


def match_provider_stop_candidates(
    stop_name: str,
    osm_index: dict[str, list[dict]],
    stop_aliases: dict[str, str],
) -> tuple[list[dict], str | None]:
    base_name, platform_token = split_stop_name_components(stop_name, stop_aliases)
    full_key = normalize_stop_name(stop_name, stop_aliases)
    base_key = normalize_stop_base_name(base_name)
    provider_match_keys = sorted(
        build_stop_match_keys(stop_name, stop_aliases=stop_aliases),
        key=lambda key: (0 if " platform " in key else 1, -len(key), key),
    )
    base_match_keys = sorted(
        {key for key in provider_match_keys if " platform " not in key},
        key=lambda key: (-len(key), key),
    )

    for lookup_key in provider_match_keys:
        exact_candidates = deduplicate_candidates(osm_index.get(lookup_key, []))
        if exact_candidates:
            matched_by = "exact" if lookup_key == full_key else "exact_alt"
            if len(exact_candidates) > 1:
                matched_by = f"{matched_by}_multi"
            return exact_candidates, matched_by

    if platform_token:
        for lookup_key in base_match_keys or [base_key]:
            base_candidates = [
                candidate
                for candidate in deduplicate_candidates(osm_index.get(lookup_key, []))
                if candidate.get("platform_token") == platform_token
            ]
            if base_candidates:
                matched_by = "platform_ref" if len(base_candidates) == 1 else "platform_ref_multi"
                return base_candidates, matched_by

    for lookup_key in base_match_keys or [base_key]:
        exact_base_candidates = deduplicate_candidates(osm_index.get(lookup_key, []))
        if exact_base_candidates:
            matched_by = "base_exact" if lookup_key == base_key else "base_exact_alt"
            if len(exact_base_candidates) > 1:
                matched_by = f"{matched_by}_multi"
            return exact_base_candidates, matched_by

    all_keys = list(osm_index.keys())
    for lookup_key in provider_match_keys:
        fuzzy_full_key = fuzzy_match_key(lookup_key, all_keys, FULL_KEY_FUZZY_CUTOFF)
        if fuzzy_full_key is not None:
            fuzzy_candidates = deduplicate_candidates(osm_index[fuzzy_full_key])
            matched_by = "fuzzy" if lookup_key == full_key else "fuzzy_alt"
            if len(fuzzy_candidates) > 1:
                matched_by = f"{matched_by}_multi"
            return fuzzy_candidates, matched_by

    for lookup_key in base_match_keys or [base_key]:
        fuzzy_base_key = fuzzy_match_key(lookup_key, all_keys, BASE_KEY_FUZZY_CUTOFF)
        if fuzzy_base_key is not None:
            fuzzy_base_candidates = deduplicate_candidates(osm_index[fuzzy_base_key])
            if platform_token:
                filtered_candidates = [
                    candidate
                    for candidate in fuzzy_base_candidates
                    if candidate.get("platform_token") == platform_token
                ]
                if filtered_candidates:
                    matched_by = "fuzzy_platform" if len(filtered_candidates) == 1 else "fuzzy_platform_multi"
                    return filtered_candidates, matched_by
            matched_by = "fuzzy_base" if lookup_key == base_key else "fuzzy_base_alt"
            if len(fuzzy_base_candidates) > 1:
                matched_by = f"{matched_by}_multi"
            return fuzzy_base_candidates, matched_by

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
    stop_aliases: dict[str, str],
) -> list[tuple[int, dict, str]]:
    matched_entries = []
    for index, stop_name in enumerate(stop_names):
        candidates, matched_by = match_provider_stop_candidates(stop_name, osm_index, stop_aliases)
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


def line_sort_key(variant: VariantAccumulator) -> tuple[int, str, tuple[str, ...]]:
    try:
        numeric_line_number = int(variant.line_number)
    except ValueError:
        numeric_line_number = 999_999
    return numeric_line_number, variant.service_bucket, tuple(variant.stop_names)


def sanitize_consecutive_assignments(
    assignments: list[tuple[int, dict, str]],
    *,
    issues: list[TransportIssue],
    line_id: str,
    source_url: str,
    metrics: dict[str, int],
) -> list[tuple[int, dict, str]]:
    sanitized_assignments: list[tuple[int, dict, str]] = []
    previous_graph_key: str | None = None

    for original_index, stop_record, matched_by in assignments:
        graph_key = stop_record["graph_stop_key"]
        if previous_graph_key == graph_key:
            metrics["duplicate_consecutive_stop_count"] += 1
            add_issue(
                issues,
                code="duplicate_consecutive_stop_assignment",
                message="Skipping duplicate consecutive stop assignment for a line.",
                document=Path(source_url).name,
                line_number=line_id,
                stop_name=stop_record["name"],
            )
            continue

        previous_graph_key = graph_key
        sanitized_assignments.append((original_index, stop_record, matched_by))

    return sanitized_assignments


def sanitize_trip_stop_times(
    trip_stop_times: list[dict[str, Any]],
    *,
    issues: list[TransportIssue],
    line_id: str,
    trip_id: str,
    metrics: dict[str, int],
) -> list[dict[str, Any]] | None:
    deduplicated_stop_times: list[dict[str, Any]] = []

    for stop_time in trip_stop_times:
        if deduplicated_stop_times and stop_time["graph_stop_key"] == deduplicated_stop_times[-1]["graph_stop_key"]:
            metrics["duplicate_consecutive_stop_count"] += 1
            add_issue(
                issues,
                code="duplicate_consecutive_trip_stop",
                message="Dropping duplicate consecutive stop within a trip.",
                line_number=line_id,
                trip_id=trip_id,
                stop_name=stop_time["provider_stop_name"],
            )
            continue
        deduplicated_stop_times.append(stop_time)

    if len(deduplicated_stop_times) < 2:
        metrics["invalid_trip_count"] += 1
        metrics["dropped_trip_count"] += 1
        add_issue(
            issues,
            code="trip_too_short",
            message="Dropping trip because fewer than two stop times remain after sanitization.",
            line_number=line_id,
            trip_id=trip_id,
        )
        return None

    if any(
        later["time_minutes"] < earlier["time_minutes"]
        for earlier, later in zip(deduplicated_stop_times, deduplicated_stop_times[1:])
    ):
        metrics["invalid_trip_count"] += 1
        metrics["descending_time_trip_count"] += 1
        metrics["dropped_trip_count"] += 1
        add_issue(
            issues,
            code="trip_descending_times",
            message="Dropping trip because stop times go backwards.",
            line_number=line_id,
            trip_id=trip_id,
        )
        return None

    for sequence, stop_time in enumerate(deduplicated_stop_times, start=1):
        stop_time["sequence"] = sequence

    return deduplicated_stop_times


def direct_connection_duration_samples(
    variant: VariantAccumulator,
    original_from_index: int,
    original_to_index: int,
) -> tuple[list[float], int, int]:
    positive_samples: list[float] = []
    comparable_count = 0
    zero_delta_count = 0

    for trip_column in variant.trip_columns:
        if original_from_index >= len(trip_column) or original_to_index >= len(trip_column):
            continue
        from_time = trip_column[original_from_index]
        to_time = trip_column[original_to_index]
        if from_time is None or to_time is None:
            continue

        comparable_count += 1
        delta_minutes = to_time - from_time
        if 0 < delta_minutes <= 120:
            positive_samples.append(delta_minutes * 60.0)
        elif delta_minutes == 0:
            zero_delta_count += 1

    return positive_samples, comparable_count, zero_delta_count


def estimated_zero_delta_connection_seconds(from_stop: dict[str, Any], to_stop: dict[str, Any], city: dict) -> float:
    transport = city.get("transport") or {}
    speed_kmh = float(transport.get("transit_speed_kmh") or 24.0)
    if speed_kmh <= 0:
        speed_kmh = 24.0

    distance_meters = haversine_km(from_stop["lat"], from_stop["lon"], to_stop["lat"], to_stop["lon"]) * 1_000
    seconds = distance_meters / max(speed_kmh * (1_000 / 3_600), 0.1)
    return round(min(180.0, max(20.0, seconds)), 1)


def is_same_station_transfer_candidate(from_stop: dict[str, Any], to_stop: dict[str, Any]) -> bool:
    if from_stop["graph_stop_key"] == to_stop["graph_stop_key"]:
        return False

    if normalize_stop_base_name(from_stop["name"]) != normalize_stop_base_name(to_stop["name"]):
        return False

    distance_meters = haversine_km(from_stop["lat"], from_stop["lon"], to_stop["lat"], to_stop["lon"]) * 1_000
    return distance_meters <= 250.0


def build_processed_graph(
    city: dict,
    variants: list[VariantAccumulator],
    osm_index: dict[str, list[dict]],
    stop_aliases: dict[str, str],
    issues: list[TransportIssue],
) -> tuple[dict, dict[str, int], list[dict[str, Any]]]:
    provider = str((city.get("transport") or {}).get("provider") or "transport_provider")
    stops_by_graph_key: dict[str, dict[str, Any]] = {}
    unmatched_stops_by_name: dict[str, dict[str, Any]] = {}
    lines: list[dict[str, Any]] = []
    connections: list[dict[str, Any]] = []
    trips: list[dict[str, Any]] = []
    line_counter = 0
    trip_counter = 0
    metrics = {
        "variant_count": len(variants),
        "invalid_trip_count": 0,
        "dropped_trip_count": 0,
        "descending_time_trip_count": 0,
        "duplicate_consecutive_stop_count": 0,
        "invalid_validity_count": 0,
        "empty_service_bucket_count": 0,
        "line_without_trip_count": 0,
    }

    for variant in sorted(variants, key=line_sort_key):
        if not variant.service_bucket:
            metrics["empty_service_bucket_count"] += 1
            variant.service_bucket = "all_days"
            add_issue(
                issues,
                code="missing_service_bucket",
                message="Missing service bucket; defaulting to all_days.",
                line_number=variant.line_number,
            )
        elif variant.service_bucket not in SERVICE_BUCKETS:
            add_issue(
                issues,
                code="unknown_service_bucket",
                message="Unknown service bucket; defaulting to all_days.",
                line_number=variant.line_number,
                service_bucket=variant.service_bucket,
            )
            variant.service_bucket = "all_days"

        if variant.valid_from is not None and variant.valid_to is not None and variant.valid_from > variant.valid_to:
            metrics["invalid_validity_count"] += 1
            add_issue(
                issues,
                code="invalid_variant_validity",
                message="Ignoring invalid variant validity range.",
                line_number=variant.line_number,
                valid_from=variant.valid_from,
                valid_to=variant.valid_to,
            )
            variant.valid_from, variant.valid_to = None, None

        matched_stops_with_indices: list[tuple[int, dict, str]] = []
        for stop_name in variant.stop_names:
            if match_provider_stop_candidates(stop_name, osm_index, stop_aliases)[0]:
                continue

            unmatched_record = unmatched_stops_by_name.setdefault(
                stop_name,
                {
                    "stop_name": stop_name,
                    "normalized_name": normalize_stop_name(stop_name, stop_aliases),
                    "occurrences": 0,
                    "line_numbers": set(),
                    "source_urls": set(),
                },
            )
            unmatched_record["occurrences"] += 1
            unmatched_record["line_numbers"].add(variant.line_number)
            unmatched_record["source_urls"].update(variant.source_urls)

        for index, osm_stop, matched_by in choose_variant_stop_assignments(variant.stop_names, osm_index, stop_aliases):
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
            add_issue(
                issues,
                code="variant_skipped_insufficient_matched_stops",
                message="Skipping variant because fewer than two stops were matched.",
                line_number=variant.line_number,
            )
            continue

        line_counter += 1
        line_id = f"{variant.line_number}:{line_counter}"
        source_url = sorted(variant.source_urls)[-1] if variant.source_urls else ""
        matched_stops_with_indices = sanitize_consecutive_assignments(
            matched_stops_with_indices,
            issues=issues,
            line_id=line_id,
            source_url=source_url,
            metrics=metrics,
        )
        if len(matched_stops_with_indices) < 2:
            add_issue(
                issues,
                code="line_skipped_after_deduplication",
                message="Skipping line because fewer than two distinct matched stops remain.",
                line_number=line_id,
            )
            continue

        ordered_stops: list[dict[str, Any]] = []
        line_connections: list[dict[str, Any]] = []
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
            edge_duration_samples: list[float] = []
            for edge_index in range(original_from_index, original_to_index):
                edge_duration_samples.extend(variant.edge_samples[edge_index])
            if not edge_duration_samples:
                is_adjacent_pair = original_to_index == original_from_index + 1
                same_station_transfer = is_adjacent_pair and is_same_station_transfer_candidate(from_stop, to_stop)
                direct_samples, comparable_count, zero_delta_count = direct_connection_duration_samples(
                    variant,
                    original_from_index,
                    original_to_index,
                )
                if direct_samples:
                    edge_duration_samples = direct_samples
                elif (
                    zero_delta_count > 0
                    and comparable_count == zero_delta_count
                    and is_adjacent_pair
                    and (
                        normalize_stop_base_name(from_stop["name"]) != normalize_stop_base_name(to_stop["name"])
                        or same_station_transfer
                    )
                ):
                    edge_duration_samples = [
                        estimated_zero_delta_connection_seconds(from_stop, to_stop, city)
                        for _ in range(zero_delta_count)
                    ]
                elif comparable_count == 0 and same_station_transfer:
                    edge_duration_samples = [estimated_zero_delta_connection_seconds(from_stop, to_stop, city)]

            if not edge_duration_samples:
                add_issue(
                    issues,
                    code="connection_without_edge_samples",
                    message="Skipping connection because no edge samples were available.",
                    line_number=line_id,
                    stop_name=f"{from_stop['name']} -> {to_stop['name']}",
                )
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
            add_issue(
                issues,
                code="line_skipped_without_connections",
                message="Skipping line because no valid connections were produced.",
                line_number=line_id,
            )
            continue

        direction_name = f"{ordered_stops[0]['provider_stop_name']} -> {ordered_stops[-1]['provider_stop_name']}"
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

        line_trip_count = 0
        for trip_column in variant.trip_columns:
            trip_counter += 1
            trip_id = f"{line_id}:trip:{trip_counter}"
            trip_stop_times: list[dict[str, Any]] = []
            for sequence, (original_index, stop_record, provider_stop_name) in enumerate(matched_stops_with_indices, start=1):
                if original_index >= len(trip_column):
                    continue
                time_minutes = trip_column[original_index]
                if time_minutes is None:
                    continue
                trip_stop_times.append(
                    {
                        "sequence": sequence,
                        "graph_stop_key": stop_record["graph_stop_key"],
                        "provider_stop_name": provider_stop_name,
                        "time_minutes": time_minutes,
                    }
                )

            sanitized_trip_stop_times = sanitize_trip_stop_times(
                trip_stop_times,
                issues=issues,
                line_id=line_id,
                trip_id=trip_id,
                metrics=metrics,
            )
            if sanitized_trip_stop_times is None:
                continue

            trips.append(
                {
                    "trip_id": trip_id,
                    "line_id": line_id,
                    "service_bucket": variant.service_bucket,
                    "source_url": source_url,
                    "valid_from": variant.valid_from,
                    "valid_to": variant.valid_to,
                    "stop_times": sanitized_trip_stop_times,
                }
            )
            line_trip_count += 1

        if line_trip_count == 0:
            metrics["line_without_trip_count"] += 1
            add_issue(
                issues,
                code="line_without_valid_trips",
                message="Line was kept with connections only because all trips were dropped.",
                line_number=line_id,
            )

        for connection in line_connections:
            connections.append(
                {
                    "line_id": line_id,
                    "source_url": source_url,
                    **connection,
                }
            )

    unmatched_stop_details = [
        {
            **record,
            "line_numbers": sorted(record["line_numbers"]),
            "source_urls": sorted(record["source_urls"]),
        }
        for record in unmatched_stops_by_name.values()
    ]
    unmatched_stop_details.sort(key=lambda item: (-item["occurrences"], item["stop_name"]))

    graph = {
        "city": city["slug"],
        "provider": provider,
        "generated_at": datetime.now(UTC).replace(microsecond=0).isoformat().replace("+00:00", "Z"),
        "stops": sorted(stops_by_graph_key.values(), key=lambda item: (item["name"], item["source_reference"])),
        "lines": lines,
        "connections": connections,
        "trips": trips,
        "unmatched_stops": [record["stop_name"] for record in unmatched_stop_details],
    }
    return graph, metrics, unmatched_stop_details


def compute_quality_report(
    graph: dict[str, Any],
    *,
    base_metrics: dict[str, int] | None = None,
) -> dict[str, Any]:
    base_metrics = {**(graph.get("quality_report") or {}), **(base_metrics or {})}
    invalid_trip_count = int(base_metrics.get("invalid_trip_count") or 0)
    dropped_trip_count = int(base_metrics.get("dropped_trip_count") or 0)
    descending_time_trip_count = int(base_metrics.get("descending_time_trip_count") or 0)
    duplicate_consecutive_stop_count = int(base_metrics.get("duplicate_consecutive_stop_count") or 0)
    invalid_validity_count = int(base_metrics.get("invalid_validity_count") or 0)
    empty_service_bucket_count = int(base_metrics.get("empty_service_bucket_count") or 0)
    line_without_trip_count = int(base_metrics.get("line_without_trip_count") or 0)
    variant_count = int(base_metrics.get("variant_count") or 0)
    parsed_document_count = int(base_metrics.get("parsed_document_count") or 0)
    source_document_count = int(base_metrics.get("source_document_count") or 0)

    total_stop_times = 0
    total_invalid_stop_times = 0
    total_duplicate_consecutive_stops = duplicate_consecutive_stop_count
    lines_without_service_bucket = 0
    lines_with_invalid_validity = invalid_validity_count

    for line in graph.get("lines", []):
        service_bucket = str(line.get("service_bucket") or "").strip()
        if not service_bucket:
            lines_without_service_bucket += 1
        if line.get("valid_from") and line.get("valid_to") and line["valid_from"] > line["valid_to"]:
            lines_with_invalid_validity += 1
        line_stops = line.get("stops") or []
        duplicate_consecutive_line_stops = sum(
            1
            for current_stop, next_stop in zip(line_stops, line_stops[1:])
            if current_stop.get("graph_stop_key") == next_stop.get("graph_stop_key")
        )
        total_duplicate_consecutive_stops += duplicate_consecutive_line_stops

    for trip in graph.get("trips", []):
        stop_times = trip.get("stop_times") or []
        total_stop_times += len(stop_times)
        if len(stop_times) < 2:
            invalid_trip_count += 1
        if any(
            later.get("time_minutes", 0) < earlier.get("time_minutes", 0)
            for earlier, later in zip(stop_times, stop_times[1:])
        ):
            invalid_trip_count += 1
            descending_time_trip_count += 1
            total_invalid_stop_times += len(stop_times)
        duplicate_consecutive_trip_stops = sum(
            1
            for current_stop, next_stop in zip(stop_times, stop_times[1:])
            if current_stop.get("graph_stop_key") == next_stop.get("graph_stop_key")
        )
        total_duplicate_consecutive_stops += duplicate_consecutive_trip_stops

    matched_stops = len(graph.get("stops") or [])
    unmatched_stop_count = len(graph.get("unmatched_stop_details") or graph.get("unmatched_stops") or [])
    denominator = matched_stops + unmatched_stop_count
    coverage_ratio = round(matched_stops / denominator, 4) if denominator else 0.0

    return {
        "city": graph.get("city"),
        "provider": graph.get("provider"),
        "generated_at": graph.get("generated_at"),
        "source_document_count": source_document_count,
        "parsed_document_count": parsed_document_count,
        "variant_count": variant_count,
        "total_stops": matched_stops,
        "matched_stops": matched_stops,
        "unmatched_stop_count": unmatched_stop_count,
        "total_lines": len(graph.get("lines") or []),
        "total_connections": len(graph.get("connections") or []),
        "total_trips": len(graph.get("trips") or []),
        "total_stop_times": total_stop_times,
        "invalid_trip_count": invalid_trip_count,
        "dropped_trip_count": dropped_trip_count,
        "descending_time_trip_count": descending_time_trip_count,
        "duplicate_consecutive_stop_count": total_duplicate_consecutive_stops,
        "invalid_stop_times": total_invalid_stop_times,
        "invalid_validity_count": lines_with_invalid_validity,
        "empty_service_bucket_count": empty_service_bucket_count + lines_without_service_bucket,
        "line_without_trip_count": line_without_trip_count,
        "warnings_count": len(graph.get("warnings") or []),
        "coverage_ratio": coverage_ratio,
    }


def load_manifest(raw_dir: Path) -> dict:
    manifest_file = raw_dir / "provider_manifest.json"
    if not manifest_file.exists():
        raise FileNotFoundError(f"Transport manifest not found: {manifest_file}")
    return json.loads(manifest_file.read_text(encoding="utf-8"))


def save_outputs(
    *,
    processed_file: Path,
    graph: dict[str, Any],
) -> tuple[Path, Path]:
    report_file, unmatched_file = report_paths(processed_file)
    processed_file.write_text(
        json.dumps(graph, ensure_ascii=False, indent=2),
        encoding="utf-8",
    )
    report_file.write_text(
        json.dumps(graph.get("quality_report") or {}, ensure_ascii=False, indent=2),
        encoding="utf-8",
    )
    unmatched_file.write_text(
        json.dumps(graph.get("unmatched_stop_details") or [], ensure_ascii=False, indent=2),
        encoding="utf-8",
    )
    return report_file, unmatched_file


def main() -> None:
    parser = argparse.ArgumentParser(
        description="Normalize transport PDFs and OSM stops into a transport graph with warnings and validation reports.",
    )
    parser.add_argument("city", nargs="?", default="nitra")
    args = parser.parse_args()

    city = load_city(args.city)
    raw_dir, processed_file = transport_paths(city)
    stop_aliases = load_stop_aliases(city["slug"])

    manifest = load_manifest(raw_dir)
    issues: list[TransportIssue] = []
    osm_stops = load_osm_stops(raw_dir, city, stop_aliases)
    if not osm_stops:
        add_issue(
            issues,
            code="empty_osm_stop_dataset",
            message="No OSM stops were loaded for the selected city.",
        )
    osm_index = build_osm_stop_index(osm_stops)

    variants: list[VariantAccumulator] = []
    documents = manifest.get("documents", []) or []
    parsed_document_count = 0
    for document in documents:
        filename = document.get("filename")
        source_url = document.get("source_url")
        line_id = str(document.get("line_id") or "")
        if not filename or not source_url or not line_id:
            add_issue(
                issues,
                code="manifest_document_skipped",
                message="Skipping manifest document with incomplete metadata.",
                document=str(filename or source_url or "<unknown>"),
            )
            continue

        pdf_path = raw_dir / "timetables" / filename
        if not pdf_path.exists():
            add_issue(
                issues,
                code="missing_timetable_pdf",
                message="Skipping missing timetable PDF referenced by manifest.",
                document=filename,
                line_number=line_id,
            )
            continue

        try:
            parsed_variants = parse_pdf_variants(pdf_path, str(source_url), line_id, issues)
        except Exception as exc:  # pragma: no cover - depends on external PDFs
            add_issue(
                issues,
                code="document_parse_failed",
                message="Skipping PDF because parsing failed unexpectedly.",
                document=filename,
                line_number=line_id,
                error=str(exc),
            )
            continue

        if parsed_variants:
            parsed_document_count += 1
        variants.extend(parsed_variants)

    graph, metrics, unmatched_stop_details = build_processed_graph(
        city,
        variants,
        osm_index,
        stop_aliases,
        issues,
    )
    metrics["source_document_count"] = len(documents)
    metrics["parsed_document_count"] = parsed_document_count
    graph["warnings"] = [issue.to_dict() for issue in issues]
    graph["unmatched_stop_details"] = unmatched_stop_details
    graph["quality_report"] = compute_quality_report(graph, base_metrics=metrics)

    report_file, unmatched_file = save_outputs(processed_file=processed_file, graph=graph)
    report = graph["quality_report"]
    print(
        f"Saved {report['matched_stops']} matched stops, {report['total_lines']} lines, "
        f"{report['total_trips']} trips and {report['total_connections']} connections to {processed_file}"
    )
    print(
        f"Report: warnings={report['warnings_count']}, dropped_trips={report['dropped_trip_count']}, "
        f"unmatched_stops={report['unmatched_stop_count']}, coverage_ratio={report['coverage_ratio']}"
    )
    print(f"Quality report written to {report_file}")
    print(f"Unmatched stops written to {unmatched_file}")


if __name__ == "__main__":
    main()

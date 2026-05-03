from __future__ import annotations

import re
import unicodedata
from datetime import datetime
from typing import Any

import yaml

from .constants import (
    FOOTNOTE_LINE_PATTERN,
    LINE_NUMBER_PATTERN,
    LOCAL_CONNECTION_RULES,
    METADATA_CONTAINS_MARKERS,
    METADATA_EXACT_MARKERS,
    PLATFORM_SUFFIX_PATTERNS,
    ROOT,
    SERVICE_BUCKETS,
    STOP_ALIAS_CONFIG_FILE,
    STOP_NAME_ABBREVIATIONS,
    STOP_NOTE_TAIL_PATTERN,
    STOP_ROW_PATTERN,
    TIME_TOKEN_PATTERN,
    TIME_VALUE_PATTERN,
    VALIDITY_PATTERN,
)
from .models import StopRow

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

def normalize_connection_rule_key(value: str) -> str:
    return normalize_display_text(value).casefold()

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

def lookup_local_connection_rule(city: dict[str, Any], from_stop_name: str, to_stop_name: str) -> dict[str, Any] | None:
    city_slug = str(city.get("slug") or "").strip().casefold()
    if not city_slug:
        return None

    from_key = normalize_connection_rule_key(from_stop_name)
    to_key = normalize_connection_rule_key(to_stop_name)
    for rule in LOCAL_CONNECTION_RULES.get(city_slug, ()):
        if (
            normalize_connection_rule_key(str(rule.get("from") or "")) == from_key
            and normalize_connection_rule_key(str(rule.get("to") or "")) == to_key
        ):
            return rule
    return None

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

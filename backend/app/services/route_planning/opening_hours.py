from __future__ import annotations

import re
from datetime import datetime

DAY_SEQUENCE = ["Mo", "Tu", "We", "Th", "Fr", "Sa", "Su"]
DAY_INDEX = {day: index for index, day in enumerate(DAY_SEQUENCE)}
DAY_ALIASES = {
    "mo": "Mo",
    "mon": "Mo",
    "monday": "Mo",
    "pondelok": "Mo",
    "pondelky": "Mo",
    "tu": "Tu",
    "tue": "Tu",
    "tuesday": "Tu",
    "utorok": "Tu",
    "utorky": "Tu",
    "utoroky": "Tu",
    "we": "We",
    "wed": "We",
    "wednesday": "We",
    "streda": "We",
    "stredy": "We",
    "th": "Th",
    "thu": "Th",
    "thursday": "Th",
    "stvrtok": "Th",
    "štvrtok": "Th",
    "stvrtky": "Th",
    "štvrtky": "Th",
    "fr": "Fr",
    "fri": "Fr",
    "friday": "Fr",
    "piatok": "Fr",
    "piatky": "Fr",
    "sa": "Sa",
    "sat": "Sa",
    "saturday": "Sa",
    "sobota": "Sa",
    "soboty": "Sa",
    "su": "Su",
    "sun": "Su",
    "sunday": "Su",
    "nedela": "Su",
    "nedeľa": "Su",
    "nedele": "Su",
}
DAY_TOKEN_PATTERN = re.compile(
    r"\b(" + "|".join(sorted((re.escape(token) for token in DAY_ALIASES), key=len, reverse=True)) + r")\b",
    re.IGNORECASE,
)
TIME_RANGE_PATTERN = re.compile(r"(\d{1,2})(?::(\d{2}))?\s*-\s*(\d{1,2})(?::(\d{2}))?")


def normalize_opening_hours(raw_value: str) -> str:
    normalized = raw_value.strip()
    normalized = normalized.replace("–", "-").replace("—", "-")
    normalized = DAY_TOKEN_PATTERN.sub(lambda match: DAY_ALIASES[match.group(0).casefold()], normalized)
    normalized = re.sub(r"\s+", " ", normalized)
    normalized = re.sub(r"(?<=\d)\s+(?=(Mo|Tu|We|Th|Fr|Sa|Su)\b)", "; ", normalized)
    return normalized


def parse_day_spec(day_spec: str) -> list[int]:
    days: list[int] = []
    normalized = day_spec.replace(" ", "")

    for token in normalized.split(","):
        if not token:
            continue

        if "-" in token:
            start_token, end_token = token.split("-", 1)
            if start_token not in DAY_INDEX or end_token not in DAY_INDEX:
                continue

            start_index = DAY_INDEX[start_token]
            end_index = DAY_INDEX[end_token]

            if start_index <= end_index:
                days.extend(range(start_index, end_index + 1))
            else:
                days.extend(range(start_index, 7))
                days.extend(range(0, end_index + 1))
        elif token in DAY_INDEX:
            days.append(DAY_INDEX[token])

    return list(dict.fromkeys(days))


def parse_time_range(raw_value: str) -> tuple[int, int] | None:
    match = TIME_RANGE_PATTERN.search(raw_value.strip())
    if not match:
        return None

    start_hour = int(match.group(1))
    start_minute = int(match.group(2) or 0)
    end_hour = int(match.group(3))
    end_minute = int(match.group(4) or 0)

    if start_hour > 23 or end_hour > 24 or start_minute > 59 or end_minute > 59:
        return None

    start_total = (start_hour * 60) + start_minute
    end_total = (end_hour * 60) + end_minute

    if end_total <= start_total:
        return None

    return start_total, end_total


def parse_opening_hours(raw_value: str | None) -> dict[int, list[tuple[int, int]]] | None:
    if not raw_value:
        return None

    normalized = normalize_opening_hours(raw_value)
    if normalized.casefold() in {"24/7", "24x7", "24h"}:
        return {day_index: [(0, 24 * 60)] for day_index in range(7)}

    schedule: dict[int, list[tuple[int, int]]] = {}
    parsed_any = False

    for segment in (part.strip() for part in normalized.split(";")):
        if not segment:
            continue

        if segment.casefold() in {"24/7", "24x7", "24h"}:
            return {day_index: [(0, 24 * 60)] for day_index in range(7)}

        match = re.match(r"^([A-Za-z,\-\s]+?)\s*:?\s*(.+)$", segment)
        if not match:
            continue

        day_indexes = parse_day_spec(match.group(1))
        if not day_indexes:
            continue

        intervals = [
            parsed_range
            for parsed_range in (parse_time_range(part) for part in match.group(2).split(","))
            if parsed_range is not None
        ]
        if not intervals:
            continue

        for day_index in day_indexes:
            schedule.setdefault(day_index, []).extend(intervals)

        parsed_any = True

    return schedule if parsed_any else None


def is_poi_open_for_visit(raw_value: str | None, arrival_dt: datetime, visit_minutes: int) -> bool:
    schedule = parse_opening_hours(raw_value)
    if schedule is None:
        return True

    visit_start_minute = (arrival_dt.hour * 60) + arrival_dt.minute
    visit_end_minute = visit_start_minute + visit_minutes
    if visit_end_minute > 24 * 60:
        return False

    intervals = schedule.get(arrival_dt.weekday(), [])
    return any(
        interval_start <= visit_start_minute and visit_end_minute <= interval_end
        for interval_start, interval_end in intervals
    )

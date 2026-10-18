from __future__ import annotations

import re
from dataclasses import dataclass
from datetime import date, datetime
from functools import lru_cache

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
MONTH_SEQUENCE = ["Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec"]
MONTH_INDEX = {month: index + 1 for index, month in enumerate(MONTH_SEQUENCE)}
DAY_NAMES = "|".join(DAY_SEQUENCE)
MONTH_NAMES = "|".join(MONTH_SEQUENCE)
ALWAYS_OPEN_VALUES = {"24/7", "24x7", "24h"}
DAY_TOKEN_PATTERN = re.compile(
    r"\b(" + "|".join(sorted((re.escape(token) for token in DAY_ALIASES), key=len, reverse=True)) + r")\b",
    re.IGNORECASE,
)
MONTH_TOKEN_PATTERN = re.compile(rf"\b({MONTH_NAMES})\b", re.IGNORECASE)
COMMENT_PATTERN = re.compile(r'"[^"]*"')
MISSING_SEPARATOR_PATTERN = re.compile(rf"(\S+ )?(\S*\d)\s+(?=(?:{DAY_NAMES})\b)")
ADDITIONAL_RULE_PATTERN = re.compile(rf"(?<=\d:\d\d)\s*,\s*(?=(?:{DAY_NAMES}|{MONTH_NAMES})\b)")
TIME_RANGE_PATTERN = re.compile(r"(\d{1,2})(?::(\d{2}))?\s*-\s*(\d{1,2})(?::(\d{2}))?")
MONTH_DAY = rf"(?:{MONTH_NAMES})(?: [0-3]?\d(?![\d:]))?"
DATE_RANGE = rf"{MONTH_DAY}(?: ?- ?(?:{MONTH_DAY}|[0-3]?\d(?![\d:])))?"
DATE_SELECTOR_PATTERN = re.compile(rf"^({DATE_RANGE}(?: ?, ?{DATE_RANGE})*)(?: ?:)?(?: |$)")
DATE_RANGE_PATTERN = re.compile(
    rf"^({MONTH_NAMES})(?: ([0-3]?\d))?(?: ?- ?(?:({MONTH_NAMES})(?: ([0-3]?\d))?|([0-3]?\d)))?$"
)
RULE_BODY_PATTERN = re.compile(r"^([A-Za-z,\-\s]*?)\s*:?\s*(\d.*)$")
CLOSED_RULE_PATTERN = re.compile(r"^([A-Za-z,\-\s]*?)\s*:?\s*\b(?:off|closed)$", re.IGNORECASE)

DateRange = tuple[tuple[int, int], tuple[int, int]]


@dataclass(frozen=True)
class OpeningRule:
    date_ranges: tuple[DateRange, ...]
    days: tuple[int, ...]
    intervals: tuple[tuple[int, int], ...]
    additional: bool

    def applies_on(self, on_date: date | None) -> bool:
        if not self.date_ranges:
            return True
        if on_date is None:
            return False
        key = (on_date.month, on_date.day)
        return any(
            start <= key <= end if start <= end else (key >= start or key <= end) for start, end in self.date_ranges
        )


def insert_missing_rule_separator(match: re.Match[str]) -> str:
    previous_token = match.group(1) or ""
    if previous_token.strip()[-3:] in MONTH_INDEX:
        return match.group(0)
    return f"{previous_token}{match.group(2)}; "


def normalize_opening_hours(raw_value: str) -> str:
    normalized = COMMENT_PATTERN.sub(" ", raw_value).strip()
    normalized = normalized.replace("–", "-").replace("—", "-")
    normalized = DAY_TOKEN_PATTERN.sub(lambda match: DAY_ALIASES[match.group(0).casefold()], normalized)
    normalized = MONTH_TOKEN_PATTERN.sub(lambda match: match.group(0).capitalize(), normalized)
    normalized = re.sub(r"\s+", " ", normalized)
    normalized = MISSING_SEPARATOR_PATTERN.sub(insert_missing_rule_separator, normalized)
    return normalized.strip()


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


def parse_date_range(raw_value: str) -> DateRange | None:
    match = DATE_RANGE_PATTERN.match(raw_value.strip())
    if not match:
        return None

    start_month, start_day, end_month, end_day, same_month_end_day = match.groups()
    start = (MONTH_INDEX[start_month], int(start_day or 1))
    if end_month:
        end = (MONTH_INDEX[end_month], int(end_day or 31))
    elif same_month_end_day:
        end = (start[0], int(same_month_end_day))
    else:
        end = (start[0], int(start_day or 31))

    if not all(1 <= day <= 31 for _, day in (start, end)):
        return None
    return start, end


def parse_date_selector(rule_text: str) -> tuple[tuple[DateRange, ...], str] | None:
    match = DATE_SELECTOR_PATTERN.match(rule_text)
    if not match:
        return (), rule_text

    date_ranges = [parse_date_range(part) for part in re.split(r" ?, ?", match.group(1))]
    if any(date_range is None for date_range in date_ranges):
        return None
    return tuple(date_ranges), rule_text[match.end() :].strip()


def parse_rule(rule_text: str, *, additional: bool) -> OpeningRule | None:
    if rule_text.casefold() in ALWAYS_OPEN_VALUES:
        return OpeningRule((), tuple(range(7)), ((0, 24 * 60),), additional)

    selector = parse_date_selector(rule_text)
    if selector is None:
        return None
    date_ranges, remainder = selector

    closed_match = CLOSED_RULE_PATTERN.match(remainder)
    body_match = closed_match or RULE_BODY_PATTERN.match(remainder)
    if not body_match:
        return None

    day_spec = body_match.group(1).strip()
    if MONTH_TOKEN_PATTERN.search(day_spec):
        return None
    days = parse_day_spec(day_spec) if day_spec else list(range(7))
    if not days:
        return None

    if closed_match:
        return OpeningRule(date_ranges, tuple(days), (), additional)

    intervals = tuple(
        parsed_range
        for parsed_range in (parse_time_range(part) for part in body_match.group(2).split(","))
        if parsed_range is not None
    )
    if not intervals:
        return None
    return OpeningRule(date_ranges, tuple(days), intervals, additional)


@lru_cache(maxsize=4096)
def parse_opening_rules(raw_value: str) -> tuple[OpeningRule, ...]:
    rules: list[OpeningRule] = []
    for segment in normalize_opening_hours(raw_value).split(";"):
        for position, rule_text in enumerate(ADDITIONAL_RULE_PATTERN.split(segment.strip())):
            if not rule_text.strip():
                continue
            rule = parse_rule(rule_text.strip(), additional=position > 0)
            if rule is not None:
                rules.append(rule)
    return tuple(rules)


def parse_opening_hours(raw_value: str | None, on_date: date | None = None) -> dict[int, list[tuple[int, int]]] | None:
    if not raw_value:
        return None

    rules = parse_opening_rules(raw_value)
    if not rules:
        return None

    schedule: dict[int, list[tuple[int, int]]] = {}
    for rule in rules:
        if not rule.applies_on(on_date):
            continue
        for day_index in rule.days:
            if rule.additional:
                schedule.setdefault(day_index, []).extend(rule.intervals)
            else:
                schedule[day_index] = list(rule.intervals)

    return schedule


def is_poi_open_for_visit(raw_value: str | None, arrival_dt: datetime, visit_minutes: int) -> bool:
    schedule = parse_opening_hours(raw_value, arrival_dt.date())
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


DEFAULT_MAX_OPENING_WAIT_MIN = 20


def wait_minutes_until_open(raw_value: str | None, arrival_dt: datetime, visit_minutes: int) -> int | None:
    schedule = parse_opening_hours(raw_value, arrival_dt.date())
    if schedule is None:
        return 0

    visit_start_minute = (arrival_dt.hour * 60) + arrival_dt.minute
    visit_end_minute = visit_start_minute + visit_minutes

    best_wait: int | None = None
    for interval_start, interval_end in schedule.get(arrival_dt.weekday(), []):
        if interval_start <= visit_start_minute and visit_end_minute <= interval_end:
            return 0
        if interval_start >= visit_start_minute and interval_start + visit_minutes <= interval_end:
            wait = interval_start - visit_start_minute
            if best_wait is None or wait < best_wait:
                best_wait = wait
    return best_wait


def resolve_visit_wait(
    raw_value: str | None,
    arrival_dt: datetime,
    visit_minutes: int,
    *,
    respect_opening_hours: bool,
    is_forced: bool,
    max_wait_minutes: int,
) -> int | None:
    if not respect_opening_hours:
        return 0
    raw_wait = wait_minutes_until_open(raw_value, arrival_dt, visit_minutes)
    if raw_wait is None or raw_wait > max_wait_minutes:
        return 0 if is_forced else None
    return raw_wait

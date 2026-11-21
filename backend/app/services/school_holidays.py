from __future__ import annotations

import time
import unicodedata
from dataclasses import dataclass
from datetime import date
from threading import Lock

from app.core.logging import get_logger
from app.db.database import get_connection
from psycopg import Error as PsycopgError

logger = get_logger(__name__)

CACHE_SECONDS = 300.0
FAILURE_CACHE_SECONDS = 30.0
KIND_NAMES = {
    "autumn": "Jesenné prázdniny",
    "christmas": "Vianočné prázdniny",
    "half_year": "Polročné prázdniny",
    "spring": "Jarné prázdniny",
    "easter": "Veľkonočné prázdniny",
    "summer": "Letné prázdniny",
}
CENTRAL = ("banskobystricky", "zilinsky", "trenciansky")
WEST = ("bratislavsky", "nitriansky", "trnavsky")
EAST = ("kosicky", "presovsky")
MINISTRY_CALENDAR: dict[int, tuple[tuple[str, date, date, tuple[str, ...] | None], ...]] = {
    2026: (
        ("autumn", date(2026, 10, 29), date(2026, 10, 30), None),
        ("christmas", date(2026, 12, 23), date(2027, 1, 7), None),
        ("half_year", date(2027, 2, 1), date(2027, 2, 1), None),
        ("spring", date(2027, 2, 15), date(2027, 2, 19), CENTRAL),
        ("spring", date(2027, 2, 22), date(2027, 2, 26), EAST),
        ("spring", date(2027, 3, 1), date(2027, 3, 5), WEST),
        ("easter", date(2027, 3, 25), date(2027, 3, 30), None),
        ("summer", date(2027, 7, 1), date(2027, 8, 31), None),
    ),
    2027: (
        ("autumn", date(2027, 10, 28), date(2027, 10, 29), None),
        ("christmas", date(2027, 12, 23), date(2028, 1, 7), None),
        ("half_year", date(2028, 2, 4), date(2028, 2, 4), None),
        ("spring", date(2028, 2, 21), date(2028, 2, 25), EAST),
        ("spring", date(2028, 2, 28), date(2028, 3, 3), WEST),
        ("spring", date(2028, 3, 6), date(2028, 3, 10), CENTRAL),
        ("easter", date(2028, 4, 13), date(2028, 4, 18), None),
        ("summer", date(2028, 7, 3), date(2028, 9, 1), None),
    ),
}
CITY_REGIONS = {
    "nitra": "nitriansky",
    "nove zamky": "nitriansky",
    "levice": "nitriansky",
    "trnava": "trnavsky",
    "trencin": "trenciansky",
    "zilina": "zilinsky",
}


@dataclass(frozen=True)
class SchoolHolidayPeriod:
    kind: str
    start_date: date
    end_date: date
    city_id: int | None

    def covers(self, service_date: date, city_id: int | None) -> bool:
        if not self.start_date <= service_date <= self.end_date:
            return False
        return self.city_id is None or self.city_id == city_id


_lock = Lock()
_cache: dict[str, object] = {"expires": 0.0, "periods": ()}


def city_key(name: str) -> str:
    decomposed = unicodedata.normalize("NFKD", name)
    return " ".join("".join(char for char in decomposed if not unicodedata.combining(char)).lower().split())


def seed_school_holidays(cur) -> int:
    cur.execute("SELECT id, name FROM cities")
    cities = cur.fetchall()
    if not cities:
        return 0
    inserted = 0
    for start_year, periods in MINISTRY_CALENDAR.items():
        cur.execute(
            "SELECT 1 AS present FROM school_holidays WHERE start_date BETWEEN %s AND %s LIMIT 1",
            (date(start_year, 9, 1), date(start_year + 1, 8, 31)),
        )
        if cur.fetchone():
            continue
        for kind, start_date, end_date, regions in periods:
            if regions is None:
                targets: list[int | None] = [None]
            else:
                targets = [city["id"] for city in cities if CITY_REGIONS.get(city_key(city["name"])) in regions]
            for city_id in targets:
                cur.execute(
                    "INSERT INTO school_holidays (name, kind, start_date, end_date, city_id) VALUES (%s, %s, %s, %s, %s)",
                    (KIND_NAMES[kind], kind, start_date, end_date, city_id),
                )
                inserted += 1
    return inserted


def _load_periods() -> tuple[SchoolHolidayPeriod, ...]:
    with get_connection() as conn:
        with conn.cursor() as cur:
            seeded = seed_school_holidays(cur)
            cur.execute("SELECT kind, start_date, end_date, city_id FROM school_holidays WHERE is_active = TRUE")
            rows = cur.fetchall()
        conn.commit()
    if seeded:
        logger.info("School holidays added to the calendar: %s", seeded)
    return tuple(SchoolHolidayPeriod(row["kind"], row["start_date"], row["end_date"], row["city_id"]) for row in rows)


def school_holiday_periods() -> tuple[SchoolHolidayPeriod, ...]:
    now = time.monotonic()
    with _lock:
        if now < _cache["expires"]:
            return _cache["periods"]
    try:
        periods = _load_periods()
        lifetime = CACHE_SECONDS
    except (PsycopgError, OSError) as exc:
        logger.warning("School holiday calendar unavailable, using school-year timetables: %s", exc)
        periods = ()
        lifetime = FAILURE_CACHE_SECONDS
    with _lock:
        _cache["periods"] = periods
        _cache["expires"] = now + lifetime
    return periods


def invalidate_school_holiday_cache() -> None:
    with _lock:
        _cache["expires"] = 0.0


def school_holiday_kinds(service_date: date, city_id: int | None) -> frozenset[str]:
    return frozenset(period.kind for period in school_holiday_periods() if period.covers(service_date, city_id))

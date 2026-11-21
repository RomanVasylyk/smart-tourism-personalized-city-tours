from __future__ import annotations

import time
from datetime import date, timedelta
from threading import Lock

from app.core.logging import get_logger
from app.db.database import get_connection
from psycopg import Error as PsycopgError

logger = get_logger(__name__)

CACHE_SECONDS = 300.0
FAILURE_CACHE_SECONDS = 30.0
HOLIDAY_WEEKDAY = 6

_lock = Lock()
_cache: dict[str, object] = {"expires": 0.0, "dates": frozenset()}


def easter_sunday(year: int) -> date:
    a = year % 19
    b, c = divmod(year, 100)
    d, e = divmod(b, 4)
    f = (b + 8) // 25
    g = (b - f + 1) // 3
    h = (19 * a + b - d - g + 15) % 30
    i, k = divmod(c, 4)
    weekday_offset = (32 + 2 * e + 2 * i - h - k) % 7
    m = (a + 11 * h + 22 * weekday_offset) // 451
    month, day = divmod(h + weekday_offset - 7 * m + 114, 31)
    return date(year, month, day + 1)


def slovak_public_holidays(year: int) -> list[tuple[date, str]]:
    easter = easter_sunday(year)
    return sorted(
        [
            (date(year, 1, 1), "Deň vzniku Slovenskej republiky"),
            (date(year, 1, 6), "Zjavenie Pána"),
            (easter - timedelta(days=2), "Veľký piatok"),
            (easter + timedelta(days=1), "Veľkonočný pondelok"),
            (date(year, 5, 1), "Sviatok práce"),
            (date(year, 5, 8), "Deň víťazstva nad fašizmom"),
            (date(year, 7, 5), "Sviatok svätého Cyrila a svätého Metoda"),
            (date(year, 8, 29), "Výročie Slovenského národného povstania"),
            (date(year, 9, 15), "Sedembolestná Panna Mária"),
            (date(year, 11, 1), "Sviatok Všetkých svätých"),
            (date(year, 11, 17), "Deň boja za slobodu a demokraciu"),
            (date(year, 12, 24), "Štedrý deň"),
            (date(year, 12, 25), "Prvý sviatok vianočný"),
            (date(year, 12, 26), "Druhý sviatok vianočný"),
        ]
    )


def _load_holiday_dates() -> frozenset[date]:
    with get_connection() as conn:
        with conn.cursor() as cur:
            cur.execute("SELECT holiday_date FROM holidays WHERE is_active = TRUE")
            return frozenset(row["holiday_date"] for row in cur.fetchall())


def holiday_dates() -> frozenset[date]:
    now = time.monotonic()
    with _lock:
        if now < _cache["expires"]:
            return _cache["dates"]
    try:
        dates = _load_holiday_dates()
        lifetime = CACHE_SECONDS
    except (PsycopgError, OSError) as exc:
        logger.warning("Holiday calendar unavailable, using regular weekdays: %s", exc)
        dates = frozenset()
        lifetime = FAILURE_CACHE_SECONDS
    with _lock:
        _cache["dates"] = dates
        _cache["expires"] = now + lifetime
    return dates


def invalidate_holiday_cache() -> None:
    with _lock:
        _cache["expires"] = 0.0


def is_public_holiday(service_date: date) -> bool:
    return service_date in holiday_dates()


def service_weekday(service_date: date) -> int:
    return HOLIDAY_WEEKDAY if is_public_holiday(service_date) else service_date.weekday()

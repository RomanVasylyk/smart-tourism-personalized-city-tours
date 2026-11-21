from __future__ import annotations

import time
from datetime import date

from app.domain.transport.service_days import ServiceDay
from app.services.holidays import invalidate_holiday_cache, is_public_holiday
from app.services.school_holidays import invalidate_school_holiday_cache, school_holiday_kinds

MEMO_SECONDS = 60.0

_memo: dict[str, object] = {"expires": 0.0, "days": {}}


def service_day(service_date: date, city_id: int | None = None) -> ServiceDay:
    now = time.monotonic()
    if now >= _memo["expires"]:
        _memo["days"] = {}
        _memo["expires"] = now + MEMO_SECONDS
    days: dict[tuple[date, int | None], ServiceDay] = _memo["days"]
    key = (service_date, city_id)
    cached = days.get(key)
    if cached is not None:
        return cached
    day = ServiceDay(
        day=service_date,
        is_public_holiday=is_public_holiday(service_date),
        school_holidays=school_holiday_kinds(service_date, city_id),
    )
    days[key] = day
    return day


def invalidate_service_calendar() -> None:
    invalidate_holiday_cache()
    invalidate_school_holiday_cache()
    _memo["expires"] = 0.0

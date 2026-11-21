from __future__ import annotations

from dataclasses import dataclass, field
from datetime import date

SUMMER = "summer"
CHRISTMAS = "christmas"
SCHOOL_HOLIDAY_KINDS = ("autumn", CHRISTMAS, "half_year", "spring", "easter", SUMMER)
NIGHT_BUCKETS = frozenset({"nights_before_rest_day", "nights_before_workday"})
SERVICE_BUCKETS = (
    "all_days",
    "workdays",
    "school_days",
    "school_holidays",
    "summer_holidays",
    "workdays_except_summer",
    "workdays_except_winter",
    "workdays_except_summer_winter",
    "weekends_holidays",
    "weekends_holidays_apr_sep",
    "weekends_holidays_oct_mar",
    "saturdays",
    "sundays_holidays",
    "nights_before_rest_day",
    "nights_before_workday",
)


@dataclass(frozen=True)
class ServiceDay:
    day: date
    is_public_holiday: bool = False
    school_holidays: frozenset[str] = field(default_factory=frozenset)

    @property
    def is_rest_day(self) -> bool:
        return self.is_public_holiday or self.day.weekday() >= 5

    @property
    def is_workday(self) -> bool:
        return not self.is_rest_day

    @property
    def is_summer_season(self) -> bool:
        return 4 <= self.day.month <= 9


def runs_on(service_bucket: str, day: ServiceDay, next_day: ServiceDay | None = None) -> bool:
    holidays = day.school_holidays
    if service_bucket == "workdays":
        return day.is_workday
    if service_bucket == "school_days":
        return day.is_workday and not holidays
    if service_bucket == "school_holidays":
        return day.is_workday and bool(holidays)
    if service_bucket == "summer_holidays":
        return day.is_workday and SUMMER in holidays
    if service_bucket == "workdays_except_summer":
        return day.is_workday and SUMMER not in holidays
    if service_bucket == "workdays_except_winter":
        return day.is_workday and CHRISTMAS not in holidays
    if service_bucket == "workdays_except_summer_winter":
        return day.is_workday and not {SUMMER, CHRISTMAS} & holidays
    if service_bucket == "weekends_holidays":
        return day.is_rest_day
    if service_bucket == "weekends_holidays_apr_sep":
        return day.is_rest_day and day.is_summer_season
    if service_bucket == "weekends_holidays_oct_mar":
        return day.is_rest_day and not day.is_summer_season
    if service_bucket == "saturdays":
        return day.day.weekday() == 5 and not day.is_public_holiday
    if service_bucket == "sundays_holidays":
        return day.day.weekday() == 6 or day.is_public_holiday
    if service_bucket in NIGHT_BUCKETS:
        if next_day is None:
            return False
        if service_bucket == "nights_before_rest_day":
            return next_day.is_rest_day
        return next_day.is_workday
    return True

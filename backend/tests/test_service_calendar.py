from datetime import date

from app.domain.transport.exact_transit import trip_operates_on_date
from app.domain.transport.service_days import ServiceDay, runs_on
from app.services import school_holidays, service_calendar
from app.services.transport_graph import GraphTrip

WEDNESDAY = date(2027, 3, 3)
SATURDAY = date(2027, 3, 6)
SUNDAY = date(2027, 3, 7)
FRIDAY = date(2027, 3, 5)


def day(value, holiday=False, kinds=()):
    return ServiceDay(value, holiday, frozenset(kinds))


def test_bucket_rules_cover_school_and_special_timetables():
    school_day = day(date(2027, 3, 10))
    spring_break = day(WEDNESDAY, kinds={"spring"})
    summer_day = day(date(2027, 7, 14), kinds={"summer"})
    christmas_day = day(date(2026, 12, 29), kinds={"christmas"})
    holiday = day(date(2027, 5, 1), holiday=True)

    assert runs_on("school_days", school_day) and not runs_on("school_days", spring_break)
    assert runs_on("school_holidays", spring_break) and not runs_on("school_holidays", school_day)
    assert runs_on("workdays", school_day) and runs_on("workdays", spring_break)
    assert runs_on("summer_holidays", summer_day) and not runs_on("summer_holidays", spring_break)
    assert runs_on("workdays_except_summer", spring_break) and not runs_on("workdays_except_summer", summer_day)
    assert not runs_on("workdays_except_winter", christmas_day)
    assert not runs_on("workdays_except_summer_winter", summer_day)
    assert runs_on("weekends_holidays", holiday) and not runs_on("workdays", holiday)
    assert runs_on("saturdays", day(SATURDAY)) and not runs_on("saturdays", day(SUNDAY))
    assert runs_on("sundays_holidays", holiday) and runs_on("sundays_holidays", day(SUNDAY))
    assert runs_on("weekends_holidays_oct_mar", day(SATURDAY))
    assert not runs_on("weekends_holidays_apr_sep", day(SATURDAY))
    assert runs_on("nights_before_rest_day", day(FRIDAY), day(SATURDAY))
    assert not runs_on("nights_before_workday", day(FRIDAY), day(SATURDAY))
    assert runs_on("all_days", day(SUNDAY)) and runs_on("unknown_bucket", day(SUNDAY))


class SeedCursor:
    def __init__(self, cities, present_years):
        self.cities = cities
        self.present_years = present_years
        self.inserted = []
        self.rows = []
        self.one = None

    def execute(self, query, params=()):
        if query.startswith("SELECT id, name FROM cities"):
            self.rows = self.cities
        elif query.startswith("SELECT 1"):
            self.one = {"present": 1} if params[0].year in self.present_years else None
        else:
            self.inserted.append(params)

    def fetchall(self):
        return self.rows

    def fetchone(self):
        return self.one


def test_spring_holidays_are_seeded_per_region():
    cities = [
        {"id": 1, "name": "Nitra"},
        {"id": 2, "name": "Trenčín"},
        {"id": 3, "name": "Nové Zámky"},
        {"id": 4, "name": "Levoča"},
    ]
    cursor = SeedCursor(cities, present_years={2027})

    assert school_holidays.seed_school_holidays(cursor) == 8
    spring = {params[4]: params[2] for params in cursor.inserted if params[1] == "spring"}
    assert spring == {1: date(2027, 3, 1), 2: date(2027, 2, 15), 3: date(2027, 3, 1)}
    assert all(params[2].year in (2026, 2027) for params in cursor.inserted)
    assert school_holidays.seed_school_holidays(SeedCursor([], present_years=set())) == 0


def test_trips_follow_school_holidays_of_their_city(monkeypatch):
    service_calendar.invalidate_service_calendar()
    monkeypatch.setattr(service_calendar, "is_public_holiday", lambda value: False)
    monkeypatch.setattr(
        service_calendar,
        "school_holiday_kinds",
        lambda value, city_id: (
            frozenset({"spring"}) if city_id == 7 and FRIDAY >= value >= date(2027, 3, 1) else frozenset()
        ),
    )
    school_year = GraphTrip(1, 1, "1", "test", "school_days", None, None, [])
    holidays = GraphTrip(2, 1, "1", "test", "school_holidays", None, None, [])

    assert not trip_operates_on_date(school_year, WEDNESDAY, city_id=7)
    assert trip_operates_on_date(holidays, WEDNESDAY, city_id=7)
    assert trip_operates_on_date(school_year, WEDNESDAY, city_id=8)
    assert not trip_operates_on_date(holidays, WEDNESDAY, city_id=8)
    assert not trip_operates_on_date(school_year, SATURDAY, city_id=8)
    service_calendar.invalidate_service_calendar()

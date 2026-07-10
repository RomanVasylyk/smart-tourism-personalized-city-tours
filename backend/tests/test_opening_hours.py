from datetime import datetime

from app.services.route_planning.opening_hours import (
    is_poi_open_for_visit,
    parse_opening_hours,
    parse_time_range,
)

MON_11 = datetime(2024, 1, 1, 11, 0)
MON_18 = datetime(2024, 1, 1, 18, 0)
SAT_11 = datetime(2024, 1, 6, 11, 0)
TUE_1230 = datetime(2024, 1, 2, 12, 30)
TUE_11 = datetime(2024, 1, 2, 11, 0)


def test_parse_time_range_basic_and_overnight():
    assert parse_time_range("09:00-17:00") == (540, 1020)
    assert parse_time_range("16-18") == (960, 1080)
    assert parse_time_range("22:00-02:00") is None


def test_parse_24_7():
    schedule = parse_opening_hours("24/7")
    assert schedule is not None
    assert all(schedule[day] == [(0, 1440)] for day in range(7))


def test_parse_multiple_intervals_and_rules():
    schedule = parse_opening_hours("Mo-Fr 09:00-12:00,13:00-16:00; Sa,Su 10:00-14:00")
    assert schedule[0] == [(540, 720), (780, 960)]
    assert schedule[5] == [(600, 840)]
    assert schedule[6] == [(600, 840)]


def test_parse_slovak_weekday_word():
    schedule = parse_opening_hours("utorok 10:00-18:00")
    assert schedule == {1: [(600, 1080)]}


def test_unparseable_value_is_treated_as_open():
    assert parse_opening_hours("momentálne mimo sezóny") is None
    assert is_poi_open_for_visit("momentálne mimo sezóny", MON_11, 30) is True


def test_open_and_closed_windows():
    hours = "Mo-Fr 09:00-17:00; Sa,Su 10:00-14:00"
    assert is_poi_open_for_visit(hours, MON_11, 30) is True
    assert is_poi_open_for_visit(hours, MON_18, 30) is False
    assert is_poi_open_for_visit(hours, SAT_11, 30) is True


def test_day_absent_from_schedule_is_closed():
    assert is_poi_open_for_visit("Mo-Fr 09:00-17:00", SAT_11, 30) is False


def test_lunch_gap_between_intervals_is_closed():
    hours = "Tu-Fr 09:00-12:00,13:00-16:00"
    assert is_poi_open_for_visit(hours, TUE_11, 30) is True
    assert is_poi_open_for_visit(hours, TUE_1230, 30) is False


def test_visit_must_fit_inside_interval():
    assert is_poi_open_for_visit("Mo-Fr 09:00-10:00", datetime(2024, 1, 1, 9, 45), 30) is False
    assert is_poi_open_for_visit("Mo-Fr 09:00-10:00", datetime(2024, 1, 1, 9, 20), 30) is True


def test_visit_past_midnight_is_rejected():
    assert is_poi_open_for_visit("24/7", datetime(2024, 1, 1, 23, 50), 30) is False


def test_overnight_interval_is_not_enforced():
    assert parse_opening_hours("Mo 22:00-02:00") is None
    assert is_poi_open_for_visit("Mo 22:00-02:00", datetime(2024, 1, 1, 23, 0), 30) is True

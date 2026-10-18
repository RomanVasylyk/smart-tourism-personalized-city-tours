from datetime import date, datetime

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
CASTLE_HOURS = (
    "May-Jun Mo-Fr 09:00-18:45; May-Jun Sa,Su 09:00-19:00; Jul 01-Oct 13 09:00-19:00; "
    "Oct 14-Mar 31 09:00-17:00; Apr Mo-Su 09:00-18:00"
)
PARK_HOURS = "Apr-Sep Mo-Su 05:00-21:00; Oct-Mar Mo-Su 06:00-18:00"
SUMMER_WEEKEND_HOURS = "May 01-Sep 01,May 01,May 08,Jul 05,Aug 29,Sep 01 Sa,Su 15:25-16:00,17:25-18:00"


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


def test_month_names_do_not_leak_into_weekdays():
    assert parse_opening_hours("May-Jun Sa,Su 09:00-19:00", date(2026, 10, 25)) == {}


def test_seasonal_rule_is_selected_by_visit_date():
    assert is_poi_open_for_visit(CASTLE_HOURS, datetime(2026, 10, 23, 10, 0), 60) is True
    assert is_poi_open_for_visit(CASTLE_HOURS, datetime(2026, 10, 23, 16, 30), 60) is False
    assert is_poi_open_for_visit(CASTLE_HOURS, datetime(2026, 10, 13, 17, 30), 60) is True
    assert is_poi_open_for_visit(CASTLE_HOURS, datetime(2026, 5, 11, 18, 0), 30) is True
    assert is_poi_open_for_visit(CASTLE_HOURS, datetime(2026, 5, 11, 18, 30), 30) is False
    assert is_poi_open_for_visit(CASTLE_HOURS, datetime(2026, 5, 16, 18, 30), 30) is True


def test_season_spanning_new_year():
    assert is_poi_open_for_visit(PARK_HOURS, datetime(2027, 1, 15, 17, 30), 30) is True
    assert is_poi_open_for_visit(PARK_HOURS, datetime(2027, 1, 15, 18, 30), 30) is False
    assert is_poi_open_for_visit(PARK_HOURS, datetime(2026, 7, 1, 20, 0), 30) is True


def test_date_list_with_weekdays_is_closed_out_of_season():
    assert is_poi_open_for_visit(SUMMER_WEEKEND_HOURS, datetime(2026, 6, 6, 15, 30), 20) is True
    assert is_poi_open_for_visit(SUMMER_WEEKEND_HOURS, datetime(2026, 6, 5, 15, 30), 20) is False
    assert parse_opening_hours(SUMMER_WEEKEND_HOURS, date(2026, 10, 24)) == {}
    assert is_poi_open_for_visit(SUMMER_WEEKEND_HOURS, datetime(2026, 10, 24, 15, 30), 20) is False


def test_month_selector_with_colon_and_rule_without_weekdays():
    assert is_poi_open_for_visit("Apr-Oct: Fr-Su 10:00-18:00", datetime(2026, 10, 23, 11, 0), 30) is True
    assert is_poi_open_for_visit("Apr-Oct: Fr-Su 10:00-18:00", datetime(2026, 11, 6, 11, 0), 30) is False
    assert is_poi_open_for_visit("Jul 01-Oct 13 09:00-19:00", datetime(2026, 8, 12, 9, 0), 30) is True


def test_additional_rule_after_comma_adds_hours():
    schedule = parse_opening_hours("Tu-Fr 08:00-17:00, Sa 09:00-13:00")
    assert schedule[1] == [(480, 1020)]
    assert schedule[5] == [(540, 780)]


def test_later_rule_overrides_earlier_rule():
    assert parse_opening_hours("Mo-Fr 09:00-17:00; We 09:00-12:00")[2] == [(540, 720)]
    assert parse_opening_hours("Mo-Sa 10:00-20:00; Tu off")[1] == []
    assert is_poi_open_for_visit("Mo-Sa 10:00-20:00; Tu off", TUE_11, 30) is False


def test_public_holiday_rule_does_not_close_regular_days():
    assert parse_opening_hours("Mo-Fr 09:00-17:00; PH off")[0] == [(540, 1020)]

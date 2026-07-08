import scripts.recompute_scheduler as scheduler


def test_interval_seconds_defaults_and_clamps(monkeypatch):
    monkeypatch.delenv("FEEDBACK_RECOMPUTE_INTERVAL_SECONDS", raising=False)
    assert scheduler._interval_seconds() == 3600

    monkeypatch.setenv("FEEDBACK_RECOMPUTE_INTERVAL_SECONDS", "not-a-number")
    assert scheduler._interval_seconds() == 3600

    monkeypatch.setenv("FEEDBACK_RECOMPUTE_INTERVAL_SECONDS", "10")
    assert scheduler._interval_seconds() == 60

    monkeypatch.setenv("FEEDBACK_RECOMPUTE_INTERVAL_SECONDS", "900")
    assert scheduler._interval_seconds() == 900


def test_truthy_parsing():
    assert scheduler._truthy(None, True) is True
    assert scheduler._truthy(None, False) is False
    for value in ["false", "0", "no", "off", "", "  False "]:
        assert scheduler._truthy(value, True) is False
    for value in ["true", "1", "yes", "on"]:
        assert scheduler._truthy(value, False) is True


def test_safe_run_swallows_errors(monkeypatch):
    def boom(city=None):
        raise RuntimeError("db down")

    monkeypatch.setattr(scheduler, "recompute_feedback_stats", boom)
    scheduler._safe_run(None)


def test_run_once_invokes_recompute(monkeypatch):
    calls = {}

    def fake(city=None):
        calls["city"] = city
        return {
            "city_name": "Nitra",
            "poi_feedback_stats": 1,
            "category_feedback_stats": 2,
            "city_feedback_stats": 3,
            "transport_mode_feedback_stats": 4,
        }

    monkeypatch.setattr(scheduler, "recompute_feedback_stats", fake)
    scheduler._run_once("nitra")
    assert calls["city"] == "nitra"

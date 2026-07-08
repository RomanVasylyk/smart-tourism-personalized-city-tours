from __future__ import annotations

import os
import signal
import sys
import time
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
if str(ROOT) not in sys.path:
    sys.path.insert(0, str(ROOT))

from app.core.logging import configure_logging, get_logger
from app.services.feedback_stats import recompute_feedback_stats

logger = get_logger(__name__)

_should_stop = False


def _handle_stop(signum, frame) -> None:
    global _should_stop
    _should_stop = True


def _interval_seconds() -> int:
    raw = os.getenv("FEEDBACK_RECOMPUTE_INTERVAL_SECONDS", "3600")
    try:
        value = int(raw)
    except ValueError:
        value = 3600
    return max(60, value)


def _truthy(raw: str | None, default: bool) -> bool:
    if raw is None:
        return default
    return raw.strip().lower() not in {"0", "false", "no", "off", ""}


def _run_once(city: str | None) -> None:
    summary = recompute_feedback_stats(city=city)
    scope = summary["city_name"] or "all cities"
    logger.info(
        "Recomputed feedback aggregates for %s (poi=%s category=%s city=%s transport=%s)",
        scope,
        summary["poi_feedback_stats"],
        summary["category_feedback_stats"],
        summary["city_feedback_stats"],
        summary["transport_mode_feedback_stats"],
    )


def _safe_run(city: str | None) -> None:
    try:
        _run_once(city)
    except Exception:
        logger.exception("Feedback recompute failed; will retry next interval")


def _sleep_interruptible(seconds: int) -> None:
    deadline = time.monotonic() + seconds
    while not _should_stop:
        remaining = deadline - time.monotonic()
        if remaining <= 0:
            return
        time.sleep(min(1.0, remaining))


def main() -> None:
    configure_logging(os.getenv("LOG_LEVEL", "INFO"))
    signal.signal(signal.SIGTERM, _handle_stop)
    signal.signal(signal.SIGINT, _handle_stop)

    city = os.getenv("FEEDBACK_RECOMPUTE_CITY") or None
    interval = _interval_seconds()

    logger.info(
        "Feedback recompute scheduler started (interval=%ss, scope=%s)",
        interval,
        city or "all cities",
    )

    if _truthy(os.getenv("FEEDBACK_RECOMPUTE_RUN_ON_START"), True):
        _safe_run(city)

    while not _should_stop:
        _sleep_interruptible(interval)
        if _should_stop:
            break
        _safe_run(city)

    logger.info("Feedback recompute scheduler stopped")


if __name__ == "__main__":
    main()

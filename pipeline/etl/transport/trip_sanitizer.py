from __future__ import annotations

from .models import GraphBuildMetrics, TransportIssue, TripStopDraft
from .parser import add_issue


def sanitize_trip_stop_times(
    trip_stop_times: list[TripStopDraft],
    *,
    issues: list[TransportIssue],
    line_id: str,
    trip_id: str,
    metrics: GraphBuildMetrics,
) -> list[TripStopDraft] | None:
    deduplicated_stop_times: list[TripStopDraft] = []

    for stop_time in trip_stop_times:
        if deduplicated_stop_times and stop_time.graph_stop_key == deduplicated_stop_times[-1].graph_stop_key:
            metrics.duplicate_consecutive_stop_count += 1
            add_issue(
                issues,
                code="duplicate_consecutive_trip_stop",
                message="Dropping duplicate consecutive stop within a trip.",
                line_number=line_id,
                trip_id=trip_id,
                stop_name=stop_time.provider_stop_name,
            )
            continue
        deduplicated_stop_times.append(stop_time)

    if len(deduplicated_stop_times) < 2:
        metrics.invalid_trip_count += 1
        metrics.dropped_trip_count += 1
        add_issue(
            issues,
            code="trip_too_short",
            message="Dropping trip because fewer than two stop times remain after sanitization.",
            line_number=line_id,
            trip_id=trip_id,
        )
        return None

    if any(
        later.time_minutes < earlier.time_minutes
        for earlier, later in zip(deduplicated_stop_times, deduplicated_stop_times[1:], strict=False)
    ):
        metrics.invalid_trip_count += 1
        metrics.descending_time_trip_count += 1
        metrics.dropped_trip_count += 1
        add_issue(
            issues,
            code="trip_descending_times",
            message="Dropping trip because stop times go backwards.",
            line_number=line_id,
            trip_id=trip_id,
        )
        return None

    for sequence, stop_time in enumerate(deduplicated_stop_times, start=1):
        stop_time.sequence = sequence

    return deduplicated_stop_times

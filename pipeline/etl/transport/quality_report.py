from __future__ import annotations

from typing import Any

from .models import TransportQualityReport


def compute_quality_report(
    graph: dict[str, Any],
    *,
    base_metrics: dict[str, int] | None = None,
) -> dict[str, Any]:
    base_metrics = {**(graph.get("quality_report") or {}), **(base_metrics or {})}
    invalid_trip_count = int(base_metrics.get("invalid_trip_count") or 0)
    dropped_trip_count = int(base_metrics.get("dropped_trip_count") or 0)
    descending_time_trip_count = int(base_metrics.get("descending_time_trip_count") or 0)
    duplicate_consecutive_stop_count = int(base_metrics.get("duplicate_consecutive_stop_count") or 0)
    invalid_validity_count = int(base_metrics.get("invalid_validity_count") or 0)
    empty_service_bucket_count = int(base_metrics.get("empty_service_bucket_count") or 0)
    line_without_trip_count = int(base_metrics.get("line_without_trip_count") or 0)
    variant_count = int(base_metrics.get("variant_count") or 0)
    parsed_document_count = int(base_metrics.get("parsed_document_count") or 0)
    source_document_count = int(base_metrics.get("source_document_count") or 0)

    total_stop_times = 0
    total_invalid_stop_times = 0
    total_duplicate_consecutive_stops = duplicate_consecutive_stop_count
    lines_without_service_bucket = 0
    lines_with_invalid_validity = invalid_validity_count

    for line in graph.get("lines", []):
        service_bucket = str(line.get("service_bucket") or "").strip()
        if not service_bucket:
            lines_without_service_bucket += 1
        if line.get("valid_from") and line.get("valid_to") and line["valid_from"] > line["valid_to"]:
            lines_with_invalid_validity += 1
        line_stops = line.get("stops") or []
        duplicate_consecutive_line_stops = sum(
            1
            for current_stop, next_stop in zip(line_stops, line_stops[1:], strict=False)
            if current_stop.get("graph_stop_key") == next_stop.get("graph_stop_key")
        )
        total_duplicate_consecutive_stops += duplicate_consecutive_line_stops

    for trip in graph.get("trips", []):
        stop_times = trip.get("stop_times") or []
        total_stop_times += len(stop_times)
        if len(stop_times) < 2:
            invalid_trip_count += 1
        if any(
            later.get("time_minutes", 0) < earlier.get("time_minutes", 0)
            for earlier, later in zip(stop_times, stop_times[1:], strict=False)
        ):
            invalid_trip_count += 1
            descending_time_trip_count += 1
            total_invalid_stop_times += len(stop_times)
        duplicate_consecutive_trip_stops = sum(
            1
            for current_stop, next_stop in zip(stop_times, stop_times[1:], strict=False)
            if current_stop.get("graph_stop_key") == next_stop.get("graph_stop_key")
        )
        total_duplicate_consecutive_stops += duplicate_consecutive_trip_stops

    matched_stops = len(graph.get("stops") or [])
    unmatched_stop_count = len(graph.get("unmatched_stop_details") or graph.get("unmatched_stops") or [])
    denominator = matched_stops + unmatched_stop_count
    coverage_ratio = round(matched_stops / denominator, 4) if denominator else 0.0

    return TransportQualityReport(
        city=graph.get("city"),
        provider=graph.get("provider"),
        generated_at=graph.get("generated_at"),
        source_document_count=source_document_count,
        parsed_document_count=parsed_document_count,
        variant_count=variant_count,
        total_stops=matched_stops,
        matched_stops=matched_stops,
        unmatched_stop_count=unmatched_stop_count,
        total_lines=len(graph.get("lines") or []),
        total_connections=len(graph.get("connections") or []),
        total_trips=len(graph.get("trips") or []),
        total_stop_times=total_stop_times,
        invalid_trip_count=invalid_trip_count,
        dropped_trip_count=dropped_trip_count,
        descending_time_trip_count=descending_time_trip_count,
        duplicate_consecutive_stop_count=total_duplicate_consecutive_stops,
        invalid_stop_times=total_invalid_stop_times,
        invalid_validity_count=lines_with_invalid_validity,
        empty_service_bucket_count=empty_service_bucket_count + lines_without_service_bucket,
        line_without_trip_count=line_without_trip_count,
        warnings_count=len(graph.get("warnings") or []),
        coverage_ratio=coverage_ratio,
    ).to_dict()

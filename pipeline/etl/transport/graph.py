from __future__ import annotations

from datetime import UTC, datetime
from typing import Any

from .connection_builder import build_line_connections
from .constants import SERVICE_BUCKETS
from .models import (
    GraphBuildMetrics,
    GraphLineRecord,
    GraphLineStopRecord,
    GraphStopRecord,
    GraphTripRecord,
    MatchedStopAssignment,
    TransportGraphData,
    TransportIssue,
    TripStopDraft,
    UnmatchedStopRecord,
    VariantAccumulator,
)
from .parser import add_issue
from .quality_report import compute_quality_report
from .stop_matching import (
    add_estimated_variant_stop_assignments,
    matched_stop_assignments_for_variant,
    sanitize_consecutive_assignments,
)
from .text import normalize_stop_name
from .trip_sanitizer import sanitize_trip_stop_times

__all__ = ["build_processed_graph", "compute_quality_report"]


def line_sort_key(variant: VariantAccumulator) -> tuple[int, str, tuple[str, ...]]:
    try:
        numeric_line_number = int(variant.line_number)
    except ValueError:
        numeric_line_number = 999_999
    return numeric_line_number, variant.service_bucket, tuple(variant.stop_names)


def build_processed_graph(
    city: dict,
    variants: list[VariantAccumulator],
    osm_index: dict[str, list[dict]],
    stop_aliases: dict[str, str],
    issues: list[TransportIssue],
) -> tuple[dict, dict[str, int], list[dict[str, Any]]]:
    provider = str((city.get("transport") or {}).get("provider") or "transport_provider")
    stops_by_graph_key: dict[str, dict[str, Any]] = {}
    unmatched_stops_by_name: dict[str, UnmatchedStopRecord] = {}
    lines: list[GraphLineRecord] = []
    connections = []
    trips: list[GraphTripRecord] = []
    line_counter = 0
    trip_counter = 0
    metrics = GraphBuildMetrics(variant_count=len(variants))

    for variant in sorted(variants, key=line_sort_key):
        normalize_variant_service_bucket(variant, issues, metrics)
        normalize_variant_validity(variant, issues, metrics)

        assignments = matched_stop_assignments_for_variant(
            variant=variant,
            osm_index=osm_index,
            stop_aliases=stop_aliases,
            stops_by_graph_key=stops_by_graph_key,
        )
        assignments = add_estimated_variant_stop_assignments(
            city,
            variant,
            assignments,
            stops_by_graph_key,
        )
        record_unmatched_stops(variant, assignments, unmatched_stops_by_name, stop_aliases)

        if len(assignments) < 2:
            add_issue(
                issues,
                code="variant_skipped_insufficient_matched_stops",
                message="Skipping variant because fewer than two stops were matched.",
                line_number=variant.line_number,
            )
            continue

        line_counter += 1
        line_id = f"{variant.line_number}:{line_counter}"
        source_url = sorted(variant.source_urls)[-1] if variant.source_urls else ""
        assignments = sanitize_consecutive_assignments(
            assignments,
            issues=issues,
            line_id=line_id,
            source_url=source_url,
            metrics=metrics,
        )
        if len(assignments) < 2:
            add_issue(
                issues,
                code="line_skipped_after_deduplication",
                message="Skipping line because fewer than two distinct matched stops remain.",
                line_number=line_id,
            )
            continue

        ordered_stops = line_stops_for_assignments(assignments)
        line_connections = build_line_connections(
            city=city,
            variant=variant,
            line_id=line_id,
            source_url=source_url,
            assignments=assignments,
            issues=issues,
        )
        if not line_connections:
            add_issue(
                issues,
                code="line_skipped_without_connections",
                message="Skipping line because no valid connections were produced.",
                line_number=line_id,
            )
            continue

        lines.append(line_record_for_variant(variant, line_id, source_url, ordered_stops))
        trip_counter, line_trip_count = append_graph_trips(
            trips=trips,
            variant=variant,
            assignments=assignments,
            source_url=source_url,
            line_id=line_id,
            trip_counter=trip_counter,
            issues=issues,
            metrics=metrics,
        )
        if line_trip_count == 0:
            metrics.line_without_trip_count += 1
            add_issue(
                issues,
                code="line_without_valid_trips",
                message="Line was kept with connections only because all trips were dropped.",
                line_number=line_id,
            )

        connections.extend(line_connections)

    unmatched_stop_details = sorted(
        [record.to_dict() for record in unmatched_stops_by_name.values()],
        key=lambda item: (-item["occurrences"], item["stop_name"]),
    )
    graph = TransportGraphData(
        city=city["slug"],
        provider=provider,
        generated_at=datetime.now(UTC).replace(microsecond=0).isoformat().replace("+00:00", "Z"),
        stops=stop_records(stops_by_graph_key),
        lines=lines,
        connections=connections,
        trips=trips,
        unmatched_stops=[record["stop_name"] for record in unmatched_stop_details],
    )
    return graph.to_dict(), metrics.to_dict(), unmatched_stop_details


def normalize_variant_service_bucket(
    variant: VariantAccumulator,
    issues: list[TransportIssue],
    metrics: GraphBuildMetrics,
) -> None:
    if not variant.service_bucket:
        metrics.empty_service_bucket_count += 1
        variant.service_bucket = "all_days"
        add_issue(
            issues,
            code="missing_service_bucket",
            message="Missing service bucket; defaulting to all_days.",
            line_number=variant.line_number,
        )
    elif variant.service_bucket not in SERVICE_BUCKETS:
        add_issue(
            issues,
            code="unknown_service_bucket",
            message="Unknown service bucket; defaulting to all_days.",
            line_number=variant.line_number,
            service_bucket=variant.service_bucket,
        )
        variant.service_bucket = "all_days"


def normalize_variant_validity(
    variant: VariantAccumulator,
    issues: list[TransportIssue],
    metrics: GraphBuildMetrics,
) -> None:
    if variant.valid_from is None or variant.valid_to is None or variant.valid_from <= variant.valid_to:
        return

    metrics.invalid_validity_count += 1
    add_issue(
        issues,
        code="invalid_variant_validity",
        message="Ignoring invalid variant validity range.",
        line_number=variant.line_number,
        valid_from=variant.valid_from,
        valid_to=variant.valid_to,
    )
    variant.valid_from, variant.valid_to = None, None


def record_unmatched_stops(
    variant: VariantAccumulator,
    assignments: list[MatchedStopAssignment],
    unmatched_stops_by_name: dict[str, UnmatchedStopRecord],
    stop_aliases: dict[str, str],
) -> None:
    matched_indices = {assignment.original_index for assignment in assignments}
    for index, stop_name in enumerate(variant.stop_names):
        if index in matched_indices:
            continue

        unmatched_record = unmatched_stops_by_name.setdefault(
            stop_name,
            UnmatchedStopRecord(
                stop_name=stop_name,
                normalized_name=normalize_stop_name(stop_name, stop_aliases),
            ),
        )
        unmatched_record.occurrences += 1
        unmatched_record.line_numbers.add(variant.line_number)
        unmatched_record.source_urls.update(variant.source_urls)


def line_stops_for_assignments(assignments: list[MatchedStopAssignment]) -> list[GraphLineStopRecord]:
    return [
        GraphLineStopRecord(
            sequence=sequence,
            provider_stop_name=assignment.provider_stop_name,
            graph_stop_key=assignment.graph_stop_key,
        )
        for sequence, assignment in enumerate(assignments, start=1)
    ]


def line_record_for_variant(
    variant: VariantAccumulator,
    line_id: str,
    source_url: str,
    ordered_stops: list[GraphLineStopRecord],
) -> GraphLineRecord:
    direction_name = f"{ordered_stops[0].provider_stop_name} -> {ordered_stops[-1].provider_stop_name}"
    return GraphLineRecord(
        line_id=line_id,
        provider_line_id=variant.line_number,
        name=f"Bus {variant.line_number}",
        direction_name=direction_name,
        service_bucket=variant.service_bucket,
        source_url=source_url,
        valid_from=variant.valid_from,
        valid_to=variant.valid_to,
        stops=ordered_stops,
    )


def append_graph_trips(
    *,
    trips: list[GraphTripRecord],
    variant: VariantAccumulator,
    assignments: list[MatchedStopAssignment],
    source_url: str,
    line_id: str,
    trip_counter: int,
    issues: list[TransportIssue],
    metrics: GraphBuildMetrics,
) -> tuple[int, int]:
    line_trip_count = 0
    for trip_column in variant.trip_columns:
        trip_counter += 1
        trip_id = f"{line_id}:trip:{trip_counter}"
        trip_stop_times = trip_stop_drafts_for_column(assignments, trip_column)

        sanitized_trip_stop_times = sanitize_trip_stop_times(
            trip_stop_times,
            issues=issues,
            line_id=line_id,
            trip_id=trip_id,
            metrics=metrics,
        )
        if sanitized_trip_stop_times is None:
            continue

        trips.append(
            GraphTripRecord(
                trip_id=trip_id,
                line_id=line_id,
                service_bucket=variant.service_bucket,
                source_url=source_url,
                valid_from=variant.valid_from,
                valid_to=variant.valid_to,
                stop_times=[stop_time.to_record() for stop_time in sanitized_trip_stop_times],
            )
        )
        line_trip_count += 1

    return trip_counter, line_trip_count


def trip_stop_drafts_for_column(
    assignments: list[MatchedStopAssignment],
    trip_column: list[int | None],
) -> list[TripStopDraft]:
    trip_stop_times: list[TripStopDraft] = []
    for sequence, assignment in enumerate(assignments, start=1):
        if assignment.original_index >= len(trip_column):
            continue
        time_minutes = trip_column[assignment.original_index]
        if time_minutes is None:
            continue
        trip_stop_times.append(
            TripStopDraft(
                sequence=sequence,
                graph_stop_key=assignment.graph_stop_key,
                provider_stop_name=assignment.provider_stop_name,
                time_minutes=time_minutes,
            )
        )
    return trip_stop_times


def stop_records(stops_by_graph_key: dict[str, dict[str, Any]]) -> list[GraphStopRecord]:
    return [
        GraphStopRecord(
            graph_stop_key=stop["graph_stop_key"],
            name=stop["name"],
            normalized_name=stop["normalized_name"],
            lat=float(stop["lat"]),
            lon=float(stop["lon"]),
            platform_ref=stop.get("platform_ref"),
            source=stop["source"],
            source_reference=stop.get("source_reference"),
            matched_by=stop.get("matched_by"),
        )
        for stop in sorted(stops_by_graph_key.values(), key=lambda item: (item["name"], item["source_reference"]))
    ]

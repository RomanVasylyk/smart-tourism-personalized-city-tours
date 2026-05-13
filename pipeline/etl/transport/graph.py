from __future__ import annotations

from datetime import UTC, datetime
from pathlib import Path
from typing import Any

from .constants import SERVICE_BUCKETS
from .matching import choose_variant_stop_assignments, haversine_km, match_provider_stop_candidates, mean
from .models import TransportIssue, VariantAccumulator
from .parser import add_issue
from .text import lookup_local_connection_rule, normalize_display_text, normalize_stop_base_name, normalize_stop_name

def line_sort_key(variant: VariantAccumulator) -> tuple[int, str, tuple[str, ...]]:
    try:
        numeric_line_number = int(variant.line_number)
    except ValueError:
        numeric_line_number = 999_999
    return numeric_line_number, variant.service_bucket, tuple(variant.stop_names)

def sanitize_consecutive_assignments(
    assignments: list[tuple[int, dict, str]],
    *,
    issues: list[TransportIssue],
    line_id: str,
    source_url: str,
    metrics: dict[str, int],
) -> list[tuple[int, dict, str]]:
    sanitized_assignments: list[tuple[int, dict, str]] = []
    previous_graph_key: str | None = None

    for original_index, stop_record, matched_by in assignments:
        graph_key = stop_record["graph_stop_key"]
        if previous_graph_key == graph_key:
            metrics["duplicate_consecutive_stop_count"] += 1
            add_issue(
                issues,
                code="duplicate_consecutive_stop_assignment",
                message="Skipping duplicate consecutive stop assignment for a line.",
                document=Path(source_url).name,
                line_number=line_id,
                stop_name=stop_record["name"],
            )
            continue

        previous_graph_key = graph_key
        sanitized_assignments.append((original_index, stop_record, matched_by))

    return sanitized_assignments

def sanitize_trip_stop_times(
    trip_stop_times: list[dict[str, Any]],
    *,
    issues: list[TransportIssue],
    line_id: str,
    trip_id: str,
    metrics: dict[str, int],
) -> list[dict[str, Any]] | None:
    deduplicated_stop_times: list[dict[str, Any]] = []

    for stop_time in trip_stop_times:
        if deduplicated_stop_times and stop_time["graph_stop_key"] == deduplicated_stop_times[-1]["graph_stop_key"]:
            metrics["duplicate_consecutive_stop_count"] += 1
            add_issue(
                issues,
                code="duplicate_consecutive_trip_stop",
                message="Dropping duplicate consecutive stop within a trip.",
                line_number=line_id,
                trip_id=trip_id,
                stop_name=stop_time["provider_stop_name"],
            )
            continue
        deduplicated_stop_times.append(stop_time)

    if len(deduplicated_stop_times) < 2:
        metrics["invalid_trip_count"] += 1
        metrics["dropped_trip_count"] += 1
        add_issue(
            issues,
            code="trip_too_short",
            message="Dropping trip because fewer than two stop times remain after sanitization.",
            line_number=line_id,
            trip_id=trip_id,
        )
        return None

    if any(
        later["time_minutes"] < earlier["time_minutes"]
        for earlier, later in zip(deduplicated_stop_times, deduplicated_stop_times[1:])
    ):
        metrics["invalid_trip_count"] += 1
        metrics["descending_time_trip_count"] += 1
        metrics["dropped_trip_count"] += 1
        add_issue(
            issues,
            code="trip_descending_times",
            message="Dropping trip because stop times go backwards.",
            line_number=line_id,
            trip_id=trip_id,
        )
        return None

    for sequence, stop_time in enumerate(deduplicated_stop_times, start=1):
        stop_time["sequence"] = sequence

    return deduplicated_stop_times

def direct_connection_duration_samples(
    variant: VariantAccumulator,
    original_from_index: int,
    original_to_index: int,
) -> tuple[list[float], int, int]:
    positive_samples: list[float] = []
    comparable_count = 0
    zero_delta_count = 0

    for trip_column in variant.trip_columns:
        if original_from_index >= len(trip_column) or original_to_index >= len(trip_column):
            continue
        from_time = trip_column[original_from_index]
        to_time = trip_column[original_to_index]
        if from_time is None or to_time is None:
            continue

        comparable_count += 1
        delta_minutes = to_time - from_time
        if 0 < delta_minutes <= 120:
            positive_samples.append(delta_minutes * 60.0)
        elif delta_minutes == 0:
            zero_delta_count += 1

    return positive_samples, comparable_count, zero_delta_count

def estimated_zero_delta_connection_seconds(from_stop: dict[str, Any], to_stop: dict[str, Any], city: dict) -> float:
    transport = city.get("transport") or {}
    speed_kmh = float(transport.get("transit_speed_kmh") or 24.0)
    if speed_kmh <= 0:
        speed_kmh = 24.0

    distance_meters = haversine_km(from_stop["lat"], from_stop["lon"], to_stop["lat"], to_stop["lon"]) * 1_000
    seconds = distance_meters / max(speed_kmh * (1_000 / 3_600), 0.1)
    return round(min(180.0, max(20.0, seconds)), 1)

def is_same_station_transfer_candidate(from_stop: dict[str, Any], to_stop: dict[str, Any]) -> bool:
    if from_stop["graph_stop_key"] == to_stop["graph_stop_key"]:
        return False

    if normalize_stop_base_name(from_stop["name"]) != normalize_stop_base_name(to_stop["name"]):
        return False

    distance_meters = haversine_km(from_stop["lat"], from_stop["lon"], to_stop["lat"], to_stop["lon"]) * 1_000
    return distance_meters <= 250.0


def synthetic_stop_graph_key(city_slug: str, stop_name: str) -> str:
    normalized_stop_key = normalize_stop_name(stop_name).replace(" ", "-")
    return f"provider_synthetic/{city_slug}/{normalized_stop_key}"


def estimate_stop_coordinates(
    missing_index: int,
    assignments: list[tuple[int, dict[str, Any], str]],
) -> tuple[float, float] | None:
    previous_assignments = [assignment for assignment in assignments if assignment[0] < missing_index]
    next_assignments = [assignment for assignment in assignments if assignment[0] > missing_index]

    previous_assignment = previous_assignments[-1] if previous_assignments else None
    next_assignment = next_assignments[0] if next_assignments else None
    previous_previous_assignment = previous_assignments[-2] if len(previous_assignments) >= 2 else None
    next_next_assignment = next_assignments[1] if len(next_assignments) >= 2 else None

    if previous_assignment is not None and next_assignment is not None:
        previous_index, previous_stop, _ = previous_assignment
        next_index, next_stop, _ = next_assignment
        step_count = next_index - previous_index
        if step_count <= 0:
            return None
        ratio = (missing_index - previous_index) / step_count
        lat = previous_stop["lat"] + ((next_stop["lat"] - previous_stop["lat"]) * ratio)
        lon = previous_stop["lon"] + ((next_stop["lon"] - previous_stop["lon"]) * ratio)
        return lat, lon

    if previous_assignment is not None and previous_previous_assignment is not None:
        previous_index, previous_stop, _ = previous_assignment
        previous_previous_index, previous_previous_stop, _ = previous_previous_assignment
        step_count = previous_index - previous_previous_index
        if step_count <= 0:
            return None
        step_lat = (previous_stop["lat"] - previous_previous_stop["lat"]) / step_count
        step_lon = (previous_stop["lon"] - previous_previous_stop["lon"]) / step_count
        offset = missing_index - previous_index
        return previous_stop["lat"] + (step_lat * offset), previous_stop["lon"] + (step_lon * offset)

    if next_assignment is not None and next_next_assignment is not None:
        next_index, next_stop, _ = next_assignment
        next_next_index, next_next_stop, _ = next_next_assignment
        step_count = next_next_index - next_index
        if step_count <= 0:
            return None
        step_lat = (next_next_stop["lat"] - next_stop["lat"]) / step_count
        step_lon = (next_next_stop["lon"] - next_stop["lon"]) / step_count
        offset = next_index - missing_index
        return next_stop["lat"] - (step_lat * offset), next_stop["lon"] - (step_lon * offset)

    return None


def build_synthetic_stop_record(
    city: dict[str, Any],
    stop_name: str,
    lat: float,
    lon: float,
    stops_by_graph_key: dict[str, dict[str, Any]],
) -> dict[str, Any]:
    graph_stop_key = synthetic_stop_graph_key(str(city.get("slug") or "city"), stop_name)
    stop_record = stops_by_graph_key.get(graph_stop_key)
    if stop_record is not None:
        return stop_record

    stop_record = {
        "graph_stop_key": graph_stop_key,
        "name": normalize_display_text(stop_name),
        "normalized_name": normalize_stop_name(stop_name),
        "lat": lat,
        "lon": lon,
        "platform_ref": None,
        "source": "provider_estimated",
        "source_reference": graph_stop_key,
        "matched_by": "estimated",
    }
    stops_by_graph_key[graph_stop_key] = stop_record
    return stop_record


def add_estimated_variant_stop_assignments(
    city: dict[str, Any],
    variant: VariantAccumulator,
    assignments: list[tuple[int, dict[str, Any], str]],
    stops_by_graph_key: dict[str, dict[str, Any]],
) -> list[tuple[int, dict[str, Any], str]]:
    if len(assignments) < 2:
        return assignments

    enriched_assignments = list(assignments)
    assigned_indices = {index for index, _, _ in enriched_assignments}

    for missing_index, stop_name in enumerate(variant.stop_names):
        if missing_index in assigned_indices:
            continue

        estimated_coordinates = estimate_stop_coordinates(missing_index, enriched_assignments)
        if estimated_coordinates is None:
            continue

        stop_record = build_synthetic_stop_record(
            city,
            stop_name,
            estimated_coordinates[0],
            estimated_coordinates[1],
            stops_by_graph_key,
        )
        enriched_assignments.append((missing_index, stop_record, stop_name))
        assigned_indices.add(missing_index)
        enriched_assignments.sort(key=lambda item: item[0])

    return enriched_assignments

def build_processed_graph(
    city: dict,
    variants: list[VariantAccumulator],
    osm_index: dict[str, list[dict]],
    stop_aliases: dict[str, str],
    issues: list[TransportIssue],
) -> tuple[dict, dict[str, int], list[dict[str, Any]]]:
    provider = str((city.get("transport") or {}).get("provider") or "transport_provider")
    stops_by_graph_key: dict[str, dict[str, Any]] = {}
    unmatched_stops_by_name: dict[str, dict[str, Any]] = {}
    lines: list[dict[str, Any]] = []
    connections: list[dict[str, Any]] = []
    trips: list[dict[str, Any]] = []
    line_counter = 0
    trip_counter = 0
    metrics = {
        "variant_count": len(variants),
        "invalid_trip_count": 0,
        "dropped_trip_count": 0,
        "descending_time_trip_count": 0,
        "duplicate_consecutive_stop_count": 0,
        "invalid_validity_count": 0,
        "empty_service_bucket_count": 0,
        "line_without_trip_count": 0,
    }

    for variant in sorted(variants, key=line_sort_key):
        if not variant.service_bucket:
            metrics["empty_service_bucket_count"] += 1
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

        if variant.valid_from is not None and variant.valid_to is not None and variant.valid_from > variant.valid_to:
            metrics["invalid_validity_count"] += 1
            add_issue(
                issues,
                code="invalid_variant_validity",
                message="Ignoring invalid variant validity range.",
                line_number=variant.line_number,
                valid_from=variant.valid_from,
                valid_to=variant.valid_to,
            )
            variant.valid_from, variant.valid_to = None, None

        matched_stops_with_indices: list[tuple[int, dict, str]] = []
        for index, osm_stop, matched_by in choose_variant_stop_assignments(variant.stop_names, osm_index, stop_aliases):
            graph_stop_key = osm_stop["osm_id"]
            stop_record = stops_by_graph_key.get(graph_stop_key)
            if stop_record is None:
                stop_record = {
                    "graph_stop_key": graph_stop_key,
                    "name": osm_stop["name"],
                    "normalized_name": osm_stop["normalized_name"],
                    "lat": osm_stop["lat"],
                    "lon": osm_stop["lon"],
                    "platform_ref": osm_stop.get("ref"),
                    "source": "osm",
                    "source_reference": osm_stop["osm_id"],
                    "matched_by": matched_by,
                }
                stops_by_graph_key[graph_stop_key] = stop_record

            matched_stops_with_indices.append((index, stop_record, variant.stop_names[index]))

        matched_stops_with_indices = add_estimated_variant_stop_assignments(
            city,
            variant,
            matched_stops_with_indices,
            stops_by_graph_key,
        )

        matched_indices = {index for index, _, _ in matched_stops_with_indices}
        for index, stop_name in enumerate(variant.stop_names):
            if index in matched_indices:
                continue

            unmatched_record = unmatched_stops_by_name.setdefault(
                stop_name,
                {
                    "stop_name": stop_name,
                    "normalized_name": normalize_stop_name(stop_name, stop_aliases),
                    "occurrences": 0,
                    "line_numbers": set(),
                    "source_urls": set(),
                },
            )
            unmatched_record["occurrences"] += 1
            unmatched_record["line_numbers"].add(variant.line_number)
            unmatched_record["source_urls"].update(variant.source_urls)

        if len(matched_stops_with_indices) < 2:
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
        matched_stops_with_indices = sanitize_consecutive_assignments(
            matched_stops_with_indices,
            issues=issues,
            line_id=line_id,
            source_url=source_url,
            metrics=metrics,
        )
        if len(matched_stops_with_indices) < 2:
            add_issue(
                issues,
                code="line_skipped_after_deduplication",
                message="Skipping line because fewer than two distinct matched stops remain.",
                line_number=line_id,
            )
            continue

        ordered_stops: list[dict[str, Any]] = []
        line_connections: list[dict[str, Any]] = []
        for sequence, (original_index, stop_record, provider_stop_name) in enumerate(matched_stops_with_indices, start=1):
            ordered_stops.append(
                {
                    "sequence": sequence,
                    "provider_stop_name": provider_stop_name,
                    "graph_stop_key": stop_record["graph_stop_key"],
                }
            )

        for index in range(len(matched_stops_with_indices) - 1):
            original_from_index, from_stop, _ = matched_stops_with_indices[index]
            original_to_index, to_stop, _ = matched_stops_with_indices[index + 1]
            edge_duration_samples: list[float] = []
            local_connection_rule = lookup_local_connection_rule(city, from_stop["name"], to_stop["name"])
            for edge_index in range(original_from_index, original_to_index):
                edge_duration_samples.extend(variant.edge_samples[edge_index])
            if not edge_duration_samples:
                is_adjacent_pair = original_to_index == original_from_index + 1
                same_station_transfer = is_adjacent_pair and is_same_station_transfer_candidate(from_stop, to_stop)
                direct_samples, comparable_count, zero_delta_count = direct_connection_duration_samples(
                    variant,
                    original_from_index,
                    original_to_index,
                )
                if direct_samples:
                    edge_duration_samples = direct_samples
                elif (
                    zero_delta_count > 0
                    and comparable_count == zero_delta_count
                    and is_adjacent_pair
                    and (
                        normalize_stop_base_name(from_stop["name"]) != normalize_stop_base_name(to_stop["name"])
                        or same_station_transfer
                    )
                ):
                    edge_duration_samples = [
                        estimated_zero_delta_connection_seconds(from_stop, to_stop, city)
                        for _ in range(zero_delta_count)
                    ]
                elif comparable_count == 0 and same_station_transfer:
                    edge_duration_samples = [estimated_zero_delta_connection_seconds(from_stop, to_stop, city)]

            if not edge_duration_samples and local_connection_rule is not None:
                action = str(local_connection_rule.get("action") or "")
                if action == "estimated_edge_seconds":
                    seconds = float(local_connection_rule.get("seconds") or 0.0)
                    if seconds > 0:
                        edge_duration_samples = [seconds]
                elif action == "ignore_missing_edge":
                    continue

            if not edge_duration_samples:
                add_issue(
                    issues,
                    code="connection_without_edge_samples",
                    message="Skipping connection because no edge samples were available.",
                    line_number=line_id,
                    stop_name=f"{from_stop['name']} -> {to_stop['name']}",
                )
                continue

            line_connections.append(
                {
                    "from_sequence": index + 1,
                    "to_sequence": index + 2,
                    "from_stop_key": from_stop["graph_stop_key"],
                    "to_stop_key": to_stop["graph_stop_key"],
                    "avg_travel_seconds": round(mean(edge_duration_samples), 1),
                    "distance_meters": round(
                        haversine_km(from_stop["lat"], from_stop["lon"], to_stop["lat"], to_stop["lon"]) * 1_000,
                        1,
                    ),
                }
            )

        if not line_connections:
            add_issue(
                issues,
                code="line_skipped_without_connections",
                message="Skipping line because no valid connections were produced.",
                line_number=line_id,
            )
            continue

        direction_name = f"{ordered_stops[0]['provider_stop_name']} -> {ordered_stops[-1]['provider_stop_name']}"
        lines.append(
            {
                "line_id": line_id,
                "provider_line_id": variant.line_number,
                "name": f"Bus {variant.line_number}",
                "direction_name": direction_name,
                "service_bucket": variant.service_bucket,
                "source_url": source_url,
                "valid_from": variant.valid_from,
                "valid_to": variant.valid_to,
                "stops": ordered_stops,
            }
        )

        line_trip_count = 0
        for trip_column in variant.trip_columns:
            trip_counter += 1
            trip_id = f"{line_id}:trip:{trip_counter}"
            trip_stop_times: list[dict[str, Any]] = []
            for sequence, (original_index, stop_record, provider_stop_name) in enumerate(matched_stops_with_indices, start=1):
                if original_index >= len(trip_column):
                    continue
                time_minutes = trip_column[original_index]
                if time_minutes is None:
                    continue
                trip_stop_times.append(
                    {
                        "sequence": sequence,
                        "graph_stop_key": stop_record["graph_stop_key"],
                        "provider_stop_name": provider_stop_name,
                        "time_minutes": time_minutes,
                    }
                )

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
                {
                    "trip_id": trip_id,
                    "line_id": line_id,
                    "service_bucket": variant.service_bucket,
                    "source_url": source_url,
                    "valid_from": variant.valid_from,
                    "valid_to": variant.valid_to,
                    "stop_times": sanitized_trip_stop_times,
                }
            )
            line_trip_count += 1

        if line_trip_count == 0:
            metrics["line_without_trip_count"] += 1
            add_issue(
                issues,
                code="line_without_valid_trips",
                message="Line was kept with connections only because all trips were dropped.",
                line_number=line_id,
            )

        for connection in line_connections:
            connections.append(
                {
                    "line_id": line_id,
                    "source_url": source_url,
                    **connection,
                }
            )

    unmatched_stop_details = [
        {
            **record,
            "line_numbers": sorted(record["line_numbers"]),
            "source_urls": sorted(record["source_urls"]),
        }
        for record in unmatched_stops_by_name.values()
    ]
    unmatched_stop_details.sort(key=lambda item: (-item["occurrences"], item["stop_name"]))

    graph = {
        "city": city["slug"],
        "provider": provider,
        "generated_at": datetime.now(UTC).replace(microsecond=0).isoformat().replace("+00:00", "Z"),
        "stops": sorted(stops_by_graph_key.values(), key=lambda item: (item["name"], item["source_reference"])),
        "lines": lines,
        "connections": connections,
        "trips": trips,
        "unmatched_stops": [record["stop_name"] for record in unmatched_stop_details],
    }
    return graph, metrics, unmatched_stop_details

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
            for current_stop, next_stop in zip(line_stops, line_stops[1:])
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
            for earlier, later in zip(stop_times, stop_times[1:])
        ):
            invalid_trip_count += 1
            descending_time_trip_count += 1
            total_invalid_stop_times += len(stop_times)
        duplicate_consecutive_trip_stops = sum(
            1
            for current_stop, next_stop in zip(stop_times, stop_times[1:])
            if current_stop.get("graph_stop_key") == next_stop.get("graph_stop_key")
        )
        total_duplicate_consecutive_stops += duplicate_consecutive_trip_stops

    matched_stops = len(graph.get("stops") or [])
    unmatched_stop_count = len(graph.get("unmatched_stop_details") or graph.get("unmatched_stops") or [])
    denominator = matched_stops + unmatched_stop_count
    coverage_ratio = round(matched_stops / denominator, 4) if denominator else 0.0

    return {
        "city": graph.get("city"),
        "provider": graph.get("provider"),
        "generated_at": graph.get("generated_at"),
        "source_document_count": source_document_count,
        "parsed_document_count": parsed_document_count,
        "variant_count": variant_count,
        "total_stops": matched_stops,
        "matched_stops": matched_stops,
        "unmatched_stop_count": unmatched_stop_count,
        "total_lines": len(graph.get("lines") or []),
        "total_connections": len(graph.get("connections") or []),
        "total_trips": len(graph.get("trips") or []),
        "total_stop_times": total_stop_times,
        "invalid_trip_count": invalid_trip_count,
        "dropped_trip_count": dropped_trip_count,
        "descending_time_trip_count": descending_time_trip_count,
        "duplicate_consecutive_stop_count": total_duplicate_consecutive_stops,
        "invalid_stop_times": total_invalid_stop_times,
        "invalid_validity_count": lines_with_invalid_validity,
        "empty_service_bucket_count": empty_service_bucket_count + lines_without_service_bucket,
        "line_without_trip_count": line_without_trip_count,
        "warnings_count": len(graph.get("warnings") or []),
        "coverage_ratio": coverage_ratio,
    }

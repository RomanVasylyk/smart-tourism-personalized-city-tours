from __future__ import annotations

from typing import Any

from .matching import haversine_km, mean
from .models import GraphConnectionRecord, MatchedStopAssignment, TransportIssue, VariantAccumulator
from .parser import add_issue
from .text import lookup_local_connection_rule, normalize_stop_base_name


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


def estimated_zero_delta_connection_seconds(
    from_stop: MatchedStopAssignment,
    to_stop: MatchedStopAssignment,
    city: dict[str, Any],
) -> float:
    transport = city.get("transport") or {}
    speed_kmh = float(transport.get("transit_speed_kmh") or 24.0)
    if speed_kmh <= 0:
        speed_kmh = 24.0

    distance_meters = haversine_km(from_stop.lat, from_stop.lon, to_stop.lat, to_stop.lon) * 1_000
    seconds = distance_meters / max(speed_kmh * (1_000 / 3_600), 0.1)
    return round(min(180.0, max(20.0, seconds)), 1)


def is_same_station_transfer_candidate(from_stop: MatchedStopAssignment, to_stop: MatchedStopAssignment) -> bool:
    if from_stop.graph_stop_key == to_stop.graph_stop_key:
        return False

    if normalize_stop_base_name(from_stop.name) != normalize_stop_base_name(to_stop.name):
        return False

    distance_meters = haversine_km(from_stop.lat, from_stop.lon, to_stop.lat, to_stop.lon) * 1_000
    return distance_meters <= 250.0


def build_line_connections(
    *,
    city: dict[str, Any],
    variant: VariantAccumulator,
    line_id: str,
    source_url: str,
    assignments: list[MatchedStopAssignment],
    issues: list[TransportIssue],
) -> list[GraphConnectionRecord]:
    connections: list[GraphConnectionRecord] = []
    for index in range(len(assignments) - 1):
        from_stop = assignments[index]
        to_stop = assignments[index + 1]
        edge_duration_samples: list[float] = []
        local_connection_rule = lookup_local_connection_rule(city, from_stop.name, to_stop.name)
        for edge_index in range(from_stop.original_index, to_stop.original_index):
            edge_duration_samples.extend(variant.edge_samples[edge_index])
        if not edge_duration_samples:
            edge_duration_samples = inferred_edge_duration_samples(
                city=city,
                variant=variant,
                from_stop=from_stop,
                to_stop=to_stop,
            )

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
                stop_name=f"{from_stop.name} -> {to_stop.name}",
            )
            continue

        connections.append(
            GraphConnectionRecord(
                line_id=line_id,
                source_url=source_url,
                from_sequence=index + 1,
                to_sequence=index + 2,
                from_stop_key=from_stop.graph_stop_key,
                to_stop_key=to_stop.graph_stop_key,
                avg_travel_seconds=round(mean(edge_duration_samples), 1),
                distance_meters=round(
                    haversine_km(from_stop.lat, from_stop.lon, to_stop.lat, to_stop.lon) * 1_000,
                    1,
                ),
            )
        )

    return connections


def inferred_edge_duration_samples(
    *,
    city: dict[str, Any],
    variant: VariantAccumulator,
    from_stop: MatchedStopAssignment,
    to_stop: MatchedStopAssignment,
) -> list[float]:
    is_adjacent_pair = to_stop.original_index == from_stop.original_index + 1
    same_station_transfer = is_adjacent_pair and is_same_station_transfer_candidate(from_stop, to_stop)
    direct_samples, comparable_count, zero_delta_count = direct_connection_duration_samples(
        variant,
        from_stop.original_index,
        to_stop.original_index,
    )
    if direct_samples:
        return direct_samples
    if (
        zero_delta_count > 0
        and comparable_count == zero_delta_count
        and is_adjacent_pair
        and (
            normalize_stop_base_name(from_stop.name) != normalize_stop_base_name(to_stop.name) or same_station_transfer
        )
    ):
        return [estimated_zero_delta_connection_seconds(from_stop, to_stop, city) for _ in range(zero_delta_count)]
    if comparable_count == 0 and same_station_transfer:
        return [estimated_zero_delta_connection_seconds(from_stop, to_stop, city)]
    return []

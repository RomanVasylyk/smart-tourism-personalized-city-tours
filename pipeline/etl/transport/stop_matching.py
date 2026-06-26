from __future__ import annotations

from pathlib import Path
from typing import Any

from .matching import choose_variant_stop_assignments
from .models import GraphBuildMetrics, MatchedStopAssignment, TransportIssue, VariantAccumulator
from .parser import add_issue
from .text import normalize_display_text, normalize_stop_name


def sanitize_consecutive_assignments(
    assignments: list[MatchedStopAssignment],
    *,
    issues: list[TransportIssue],
    line_id: str,
    source_url: str,
    metrics: GraphBuildMetrics,
) -> list[MatchedStopAssignment]:
    sanitized_assignments: list[MatchedStopAssignment] = []
    previous_graph_key: str | None = None

    for assignment in assignments:
        graph_key = assignment.graph_stop_key
        if previous_graph_key == graph_key:
            metrics.duplicate_consecutive_stop_count += 1
            add_issue(
                issues,
                code="duplicate_consecutive_stop_assignment",
                message="Skipping duplicate consecutive stop assignment for a line.",
                document=Path(source_url).name,
                line_number=line_id,
                stop_name=assignment.name,
            )
            continue

        previous_graph_key = graph_key
        sanitized_assignments.append(assignment)

    return sanitized_assignments


def matched_stop_assignments_for_variant(
    variant: VariantAccumulator,
    osm_index: dict[str, list[dict]],
    stop_aliases: dict[str, str],
    stops_by_graph_key: dict[str, dict[str, Any]],
) -> list[MatchedStopAssignment]:
    assignments: list[MatchedStopAssignment] = []
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

        assignments.append(
            MatchedStopAssignment(
                original_index=index,
                stop_record=stop_record,
                provider_stop_name=variant.stop_names[index],
            )
        )

    return assignments


def synthetic_stop_graph_key(city_slug: str, stop_name: str) -> str:
    normalized_stop_key = normalize_stop_name(stop_name).replace(" ", "-")
    return f"provider_synthetic/{city_slug}/{normalized_stop_key}"


def estimate_stop_coordinates(
    missing_index: int,
    assignments: list[MatchedStopAssignment],
) -> tuple[float, float] | None:
    previous_assignments = [assignment for assignment in assignments if assignment.original_index < missing_index]
    next_assignments = [assignment for assignment in assignments if assignment.original_index > missing_index]

    previous_assignment = previous_assignments[-1] if previous_assignments else None
    next_assignment = next_assignments[0] if next_assignments else None
    previous_previous_assignment = previous_assignments[-2] if len(previous_assignments) >= 2 else None
    next_next_assignment = next_assignments[1] if len(next_assignments) >= 2 else None

    if previous_assignment is not None and next_assignment is not None:
        step_count = next_assignment.original_index - previous_assignment.original_index
        if step_count <= 0:
            return None
        ratio = (missing_index - previous_assignment.original_index) / step_count
        lat = previous_assignment.lat + ((next_assignment.lat - previous_assignment.lat) * ratio)
        lon = previous_assignment.lon + ((next_assignment.lon - previous_assignment.lon) * ratio)
        return lat, lon

    if previous_assignment is not None and previous_previous_assignment is not None:
        step_count = previous_assignment.original_index - previous_previous_assignment.original_index
        if step_count <= 0:
            return None
        step_lat = (previous_assignment.lat - previous_previous_assignment.lat) / step_count
        step_lon = (previous_assignment.lon - previous_previous_assignment.lon) / step_count
        offset = missing_index - previous_assignment.original_index
        return previous_assignment.lat + (step_lat * offset), previous_assignment.lon + (step_lon * offset)

    if next_assignment is not None and next_next_assignment is not None:
        step_count = next_next_assignment.original_index - next_assignment.original_index
        if step_count <= 0:
            return None
        step_lat = (next_next_assignment.lat - next_assignment.lat) / step_count
        step_lon = (next_next_assignment.lon - next_assignment.lon) / step_count
        offset = next_assignment.original_index - missing_index
        return next_assignment.lat - (step_lat * offset), next_assignment.lon - (step_lon * offset)

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
    assignments: list[MatchedStopAssignment],
    stops_by_graph_key: dict[str, dict[str, Any]],
) -> list[MatchedStopAssignment]:
    if len(assignments) < 2:
        return assignments

    enriched_assignments = list(assignments)
    assigned_indices = {assignment.original_index for assignment in enriched_assignments}

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
        enriched_assignments.append(
            MatchedStopAssignment(
                original_index=missing_index,
                stop_record=stop_record,
                provider_stop_name=stop_name,
            )
        )
        assigned_indices.add(missing_index)
        enriched_assignments.sort(key=lambda item: item.original_index)

    return enriched_assignments

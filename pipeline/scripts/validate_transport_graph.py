from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path
from typing import Any

ROOT = Path(__file__).resolve().parents[1]
if str(ROOT) not in sys.path:
    sys.path.insert(0, str(ROOT))

from scripts.normalize_transport import SERVICE_BUCKETS, compute_quality_report, report_paths, transport_paths
from utils.cities import load_city


def add_warning(
    warnings: list[dict[str, Any]],
    *,
    code: str,
    message: str,
    line_id: str | None = None,
    trip_id: str | None = None,
    details: dict[str, Any] | None = None,
) -> None:
    payload: dict[str, Any] = {
        "severity": "warning",
        "code": code,
        "message": message,
    }
    if line_id is not None:
        payload["line_id"] = line_id
    if trip_id is not None:
        payload["trip_id"] = trip_id
    if details:
        payload["details"] = details
    warnings.append(payload)


def validate_graph(graph: dict[str, Any]) -> list[dict[str, Any]]:
    warnings: list[dict[str, Any]] = []
    trips_by_line: dict[str, list[dict[str, Any]]] = {}
    lines = graph.get("lines") or []
    trips = graph.get("trips") or []

    for trip in trips:
        line_id = str(trip.get("line_id") or "")
        trips_by_line.setdefault(line_id, []).append(trip)

        stop_times = trip.get("stop_times") or []
        if len(stop_times) < 2:
            add_warning(
                warnings,
                code="trip_too_short",
                message="Trip has fewer than two stop_times.",
                line_id=line_id or None,
                trip_id=str(trip.get("trip_id") or "") or None,
            )
            continue

        sequences = [int(stop_time.get("sequence") or 0) for stop_time in stop_times]
        if sequences != list(range(1, len(stop_times) + 1)):
            add_warning(
                warnings,
                code="trip_sequence_gap",
                message="Trip stop_time sequences are not consecutive from 1..N.",
                line_id=line_id or None,
                trip_id=str(trip.get("trip_id") or "") or None,
                details={"sequences": sequences[:20]},
            )

        descending_pairs = [
            (earlier.get("time_minutes"), later.get("time_minutes"))
            for earlier, later in zip(stop_times, stop_times[1:])
            if int(later.get("time_minutes") or 0) < int(earlier.get("time_minutes") or 0)
        ]
        if descending_pairs:
            add_warning(
                warnings,
                code="trip_descending_times",
                message="Trip contains descending stop_times.",
                line_id=line_id or None,
                trip_id=str(trip.get("trip_id") or "") or None,
                details={"descending_pair_count": len(descending_pairs)},
            )

        duplicate_consecutive_stops = sum(
            1
            for current_stop, next_stop in zip(stop_times, stop_times[1:])
            if current_stop.get("graph_stop_key") == next_stop.get("graph_stop_key")
        )
        if duplicate_consecutive_stops:
            add_warning(
                warnings,
                code="trip_duplicate_consecutive_stops",
                message="Trip contains duplicate consecutive stops.",
                line_id=line_id or None,
                trip_id=str(trip.get("trip_id") or "") or None,
                details={"duplicate_count": duplicate_consecutive_stops},
            )

    for line in lines:
        line_id = str(line.get("line_id") or "")
        service_bucket = str(line.get("service_bucket") or "").strip()
        line_stops = line.get("stops") or []

        if not line_stops:
            add_warning(
                warnings,
                code="line_without_stops",
                message="Line has no stops.",
                line_id=line_id or None,
            )

        if not service_bucket:
            add_warning(
                warnings,
                code="line_missing_service_bucket",
                message="Line has an empty service bucket.",
                line_id=line_id or None,
            )
        elif service_bucket not in SERVICE_BUCKETS:
            add_warning(
                warnings,
                code="line_unknown_service_bucket",
                message="Line uses an unknown service bucket.",
                line_id=line_id or None,
                details={"service_bucket": service_bucket},
            )

        valid_from = line.get("valid_from")
        valid_to = line.get("valid_to")
        if valid_from and valid_to and valid_from > valid_to:
            add_warning(
                warnings,
                code="line_invalid_validity",
                message="Line has valid_from after valid_to.",
                line_id=line_id or None,
                details={"valid_from": valid_from, "valid_to": valid_to},
            )

        stop_sequences = [int(stop.get("sequence") or 0) for stop in line_stops]
        if stop_sequences and stop_sequences != list(range(1, len(line_stops) + 1)):
            add_warning(
                warnings,
                code="line_sequence_gap",
                message="Line stop sequences are not consecutive from 1..N.",
                line_id=line_id or None,
                details={"sequences": stop_sequences[:20]},
            )

        duplicate_consecutive_stops = sum(
            1
            for current_stop, next_stop in zip(line_stops, line_stops[1:])
            if current_stop.get("graph_stop_key") == next_stop.get("graph_stop_key")
        )
        if duplicate_consecutive_stops:
            add_warning(
                warnings,
                code="line_duplicate_consecutive_stops",
                message="Line contains duplicate consecutive stops.",
                line_id=line_id or None,
                details={"duplicate_count": duplicate_consecutive_stops},
            )

        if not trips_by_line.get(line_id):
            add_warning(
                warnings,
                code="line_without_trips",
                message="Line has no valid trips.",
                line_id=line_id or None,
            )

    return warnings


def main() -> None:
    parser = argparse.ArgumentParser(description="Validate a normalized transport graph and write a quality report.")
    parser.add_argument("city", nargs="?", default="nitra")
    parser.add_argument(
        "--write",
        action="store_true",
        help="Overwrite the transport_graph_report.json file with validation output.",
    )
    args = parser.parse_args()

    city = load_city(args.city)
    _, graph_file = transport_paths(city)
    if not graph_file.exists():
        raise FileNotFoundError(f"Transport graph file not found: {graph_file}")

    graph = json.loads(graph_file.read_text(encoding="utf-8"))
    validation_warnings = validate_graph(graph)
    report = compute_quality_report(graph, base_metrics=graph.get("quality_report") or {})
    report["validation_warning_count"] = len(validation_warnings)
    report["validation_warnings"] = validation_warnings

    report_file, _ = report_paths(graph_file)
    if args.write:
        report_file.write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")

    print(
        f"Validation summary for {args.city}: "
        f"warnings={report['warnings_count']}, validation_warnings={report['validation_warning_count']}, "
        f"invalid_trips={report['invalid_trip_count']}, unmatched_stops={report['unmatched_stop_count']}, "
        f"coverage_ratio={report['coverage_ratio']}"
    )
    print(f"Report target: {report_file}")


if __name__ == "__main__":
    main()

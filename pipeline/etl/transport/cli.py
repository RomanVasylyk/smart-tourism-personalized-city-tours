from __future__ import annotations

import argparse

from utils.cities import load_city

from .graph import build_processed_graph, compute_quality_report
from .matching import build_osm_stop_index
from .models import TransportIssue, VariantAccumulator
from .parser import add_issue, load_osm_stops, parse_document_variants
from .paths import load_manifest, save_outputs, transport_paths
from .text import load_stop_aliases


def main() -> None:
    parser = argparse.ArgumentParser(
        description="Normalize transport PDFs and OSM stops into a transport graph with warnings and validation reports.",
    )
    parser.add_argument("city", nargs="?", default="nitra")
    args = parser.parse_args()

    city = load_city(args.city)
    raw_dir, processed_file = transport_paths(city)
    stop_aliases = load_stop_aliases(city["slug"])

    manifest = load_manifest(raw_dir)
    issues: list[TransportIssue] = []
    osm_stops = load_osm_stops(raw_dir, city, stop_aliases)
    if not osm_stops:
        add_issue(
            issues,
            code="empty_osm_stop_dataset",
            message="No OSM stops were loaded for the selected city.",
        )
    osm_index = build_osm_stop_index(osm_stops)

    variants: list[VariantAccumulator] = []
    documents = manifest.get("documents", []) or []
    parsed_document_count = 0
    for document in documents:
        filename = document.get("filename")
        source_url = document.get("source_url")
        line_id = str(document.get("line_id") or "")
        document_format = str(document.get("document_format") or "pdf")
        if not filename or not source_url or not line_id:
            add_issue(
                issues,
                code="manifest_document_skipped",
                message="Skipping manifest document with incomplete metadata.",
                document=str(filename or source_url or "<unknown>"),
            )
            continue

        document_path = raw_dir / "timetables" / filename
        if not document_path.exists():
            add_issue(
                issues,
                code="missing_timetable_document",
                message="Skipping missing timetable document referenced by manifest.",
                document=filename,
                line_number=line_id,
            )
            continue

        try:
            parsed_variants = parse_document_variants(
                document_path,
                str(source_url),
                line_id,
                issues,
                document_format=document_format,
            )
        except Exception as exc:  # pragma: no cover - depends on external PDFs
            add_issue(
                issues,
                code="document_parse_failed",
                message="Skipping timetable document because parsing failed unexpectedly.",
                document=filename,
                line_number=line_id,
                error=str(exc),
            )
            continue

        if parsed_variants:
            parsed_document_count += 1
        variants.extend(parsed_variants)

    graph, metrics, unmatched_stop_details = build_processed_graph(
        city,
        variants,
        osm_index,
        stop_aliases,
        issues,
    )
    metrics["source_document_count"] = len(documents)
    metrics["parsed_document_count"] = parsed_document_count
    graph["warnings"] = [issue.to_dict() for issue in issues]
    graph["unmatched_stop_details"] = unmatched_stop_details
    graph["quality_report"] = compute_quality_report(graph, base_metrics=metrics)

    report_file, unmatched_file = save_outputs(processed_file=processed_file, graph=graph)
    report = graph["quality_report"]
    print(
        f"Saved {report['matched_stops']} matched stops, {report['total_lines']} lines, "
        f"{report['total_trips']} trips and {report['total_connections']} connections to {processed_file}"
    )
    print(
        f"Report: warnings={report['warnings_count']}, dropped_trips={report['dropped_trip_count']}, "
        f"unmatched_stops={report['unmatched_stop_count']}, coverage_ratio={report['coverage_ratio']}"
    )
    print(f"Quality report written to {report_file}")
    print(f"Unmatched stops written to {unmatched_file}")

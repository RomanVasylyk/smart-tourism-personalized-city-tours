from __future__ import annotations

from .cli import main
from .constants import SERVICE_BUCKETS
from .graph import (
    build_processed_graph,
    compute_quality_report,
)
from .matching import (
    build_osm_stop_index,
    choose_variant_stop_assignments,
    haversine_km,
    match_provider_stop_candidates,
)
from .models import StopRow, TransportIssue, VariantAccumulator
from .parser import add_issue, load_osm_stops, parse_pdf_variants
from .paths import load_manifest, report_paths, save_outputs, transport_paths
from .text import (
    build_stop_match_keys,
    collapse_zero_delta_duplicate_name_rows,
    load_stop_aliases,
    normalize_stop_name,
    parse_stop_rows,
    split_page_sections,
    split_stop_row_blocks,
)

__all__ = [
    "StopRow",
    "TransportIssue",
    "VariantAccumulator",
    "add_issue",
    "build_osm_stop_index",
    "build_processed_graph",
    "build_stop_match_keys",
    "choose_variant_stop_assignments",
    "collapse_zero_delta_duplicate_name_rows",
    "compute_quality_report",
    "haversine_km",
    "load_manifest",
    "load_osm_stops",
    "load_stop_aliases",
    "main",
    "match_provider_stop_candidates",
    "normalize_stop_name",
    "parse_pdf_variants",
    "parse_stop_rows",
    "report_paths",
    "save_outputs",
    "SERVICE_BUCKETS",
    "split_page_sections",
    "split_stop_row_blocks",
    "transport_paths",
]

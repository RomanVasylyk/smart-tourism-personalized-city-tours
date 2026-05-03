from __future__ import annotations

import json
from pathlib import Path

from .constants import ROOT

def transport_paths(city: dict) -> tuple[Path, Path]:
    transport = city.get("transport") or {}
    raw_subdir = str(transport.get("raw_data_subdir") or f"transport/{city['slug']}/raw")
    processed_path = str(
        transport.get("processed_graph_path")
        or f"transport/{city['slug']}/processed/transport_graph.json"
    )
    raw_dir = ROOT / "data" / raw_subdir.removeprefix("data/")
    processed_file = ROOT / "data" / processed_path.removeprefix("data/")
    processed_file.parent.mkdir(parents=True, exist_ok=True)
    return raw_dir, processed_file

def report_paths(processed_file: Path) -> tuple[Path, Path]:
    report_file = processed_file.with_name(f"{processed_file.stem}_report.json")
    unmatched_file = processed_file.with_name(f"{processed_file.stem}_unmatched_stops.json")
    return report_file, unmatched_file

def load_manifest(raw_dir: Path) -> dict:
    manifest_file = raw_dir / "provider_manifest.json"
    if not manifest_file.exists():
        raise FileNotFoundError(f"Transport manifest not found: {manifest_file}")
    return json.loads(manifest_file.read_text(encoding="utf-8"))

def save_outputs(
    *,
    processed_file: Path,
    graph: dict[str, Any],
) -> tuple[Path, Path]:
    report_file, unmatched_file = report_paths(processed_file)
    processed_file.write_text(
        json.dumps(graph, ensure_ascii=False, indent=2),
        encoding="utf-8",
    )
    report_file.write_text(
        json.dumps(graph.get("quality_report") or {}, ensure_ascii=False, indent=2),
        encoding="utf-8",
    )
    unmatched_file.write_text(
        json.dumps(graph.get("unmatched_stop_details") or [], ensure_ascii=False, indent=2),
        encoding="utf-8",
    )
    return report_file, unmatched_file

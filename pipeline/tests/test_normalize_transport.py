from __future__ import annotations

import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
if str(ROOT) not in sys.path:
    sys.path.insert(0, str(ROOT))

from scripts.normalize_transport import (
    TransportIssue,
    VariantAccumulator,
    build_osm_stop_index,
    build_processed_graph,
    build_stop_match_keys,
    collapse_zero_delta_duplicate_name_rows,
    match_provider_stop_candidates,
    normalize_stop_name,
    parse_stop_rows,
    split_stop_row_blocks,
    split_page_sections,
)

FIXTURES_DIR = Path(__file__).parent / "fixtures" / "transport"


def fixture_text(name: str) -> str:
    return (FIXTURES_DIR / name).read_text(encoding="utf-8")


def make_stop(osm_id: str, name: str, lat: float, lon: float, ref: str | None = None) -> dict:
    return {
        "osm_id": osm_id,
        "name": name,
        "normalized_name": normalize_stop_name(name),
        "lat": lat,
        "lon": lon,
        "ref": ref,
        "platform_token": ref.casefold() if ref else None,
        "distance_to_center_meters": 0.0,
        "match_keys": sorted(build_stop_match_keys(name, ref)),
    }


def test_split_page_sections_parses_workday_and_weekend_sections():
    text = fixture_text("mixed_sections.txt")

    sections = split_page_sections(text)

    assert [bucket for bucket, _ in sections] == ["workdays", "weekends_holidays"]
    assert [len(parse_stop_rows(section_text)) for _, section_text in sections] == [3, 3]


def test_parse_stop_rows_handles_wrapped_stop_name():
    text = fixture_text("wrapped_stop_name.txt")

    rows = parse_stop_rows(text)

    assert [row.name for row in rows] == [
        "Dlhý názov zastávky pokračovanie",
        "Druhá zastávka",
        "Tretia",
    ]


def test_normalize_stop_name_does_not_turn_regular_suffix_into_platform():
    assert normalize_stop_name("Lužianky, ZŠ") == "luzianky zs"
    assert normalize_stop_name("Centrum (A)") == "centrum platform a"
    assert normalize_stop_name("Štúrova A") == "sturova platform a"


def test_match_provider_stop_candidates_uses_alt_keys_and_alias_map():
    osm_index = build_osm_stop_index(
        [
            make_stop("node/1", "CENTRUM, Mlyny", 48.30, 18.08, ref="A"),
            make_stop("node/2", "CENTRUM, Mlyny", 48.30, 18.0805, ref="B"),
            make_stop("node/3", "Centrum (A)", 48.31, 18.09, ref="A"),
        ]
    )

    candidates, matched_by = match_provider_stop_candidates("Nitra, CENTRUM Mlyny", osm_index, {})
    assert matched_by == "exact_alt_multi"
    assert {candidate["osm_id"] for candidate in candidates} == {"node/1", "node/2"}

    candidates, matched_by = match_provider_stop_candidates(
        "Centrum A",
        osm_index,
        {"centrum a": "Centrum (A)"},
    )
    assert matched_by == "exact"
    assert [candidate["osm_id"] for candidate in candidates] == ["node/3"]


def test_build_processed_graph_drops_invalid_trip_but_keeps_valid_line():
    city = {"slug": "test-city", "transport": {"provider": "test_provider"}}
    variant = VariantAccumulator(
        line_number="1",
        service_bucket="workdays",
        source_urls={"https://example.invalid/1.pdf"},
        stop_names=["Stanica", "Centrum", "Nemocnica"],
        edge_samples=[[120.0], [180.0]],
        trip_columns=[
            [310, 312, 318],
            [350, 330, 358],
        ],
        valid_from="2026-01-01",
        valid_to="2026-12-31",
    )
    osm_index = build_osm_stop_index(
        [
            make_stop("node/10", "Stanica", 48.30, 18.08),
            make_stop("node/11", "Centrum", 48.301, 18.081),
            make_stop("node/12", "Nemocnica", 48.302, 18.082),
        ]
    )

    graph, metrics, unmatched = build_processed_graph(
        city,
        [variant],
        osm_index,
        {},
        [],
    )

    assert unmatched == []
    assert len(graph["lines"]) == 1
    assert len(graph["trips"]) == 1
    assert metrics["invalid_trip_count"] == 1
    assert metrics["dropped_trip_count"] == 1
    assert graph["trips"][0]["stop_times"][-1]["time_minutes"] == 318


def test_build_processed_graph_estimates_adjacent_zero_delta_connection():
    city = {"slug": "test-city", "transport": {"provider": "test_provider", "transit_speed_kmh": 24}}
    variant = VariantAccumulator(
        line_number="2",
        service_bucket="workdays",
        source_urls={"https://example.invalid/2.pdf"},
        stop_names=["Kmeťova", "Bizetova", "Centrum"],
        edge_samples=[[], [120.0]],
        trip_columns=[
            [310, 310, 312],
            [370, 370, 372],
        ],
        valid_from="2026-01-01",
        valid_to="2026-12-31",
    )
    osm_index = build_osm_stop_index(
        [
            make_stop("node/20", "Kmeťova", 48.30, 18.08),
            make_stop("node/21", "Bizetova", 48.3004, 18.0805),
            make_stop("node/22", "Centrum", 48.301, 18.081),
        ]
    )

    graph, metrics, unmatched = build_processed_graph(
        city,
        [variant],
        osm_index,
        {},
        [],
    )

    assert unmatched == []
    assert metrics["dropped_trip_count"] == 0
    assert len(graph["connections"]) == 2
    first_connection = graph["connections"][0]
    assert first_connection["from_stop_key"] == "node/20"
    assert first_connection["to_stop_key"] == "node/21"
    assert first_connection["avg_travel_seconds"] > 0


def test_build_processed_graph_estimates_same_station_zero_delta_connection():
    city = {"slug": "test-city", "transport": {"provider": "test_provider", "transit_speed_kmh": 24}}
    variant = VariantAccumulator(
        line_number="2A",
        service_bucket="workdays",
        source_urls={"https://example.invalid/2a.pdf"},
        stop_names=["ZŠ Krškany A", "ZŠ Krškany D", "Centrum"],
        edge_samples=[[], [180.0]],
        trip_columns=[
            [310, 310, 314],
            [370, 370, 374],
        ],
        valid_from="2026-01-01",
        valid_to="2026-12-31",
    )
    osm_index = build_osm_stop_index(
        [
            make_stop("node/30", "ZŠ Krškany", 48.273539, 18.0990411, ref="A"),
            make_stop("node/31", "ZŠ Krškany", 48.2727715, 18.0988376, ref="D"),
            make_stop("node/32", "Centrum", 48.301, 18.081),
        ]
    )
    issues: list[TransportIssue] = []

    graph, metrics, unmatched = build_processed_graph(
        city,
        [variant],
        osm_index,
        {},
        issues,
    )

    assert unmatched == []
    assert metrics["dropped_trip_count"] == 0
    assert len(graph["connections"]) == 2
    assert graph["connections"][0]["from_stop_key"] == "node/30"
    assert graph["connections"][0]["to_stop_key"] == "node/31"
    assert graph["connections"][0]["avg_travel_seconds"] > 0
    assert not any(issue.code == "connection_without_edge_samples" for issue in issues)


def test_build_processed_graph_estimates_same_station_connection_without_trip_overlap():
    city = {"slug": "test-city", "transport": {"provider": "test_provider", "transit_speed_kmh": 24}}
    variant = VariantAccumulator(
        line_number="2B",
        service_bucket="workdays",
        source_urls={"https://example.invalid/2b.pdf"},
        stop_names=["ZŠ Krškany A", "ZŠ Krškany D", "Centrum"],
        edge_samples=[[], [180.0]],
        trip_columns=[
            [310, None, 314],
            [None, 370, 374],
        ],
        valid_from="2026-01-01",
        valid_to="2026-12-31",
    )
    osm_index = build_osm_stop_index(
        [
            make_stop("node/40", "ZŠ Krškany", 48.273539, 18.0990411, ref="A"),
            make_stop("node/41", "ZŠ Krškany", 48.2727715, 18.0988376, ref="D"),
            make_stop("node/42", "Centrum", 48.301, 18.081),
        ]
    )
    issues: list[TransportIssue] = []

    graph, metrics, unmatched = build_processed_graph(
        city,
        [variant],
        osm_index,
        {},
        issues,
    )

    assert unmatched == []
    assert metrics["dropped_trip_count"] == 0
    assert len(graph["connections"]) == 2
    assert graph["connections"][0]["from_stop_key"] == "node/40"
    assert graph["connections"][0]["to_stop_key"] == "node/41"
    assert graph["connections"][0]["avg_travel_seconds"] > 0
    assert not any(issue.code == "connection_without_edge_samples" for issue in issues)


def test_partial_invalid_trip_fixture_produces_one_descending_warning():
    text = fixture_text("partial_invalid_trip.txt")
    rows = parse_stop_rows(text)
    assert len(rows) == 3

    issues: list[TransportIssue] = []
    # Simulate the per-section descending-trip detection contract indirectly:
    column_1 = [row.times[0] for row in rows]
    column_2 = [row.times[1] for row in rows]
    assert column_1 == [310, 312, 318]
    assert column_2 == [350, 330, 358]
    assert any(later < earlier for earlier, later in zip(column_2, column_2[1:]))
    assert issues == []


def test_parse_stop_rows_ignores_footnote_tail_without_real_times():
    text = "\n".join(
        [
            "D Partizánska prích",
            "+ premáva v nedeľu a v štátom uznaný deň pracovného pokoja 6 premáva v sobotu",
            "D SEC prích",
            "p spoj 2,6,34,52,56 zo zast. Jakuba Haška pokračuje na zast. SEC",
        ]
    )

    rows = parse_stop_rows(text)

    assert rows == []


def test_split_stop_row_blocks_breaks_on_strong_time_regression():
    text = "\n".join(
        [
            "D Stop A 05 10 05 40",
            "D Stop B 05 12 05 42",
            "D Stop C 05 18 05 48",
            "D Delta 04 50 05 20",
            "D Echo 04 52 05 22",
            "D Foxtrot 04 58 05 28",
        ]
    )

    blocks = split_stop_row_blocks(parse_stop_rows(text))

    assert [[row.name for row in block] for block in blocks] == [
        ["Stop A", "Stop B", "Stop C"],
        ["Delta", "Echo", "Foxtrot"],
    ]


def test_collapse_zero_delta_duplicate_name_rows_removes_arrival_departure_duplicate():
    rows = parse_stop_rows(
        "\n".join(
            [
                "D Rázcestie Autobusová stanica prích 05 46 06 16",
                "D Rázcestie Autobusová stanica odch 05 46 06 16",
                "D CENTRUM, Mlyny 05 49 06 19",
            ]
        )
    )

    collapsed_rows = collapse_zero_delta_duplicate_name_rows(rows)

    assert [row.name for row in collapsed_rows] == [
        "Rázcestie Autobusová stanica",
        "CENTRUM , Mlyny",
    ]

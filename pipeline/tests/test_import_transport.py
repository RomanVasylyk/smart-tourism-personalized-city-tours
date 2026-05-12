from __future__ import annotations

import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
if str(ROOT) not in sys.path:
    sys.path.insert(0, str(ROOT))

from scripts.import_transport import (
    build_stop_query,
    discover_imhd_direction_documents,
    discover_imhd_line_links,
    stop_query_areas,
)


def test_stop_query_areas_prefers_transport_specific_areas():
    city = {
        "slug": "nitra",
        "bbox": {"south": 1, "west": 2, "north": 3, "east": 4},
        "transport": {
            "stop_query_areas": [
                {"south": 10, "west": 20, "north": 30, "east": 40},
                {"south": 11, "west": 21, "north": 31, "east": 41},
            ]
        },
    }

    assert stop_query_areas(city) == city["transport"]["stop_query_areas"]


def test_build_stop_query_includes_all_transport_query_areas():
    city = {
        "slug": "nitra",
        "bbox": {"south": 1, "west": 2, "north": 3, "east": 4},
        "transport": {
            "stop_query_areas": [
                {"south": 10, "west": 20, "north": 30, "east": 40},
                {"south": 11, "west": 21, "north": 31, "east": 41},
            ]
        },
    }

    query = build_stop_query(city)

    assert '(10.0,20.0,30.0,40.0);' in query
    assert '(11.0,21.0,31.0,41.0);' in query
    assert query.count('node["highway"="bus_stop"]') == 2
    assert query.count('way["public_transport"="platform"]') == 2


def test_discover_imhd_line_links_extracts_unique_line_pages():
    html = """
    <html><body>
      <a href="/tt/linka/1/hash-1">1</a>
      <a href="/tt/linka/2/hash-2">2</a>
      <a href="/tt/linka/1/hash-1">1</a>
      <a href="/nr/linka/3/hash-3">3</a>
    </body></html>
    """

    documents = discover_imhd_line_links(html, "https://imhd.sk/tt/cestovne-poriadky", "tt")

    assert [document["line_id"] for document in documents] == ["1", "2"]
    assert documents[0]["source_url"] == "https://imhd.sk/tt/linka/1/hash-1"


def test_discover_imhd_line_links_honors_allowlist():
    html = """
    <html><body>
      <a href="/tt/linka/1/hash-1">1</a>
      <a href="/tt/linka/555/hash-555">555</a>
      <a href="/tt/linka/N1/hash-n1">N1</a>
    </body></html>
    """

    documents = discover_imhd_line_links(
        html,
        "https://imhd.sk/tt/cestovne-poriadky",
        "tt",
        allowed_line_ids={"1", "N1"},
    )

    assert [document["line_id"] for document in documents] == ["1", "N1"]


def test_discover_imhd_direction_documents_selects_first_stop_schedule_per_direction():
    html = """
    <section class="Timetable">
      <table class="table table-hover table-borderless table-striped table-sm">
        <tr><td><a href="/tt/cestovny-poriadok/linka/1/Letisko/smer-BOGE/hash-a">Letisko</a></td></tr>
        <tr><td><a href="/tt/cestovny-poriadok/linka/1/Lincianska/smer-BOGE/hash-b">Lincianska</a></td></tr>
        <tr><td><a href="/tt/cestovny-poriadok/linka/1/BOGE/smer-BOGE/hash-c">BOGE</a></td></tr>
      </table>
      <table class="table table-hover table-borderless table-striped table-sm">
        <tr><td><a href="/tt/cestovny-poriadok/linka/1/BOGE/smer-Letisko/hash-d">BOGE</a></td></tr>
        <tr><td><a href="/tt/cestovny-poriadok/linka/1/Letisko/smer-Letisko/hash-e">Letisko</a></td></tr>
      </table>
      <table class="table table-hover table-borderless table-striped table-sm d-none">
        <tr><td><a href="/tt/cestovny-poriadok/linka/1/hidden/smer-x/hash-hidden">Hidden</a></td></tr>
      </table>
    </section>
    """

    documents = discover_imhd_direction_documents(
        html,
        "https://imhd.sk/tt/linka/1/hash-line",
        "tt",
        "1",
    )

    assert len(documents) == 2
    assert documents[0]["origin_stop_name"] == "Letisko"
    assert documents[0]["destination_stop_name"] == "BOGE"
    assert documents[0]["source_url"] == "https://imhd.sk/tt/cestovny-poriadok/linka/1/Letisko/smer-BOGE/hash-a"
    assert documents[0]["filename"] == "1_01.html"
    assert documents[1]["origin_stop_name"] == "BOGE"

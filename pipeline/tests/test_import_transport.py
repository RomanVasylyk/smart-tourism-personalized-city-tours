from __future__ import annotations

import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
if str(ROOT) not in sys.path:
    sys.path.insert(0, str(ROOT))

from scripts.import_transport import build_stop_query, stop_query_areas


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

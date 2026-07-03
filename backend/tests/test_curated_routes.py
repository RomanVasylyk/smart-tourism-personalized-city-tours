from conftest import FakeRoutingService


def _route_row():
    return {
        "id": 1,
        "slug": "nitra-historic-core",
        "title": "Historické jadro Nitry",
        "summary": "Prechádzka po najvýznamnejších pamiatkach Nitry.",
        "theme": "history",
        "recommended_duration_min": 120,
        "pace": "normal",
        "transport_mode": "walk",
        "return_to_start": True,
        "start_lat": 48.3076,
        "start_lon": 18.0845,
        "city_name": "Nitra",
    }


def _poi_rows():
    return [
        {
            "id": 10,
            "name": "Nitriansky hrad",
            "category": "historical_site",
            "lat": 48.3172,
            "lon": 18.0861,
            "opening_hours_raw": None,
            "visit_duration_min": 30,
            "base_score": 0.95,
            "short_description": "Castle complex above Nitra.",
            "wikipedia_url": "https://sk.wikipedia.org/wiki/Nitriansky_hrad",
            "visit_order": 1,
            "override_visit_duration_min": None,
            "note": None,
        },
        {
            "id": 11,
            "name": "Pribinovo námestie",
            "category": "monument",
            "lat": 48.3088,
            "lon": 18.0870,
            "opening_hours_raw": None,
            "visit_duration_min": 15,
            "base_score": 0.7,
            "short_description": None,
            "wikipedia_url": None,
            "visit_order": 2,
            "override_visit_duration_min": 20,
            "note": "Sochy a fontána.",
        },
    ]


def test_list_curated_routes_returns_summaries(client, monkeypatch):
    rows = [
        {
            **_route_row(),
            "poi_count": 2,
            "stop_names": ["Nitriansky hrad", "Pribinovo námestie"],
        }
    ]
    monkeypatch.setattr("app.services.curated_routes.list_curated_route_rows", lambda city: rows)

    response = client.get("/cities/nitra/curated-routes")
    body = response.json()

    assert response.status_code == 200
    assert len(body) == 1
    assert body[0]["id"] == 1
    assert body[0]["title"] == "Historické jadro Nitry"
    assert body[0]["theme"] == "history"
    assert body[0]["poi_count"] == 2
    assert body[0]["stop_names"] == ["Nitriansky hrad", "Pribinovo námestie"]
    assert body[0]["transport_mode"] == "walk"


def test_get_curated_route_materializes_schedule(client, monkeypatch):
    monkeypatch.setattr("app.services.curated_routes.get_curated_route_row", lambda route_id: _route_row())
    monkeypatch.setattr("app.services.curated_routes.list_curated_route_poi_rows", lambda route_id: _poi_rows())
    monkeypatch.setattr("app.services.curated_routes.get_routing_service", lambda: FakeRoutingService())

    response = client.get("/curated-routes/1?start_datetime=2026-04-19T10:00")
    body = response.json()

    assert response.status_code == 200
    assert body["title"] == "Historické jadro Nitry"
    assert body["poi_count"] == 2

    route = body["route"]
    assert route["poi_count"] == 2
    assert route["respect_opening_hours"] is False
    assert route["return_to_start"] is True
    assert route["route"][0]["poi_id"] == 10
    assert route["route"][0]["visit_duration_min"] == 30
    # Per-stop override from curated_route_pois wins over the POI default.
    assert route["route"][1]["visit_duration_min"] == 20
    # Two stops plus the return-to-start leg.
    assert len(route["legs"]) == 3
    assert route["start_datetime"] == "2026-04-19T10:00"
    assert route["used_minutes"] <= route["available_minutes"]


def test_get_curated_route_returns_404_when_missing(client, monkeypatch):
    monkeypatch.setattr("app.services.curated_routes.get_curated_route_row", lambda route_id: None)

    response = client.get("/curated-routes/999")

    assert response.status_code == 404


def test_get_curated_route_returns_409_when_no_active_pois(client, monkeypatch):
    monkeypatch.setattr("app.services.curated_routes.get_curated_route_row", lambda route_id: _route_row())
    monkeypatch.setattr("app.services.curated_routes.list_curated_route_poi_rows", lambda route_id: [])

    response = client.get("/curated-routes/1")

    assert response.status_code == 409

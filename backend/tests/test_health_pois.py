from conftest import FakeConnection


def test_health_returns_ok(client):
    response = client.get("/health")

    assert response.status_code == 200
    assert response.json() == {"status": "ok"}


def test_openapi_declares_explicit_response_contracts(client):
    response = client.get("/openapi.json")

    assert response.status_code == 200
    openapi = response.json()
    schemas = openapi["components"]["schemas"]

    for schema_name in (
        "CityResponse",
        "PoiResponse",
        "RouteResponse",
        "RouteSessionResponse",
        "RouteSnapshotResponse",
        "PlannerScoreBreakdownResponse",
    ):
        assert schema_name in schemas

    cities_response = openapi["paths"]["/cities"]["get"]["responses"]["200"]
    assert (
        cities_response["content"]["application/json"]["schema"]["items"]["$ref"] == "#/components/schemas/CityResponse"
    )

    route_response = openapi["paths"]["/route/generate"]["post"]["responses"]["200"]
    assert route_response["content"]["application/json"]["schema"]["$ref"] == "#/components/schemas/RouteResponse"

    session_response = openapi["paths"]["/route-sessions/{session_id}"]["get"]["responses"]["200"]
    assert (
        session_response["content"]["application/json"]["schema"]["$ref"] == "#/components/schemas/RouteSessionResponse"
    )

    route_snapshot_schema = schemas["RouteSessionResponse"]["properties"]["route_snapshot_json"]["anyOf"][0]
    assert route_snapshot_schema["$ref"] == "#/components/schemas/RouteSnapshotResponse"


def test_get_pois_returns_database_rows(client, monkeypatch):
    rows = [
        {
            "id": 1,
            "name": "Ponitrianske muzeum",
            "category": "museum",
            "lat": 48.3132523,
            "lon": 18.0881342,
            "address": "Štefánikova trieda 1, Nitra",
            "opening_hours_raw": None,
            "opening_hours_source": None,
            "visit_duration_min": 60,
            "base_score": 0.9,
            "short_description": "Regional museum in Nitra.",
            "website": "https://www.muzeumnitra.sk",
            "image_url": None,
            "wikipedia_url": "https://sk.wikipedia.org/wiki/Ponitrianske_muzeum",
        }
    ]

    monkeypatch.setattr("app.repositories.pois.get_connection", lambda: FakeConnection(rows))

    response = client.get("/pois", params={"city": "nitra"})

    assert response.status_code == 200
    assert response.json() == rows

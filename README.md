# Smart Tourism

A planner for personalized city walks: the user gives a time budget, interests,
start point and pace, and the backend returns a round trip over points of interest
that respects opening hours and optional public transport (MHD). Ready-made
"verified tours" and route ratings round out the recommendations.

## Repository structure

- `android-app/` — native Android app (Kotlin + Jetpack Compose)
- `backend/` — FastAPI backend (route planning, sessions, feedback, curated routes)
- `pipeline/` — ETL scripts (OSM POI, Wikidata/Wikipedia, MHD graph, curated routes)
- `routing-data/` — prepared OSRM data
- `docs/` — [docs](docs/architecture.md)

Configured pilot cities: `nitra`, `trnava`, `nove-zamky`, `trencin`, `zilina`.

## Prerequisites

- Docker + Docker Compose
- Python 3.12+ (to run ETL and tests outside the container)
- For the app: JDK 17 and the Android SDK

## Quick start (Docker)

```bash
cp .env.example .env
docker compose up -d --build
```

The backend runs `alembic upgrade head` on startup. OSRM needs prepared data in
`routing-data/` (see "Rebuild OSRM routing data"). Then load city data:

```bash
docker compose up -d db
python3 -m pip install -r pipeline/requirements.txt
python3 pipeline/scripts/load_to_db.py --all-cities
for city in nitra trnava nove-zamky trencin zilina; do
  python3 pipeline/scripts/load_transport_to_db.py "$city"
done
python3 pipeline/scripts/load_curated_routes.py --all-cities
```

| Service | Container | Port (default) |
|---|---|---|
| PostGIS | `smart-tourism-db` | `${POSTGRES_PORT}` = 5432 |
| OSRM | `smart-tourism-routing` | `${ROUTING_PORT}` = 5000 |
| Backend (FastAPI) | `smart-tourism-backend` | `${BACKEND_PORT}` = 8000 |
| Feedback scheduler | `smart-tourism-scheduler` | — |

## Database migrations

Applied automatically when the backend starts. To run manually:

```bash
docker compose exec backend python -m alembic upgrade head
```

## Data pipeline (ETL)

ETL is parameterized by city slug. The load scripts connect through `DATABASE_URL`
(default `postgresql://smart_tourism:smart_tourism@localhost:5432/smart_tourism`),
so start the DB first: `docker compose up -d db`.

```bash
python3 -m pip install -r pipeline/requirements.txt
```

POI ETL (one city, then loop for all):

```bash
python3 pipeline/scripts/import_osm.py nitra
python3 pipeline/scripts/normalize_categories.py nitra
python3 pipeline/scripts/enrich_pois.py nitra            # or --all-cities
python3 pipeline/scripts/load_to_db.py nitra             # or --all-cities
```

Transport (MHD) ETL:

```bash
python3 pipeline/scripts/import_transport.py nitra
python3 pipeline/scripts/normalize_transport.py nitra
python3 pipeline/scripts/validate_transport_graph.py nitra
python3 pipeline/scripts/load_transport_to_db.py nitra
```

Curated routes (defined in `pipeline/config/curated_routes.yaml`; resolves POIs by
`osm_type`/`osm_id`, so load POIs first):

```bash
python3 pipeline/scripts/load_curated_routes.py --all-cities
```

More detail: [pipeline/README.md](pipeline/README.md).

## Rebuild OSRM routing data

`routing-data/` (gitignored, ~1.9 GB) is mounted into the OSRM container, which
runs `osrm-routed --algorithm mld`, so the data needs the MLD pipeline. Use the
image pinned in `.env` (`OSRM_IMAGE`).

```bash
cd routing-data
curl -L -o slovakia-latest.osm.pbf \
  https://download.geofabrik.de/europe/slovakia-latest.osm.pbf
docker run --rm -v "$PWD:/data" osrm/osrm-backend:v5.27.1 \
  osrm-extract -p /opt/foot.lua /data/slovakia-latest.osm.pbf
docker run --rm -v "$PWD:/data" osrm/osrm-backend:v5.27.1 \
  osrm-partition /data/slovakia-latest.osrm
docker run --rm -v "$PWD:/data" osrm/osrm-backend:v5.27.1 \
  osrm-customize /data/slovakia-latest.osrm
```

The base name (`slovakia-latest.osrm`) must match `OSRM_DATA_FILE` in `.env`.

## Feedback recompute

The `scheduler` container recomputes feedback aggregates on an interval
(`FEEDBACK_RECOMPUTE_INTERVAL_SECONDS`, default hourly). To run once manually:

```bash
docker compose exec backend python scripts/recompute_feedback_scores.py   # or --city nitra
```

## Tests and quality checks

Python (backend + pipeline):

```bash
python3 -m pip install -r backend/requirements.txt -r pipeline/requirements.txt -r requirements-dev.txt
python3 -m pytest backend/tests pipeline/tests
python3 -m ruff check backend pipeline
python3 -m black --check backend pipeline
```

Pre-commit and Kotlin lint:

```bash
brew install ktlint
pre-commit install
pre-commit run --all-files
```

Android unit tests:

```bash
cd android-app
./gradlew testDebugUnitTest
```

## Android app

Requires JDK 17 and the Android SDK (set `sdk.dir` in `android-app/local.properties`).
The debug build points at `http://127.0.0.1:8000/`, so forward the backend port to
the device/emulator:

```bash
cd android-app
./gradlew installDebug
adb reverse tcp:8000 tcp:8000   # device/emulator 127.0.0.1:8000 -> host backend
```

## API

The backend serves its own specification: OpenAPI at
`http://localhost:8000/openapi.json` and Swagger UI at `http://localhost:8000/docs`.

```bash
curl http://localhost:8000/health
curl "http://localhost:8000/cities"
curl "http://localhost:8000/cities/nove-zamky/curated-routes"
```

## Configuration

All settings come from `.env` (see [.env.example](.env.example)) — Postgres/OSRM
images and ports, `DATABASE_URL`, routing (`ROUTING_*`, `ROUTING_TABLE_ENABLED`),
rate limits (`RATE_LIMIT_*`), and the feedback scheduler (`FEEDBACK_RECOMPUTE_*`).
`.env` is gitignored; the example uses dev-only credentials.

# Smart Tourism

Planner for personalized city walks: given a time budget, interests, start point
and pace, the backend returns a round trip over points of interest that respects
opening hours and optional public transport (MHD). Plus ready-made curated tours
and route ratings.

- `android-app/` — Android app (Kotlin + Jetpack Compose)
- `backend/` — FastAPI backend (planning, sessions, feedback, curated routes)
- `pipeline/` — ETL (OSM POIs, Wikidata/Wikipedia, MHD graph, curated routes)
- `routing-data/` — prepared OSRM data
- `docs/architecture.md` — architecture

Pilot cities: `nitra`, `trnava`, `nove-zamky`, `trencin`, `zilina`.

## Run

Requires Docker + Docker Compose and Python 3.12+ (for the ETL).

```bash
cp .env.example .env
docker compose up -d --build
```

Then load city data into the DB:

```bash
python3 -m pip install -r pipeline/requirements.txt
python3 pipeline/scripts/load_to_db.py --all-cities
for city in nitra trnava nove-zamky trencin zilina; do
  python3 pipeline/scripts/load_transport_to_db.py "$city"
done
python3 pipeline/scripts/load_curated_routes.py --all-cities
```

Check it works:

```bash
curl http://localhost:8000/health
curl http://localhost:8000/cities
```

Swagger UI: http://localhost:8000/docs

| Service | Container | Port |
|---|---|---|
| PostGIS | `smart-tourism-db` | 5432 |
| OSRM | `smart-tourism-routing` | 5000 |
| Backend | `smart-tourism-backend` | 8000 |
| Scheduler | `smart-tourism-scheduler` | — |

## Android app

Requires JDK 17 and the Android SDK (set `sdk.dir` in `android-app/local.properties`).
The debug build targets `http://127.0.0.1:8000/`:

```bash
cd android-app
./gradlew installDebug
adb reverse tcp:8000 tcp:8000
```

## Tests

```bash
python3 -m pip install -r backend/requirements.txt -r pipeline/requirements.txt -r requirements-dev.txt
python3 -m pytest backend/tests pipeline/tests
cd android-app && ./gradlew testDebugUnitTest
```

## Data & OSRM

Full ETL (refresh POIs/transport from source): [pipeline/README.md](pipeline/README.md).

`OSRM_IMAGE` in `.env` is pinned by digest to OSRM **5.26.0** — the version that
built `routing-data/`. `osrm-routed` rejects data from a different major/minor. If
you change the version, rebuild the data with that same image:

```bash
cd routing-data
set -a; source ../.env; set +a
curl -L -o slovakia-latest.osm.pbf https://download.geofabrik.de/europe/slovakia-latest.osm.pbf
docker run --rm -v "$PWD:/data" "$OSRM_IMAGE" osrm-extract -p /opt/foot.lua /data/slovakia-latest.osm.pbf
docker run --rm -v "$PWD:/data" "$OSRM_IMAGE" osrm-partition /data/slovakia-latest.osrm
docker run --rm -v "$PWD:/data" "$OSRM_IMAGE" osrm-customize /data/slovakia-latest.osrm
```

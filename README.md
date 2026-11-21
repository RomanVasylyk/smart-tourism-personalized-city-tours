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

Requires Docker + Docker Compose. The terminal is needed only to start the project and to create
the first administrator; everything else is done in the admin panel.

```bash
cp .env.example .env
docker compose up -d --build
docker compose exec backend python scripts/create_admin.py admin@example.com --name "Admin"
```

Then open http://localhost:8000/admin, go to *Dátová pipeline → Kontrola a schválenie* and for every city
download or process the data, check it, approve it and load it into the database (see *Administration* below).
The same steps can still be scripted from the command line, see [pipeline/README.md](pipeline/README.md).

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

## Administration

The admin panel runs at http://localhost:8000/admin. Create the first administrator
(the command asks for the password twice; running it for an existing e-mail resets the password and grants admin rights):

```bash
docker compose exec backend python scripts/create_admin.py admin@example.com --name "Admin"
```

From the panel the administrator can edit cities, points of interest, curated routes, public holidays,
public-transport lines, stops and trips, users and their sign-ins, visitor routes and feedback.
An opening-hours override entered for a POI takes precedence over the OSM value. A POI without any opening hours
is treated as open all day. On an active public holiday, the planner uses the weekend-and-holiday
public-transport timetable. Statutory Slovak holidays for the current and the next year are added
automatically; uncheck *Platí* instead of deleting a day that should not apply.

Public-transport trips keep the day type of their imhd.sk timetable: workdays, school-year workdays,
school-holiday workdays, summer-holiday variants, days off, Saturdays, Sundays and holidays, seasonal
weekends and night services. On a workday inside a school holiday of the trip's city the planner uses
the school-holiday timetable. School holidays for 2026/2027 and 2027/2028 are added from the Ministry
of Education calendar (spring holidays per region) and can be edited under *Kalendár → Školské prázdniny*.

| Variable | Default | Meaning |
|---|---|---|
| `RATE_LIMIT_AUTH` | `10/minute` | Limit for registration, sign-in and admin sign-in per client |
| `ADMIN_COOKIE_SECURE` | `false` | Set to `true` when the panel is served over HTTPS |

The whole data pipeline is operated from the panel. *Dátová pipeline → Kontrola a schválenie* starts background
jobs with a live log (download POIs from OpenStreetMap, download timetables from imhd.sk, process them again,
load curated routes; one job runs at a time) and opens the data viewers:

- the POI viewer filters errors, warnings, excluded and edited POIs; each POI can be included, excluded or kept
  despite an error (for example a park just outside the city boundary), and its fields can be corrected. The
  decisions are stored in `<city>_pois_decisions.json` and are applied again after every new download;
- the line viewer lists the processed timetable variants with their day type and source;
- processed, reviewed and report files can be downloaded as JSON, and a corrected POI file can be uploaded;
- *Nastavenia mesta* edits the city boundary, centre, categories and planning limits, and the raw
  `cities.yaml` can be edited for new cities (a backup is kept as `cities.backup.yaml`).

Processed pipeline data reaches the database only after an administrator approves it. In the panel,
*Dátová pipeline → Kontrola a schválenie* shows for every city the validation report of the transport graph
and the POI review, a comparison of trips per day type with the database, and who approved and loaded which
version. *Schváliť* signs the current file with the administrator's name (the approval is tied to the file
checksum), *Načítať do databázy* loads it and refreshes the planner. Every load is recorded in
*História importov*. POIs edited by hand in the panel are marked *Chrániť pred importom* and a later import
does not overwrite them. The backend reads the pipeline folder through the `./pipeline:/pipeline` volume.

Visitors can register and sign in from the app (account icon in the top bar). No e-mail confirmation is needed.
Signing in links the routes already recorded on that device to the account.

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

# Pipeline

ETL scripts are parameterized by city slug from `pipeline/config/cities.yaml`.

Configured city slugs:

- `nitra`
- `trnava`
- `nove-zamky`
- `trencin`
- `zilina`

## Setup

Install the pipeline dependencies:

```bash
python3 -m pip install -r pipeline/requirements.txt
```

The load scripts connect to Postgres through `DATABASE_URL`. If it is not set,
they use:

```bash
postgresql://smart_tourism:smart_tourism@localhost:5432/smart_tourism
```

When using the local Docker stack, start the database before loading data:

```bash
docker compose up -d db
```

## POI ETL

Run the POI import, normalization, and database load for one city:

```bash
python3 pipeline/scripts/import_osm.py nitra
python3 pipeline/scripts/normalize_categories.py nitra
python3 pipeline/scripts/enrich_pois.py nitra
python3 pipeline/scripts/load_to_db.py nitra
```

Run it for every configured city:

```bash
for city in nitra trnava nove-zamky trencin zilina; do
  python3 pipeline/scripts/import_osm.py "$city"
  python3 pipeline/scripts/normalize_categories.py "$city"
done

python3 pipeline/scripts/enrich_pois.py --all-cities

python3 pipeline/scripts/load_to_db.py --all-cities
```

The enrichment step fills `short_description`, `wikipedia_title`, and
`wikipedia_url` from Wikipedia/Wikidata. It uses a local cache at
`pipeline/data/cache/wiki_summaries.json`; set `WIKIMEDIA_USER_AGENT` when
running it outside local development. Pass `--languages sk,en,cs,de,pl,hu` to
override the preferred Wikipedia/Wikidata language order.

## Curated routes

Hand-curated, verified city tours ("hotové okruhy") are defined in
`pipeline/config/curated_routes.yaml`, keyed by city slug. Each route lists its
stops in visit order; stops are resolved against POIs already loaded in the
database (by `osm_type`/`osm_id`, or by exact name as a fallback), so run the POI
load first.

```bash
python3 pipeline/scripts/load_curated_routes.py nove-zamky
python3 pipeline/scripts/load_curated_routes.py --all-cities
```

The backend exposes them through `GET /cities/{slug}/curated-routes` (catalog)
and `GET /curated-routes/{id}` (full schedule and geometry, materialized through
the same route timeline builder used by the planner).

## Transport ETL

Transport data is configured under each city's `transport` section in
`pipeline/config/cities.yaml`. The current transport source is `imhd.sk` for all
configured cities, including Nitra (`https://imhd.sk/nr/cestovne-poriadky`).

Run the transport import, graph normalization, validation, and database load for
one city:

```bash
python3 pipeline/scripts/import_transport.py nitra
python3 pipeline/scripts/normalize_transport.py nitra
python3 pipeline/scripts/validate_transport_graph.py nitra
python3 pipeline/scripts/load_transport_to_db.py nitra
```

Run it for every configured city:

```bash
for city in nitra trnava nove-zamky trencin zilina; do
  python3 pipeline/scripts/import_transport.py "$city"
  python3 pipeline/scripts/normalize_transport.py "$city"
  python3 pipeline/scripts/validate_transport_graph.py "$city"
  python3 pipeline/scripts/load_transport_to_db.py "$city"
done
```

## Rebuild Local Data After Container Reset

If the Docker volume was recreated or the database is empty, rebuild and load all
city data in this order:

```bash
docker compose up -d db

python3 pipeline/scripts/enrich_pois.py --all-cities

python3 pipeline/scripts/load_to_db.py --all-cities

for city in nitra trnava nove-zamky trencin zilina; do
  python3 pipeline/scripts/load_transport_to_db.py "$city"
done
```

If the raw source data should be refreshed first, run the POI and transport ETL
sections before the load commands.

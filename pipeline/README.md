# Pipeline

ETL flow is parameterized by city slug from `pipeline/config/cities.yaml`.

Run for a city:
1. `python pipeline/scripts/import_osm.py nitra`
2. `python pipeline/scripts/normalize_categories.py nitra`
3. `python pipeline/scripts/load_to_db.py nitra`

Example for the second pilot city:
1. `python pipeline/scripts/import_osm.py trnava`
2. `python pipeline/scripts/normalize_categories.py trnava`
3. `python pipeline/scripts/load_to_db.py trnava`

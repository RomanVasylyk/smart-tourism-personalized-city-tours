# Pipeline

ETL flow for one city:
1. `import_osm.py` – fetch raw POI data from OSM / Overpass
2. `normalize_categories.py` – convert OSM tags to project categories
3. `load_to_db.py` – insert prepared POIs into PostgreSQL + PostGIS


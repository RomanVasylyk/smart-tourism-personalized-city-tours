CREATE EXTENSION IF NOT EXISTS postgis;

CREATE TABLE IF NOT EXISTS cities (
    id SERIAL PRIMARY KEY,
    name TEXT NOT NULL,
    country TEXT NOT NULL,
    center_lat DOUBLE PRECISION,
    center_lon DOUBLE PRECISION,
    boundary_geom geometry(MultiPolygon, 4326)
);

CREATE TABLE IF NOT EXISTS pois (
    id SERIAL PRIMARY KEY,
    city_id INTEGER NOT NULL REFERENCES cities(id),
    osm_id TEXT NOT NULL,
    osm_type TEXT NOT NULL,
    name TEXT,
    category TEXT NOT NULL,
    subcategory TEXT,
    lat DOUBLE PRECISION NOT NULL,
    lon DOUBLE PRECISION NOT NULL,
    geom geometry(Point, 4326) NOT NULL,
    address TEXT,
    opening_hours_raw TEXT,
    visit_duration_min INTEGER,
    base_score DOUBLE PRECISION,
    wikidata_id TEXT,
    wikipedia_title TEXT,
    wikipedia_url TEXT,
    short_description TEXT,
    source TEXT NOT NULL DEFAULT 'osm',
    is_active BOOLEAN NOT NULL DEFAULT TRUE
);

CREATE INDEX IF NOT EXISTS idx_pois_city_id ON pois(city_id);
CREATE INDEX IF NOT EXISTS idx_pois_category ON pois(category);
CREATE INDEX IF NOT EXISTS idx_pois_geom ON pois USING GIST (geom);

INSERT INTO cities (name, country, center_lat, center_lon)
VALUES ('Nitra', 'Slovakia', 48.3076, 18.0845)
ON CONFLICT DO NOTHING;
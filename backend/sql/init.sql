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

CREATE TABLE IF NOT EXISTS route_sessions (
    id UUID PRIMARY KEY,
    device_id TEXT NOT NULL,
    city_id INTEGER NOT NULL REFERENCES cities(id),
    status TEXT NOT NULL CHECK (status IN ('not_started', 'in_progress', 'paused', 'completed', 'cancelled')),
    start_lat DOUBLE PRECISION NOT NULL,
    start_lon DOUBLE PRECISION NOT NULL,
    available_minutes INTEGER NOT NULL,
    pace TEXT NOT NULL,
    return_to_start BOOLEAN NOT NULL,
    opening_hours_enabled BOOLEAN NOT NULL,
    started_at TIMESTAMPTZ NOT NULL,
    finished_at TIMESTAMPTZ,
    used_minutes INTEGER,
    total_walk_minutes INTEGER,
    total_visit_minutes INTEGER,
    route_snapshot_json JSONB NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE TABLE IF NOT EXISTS route_session_pois (
    id BIGSERIAL PRIMARY KEY,
    session_id UUID NOT NULL REFERENCES route_sessions(id) ON DELETE CASCADE,
    poi_id INTEGER NOT NULL REFERENCES pois(id),
    visit_order INTEGER NOT NULL,
    planned_arrival_min INTEGER,
    planned_departure_min INTEGER,
    visited BOOLEAN NOT NULL DEFAULT FALSE,
    visited_at TIMESTAMPTZ,
    skipped BOOLEAN NOT NULL DEFAULT FALSE,
    UNIQUE (session_id, poi_id)
);

CREATE TABLE IF NOT EXISTS route_feedback (
    id BIGSERIAL PRIMARY KEY,
    session_id UUID NOT NULL REFERENCES route_sessions(id) ON DELETE CASCADE,
    rating INTEGER NOT NULL CHECK (rating BETWEEN 1 AND 5),
    was_convenient BOOLEAN NOT NULL,
    too_much_walking BOOLEAN NOT NULL,
    pois_were_interesting BOOLEAN NOT NULL,
    comment TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_route_sessions_device_id ON route_sessions(device_id);
CREATE INDEX IF NOT EXISTS idx_route_sessions_city_id ON route_sessions(city_id);
CREATE INDEX IF NOT EXISTS idx_route_sessions_status ON route_sessions(status);
CREATE INDEX IF NOT EXISTS idx_route_session_pois_session_id ON route_session_pois(session_id);
CREATE INDEX IF NOT EXISTS idx_route_feedback_session_id ON route_feedback(session_id);

CREATE TABLE IF NOT EXISTS transport_stops (
    id SERIAL PRIMARY KEY,
    city_id INTEGER NOT NULL REFERENCES cities(id) ON DELETE CASCADE,
    provider_stop_code TEXT,
    name TEXT NOT NULL,
    normalized_name TEXT NOT NULL,
    lat DOUBLE PRECISION NOT NULL,
    lon DOUBLE PRECISION NOT NULL,
    geom geometry(Point, 4326) NOT NULL,
    source TEXT NOT NULL,
    source_reference TEXT,
    platform_ref TEXT,
    matched_by TEXT,
    is_active BOOLEAN NOT NULL DEFAULT TRUE
);

CREATE TABLE IF NOT EXISTS transport_lines (
    id SERIAL PRIMARY KEY,
    city_id INTEGER NOT NULL REFERENCES cities(id) ON DELETE CASCADE,
    provider TEXT NOT NULL,
    provider_line_id TEXT NOT NULL,
    name TEXT NOT NULL,
    direction_name TEXT,
    service_bucket TEXT,
    source_url TEXT,
    valid_from DATE,
    valid_to DATE,
    is_active BOOLEAN NOT NULL DEFAULT TRUE
);

CREATE TABLE IF NOT EXISTS transport_line_stops (
    id BIGSERIAL PRIMARY KEY,
    line_id INTEGER NOT NULL REFERENCES transport_lines(id) ON DELETE CASCADE,
    stop_id INTEGER NOT NULL REFERENCES transport_stops(id),
    stop_sequence INTEGER NOT NULL,
    UNIQUE (line_id, stop_sequence)
);

CREATE TABLE IF NOT EXISTS transport_connections (
    id BIGSERIAL PRIMARY KEY,
    city_id INTEGER NOT NULL REFERENCES cities(id) ON DELETE CASCADE,
    line_id INTEGER NOT NULL REFERENCES transport_lines(id) ON DELETE CASCADE,
    from_stop_id INTEGER NOT NULL REFERENCES transport_stops(id),
    to_stop_id INTEGER NOT NULL REFERENCES transport_stops(id),
    from_sequence INTEGER NOT NULL,
    to_sequence INTEGER NOT NULL,
    avg_travel_seconds DOUBLE PRECISION NOT NULL,
    distance_meters DOUBLE PRECISION NOT NULL,
    source_url TEXT,
    is_active BOOLEAN NOT NULL DEFAULT TRUE,
    UNIQUE (line_id, from_stop_id, to_stop_id, from_sequence, to_sequence)
);

CREATE INDEX IF NOT EXISTS idx_transport_stops_city_id ON transport_stops(city_id);
CREATE INDEX IF NOT EXISTS idx_transport_stops_geom ON transport_stops USING GIST (geom);
CREATE UNIQUE INDEX IF NOT EXISTS idx_transport_stops_city_source_reference_unique
    ON transport_stops(city_id, source_reference)
    WHERE source_reference IS NOT NULL;
CREATE INDEX IF NOT EXISTS idx_transport_lines_city_id ON transport_lines(city_id);
CREATE INDEX IF NOT EXISTS idx_transport_connections_city_id ON transport_connections(city_id);
CREATE INDEX IF NOT EXISTS idx_transport_connections_from_stop_id ON transport_connections(from_stop_id);
CREATE INDEX IF NOT EXISTS idx_transport_connections_to_stop_id ON transport_connections(to_stop_id);
CREATE INDEX IF NOT EXISTS idx_transport_connections_line_id ON transport_connections(line_id);

INSERT INTO cities (name, country, center_lat, center_lon)
VALUES ('Nitra', 'Slovakia', 48.3076, 18.0845)
ON CONFLICT DO NOTHING;

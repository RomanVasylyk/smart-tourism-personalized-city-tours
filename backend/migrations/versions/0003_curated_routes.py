from alembic import op

revision = "0003_curated_routes"
down_revision = "0002_device_token_hash"
branch_labels = None
depends_on = None


CURATED_ROUTES_SCHEMA_SQL = """
CREATE TABLE IF NOT EXISTS curated_routes (
    id SERIAL PRIMARY KEY,
    city_id INTEGER NOT NULL REFERENCES cities(id) ON DELETE CASCADE,
    slug TEXT NOT NULL,
    title TEXT NOT NULL,
    summary TEXT,
    theme TEXT,
    start_lat DOUBLE PRECISION NOT NULL,
    start_lon DOUBLE PRECISION NOT NULL,
    recommended_duration_min INTEGER,
    pace TEXT NOT NULL DEFAULT 'normal',
    transport_mode TEXT NOT NULL DEFAULT 'walk',
    return_to_start BOOLEAN NOT NULL DEFAULT TRUE,
    is_active BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    UNIQUE (city_id, slug)
);

CREATE TABLE IF NOT EXISTS curated_route_pois (
    id BIGSERIAL PRIMARY KEY,
    route_id INTEGER NOT NULL REFERENCES curated_routes(id) ON DELETE CASCADE,
    poi_id INTEGER NOT NULL REFERENCES pois(id),
    visit_order INTEGER NOT NULL,
    visit_duration_min INTEGER,
    note TEXT,
    UNIQUE (route_id, visit_order)
);

CREATE INDEX IF NOT EXISTS idx_curated_routes_city_id ON curated_routes(city_id);
CREATE INDEX IF NOT EXISTS idx_curated_route_pois_route_id ON curated_route_pois(route_id);
CREATE INDEX IF NOT EXISTS idx_curated_route_pois_poi_id ON curated_route_pois(poi_id);
"""


def upgrade() -> None:
    for statement in migration_statements(CURATED_ROUTES_SCHEMA_SQL):
        op.execute(statement)


def downgrade() -> None:
    for table_name in ("curated_route_pois", "curated_routes"):
        op.execute(f"DROP TABLE IF EXISTS {table_name} CASCADE")


def migration_statements(schema_sql: str) -> list[str]:
    return [statement.strip() for statement in schema_sql.split(";") if statement.strip()]

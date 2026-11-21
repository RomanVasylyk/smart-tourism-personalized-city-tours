from alembic import op

revision = "0006_users_admin_holidays"
down_revision = "0005_unique_constraints"
branch_labels = None
depends_on = None


UPGRADE_STATEMENTS = (
    """
    CREATE TABLE IF NOT EXISTS users (
        id SERIAL PRIMARY KEY,
        email TEXT NOT NULL,
        password_hash TEXT NOT NULL,
        display_name TEXT,
        is_admin BOOLEAN NOT NULL DEFAULT FALSE,
        is_active BOOLEAN NOT NULL DEFAULT TRUE,
        created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
        last_login_at TIMESTAMPTZ
    )
    """,
    "CREATE UNIQUE INDEX IF NOT EXISTS idx_users_email_unique ON users (lower(email))",
    """
    CREATE TABLE IF NOT EXISTS user_tokens (
        id BIGSERIAL PRIMARY KEY,
        user_id INTEGER NOT NULL REFERENCES users (id) ON DELETE CASCADE,
        token_hash TEXT NOT NULL UNIQUE,
        created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
        last_used_at TIMESTAMPTZ,
        expires_at TIMESTAMPTZ NOT NULL
    )
    """,
    "CREATE INDEX IF NOT EXISTS idx_user_tokens_user ON user_tokens (user_id)",
    """
    CREATE TABLE IF NOT EXISTS holidays (
        id SERIAL PRIMARY KEY,
        holiday_date DATE NOT NULL UNIQUE,
        name TEXT NOT NULL,
        is_active BOOLEAN NOT NULL DEFAULT TRUE
    )
    """,
    "ALTER TABLE pois ADD COLUMN IF NOT EXISTS opening_hours_override TEXT",
    "ALTER TABLE pois ADD COLUMN IF NOT EXISTS opening_hours_override_by TEXT",
    "ALTER TABLE pois ADD COLUMN IF NOT EXISTS opening_hours_override_at TIMESTAMPTZ",
    "ALTER TABLE route_sessions ADD COLUMN IF NOT EXISTS user_id INTEGER REFERENCES users (id) ON DELETE SET NULL",
    "CREATE INDEX IF NOT EXISTS idx_route_sessions_user ON route_sessions (user_id)",
    """
    CREATE OR REPLACE FUNCTION sync_point_geom() RETURNS trigger AS $$
    BEGIN
        IF NEW.lat IS NOT NULL AND NEW.lon IS NOT NULL THEN
            NEW.geom := ST_SetSRID(ST_MakePoint(NEW.lon, NEW.lat), 4326);
        END IF;
        RETURN NEW;
    END;
    $$ LANGUAGE plpgsql
    """,
    "DROP TRIGGER IF EXISTS trg_pois_geom ON pois",
    "CREATE TRIGGER trg_pois_geom BEFORE INSERT OR UPDATE OF lat, lon ON pois FOR EACH ROW EXECUTE FUNCTION sync_point_geom()",
    "DROP TRIGGER IF EXISTS trg_transport_stops_geom ON transport_stops",
    "CREATE TRIGGER trg_transport_stops_geom BEFORE INSERT OR UPDATE OF lat, lon ON transport_stops FOR EACH ROW EXECUTE FUNCTION sync_point_geom()",
)

DOWNGRADE_STATEMENTS = (
    "DROP TRIGGER IF EXISTS trg_transport_stops_geom ON transport_stops",
    "DROP TRIGGER IF EXISTS trg_pois_geom ON pois",
    "DROP FUNCTION IF EXISTS sync_point_geom()",
    "DROP INDEX IF EXISTS idx_route_sessions_user",
    "ALTER TABLE route_sessions DROP COLUMN IF EXISTS user_id",
    "ALTER TABLE pois DROP COLUMN IF EXISTS opening_hours_override_at",
    "ALTER TABLE pois DROP COLUMN IF EXISTS opening_hours_override_by",
    "ALTER TABLE pois DROP COLUMN IF EXISTS opening_hours_override",
    "DROP TABLE IF EXISTS holidays",
    "DROP TABLE IF EXISTS user_tokens",
    "DROP TABLE IF EXISTS users",
)


def upgrade() -> None:
    for statement in UPGRADE_STATEMENTS:
        op.execute(statement)


def downgrade() -> None:
    for statement in DOWNGRADE_STATEMENTS:
        op.execute(statement)

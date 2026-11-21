from alembic import op

revision = "0008_poi_admin_lock"
down_revision = "0007_school_holidays"
branch_labels = None
depends_on = None


UPGRADE_STATEMENTS = (
    "ALTER TABLE pois ADD COLUMN IF NOT EXISTS admin_locked BOOLEAN NOT NULL DEFAULT FALSE",
    """
    CREATE TABLE IF NOT EXISTS data_loads (
        id SERIAL PRIMARY KEY,
        dataset TEXT NOT NULL CHECK (dataset IN ('transport', 'pois')),
        city_slug TEXT NOT NULL,
        file_name TEXT NOT NULL,
        sha256 TEXT NOT NULL,
        loaded_by TEXT,
        loaded_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
    )
    """,
    "CREATE INDEX IF NOT EXISTS idx_data_loads_lookup ON data_loads (dataset, city_slug, loaded_at DESC)",
)

DOWNGRADE_STATEMENTS = (
    "DROP TABLE IF EXISTS data_loads",
    "ALTER TABLE pois DROP COLUMN IF EXISTS admin_locked",
)


def upgrade() -> None:
    for statement in UPGRADE_STATEMENTS:
        op.execute(statement)


def downgrade() -> None:
    for statement in DOWNGRADE_STATEMENTS:
        op.execute(statement)

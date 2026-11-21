from alembic import op

revision = "0009_pipeline_jobs"
down_revision = "0008_poi_admin_lock"
branch_labels = None
depends_on = None


UPGRADE_STATEMENTS = (
    """
    CREATE TABLE IF NOT EXISTS pipeline_jobs (
        id SERIAL PRIMARY KEY,
        city_slug TEXT NOT NULL,
        kind TEXT NOT NULL,
        status TEXT NOT NULL CHECK (status IN ('running', 'succeeded', 'failed')),
        started_by TEXT,
        started_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
        finished_at TIMESTAMPTZ,
        exit_code INTEGER,
        log TEXT NOT NULL DEFAULT ''
    )
    """,
    "CREATE INDEX IF NOT EXISTS idx_pipeline_jobs_started ON pipeline_jobs (started_at DESC)",
)

DOWNGRADE_STATEMENTS = ("DROP TABLE IF EXISTS pipeline_jobs",)


def upgrade() -> None:
    for statement in UPGRADE_STATEMENTS:
        op.execute(statement)


def downgrade() -> None:
    for statement in DOWNGRADE_STATEMENTS:
        op.execute(statement)

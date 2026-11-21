from alembic import op

revision = "0007_school_holidays"
down_revision = "0006_users_admin_holidays"
branch_labels = None
depends_on = None


UPGRADE_STATEMENTS = (
    """
    CREATE TABLE IF NOT EXISTS school_holidays (
        id SERIAL PRIMARY KEY,
        name TEXT NOT NULL,
        kind TEXT NOT NULL CHECK (kind IN ('autumn', 'christmas', 'half_year', 'spring', 'easter', 'summer')),
        start_date DATE NOT NULL,
        end_date DATE NOT NULL,
        city_id INTEGER REFERENCES cities(id) ON DELETE CASCADE,
        is_active BOOLEAN NOT NULL DEFAULT TRUE,
        created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
        CONSTRAINT school_holidays_dates_check CHECK (end_date >= start_date)
    )
    """,
    "CREATE INDEX IF NOT EXISTS idx_school_holidays_dates ON school_holidays (start_date, end_date)",
)

DOWNGRADE_STATEMENTS = ("DROP TABLE IF EXISTS school_holidays",)


def upgrade() -> None:
    for statement in UPGRADE_STATEMENTS:
        op.execute(statement)


def downgrade() -> None:
    for statement in DOWNGRADE_STATEMENTS:
        op.execute(statement)

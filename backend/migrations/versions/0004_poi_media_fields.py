from alembic import op

revision = "0004_poi_media_fields"
down_revision = "0003_curated_routes"
branch_labels = None
depends_on = None


UPGRADE_STATEMENTS = (
    "ALTER TABLE pois ADD COLUMN IF NOT EXISTS website TEXT",
    "ALTER TABLE pois ADD COLUMN IF NOT EXISTS image_url TEXT",
    "ALTER TABLE pois ADD COLUMN IF NOT EXISTS opening_hours_source TEXT",
)

DOWNGRADE_STATEMENTS = (
    "ALTER TABLE pois DROP COLUMN IF EXISTS website",
    "ALTER TABLE pois DROP COLUMN IF EXISTS image_url",
    "ALTER TABLE pois DROP COLUMN IF EXISTS opening_hours_source",
)


def upgrade() -> None:
    for statement in UPGRADE_STATEMENTS:
        op.execute(statement)


def downgrade() -> None:
    for statement in DOWNGRADE_STATEMENTS:
        op.execute(statement)

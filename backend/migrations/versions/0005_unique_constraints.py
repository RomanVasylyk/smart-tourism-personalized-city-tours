from alembic import op

revision = "0005_unique_constraints"
down_revision = "0004_poi_media_fields"
branch_labels = None
depends_on = None


UPGRADE_STATEMENTS = (
    """
    DELETE FROM pois a
    USING pois b
    WHERE a.id > b.id
      AND a.city_id = b.city_id
      AND a.osm_id = b.osm_id
      AND a.osm_type = b.osm_type
    """,
    """
    CREATE UNIQUE INDEX IF NOT EXISTS idx_pois_city_osm_unique
    ON pois (city_id, osm_id, osm_type)
    """,
    """
    DELETE FROM route_feedback a
    USING route_feedback b
    WHERE a.session_id = b.session_id
      AND a.created_at < b.created_at
    """,
    """
    DELETE FROM route_feedback a
    USING route_feedback b
    WHERE a.session_id = b.session_id
      AND a.created_at = b.created_at
      AND a.id < b.id
    """,
    """
    CREATE UNIQUE INDEX IF NOT EXISTS idx_route_feedback_session_unique
    ON route_feedback (session_id)
    """,
)

DOWNGRADE_STATEMENTS = (
    "DROP INDEX IF EXISTS idx_route_feedback_session_unique",
    "DROP INDEX IF EXISTS idx_pois_city_osm_unique",
)


def upgrade() -> None:
    for statement in UPGRADE_STATEMENTS:
        op.execute(statement)


def downgrade() -> None:
    for statement in DOWNGRADE_STATEMENTS:
        op.execute(statement)

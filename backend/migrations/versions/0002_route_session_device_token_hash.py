import sqlalchemy as sa
from alembic import op


revision = "0002_route_session_device_token_hash"
down_revision = "0001_initial_schema"
branch_labels = None
depends_on = None


def upgrade() -> None:
    op.add_column(
        "route_sessions",
        sa.Column("device_token_hash", sa.Text(), nullable=True),
    )
    op.create_index(
        "idx_route_sessions_device_auth",
        "route_sessions",
        ["device_id", "device_token_hash"],
    )


def downgrade() -> None:
    op.drop_index("idx_route_sessions_device_auth", table_name="route_sessions")
    op.drop_column("route_sessions", "device_token_hash")

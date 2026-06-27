from alembic import op

revision = "0002_device_token_hash"
down_revision = "0001_initial_schema"
branch_labels = None
depends_on = None


def upgrade() -> None:
    op.execute(
        """
        ALTER TABLE route_sessions
        ADD COLUMN IF NOT EXISTS device_token_hash TEXT
        """
    )
    op.execute(
        """
        CREATE INDEX IF NOT EXISTS idx_route_sessions_device_auth
        ON route_sessions(device_id, device_token_hash)
        """
    )


def downgrade() -> None:
    op.execute("DROP INDEX IF EXISTS idx_route_sessions_device_auth")
    op.execute("ALTER TABLE route_sessions DROP COLUMN IF EXISTS device_token_hash")

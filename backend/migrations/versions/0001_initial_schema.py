from pathlib import Path

from alembic import op


revision = "0001_initial_schema"
down_revision = None
branch_labels = None
depends_on = None


def upgrade() -> None:
    schema_sql = schema_file().read_text(encoding="utf-8")
    for statement in migration_statements(schema_sql):
        op.execute(statement)


def downgrade() -> None:
    for table_name in (
        "transport_connections",
        "transport_trip_stop_times",
        "transport_trips",
        "transport_line_stops",
        "transport_lines",
        "transport_stops",
        "transport_mode_feedback_stats",
        "city_feedback_stats",
        "category_feedback_stats",
        "poi_feedback_stats",
        "route_feedback",
        "route_session_pois",
        "route_sessions",
        "pois",
        "cities",
    ):
        op.execute(f"DROP TABLE IF EXISTS {table_name} CASCADE")


def schema_file() -> Path:
    return Path(__file__).resolve().parents[2] / "sql" / "init.sql"


def migration_statements(schema_sql: str) -> list[str]:
    statements = []
    for statement in schema_sql.split(";"):
        normalized = statement.strip()
        if not normalized or normalized.upper().startswith("INSERT INTO CITIES"):
            continue
        statements.append(normalized)
    return statements

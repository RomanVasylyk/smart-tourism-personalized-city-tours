from __future__ import annotations

from dataclasses import dataclass

from psycopg import Error as PsycopgError

from app.db.database import get_connection
from app.services.city_profiles import city_profile_by_token

FEEDBACK_STATS_SCHEMA_SQL = """
CREATE TABLE IF NOT EXISTS poi_feedback_stats (
    poi_id INTEGER PRIMARY KEY REFERENCES pois(id) ON DELETE CASCADE,
    session_count INTEGER NOT NULL DEFAULT 0,
    feedback_count INTEGER NOT NULL DEFAULT 0,
    planned_count INTEGER NOT NULL DEFAULT 0,
    visited_count INTEGER NOT NULL DEFAULT 0,
    skipped_count INTEGER NOT NULL DEFAULT 0,
    completed_session_count INTEGER NOT NULL DEFAULT 0,
    average_rating DOUBLE PRECISION,
    completion_rate DOUBLE PRECISION,
    skip_rate DOUBLE PRECISION,
    too_much_walking_rate DOUBLE PRECISION,
    interesting_pois_rate DOUBLE PRECISION,
    convenient_rate DOUBLE PRECISION,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE TABLE IF NOT EXISTS category_feedback_stats (
    city_id INTEGER NOT NULL REFERENCES cities(id) ON DELETE CASCADE,
    category TEXT NOT NULL,
    session_count INTEGER NOT NULL DEFAULT 0,
    feedback_count INTEGER NOT NULL DEFAULT 0,
    planned_count INTEGER NOT NULL DEFAULT 0,
    visited_count INTEGER NOT NULL DEFAULT 0,
    skipped_count INTEGER NOT NULL DEFAULT 0,
    completed_session_count INTEGER NOT NULL DEFAULT 0,
    average_rating DOUBLE PRECISION,
    completion_rate DOUBLE PRECISION,
    skip_rate DOUBLE PRECISION,
    too_much_walking_rate DOUBLE PRECISION,
    interesting_pois_rate DOUBLE PRECISION,
    convenient_rate DOUBLE PRECISION,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    PRIMARY KEY (city_id, category)
);

CREATE TABLE IF NOT EXISTS city_feedback_stats (
    city_id INTEGER PRIMARY KEY REFERENCES cities(id) ON DELETE CASCADE,
    session_count INTEGER NOT NULL DEFAULT 0,
    feedback_count INTEGER NOT NULL DEFAULT 0,
    planned_count INTEGER NOT NULL DEFAULT 0,
    skipped_count INTEGER NOT NULL DEFAULT 0,
    completed_session_count INTEGER NOT NULL DEFAULT 0,
    average_rating DOUBLE PRECISION,
    completion_rate DOUBLE PRECISION,
    skip_rate DOUBLE PRECISION,
    too_much_walking_rate DOUBLE PRECISION,
    interesting_pois_rate DOUBLE PRECISION,
    convenient_rate DOUBLE PRECISION,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE TABLE IF NOT EXISTS transport_mode_feedback_stats (
    city_id INTEGER NOT NULL REFERENCES cities(id) ON DELETE CASCADE,
    transport_mode TEXT NOT NULL,
    session_count INTEGER NOT NULL DEFAULT 0,
    feedback_count INTEGER NOT NULL DEFAULT 0,
    planned_count INTEGER NOT NULL DEFAULT 0,
    skipped_count INTEGER NOT NULL DEFAULT 0,
    completed_session_count INTEGER NOT NULL DEFAULT 0,
    average_rating DOUBLE PRECISION,
    completion_rate DOUBLE PRECISION,
    skip_rate DOUBLE PRECISION,
    too_much_walking_rate DOUBLE PRECISION,
    interesting_pois_rate DOUBLE PRECISION,
    convenient_rate DOUBLE PRECISION,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    PRIMARY KEY (city_id, transport_mode)
);
"""

POI_FEEDBACK_RECOMPUTE_SQL = """
INSERT INTO poi_feedback_stats (
    poi_id,
    session_count,
    feedback_count,
    planned_count,
    visited_count,
    skipped_count,
    completed_session_count,
    average_rating,
    completion_rate,
    skip_rate,
    too_much_walking_rate,
    interesting_pois_rate,
    convenient_rate,
    updated_at
)
SELECT
    rsp.poi_id,
    COUNT(DISTINCT rs.id) AS session_count,
    COUNT(rf.id) AS feedback_count,
    COUNT(*) AS planned_count,
    COUNT(*) FILTER (WHERE rsp.visited) AS visited_count,
    COUNT(*) FILTER (WHERE rsp.skipped) AS skipped_count,
    COUNT(*) FILTER (WHERE rs.status = 'completed') AS completed_session_count,
    AVG(rf.rating::DOUBLE PRECISION) AS average_rating,
    AVG(CASE WHEN rsp.visited THEN 1.0 ELSE 0.0 END) AS completion_rate,
    AVG(CASE WHEN rsp.skipped THEN 1.0 ELSE 0.0 END) AS skip_rate,
    AVG(CASE WHEN rf.id IS NULL THEN NULL WHEN rf.too_much_walking THEN 1.0 ELSE 0.0 END) AS too_much_walking_rate,
    AVG(CASE WHEN rf.id IS NULL THEN NULL WHEN rf.pois_were_interesting THEN 1.0 ELSE 0.0 END) AS interesting_pois_rate,
    AVG(CASE WHEN rf.id IS NULL THEN NULL WHEN rf.was_convenient THEN 1.0 ELSE 0.0 END) AS convenient_rate,
    NOW()
FROM route_session_pois rsp
JOIN route_sessions rs ON rs.id = rsp.session_id
LEFT JOIN route_feedback rf ON rf.session_id = rs.id
WHERE (%(city_id)s::INTEGER IS NULL OR rs.city_id = %(city_id)s::INTEGER)
GROUP BY rsp.poi_id
"""

CATEGORY_FEEDBACK_RECOMPUTE_SQL = """
INSERT INTO category_feedback_stats (
    city_id,
    category,
    session_count,
    feedback_count,
    planned_count,
    visited_count,
    skipped_count,
    completed_session_count,
    average_rating,
    completion_rate,
    skip_rate,
    too_much_walking_rate,
    interesting_pois_rate,
    convenient_rate,
    updated_at
)
SELECT
    rs.city_id,
    p.category,
    COUNT(DISTINCT rs.id) AS session_count,
    COUNT(rf.id) AS feedback_count,
    COUNT(*) AS planned_count,
    COUNT(*) FILTER (WHERE rsp.visited) AS visited_count,
    COUNT(*) FILTER (WHERE rsp.skipped) AS skipped_count,
    COUNT(*) FILTER (WHERE rs.status = 'completed') AS completed_session_count,
    AVG(rf.rating::DOUBLE PRECISION) AS average_rating,
    AVG(CASE WHEN rsp.visited THEN 1.0 ELSE 0.0 END) AS completion_rate,
    AVG(CASE WHEN rsp.skipped THEN 1.0 ELSE 0.0 END) AS skip_rate,
    AVG(CASE WHEN rf.id IS NULL THEN NULL WHEN rf.too_much_walking THEN 1.0 ELSE 0.0 END) AS too_much_walking_rate,
    AVG(CASE WHEN rf.id IS NULL THEN NULL WHEN rf.pois_were_interesting THEN 1.0 ELSE 0.0 END) AS interesting_pois_rate,
    AVG(CASE WHEN rf.id IS NULL THEN NULL WHEN rf.was_convenient THEN 1.0 ELSE 0.0 END) AS convenient_rate,
    NOW()
FROM route_session_pois rsp
JOIN route_sessions rs ON rs.id = rsp.session_id
JOIN pois p ON p.id = rsp.poi_id
LEFT JOIN route_feedback rf ON rf.session_id = rs.id
WHERE (%(city_id)s::INTEGER IS NULL OR rs.city_id = %(city_id)s::INTEGER)
GROUP BY rs.city_id, p.category
"""

CITY_FEEDBACK_RECOMPUTE_SQL = """
WITH session_poi_stats AS (
    SELECT
        rs.id AS session_id,
        rs.city_id,
        COUNT(rsp.id) AS planned_count,
        COUNT(*) FILTER (WHERE rsp.skipped) AS skipped_count
    FROM route_sessions rs
    LEFT JOIN route_session_pois rsp ON rsp.session_id = rs.id
    WHERE (%(city_id)s::INTEGER IS NULL OR rs.city_id = %(city_id)s::INTEGER)
    GROUP BY rs.id, rs.city_id
)
INSERT INTO city_feedback_stats (
    city_id,
    session_count,
    feedback_count,
    planned_count,
    skipped_count,
    completed_session_count,
    average_rating,
    completion_rate,
    skip_rate,
    too_much_walking_rate,
    interesting_pois_rate,
    convenient_rate,
    updated_at
)
SELECT
    rs.city_id,
    COUNT(*) AS session_count,
    COUNT(rf.id) AS feedback_count,
    COALESCE(SUM(sp.planned_count), 0) AS planned_count,
    COALESCE(SUM(sp.skipped_count), 0) AS skipped_count,
    COUNT(*) FILTER (WHERE rs.status = 'completed') AS completed_session_count,
    AVG(rf.rating::DOUBLE PRECISION) AS average_rating,
    AVG(CASE WHEN rs.status = 'completed' THEN 1.0 ELSE 0.0 END) AS completion_rate,
    CASE
        WHEN COALESCE(SUM(sp.planned_count), 0) = 0 THEN NULL
        ELSE COALESCE(SUM(sp.skipped_count), 0)::DOUBLE PRECISION / SUM(sp.planned_count)
    END AS skip_rate,
    AVG(CASE WHEN rf.id IS NULL THEN NULL WHEN rf.too_much_walking THEN 1.0 ELSE 0.0 END) AS too_much_walking_rate,
    AVG(CASE WHEN rf.id IS NULL THEN NULL WHEN rf.pois_were_interesting THEN 1.0 ELSE 0.0 END) AS interesting_pois_rate,
    AVG(CASE WHEN rf.id IS NULL THEN NULL WHEN rf.was_convenient THEN 1.0 ELSE 0.0 END) AS convenient_rate,
    NOW()
FROM route_sessions rs
LEFT JOIN route_feedback rf ON rf.session_id = rs.id
LEFT JOIN session_poi_stats sp ON sp.session_id = rs.id
WHERE (%(city_id)s::INTEGER IS NULL OR rs.city_id = %(city_id)s::INTEGER)
GROUP BY rs.city_id
"""

TRANSPORT_MODE_FEEDBACK_RECOMPUTE_SQL = """
WITH session_poi_stats AS (
    SELECT
        rs.id AS session_id,
        rs.city_id,
        COALESCE(NULLIF(rs.route_snapshot_json ->> 'transport_mode', ''), 'walk') AS transport_mode,
        COUNT(rsp.id) AS planned_count,
        COUNT(*) FILTER (WHERE rsp.skipped) AS skipped_count
    FROM route_sessions rs
    LEFT JOIN route_session_pois rsp ON rsp.session_id = rs.id
    WHERE (%(city_id)s::INTEGER IS NULL OR rs.city_id = %(city_id)s::INTEGER)
    GROUP BY rs.id, rs.city_id, transport_mode
)
INSERT INTO transport_mode_feedback_stats (
    city_id,
    transport_mode,
    session_count,
    feedback_count,
    planned_count,
    skipped_count,
    completed_session_count,
    average_rating,
    completion_rate,
    skip_rate,
    too_much_walking_rate,
    interesting_pois_rate,
    convenient_rate,
    updated_at
)
SELECT
    rs.city_id,
    sp.transport_mode,
    COUNT(*) AS session_count,
    COUNT(rf.id) AS feedback_count,
    COALESCE(SUM(sp.planned_count), 0) AS planned_count,
    COALESCE(SUM(sp.skipped_count), 0) AS skipped_count,
    COUNT(*) FILTER (WHERE rs.status = 'completed') AS completed_session_count,
    AVG(rf.rating::DOUBLE PRECISION) AS average_rating,
    AVG(CASE WHEN rs.status = 'completed' THEN 1.0 ELSE 0.0 END) AS completion_rate,
    CASE
        WHEN COALESCE(SUM(sp.planned_count), 0) = 0 THEN NULL
        ELSE COALESCE(SUM(sp.skipped_count), 0)::DOUBLE PRECISION / SUM(sp.planned_count)
    END AS skip_rate,
    AVG(CASE WHEN rf.id IS NULL THEN NULL WHEN rf.too_much_walking THEN 1.0 ELSE 0.0 END) AS too_much_walking_rate,
    AVG(CASE WHEN rf.id IS NULL THEN NULL WHEN rf.pois_were_interesting THEN 1.0 ELSE 0.0 END) AS interesting_pois_rate,
    AVG(CASE WHEN rf.id IS NULL THEN NULL WHEN rf.was_convenient THEN 1.0 ELSE 0.0 END) AS convenient_rate,
    NOW()
FROM route_sessions rs
LEFT JOIN route_feedback rf ON rf.session_id = rs.id
LEFT JOIN session_poi_stats sp ON sp.session_id = rs.id
WHERE (%(city_id)s::INTEGER IS NULL OR rs.city_id = %(city_id)s::INTEGER)
GROUP BY rs.city_id, sp.transport_mode
"""


@dataclass(frozen=True)
class PlannerFeedbackStats:
    average_rating: float | None = None
    completion_rate: float | None = None
    skip_rate: float | None = None
    too_much_walking_rate: float | None = None
    interesting_pois_rate: float | None = None
    convenient_rate: float | None = None
    session_count: int = 0
    feedback_count: int = 0
    planned_count: int = 0
    visited_count: int = 0
    skipped_count: int = 0
    completed_session_count: int = 0

    @classmethod
    def from_row(cls, row: dict | None, prefix: str = "") -> "PlannerFeedbackStats":
        if row is None:
            return cls()

        return cls(
            average_rating=row.get(f"{prefix}average_rating"),
            completion_rate=row.get(f"{prefix}completion_rate"),
            skip_rate=row.get(f"{prefix}skip_rate"),
            too_much_walking_rate=row.get(f"{prefix}too_much_walking_rate"),
            interesting_pois_rate=row.get(f"{prefix}interesting_pois_rate"),
            convenient_rate=row.get(f"{prefix}convenient_rate"),
            session_count=int(row.get(f"{prefix}session_count") or 0),
            feedback_count=int(row.get(f"{prefix}feedback_count") or 0),
            planned_count=int(row.get(f"{prefix}planned_count") or 0),
            visited_count=int(row.get(f"{prefix}visited_count") or 0),
            skipped_count=int(row.get(f"{prefix}skipped_count") or 0),
            completed_session_count=int(row.get(f"{prefix}completed_session_count") or 0),
        )

    @property
    def sample_size(self) -> int:
        return max(self.planned_count, self.session_count, self.feedback_count)


@dataclass(frozen=True)
class PlannerFeedbackProfile:
    city_stats: PlannerFeedbackStats = PlannerFeedbackStats()
    transport_mode_stats: PlannerFeedbackStats = PlannerFeedbackStats()


def ensure_feedback_stats_schema() -> None:
    with get_connection() as conn:
        with conn.cursor() as cur:
            cur.execute(FEEDBACK_STATS_SCHEMA_SQL)
        conn.commit()


def recompute_feedback_stats(city: str | None = None) -> dict[str, int | str | None]:
    city_id = None
    city_name = None
    if city is not None:
        city_profile = city_profile_by_token(city) or {}
        city_name = city_profile.get("name") or city

    with get_connection() as conn:
        with conn.cursor() as cur:
            cur.execute(FEEDBACK_STATS_SCHEMA_SQL)

            if city_name is not None:
                cur.execute(
                    "SELECT id, name FROM cities WHERE lower(name) = lower(%s)",
                    (city_name,),
                )
                city_row = cur.fetchone()
                if city_row is None:
                    raise ValueError(f"City not found: {city_name}")
                city_id = int(city_row["id"])
                city_name = city_row["name"]
                _delete_city_feedback_stats(cur, city_id)
            else:
                cur.execute("TRUNCATE poi_feedback_stats, category_feedback_stats, city_feedback_stats, transport_mode_feedback_stats")

            params = {"city_id": city_id}
            cur.execute(POI_FEEDBACK_RECOMPUTE_SQL, params)
            poi_rows = cur.rowcount
            cur.execute(CATEGORY_FEEDBACK_RECOMPUTE_SQL, params)
            category_rows = cur.rowcount
            cur.execute(CITY_FEEDBACK_RECOMPUTE_SQL, params)
            city_rows = cur.rowcount
            cur.execute(TRANSPORT_MODE_FEEDBACK_RECOMPUTE_SQL, params)
            transport_rows = cur.rowcount
        conn.commit()

    return {
        "city_id": city_id,
        "city_name": city_name,
        "poi_feedback_stats": max(poi_rows, 0),
        "category_feedback_stats": max(category_rows, 0),
        "city_feedback_stats": max(city_rows, 0),
        "transport_mode_feedback_stats": max(transport_rows, 0),
    }


def load_planner_feedback_profile(city: str, transport_mode: str) -> PlannerFeedbackProfile:
    city_profile = city_profile_by_token(city) or {}
    city_name = city_profile.get("name") or city

    try:
        with get_connection() as conn:
            with conn.cursor() as cur:
                cur.execute(
                    """
                    SELECT
                        c.id AS city_id,
                        cfs.average_rating AS city_average_rating,
                        cfs.completion_rate AS city_completion_rate,
                        cfs.skip_rate AS city_skip_rate,
                        cfs.too_much_walking_rate AS city_too_much_walking_rate,
                        cfs.interesting_pois_rate AS city_interesting_pois_rate,
                        cfs.convenient_rate AS city_convenient_rate,
                        cfs.session_count AS city_session_count,
                        cfs.feedback_count AS city_feedback_count,
                        cfs.planned_count AS city_planned_count,
                        cfs.skipped_count AS city_skipped_count,
                        cfs.completed_session_count AS city_completed_session_count,
                        tmfs.average_rating AS transport_average_rating,
                        tmfs.completion_rate AS transport_completion_rate,
                        tmfs.skip_rate AS transport_skip_rate,
                        tmfs.too_much_walking_rate AS transport_too_much_walking_rate,
                        tmfs.interesting_pois_rate AS transport_interesting_pois_rate,
                        tmfs.convenient_rate AS transport_convenient_rate,
                        tmfs.session_count AS transport_session_count,
                        tmfs.feedback_count AS transport_feedback_count,
                        tmfs.planned_count AS transport_planned_count,
                        tmfs.skipped_count AS transport_skipped_count,
                        tmfs.completed_session_count AS transport_completed_session_count
                    FROM cities c
                    LEFT JOIN city_feedback_stats cfs ON cfs.city_id = c.id
                    LEFT JOIN transport_mode_feedback_stats tmfs
                        ON tmfs.city_id = c.id
                       AND tmfs.transport_mode = %s
                    WHERE lower(c.name) = lower(%s)
                    """,
                    (transport_mode, city_name),
                )
                row = cur.fetchone()
    except PsycopgError:
        return PlannerFeedbackProfile()

    if row is None:
        return PlannerFeedbackProfile()

    return PlannerFeedbackProfile(
        city_stats=PlannerFeedbackStats.from_row(row, "city_"),
        transport_mode_stats=PlannerFeedbackStats.from_row(row, "transport_"),
    )


def _delete_city_feedback_stats(cur, city_id: int) -> None:
    cur.execute(
        """
        DELETE FROM poi_feedback_stats pfs
        USING pois p
        WHERE p.id = pfs.poi_id
          AND p.city_id = %s
        """,
        (city_id,),
    )
    cur.execute("DELETE FROM category_feedback_stats WHERE city_id = %s", (city_id,))
    cur.execute("DELETE FROM city_feedback_stats WHERE city_id = %s", (city_id,))
    cur.execute("DELETE FROM transport_mode_feedback_stats WHERE city_id = %s", (city_id,))

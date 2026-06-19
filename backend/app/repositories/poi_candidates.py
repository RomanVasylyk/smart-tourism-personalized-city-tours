from psycopg import Error as PsycopgError

from app.db.database import get_connection
from app.schemas.route import RouteGenerateRequest
from app.services.city_lookup import find_city_row
from app.services.city_profiles import city_profile_by_token


CANDIDATE_COLUMNS = """
    p.id,
    p.name,
    p.category,
    p.lat,
    p.lon,
    p.opening_hours_raw,
    p.visit_duration_min,
    p.base_score,
    p.wikipedia_url
"""

FEEDBACK_COLUMNS = """
    pfs.average_rating AS poi_feedback_average_rating,
    pfs.completion_rate AS poi_feedback_completion_rate,
    pfs.skip_rate AS poi_feedback_skip_rate,
    pfs.too_much_walking_rate AS poi_feedback_too_much_walking_rate,
    pfs.interesting_pois_rate AS poi_feedback_interesting_pois_rate,
    pfs.convenient_rate AS poi_feedback_convenient_rate,
    pfs.session_count AS poi_feedback_session_count,
    pfs.feedback_count AS poi_feedback_feedback_count,
    pfs.planned_count AS poi_feedback_planned_count,
    pfs.visited_count AS poi_feedback_visited_count,
    pfs.skipped_count AS poi_feedback_skipped_count,
    pfs.completed_session_count AS poi_feedback_completed_session_count,
    cfs.average_rating AS category_feedback_average_rating,
    cfs.completion_rate AS category_feedback_completion_rate,
    cfs.skip_rate AS category_feedback_skip_rate,
    cfs.too_much_walking_rate AS category_feedback_too_much_walking_rate,
    cfs.interesting_pois_rate AS category_feedback_interesting_pois_rate,
    cfs.convenient_rate AS category_feedback_convenient_rate,
    cfs.session_count AS category_feedback_session_count,
    cfs.feedback_count AS category_feedback_feedback_count,
    cfs.planned_count AS category_feedback_planned_count,
    cfs.visited_count AS category_feedback_visited_count,
    cfs.skipped_count AS category_feedback_skipped_count,
    cfs.completed_session_count AS category_feedback_completed_session_count
"""

FEEDBACK_JOINS = """
    LEFT JOIN poi_feedback_stats pfs ON pfs.poi_id = p.id
    LEFT JOIN category_feedback_stats cfs
        ON cfs.city_id = c.id
       AND cfs.category = p.category
"""


def get_route_candidates(request: RouteGenerateRequest) -> list[dict]:
    city_profile = city_profile_by_token(request.city) or {}
    city_name = city_profile.get("name") or request.city
    limit = candidate_limit(city_profile)

    with get_connection() as conn:
        with conn.cursor() as cur:
            city_row = find_city_row(cur, city_name)
            if city_row is None:
                return []

            city_id = int(city_row["id"])
            rows = select_candidates_with_feedback_fallback(cur, request, city_id, limit)
            preferred_rows = select_missing_preferred_candidates(cur, request, city_id, rows)
            return rows + preferred_rows


def candidate_limit(city_profile: dict) -> int:
    routing_limits = city_profile.get("routing_limits") or {}
    return int(routing_limits.get("max_poi_candidates") or 300)


def select_candidates_with_feedback_fallback(cur, request: RouteGenerateRequest, city_id: int, limit: int) -> list[dict]:
    try:
        return select_candidates(cur, request, city_id, limit, include_feedback=True)
    except PsycopgError:
        rollback_after_query_error(cur)
        return select_candidates(cur, request, city_id, limit, include_feedback=False)


def select_candidates(
    cur,
    request: RouteGenerateRequest,
    city_id: int,
    limit: int,
    *,
    include_feedback: bool,
) -> list[dict]:
    sql, params = candidate_query(
        city_id=city_id,
        interests=request.interests,
        exclude_poi_ids=request.exclude_poi_ids,
        limit=limit,
        include_feedback=include_feedback,
    )
    cur.execute(sql, params)
    return cur.fetchall()


def select_missing_preferred_candidates(
    cur,
    request: RouteGenerateRequest,
    city_id: int,
    existing_rows: list[dict],
) -> list[dict]:
    missing_preferred_ids = missing_preferred_candidate_ids(request, existing_rows)
    if not missing_preferred_ids:
        return []

    try:
        return select_preferred_candidates(cur, city_id, missing_preferred_ids, include_feedback=True)
    except PsycopgError:
        rollback_after_query_error(cur)
        return select_preferred_candidates(cur, city_id, missing_preferred_ids, include_feedback=False)


def select_preferred_candidates(cur, city_id: int, preferred_ids: list[int], *, include_feedback: bool) -> list[dict]:
    sql, params = preferred_candidate_query(
        city_id=city_id,
        preferred_ids=preferred_ids,
        include_feedback=include_feedback,
    )
    cur.execute(sql, params)
    return cur.fetchall()


def missing_preferred_candidate_ids(request: RouteGenerateRequest, existing_rows: list[dict]) -> list[int]:
    excluded_ids = {int(poi_id) for poi_id in request.exclude_poi_ids}
    preferred_ids = [
        int(poi_id)
        for poi_id in request.preferred_poi_ids
        if int(poi_id) not in excluded_ids
    ]
    if not preferred_ids:
        return []

    existing_ids = {int(row["id"]) for row in existing_rows}
    return [poi_id for poi_id in preferred_ids if poi_id not in existing_ids]


def candidate_query(
    *,
    city_id: int,
    interests: list[str],
    exclude_poi_ids: list[int],
    limit: int,
    include_feedback: bool,
) -> tuple[str, list]:
    where_clauses = ["c.id = %s", "p.is_active = TRUE"]
    params: list = [city_id]

    if interests:
        where_clauses.append("p.category = ANY(%s)")
        params.append(interests)

    if exclude_poi_ids:
        where_clauses.append("NOT (p.id = ANY(%s))")
        params.append(exclude_poi_ids)

    params.append(limit)
    return (
        select_candidates_sql(include_feedback=include_feedback)
        + f"""
        WHERE {" AND ".join(where_clauses)}
        ORDER BY p.base_score DESC NULLS LAST, p.name
        LIMIT %s
        """,
        params,
    )


def preferred_candidate_query(
    *,
    city_id: int,
    preferred_ids: list[int],
    include_feedback: bool,
) -> tuple[str, tuple]:
    return (
        select_candidates_sql(include_feedback=include_feedback)
        + """
        WHERE c.id = %s
          AND p.is_active = TRUE
          AND p.id = ANY(%s)
        """,
        (city_id, preferred_ids),
    )


def select_candidates_sql(*, include_feedback: bool) -> str:
    columns = CANDIDATE_COLUMNS
    joins = ""
    if include_feedback:
        columns = f"{CANDIDATE_COLUMNS},\n{FEEDBACK_COLUMNS}"
        joins = FEEDBACK_JOINS

    return f"""
        SELECT
        {columns}
        FROM pois p
        JOIN cities c ON c.id = p.city_id
        {joins}
    """


def rollback_after_query_error(cur) -> None:
    connection = getattr(cur, "connection", None)
    if connection is not None:
        connection.rollback()

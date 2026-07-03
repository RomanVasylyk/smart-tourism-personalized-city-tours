from app.db.database import get_connection
from app.services.city_lookup import find_city_row
from app.services.city_profiles import city_profile_by_token

CURATED_ROUTE_COLUMNS = """
    cr.id,
    cr.slug,
    cr.title,
    cr.summary,
    cr.theme,
    cr.recommended_duration_min,
    cr.pace,
    cr.transport_mode,
    cr.return_to_start,
    cr.start_lat,
    cr.start_lon,
    c.name AS city_name
"""


def list_curated_route_rows(city: str) -> list[dict]:
    city_profile = city_profile_by_token(city) or {}
    city_name = city_profile.get("name") or city

    with get_connection() as conn:
        with conn.cursor() as cur:
            city_row = find_city_row(cur, city_name)
            if city_row is None:
                return []

            cur.execute(
                f"""
                SELECT
                {CURATED_ROUTE_COLUMNS},
                    COUNT(crp.id) AS poi_count,
                    COALESCE(
                        array_agg(p.name ORDER BY crp.visit_order)
                            FILTER (WHERE p.name IS NOT NULL),
                        ARRAY[]::text[]
                    ) AS stop_names
                FROM curated_routes cr
                JOIN cities c ON c.id = cr.city_id
                LEFT JOIN curated_route_pois crp ON crp.route_id = cr.id
                LEFT JOIN pois p ON p.id = crp.poi_id AND p.is_active = TRUE
                WHERE cr.is_active = TRUE
                  AND cr.city_id = %s
                GROUP BY cr.id, c.name
                ORDER BY cr.title, cr.id
                """,
                (int(city_row["id"]),),
            )
            return cur.fetchall()


def get_curated_route_row(route_id: int) -> dict | None:
    with get_connection() as conn:
        with conn.cursor() as cur:
            cur.execute(
                f"""
                SELECT
                {CURATED_ROUTE_COLUMNS}
                FROM curated_routes cr
                JOIN cities c ON c.id = cr.city_id
                WHERE cr.id = %s
                  AND cr.is_active = TRUE
                """,
                (route_id,),
            )
            return cur.fetchone()


def list_curated_route_poi_rows(route_id: int) -> list[dict]:
    with get_connection() as conn:
        with conn.cursor() as cur:
            cur.execute(
                """
                SELECT
                    p.id,
                    p.name,
                    p.category,
                    p.lat,
                    p.lon,
                    p.opening_hours_raw,
                    p.visit_duration_min,
                    p.base_score,
                    p.short_description,
                    p.wikipedia_url,
                    crp.visit_order,
                    crp.visit_duration_min AS override_visit_duration_min,
                    crp.note
                FROM curated_route_pois crp
                JOIN pois p ON p.id = crp.poi_id
                WHERE crp.route_id = %s
                  AND p.is_active = TRUE
                ORDER BY crp.visit_order
                """,
                (route_id,),
            )
            return cur.fetchall()

from app.db.database import get_connection
from app.services.city_lookup import find_city_row
from app.services.city_profiles import city_profile_by_token


def list_poi_rows(city: str = "nitra") -> list[dict]:
    city_profile = city_profile_by_token(city) or {}

    with get_connection() as conn:
        with conn.cursor() as cur:
            city_row = find_city_row(cur, city_profile.get("name") or city)
            if city_row is None:
                return []

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
                    p.wikipedia_url
                FROM pois p
                WHERE p.city_id = %s
                ORDER BY p.base_score DESC NULLS LAST, p.name
                """,
                (city_row["id"],),
            )
            return cur.fetchall()

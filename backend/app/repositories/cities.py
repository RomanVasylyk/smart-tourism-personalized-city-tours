from app.db.database import get_connection
from app.services.city_profiles import load_city_profiles, normalized_city_token


def list_city_rows() -> list[dict]:
    with get_connection() as conn:
        with conn.cursor() as cur:
            cur.execute(
                """
                SELECT id, name, country, center_lat, center_lon
                FROM cities
                ORDER BY name, id
                """
            )
            rows = cur.fetchall()

    rows_by_name = {}
    for row in rows:
        rows_by_name.setdefault(normalized_city_token(row["name"]), row)

    ordered_rows = []
    for profile in load_city_profiles():
        row = rows_by_name.get(normalized_city_token(str(profile.get("name", ""))))
        if row is None:
            continue

        ordered_rows.append(
            {
                **row,
                "slug": profile.get("slug"),
                "bbox": profile.get("bbox"),
                "available_categories": profile.get("available_categories") or [],
                "default_zoom": profile.get("default_zoom"),
                "routing_limits": profile.get("routing_limits") or {},
                "transport": profile.get("transport") or {},
            }
        )

    return ordered_rows

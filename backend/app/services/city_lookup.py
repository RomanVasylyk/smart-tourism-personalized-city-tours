from __future__ import annotations

from app.services.city_profiles import city_profile_by_token, city_profile_tokens, normalized_city_token


def find_city_row(cur, city: str | None, country: str | None = None) -> dict | None:
    if not hasattr(cur, "fetchone"):
        return {"id": 0, "name": city or "", "country": country or ""}

    profile = city_profile_by_token(city) or {}
    candidate_names = []
    for value in (profile.get("name"), city):
        value = str(value or "").strip()
        if value and value.lower() not in {name.lower() for name in candidate_names}:
            candidate_names.append(value)

    for name in candidate_names:
        if country:
            cur.execute(
                """
                SELECT id, name, country
                FROM cities
                WHERE lower(name) = lower(%s)
                  AND lower(country) = lower(%s)
                ORDER BY id
                LIMIT 1
                """,
                (name, country),
            )
        else:
            cur.execute(
                """
                SELECT id, name, country
                FROM cities
                WHERE lower(name) = lower(%s)
                ORDER BY id
                LIMIT 1
                """,
                (name,),
            )
        row = cur.fetchone()
        if row is not None:
            return row

    tokens = set()
    if profile:
        tokens.update(city_profile_tokens(profile))
    token = normalized_city_token(city)
    if token:
        tokens.add(token)
    if not tokens:
        return None

    if country:
        cur.execute(
            """
            SELECT id, name, country
            FROM cities
            WHERE lower(country) = lower(%s)
            ORDER BY id
            """,
            (country,),
        )
    else:
        cur.execute(
            """
            SELECT id, name, country
            FROM cities
            ORDER BY id
            """
        )

    for row in cur.fetchall():
        if normalized_city_token(row.get("name")) in tokens:
            return row

    return None

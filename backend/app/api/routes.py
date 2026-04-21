from fastapi import APIRouter

from app.db.database import get_connection
from app.services.route_planner import RouteGenerateRequest, generate_route

router = APIRouter()


@router.get("/health")
def health():
    return {"status": "ok"}


@router.get("/cities")
def get_cities():
    with get_connection() as conn:
        with conn.cursor() as cur:
            cur.execute(
                """
                SELECT id, name, country, center_lat, center_lon
                FROM cities
                ORDER BY name
                """
            )
            return cur.fetchall()


@router.get("/pois")
def get_pois(city: str = "nitra"):
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
                    p.wikipedia_url
                FROM pois p
                         JOIN cities c ON c.id = p.city_id
                WHERE lower(c.name) = lower(%s)
                ORDER BY p.base_score DESC NULLS LAST, p.name
                """,
                (city,),
            )
            return cur.fetchall()


@router.post("/route/generate")
def generate_route_endpoint(request: RouteGenerateRequest):
    return generate_route(request)

import re
from datetime import datetime, timedelta

from fastapi import HTTPException
from pydantic import BaseModel, Field

from app.db.database import get_connection
from app.services.routing_service import RoutePoint, RoutingLeg, get_routing_service

DAY_SEQUENCE = ["Mo", "Tu", "We", "Th", "Fr", "Sa", "Su"]
DAY_INDEX = {day: index for index, day in enumerate(DAY_SEQUENCE)}
DAY_ALIASES = {
    "mo": "Mo",
    "mon": "Mo",
    "monday": "Mo",
    "pondelok": "Mo",
    "pondelky": "Mo",
    "tu": "Tu",
    "tue": "Tu",
    "tuesday": "Tu",
    "utorok": "Tu",
    "utorky": "Tu",
    "utoroky": "Tu",
    "we": "We",
    "wed": "We",
    "wednesday": "We",
    "streda": "We",
    "stredy": "We",
    "th": "Th",
    "thu": "Th",
    "thursday": "Th",
    "stvrtok": "Th",
    "štvrtok": "Th",
    "stvrtky": "Th",
    "štvrtky": "Th",
    "fr": "Fr",
    "fri": "Fr",
    "friday": "Fr",
    "piatok": "Fr",
    "piatky": "Fr",
    "sa": "Sa",
    "sat": "Sa",
    "saturday": "Sa",
    "sobota": "Sa",
    "soboty": "Sa",
    "su": "Su",
    "sun": "Su",
    "sunday": "Su",
    "nedela": "Su",
    "nedeľa": "Su",
    "nedele": "Su",
}
DAY_TOKEN_PATTERN = re.compile(
    r"\b(" + "|".join(sorted((re.escape(token) for token in DAY_ALIASES), key=len, reverse=True)) + r")\b",
    re.IGNORECASE,
)
TIME_RANGE_PATTERN = re.compile(r"(\d{1,2})(?::(\d{2}))?\s*-\s*(\d{1,2})(?::(\d{2}))?")


class RouteGenerateRequest(BaseModel):
    city: str = "nitra"
    start_lat: float
    start_lon: float
    available_minutes: int = Field(ge=30, le=720)
    interests: list[str] = Field(default_factory=list)
    pace: str = "normal"
    return_to_start: bool = True
    start_datetime: str | None = None
    respect_opening_hours: bool = True


def parse_start_datetime(raw_value: str | None) -> datetime:
    if raw_value is None:
        return datetime.now().replace(second=0, microsecond=0)

    try:
        return datetime.fromisoformat(raw_value).replace(second=0, microsecond=0)
    except ValueError as exc:
        raise HTTPException(
            status_code=400,
            detail="Invalid start_datetime. Use ISO local datetime, for example 2026-04-19T14:30.",
        ) from exc


def normalize_opening_hours(raw_value: str) -> str:
    normalized = raw_value.strip()
    normalized = normalized.replace("–", "-").replace("—", "-")
    normalized = DAY_TOKEN_PATTERN.sub(lambda match: DAY_ALIASES[match.group(0).casefold()], normalized)
    normalized = re.sub(r"\s+", " ", normalized)
    normalized = re.sub(r"(?<=\d)\s+(?=(Mo|Tu|We|Th|Fr|Sa|Su)\b)", "; ", normalized)
    return normalized


def parse_day_spec(day_spec: str) -> list[int]:
    days: list[int] = []
    normalized = day_spec.replace(" ", "")

    for token in normalized.split(","):
        if not token:
            continue

        if "-" in token:
            start_token, end_token = token.split("-", 1)
            if start_token not in DAY_INDEX or end_token not in DAY_INDEX:
                continue

            start_index = DAY_INDEX[start_token]
            end_index = DAY_INDEX[end_token]

            if start_index <= end_index:
                days.extend(range(start_index, end_index + 1))
            else:
                days.extend(range(start_index, 7))
                days.extend(range(0, end_index + 1))
        elif token in DAY_INDEX:
            days.append(DAY_INDEX[token])

    return list(dict.fromkeys(days))


def parse_time_range(raw_value: str) -> tuple[int, int] | None:
    match = TIME_RANGE_PATTERN.search(raw_value.strip())
    if not match:
        return None

    start_hour = int(match.group(1))
    start_minute = int(match.group(2) or 0)
    end_hour = int(match.group(3))
    end_minute = int(match.group(4) or 0)

    if start_hour > 23 or end_hour > 24 or start_minute > 59 or end_minute > 59:
        return None

    start_total = (start_hour * 60) + start_minute
    end_total = (end_hour * 60) + end_minute

    if end_total <= start_total:
        return None

    return start_total, end_total


def parse_opening_hours(raw_value: str | None) -> dict[int, list[tuple[int, int]]] | None:
    if not raw_value:
        return None

    normalized = normalize_opening_hours(raw_value)
    if normalized.casefold() in {"24/7", "24x7", "24h"}:
        return {day_index: [(0, 24 * 60)] for day_index in range(7)}

    schedule: dict[int, list[tuple[int, int]]] = {}
    parsed_any = False

    for segment in (part.strip() for part in normalized.split(";")):
        if not segment:
            continue

        if segment.casefold() in {"24/7", "24x7", "24h"}:
            return {day_index: [(0, 24 * 60)] for day_index in range(7)}

        match = re.match(r"^([A-Za-z,\-\s]+?)\s*:?\s*(.+)$", segment)
        if not match:
            continue

        day_indexes = parse_day_spec(match.group(1))
        if not day_indexes:
            continue

        intervals = [
            parsed_range
            for parsed_range in (parse_time_range(part) for part in match.group(2).split(","))
            if parsed_range is not None
        ]
        if not intervals:
            continue

        for day_index in day_indexes:
            schedule.setdefault(day_index, []).extend(intervals)

        parsed_any = True

    return schedule if parsed_any else None


def is_poi_open_for_visit(raw_value: str | None, arrival_dt: datetime, visit_minutes: int) -> bool:
    schedule = parse_opening_hours(raw_value)
    if schedule is None:
        return True

    visit_start_minute = (arrival_dt.hour * 60) + arrival_dt.minute
    visit_end_minute = visit_start_minute + visit_minutes
    if visit_end_minute > 24 * 60:
        return False

    intervals = schedule.get(arrival_dt.weekday(), [])
    return any(
        interval_start <= visit_start_minute and visit_end_minute <= interval_end
        for interval_start, interval_end in intervals
    )


def get_route_candidates(request: RouteGenerateRequest) -> list[dict]:
    with get_connection() as conn:
        with conn.cursor() as cur:
            sql = """
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
                  AND p.is_active = TRUE
            """
            params: list = [request.city]

            if request.interests:
                sql += " AND p.category = ANY(%s)"
                params.append(request.interests)

            sql += """
                ORDER BY p.base_score DESC NULLS LAST, p.name
                LIMIT 300
            """

            cur.execute(sql, params)
            return cur.fetchall()


def point_dict(point_type: str, lat: float, lon: float, poi: dict | None = None) -> dict:
    return {
        "type": point_type,
        "poi_id": poi["id"] if poi else None,
        "name": poi["name"] if poi else None,
        "lat": lat,
        "lon": lon,
    }


def leg_dict(
    order: int,
    from_point: dict,
    to_point: dict,
    routing_leg: RoutingLeg,
) -> dict:
    return {
        "order": order,
        "from": from_point,
        "to": to_point,
        "duration_seconds": round(routing_leg.duration_seconds, 1),
        "duration_minutes": routing_leg.duration_minutes,
        "distance_meters": round(routing_leg.distance_meters, 1),
        "geometry": routing_leg.geometry,
        "routing_source": routing_leg.source,
    }


def append_geometry(full_geometry: list[dict], leg_geometry: list[dict]) -> None:
    if not leg_geometry:
        return

    if not full_geometry:
        full_geometry.extend(leg_geometry)
        return

    full_geometry.extend(leg_geometry[1:])


def generate_route(request: RouteGenerateRequest) -> dict:
    start_dt = parse_start_datetime(request.start_datetime)
    candidates = get_route_candidates(request)

    if not candidates:
        raise HTTPException(
            status_code=404,
            detail="No POIs found for the selected city/interests.",
        )

    routing_service = get_routing_service()
    start_point = RoutePoint(lat=request.start_lat, lon=request.start_lon)
    current_point = start_point
    start_endpoint = point_dict("start", request.start_lat, request.start_lon)
    current_endpoint = start_endpoint

    used_ids: set[int] = set()
    category_counts: dict[str, int] = {}
    route_items: list[dict] = []
    legs: list[dict] = []
    full_geometry: list[dict] = []
    elapsed_minutes = 0

    while True:
        best_poi = None
        best_travel_leg = None
        best_visit_minutes = None
        best_utility = -10**9

        for poi in candidates:
            if poi["id"] in used_ids:
                continue

            poi_point = RoutePoint(lat=poi["lat"], lon=poi["lon"])
            travel_leg = routing_service.route_between(current_point, poi_point, request.pace)
            travel_minutes = travel_leg.duration_minutes
            visit_minutes = poi["visit_duration_min"] or 20
            arrival_dt = start_dt + timedelta(minutes=elapsed_minutes + travel_minutes)

            if request.respect_opening_hours and not is_poi_open_for_visit(
                poi.get("opening_hours_raw"),
                arrival_dt,
                visit_minutes,
            ):
                continue

            return_minutes = 0
            if request.return_to_start:
                return_leg = routing_service.route_between(poi_point, start_point, request.pace)
                return_minutes = return_leg.duration_minutes

            projected_total = elapsed_minutes + travel_minutes + visit_minutes + return_minutes
            if projected_total > request.available_minutes:
                continue

            score = float(poi["base_score"] or 0.0)
            repeat_penalty = 0.8 * category_counts.get(poi["category"], 0)
            utility = (score * 10) - (travel_minutes * 0.35) - repeat_penalty

            if utility > best_utility:
                best_utility = utility
                best_poi = poi
                best_travel_leg = travel_leg
                best_visit_minutes = visit_minutes

        if best_poi is None:
            break

        elapsed_minutes += best_travel_leg.duration_minutes + best_visit_minutes
        used_ids.add(best_poi["id"])
        category_counts[best_poi["category"]] = category_counts.get(best_poi["category"], 0) + 1
        next_endpoint = point_dict("poi", best_poi["lat"], best_poi["lon"], best_poi)

        route_items.append(
            {
                "order": len(route_items) + 1,
                "poi_id": best_poi["id"],
                "name": best_poi["name"],
                "category": best_poi["category"],
                "lat": best_poi["lat"],
                "lon": best_poi["lon"],
                "travel_minutes_from_previous": best_travel_leg.duration_minutes,
                "travel_distance_meters_from_previous": round(best_travel_leg.distance_meters, 1),
                "routing_source_from_previous": best_travel_leg.source,
                "visit_duration_min": best_visit_minutes,
                "arrival_after_min": elapsed_minutes - best_visit_minutes,
                "departure_after_min": elapsed_minutes,
                "base_score": best_poi["base_score"],
                "wikipedia_url": best_poi["wikipedia_url"],
                "opening_hours_raw": best_poi["opening_hours_raw"],
            }
        )

        legs.append(
            leg_dict(
                order=len(legs) + 1,
                from_point=current_endpoint,
                to_point=next_endpoint,
                routing_leg=best_travel_leg,
            )
        )
        append_geometry(full_geometry, best_travel_leg.geometry)

        current_point = RoutePoint(lat=best_poi["lat"], lon=best_poi["lon"])
        current_endpoint = next_endpoint

    return_to_start_minutes = 0
    if request.return_to_start and route_items:
        return_leg = routing_service.route_between(current_point, start_point, request.pace)
        return_to_start_minutes = return_leg.duration_minutes
        elapsed_minutes += return_to_start_minutes
        legs.append(
            leg_dict(
                order=len(legs) + 1,
                from_point=current_endpoint,
                to_point=start_endpoint,
                routing_leg=return_leg,
            )
        )
        append_geometry(full_geometry, return_leg.geometry)

    total_visit_minutes = sum(item["visit_duration_min"] for item in route_items)
    total_walk_minutes = elapsed_minutes - total_visit_minutes

    return {
        "city": request.city,
        "start": {"lat": request.start_lat, "lon": request.start_lon},
        "start_datetime": start_dt.isoformat(timespec="minutes"),
        "pace": request.pace,
        "interests": request.interests,
        "return_to_start": request.return_to_start,
        "respect_opening_hours": request.respect_opening_hours,
        "available_minutes": request.available_minutes,
        "used_minutes": elapsed_minutes,
        "remaining_minutes": max(0, request.available_minutes - elapsed_minutes),
        "total_visit_minutes": total_visit_minutes,
        "total_walk_minutes": total_walk_minutes,
        "return_to_start_minutes": return_to_start_minutes,
        "poi_count": len(route_items),
        "route": route_items,
        "legs": legs,
        "full_geometry": full_geometry,
    }

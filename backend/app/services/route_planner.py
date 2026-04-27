import re
from datetime import datetime, timedelta
from typing import Literal

from fastapi import HTTPException
from pydantic import BaseModel, Field
from psycopg import Error as PsycopgError

from app.db.database import get_connection
from app.services.city_profiles import city_profile_by_token
from app.services.feedback_stats import (
    PlannerFeedbackProfile,
    PlannerFeedbackStats,
    load_planner_feedback_profile,
)
from app.services.routing_service import RoutePoint, get_routing_service
from app.services.transport_planner import (
    TRANSPORT_MODE_WALK,
    TravelPlan,
    TravelSegment,
    normalized_transport_mode,
    plan_travel,
)

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
BASE_SCORE_MULTIPLIER = 10.0
TRAVEL_MINUTE_PENALTY = 0.35
REPEAT_CATEGORY_PENALTY = 0.8


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
    exclude_poi_ids: list[int] = Field(default_factory=list)
    transport_mode: Literal["walk", "walk_or_mhd"] = TRANSPORT_MODE_WALK


def clamp(value: float, minimum: float, maximum: float) -> float:
    return max(minimum, min(maximum, value))


def rating_signal(value: float | None) -> float:
    if value is None:
        return 0.0
    return clamp((value - 3.0) / 2.0, -1.0, 1.0)


def rate_signal(value: float | None, neutral: float = 0.5) -> float:
    if value is None:
        return 0.0
    return clamp(value - neutral, -1.0, 1.0)


def stats_confidence(stats: PlannerFeedbackStats, full_weight_after: int) -> float:
    if full_weight_after <= 0:
        return 1.0
    return clamp(stats.sample_size / full_weight_after, 0.0, 1.0)


def walking_discomfort_signal(
    feedback_profile: PlannerFeedbackProfile,
    effective_transport_mode: str,
) -> float:
    city_confidence = stats_confidence(feedback_profile.city_stats, 6)
    discomfort = city_confidence * max(0.0, (feedback_profile.city_stats.too_much_walking_rate or 0.0) - 0.35)

    if effective_transport_mode != TRANSPORT_MODE_WALK:
        transport_confidence = stats_confidence(feedback_profile.transport_mode_stats, 4)
        discomfort += transport_confidence * max(
            0.0,
            (feedback_profile.transport_mode_stats.too_much_walking_rate or 0.0) - 0.30,
        )

    return discomfort


def score_candidate(
    poi: dict,
    travel_plan: TravelPlan,
    category_counts: dict[str, int],
    feedback_profile: PlannerFeedbackProfile,
    effective_transport_mode: str,
) -> tuple[float, dict[str, float]]:
    poi_stats = PlannerFeedbackStats.from_row(poi, "poi_feedback_")
    category_stats = PlannerFeedbackStats.from_row(poi, "category_feedback_")
    city_stats = feedback_profile.city_stats
    transport_stats = feedback_profile.transport_mode_stats

    poi_confidence = stats_confidence(poi_stats, 4)
    category_confidence = stats_confidence(category_stats, 10)
    city_confidence = stats_confidence(city_stats, 6)
    transport_confidence = stats_confidence(transport_stats, 4)

    base_component = float(poi["base_score"] or 0.0) * BASE_SCORE_MULTIPLIER
    travel_penalty = travel_plan.duration_minutes * TRAVEL_MINUTE_PENALTY
    repeat_penalty = REPEAT_CATEGORY_PENALTY * category_counts.get(poi["category"], 0)

    poi_bonus = poi_confidence * (
        (rating_signal(poi_stats.average_rating) * 1.8)
        + (rate_signal(poi_stats.interesting_pois_rate) * 1.3)
    )
    category_bonus = category_confidence * (
        (rating_signal(category_stats.average_rating) * 0.9)
        + (rate_signal(category_stats.interesting_pois_rate) * 0.8)
    )
    completion_bonus = (
        (poi_confidence * rate_signal(poi_stats.completion_rate) * 2.0)
        + (category_confidence * rate_signal(category_stats.completion_rate) * 1.0)
        + (city_confidence * rate_signal(city_stats.completion_rate) * 0.7)
    )
    skip_penalty = (
        (poi_confidence * max(0.0, (poi_stats.skip_rate or 0.0) - 0.15) * 4.0)
        + (category_confidence * max(0.0, (category_stats.skip_rate or 0.0) - 0.18) * 2.2)
        + (city_confidence * max(0.0, (city_stats.skip_rate or 0.0) - 0.20) * 1.2)
    )

    walking_penalty = 0.0
    if travel_plan.mode == "walk":
        long_walk_factor = clamp((travel_plan.duration_minutes - 12) / 16, 0.0, 1.5)
        walking_penalty = (
            walking_discomfort_signal(feedback_profile, effective_transport_mode)
            * long_walk_factor
            * travel_plan.duration_minutes
            * 0.55
        )

    transport_bonus = 0.0
    if effective_transport_mode != TRANSPORT_MODE_WALK and travel_plan.mode == "transit":
        transport_bonus = transport_confidence * (
            (rating_signal(transport_stats.average_rating) * 1.2)
            + (rate_signal(transport_stats.convenient_rate) * 1.7)
            + (rate_signal(transport_stats.completion_rate) * 0.7)
            + (rate_signal(transport_stats.interesting_pois_rate) * 0.6)
        )

    final_score = (
        base_component
        + poi_bonus
        + category_bonus
        + completion_bonus
        + transport_bonus
        - travel_penalty
        - walking_penalty
        - skip_penalty
        - repeat_penalty
    )

    return final_score, {
        "base_score": base_component,
        "poi_bonus": poi_bonus,
        "category_bonus": category_bonus,
        "completion_bonus": completion_bonus,
        "transport_bonus": transport_bonus,
        "travel_penalty": travel_penalty,
        "walking_penalty": walking_penalty,
        "skip_penalty": skip_penalty,
        "repeat_penalty": repeat_penalty,
        "final_score": final_score,
    }


def rounded_score_breakdown(score_breakdown: dict[str, float]) -> dict[str, float]:
    return {key: round(value, 3) for key, value in score_breakdown.items()}


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
    city_profile = city_profile_by_token(request.city) or {}
    city_name = city_profile.get("name") or request.city
    routing_limits = city_profile.get("routing_limits") or {}
    limit = int(routing_limits.get("max_poi_candidates") or 300)

    base_select = """
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
    """
    feedback_joins = """
        LEFT JOIN poi_feedback_stats pfs ON pfs.poi_id = p.id
        LEFT JOIN category_feedback_stats cfs
            ON cfs.city_id = c.id
           AND cfs.category = p.category
    """
    feedback_columns = """
        ,
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

    def build_sql(include_feedback: bool) -> tuple[str, list]:
        sql = base_select
        if include_feedback:
            sql = sql.replace("p.wikipedia_url", f"p.wikipedia_url{feedback_columns}")
            sql += feedback_joins

        sql += """
            WHERE lower(c.name) = lower(%s)
              AND p.is_active = TRUE
        """
        params: list = [city_name]

        if request.interests:
            sql += " AND p.category = ANY(%s)"
            params.append(request.interests)

        if request.exclude_poi_ids:
            sql += " AND NOT (p.id = ANY(%s))"
            params.append(request.exclude_poi_ids)

        sql += """
            ORDER BY p.base_score DESC NULLS LAST, p.name
            LIMIT %s
        """
        params.append(limit)
        return sql, params

    with get_connection() as conn:
        with conn.cursor() as cur:
            sql, params = build_sql(include_feedback=True)
            try:
                cur.execute(sql, params)
            except PsycopgError:
                fallback_sql, fallback_params = build_sql(include_feedback=False)
                cur.execute(fallback_sql, fallback_params)
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
    travel_plan: TravelPlan,
) -> dict:
    first_segment = travel_plan.segments[0] if travel_plan.segments else None
    last_segment = travel_plan.segments[-1] if travel_plan.segments else None
    return {
        "order": order,
        "mode": travel_plan.mode,
        "from": from_point,
        "to": to_point,
        "duration_seconds": round(travel_plan.duration_seconds, 1),
        "duration_minutes": travel_plan.duration_minutes,
        "distance_meters": round(travel_plan.distance_meters, 1),
        "geometry": travel_plan.geometry,
        "routing_source": travel_plan.source,
        "departure_time": format_optional_datetime(first_segment.departure_time if first_segment else None),
        "arrival_time": format_optional_datetime(last_segment.arrival_time if last_segment else None),
        "segments": [
            segment_dict(index + 1, segment)
            for index, segment in enumerate(travel_plan.segments)
        ],
    }


def segment_dict(order: int, segment: TravelSegment) -> dict:
    return {
        "order": order,
        "mode": segment.mode,
        "duration_seconds": round(segment.duration_seconds, 1),
        "duration_minutes": segment.duration_minutes,
        "distance_meters": round(segment.distance_meters, 1),
        "geometry": segment.geometry,
        "source": segment.source,
        "line_name": segment.line_name,
        "from_stop_name": segment.from_stop_name,
        "to_stop_name": segment.to_stop_name,
        "departure_time": format_optional_datetime(segment.departure_time),
        "arrival_time": format_optional_datetime(segment.arrival_time),
        "wait_minutes_before_departure": segment.wait_minutes_before_departure,
        "in_vehicle_minutes": segment.in_vehicle_minutes,
    }


def format_optional_datetime(value: datetime | None) -> str | None:
    if value is None:
        return None
    return value.isoformat(timespec="minutes")


def append_geometry(full_geometry: list[dict], leg_geometry: list[dict]) -> None:
    if not leg_geometry:
        return

    if not full_geometry:
        full_geometry.extend(leg_geometry)
        return

    full_geometry.extend(leg_geometry[1:])


def generate_route(request: RouteGenerateRequest) -> dict:
    start_dt = parse_start_datetime(request.start_datetime)
    city_profile = city_profile_by_token(request.city) or {}
    effective_transport_mode = normalized_transport_mode(request.transport_mode, city_profile)
    feedback_profile = load_planner_feedback_profile(request.city, effective_transport_mode)
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
    elapsed_actual_seconds = 0.0

    while True:
        best_poi = None
        best_travel_leg = None
        best_visit_minutes = None
        best_score_breakdown = None
        best_utility = -10**9

        for poi in candidates:
            if poi["id"] in used_ids:
                continue

            poi_point = RoutePoint(lat=poi["lat"], lon=poi["lon"])
            departure_dt = start_dt + timedelta(seconds=elapsed_actual_seconds)
            travel_plan = plan_travel(
                start=current_point,
                end=poi_point,
                pace=request.pace,
                routing_service=routing_service,
                city_profile=city_profile,
                transport_mode=effective_transport_mode,
                departure_dt=departure_dt,
            )
            travel_minutes = travel_plan.duration_minutes
            visit_minutes = poi["visit_duration_min"] or 20
            arrival_dt = departure_dt + timedelta(seconds=travel_plan.duration_seconds)

            if request.respect_opening_hours and not is_poi_open_for_visit(
                poi.get("opening_hours_raw"),
                arrival_dt,
                visit_minutes,
            ):
                continue

            return_minutes = 0
            if request.return_to_start:
                return_departure_dt = arrival_dt + timedelta(minutes=visit_minutes)
                return_plan = plan_travel(
                    start=poi_point,
                    end=start_point,
                    pace=request.pace,
                    routing_service=routing_service,
                    city_profile=city_profile,
                    transport_mode=effective_transport_mode,
                    departure_dt=return_departure_dt,
                )
                return_minutes = return_plan.duration_minutes

            projected_total = elapsed_minutes + travel_minutes + visit_minutes + return_minutes
            if projected_total > request.available_minutes:
                continue

            utility, score_breakdown = score_candidate(
                poi=poi,
                travel_plan=travel_plan,
                category_counts=category_counts,
                feedback_profile=feedback_profile,
                effective_transport_mode=effective_transport_mode,
            )

            if utility > best_utility:
                best_utility = utility
                best_poi = poi
                best_travel_leg = travel_plan
                best_visit_minutes = visit_minutes
                best_score_breakdown = score_breakdown

        if best_poi is None:
            break

        elapsed_actual_seconds += best_travel_leg.duration_seconds + (best_visit_minutes * 60)
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
                "travel_mode_from_previous": best_travel_leg.mode,
                "visit_duration_min": best_visit_minutes,
                "arrival_after_min": elapsed_minutes - best_visit_minutes,
                "departure_after_min": elapsed_minutes,
                "base_score": best_poi["base_score"],
                "planner_score": round(best_utility, 3),
                "planner_score_breakdown": rounded_score_breakdown(best_score_breakdown or {}),
                "wikipedia_url": best_poi["wikipedia_url"],
                "opening_hours_raw": best_poi["opening_hours_raw"],
            }
        )

        legs.append(
            leg_dict(
                order=len(legs) + 1,
                from_point=current_endpoint,
                to_point=next_endpoint,
                travel_plan=best_travel_leg,
            )
        )
        append_geometry(full_geometry, best_travel_leg.geometry)

        current_point = RoutePoint(lat=best_poi["lat"], lon=best_poi["lon"])
        current_endpoint = next_endpoint

    return_to_start_minutes = 0
    if request.return_to_start and route_items:
        return_departure_dt = start_dt + timedelta(seconds=elapsed_actual_seconds)
        return_plan = plan_travel(
            start=current_point,
            end=start_point,
            pace=request.pace,
            routing_service=routing_service,
            city_profile=city_profile,
            transport_mode=effective_transport_mode,
            departure_dt=return_departure_dt,
        )
        return_to_start_minutes = return_plan.duration_minutes
        elapsed_actual_seconds += return_plan.duration_seconds
        elapsed_minutes += return_to_start_minutes
        legs.append(
            leg_dict(
                order=len(legs) + 1,
                from_point=current_endpoint,
                to_point=start_endpoint,
                travel_plan=return_plan,
            )
        )
        append_geometry(full_geometry, return_plan.geometry)

    total_visit_minutes = sum(item["visit_duration_min"] for item in route_items)
    total_walk_minutes = elapsed_minutes - total_visit_minutes

    return {
        "city": request.city,
        "start": {"lat": request.start_lat, "lon": request.start_lon},
        "start_datetime": start_dt.isoformat(timespec="minutes"),
        "pace": request.pace,
        "interests": request.interests,
        "transport_mode": effective_transport_mode,
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

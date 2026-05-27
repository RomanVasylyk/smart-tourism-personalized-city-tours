import re
from datetime import datetime, timedelta
from typing import Literal

from fastapi import HTTPException
from pydantic import BaseModel, Field
from psycopg import Error as PsycopgError

from app.db.database import get_connection
from app.services.city_lookup import find_city_row
from app.services.city_profiles import city_profile_by_token
from app.services.feedback_stats import load_planner_feedback_profile
from app.services.route_planning.opening_hours import is_poi_open_for_visit
from app.services.route_planning.response import append_geometry, leg_dict, point_dict
from app.services.route_planning.scoring import (
    BASE_SCORE_MULTIPLIER,
    PREFERRED_POI_BONUS,
    REPEAT_CATEGORY_PENALTY,
    TRAVEL_MINUTE_PENALTY,
    rounded_score_breakdown,
    score_candidate,
)
from app.services.routing_service import RoutePoint, get_routing_service, haversine_km, walking_speed_kmh
from app.services.transport_planner import (
    TRANSPORT_MODE_WALK,
    normalized_transport_mode,
    plan_travel,
)

DEFAULT_MAX_EXACT_POI_EVALUATIONS_PER_STEP = 60
DEFAULT_MAX_EXACT_POI_EVALUATIONS_PER_STEP_TRANSIT = 28
APPROXIMATE_DISTANCE_INFLATION_FACTOR = 1.2


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
    preferred_poi_ids: list[int] = Field(default_factory=list)
    transport_mode: Literal["walk", "walk_or_mhd"] = TRANSPORT_MODE_WALK


class RouteLegRequest(BaseModel):
    city: str = "nitra"
    start_lat: float
    start_lon: float
    end_lat: float
    end_lon: float
    end_poi_id: int
    end_name: str | None = None
    pace: str = "normal"
    start_datetime: str | None = None
    transport_mode: Literal["walk", "walk_or_mhd"] = TRANSPORT_MODE_WALK


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


def get_route_candidates(request: RouteGenerateRequest) -> list[dict]:
    city_profile = city_profile_by_token(request.city) or {}
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

    def build_sql(include_feedback: bool, city_id: int) -> tuple[str, list]:
        sql = base_select
        if include_feedback:
            sql = sql.replace("p.wikipedia_url", f"p.wikipedia_url{feedback_columns}")
            sql += feedback_joins

        sql += """
            WHERE c.id = %s
              AND p.is_active = TRUE
        """
        params: list = [city_id]

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
            city_row = find_city_row(cur, city_profile.get("name") or request.city)
            if city_row is None:
                return []

            city_id = int(city_row["id"])
            sql, params = build_sql(include_feedback=True, city_id=city_id)
            try:
                cur.execute(sql, params)
            except PsycopgError:
                fallback_sql, fallback_params = build_sql(include_feedback=False, city_id=city_id)
                cur.execute(fallback_sql, fallback_params)
            rows = cur.fetchall()

            excluded_ids = {int(poi_id) for poi_id in request.exclude_poi_ids}
            preferred_ids = [
                int(poi_id)
                for poi_id in request.preferred_poi_ids
                if int(poi_id) not in excluded_ids
            ]
            if not preferred_ids:
                return rows

            existing_ids = {int(row["id"]) for row in rows}
            missing_preferred_ids = [poi_id for poi_id in preferred_ids if poi_id not in existing_ids]
            if not missing_preferred_ids:
                return rows

            preferred_sql = base_select
            preferred_sql += feedback_joins
            preferred_sql = preferred_sql.replace("p.wikipedia_url", f"p.wikipedia_url{feedback_columns}")
            preferred_sql += """
                WHERE c.id = %s
                  AND p.is_active = TRUE
                  AND p.id = ANY(%s)
            """
            cur.execute(preferred_sql, (city_id, missing_preferred_ids))
            preferred_rows = cur.fetchall()
            return rows + preferred_rows


def max_exact_poi_evaluations_per_step(
    city_profile: dict,
    effective_transport_mode: str,
) -> int:
    routing_limits = city_profile.get("routing_limits") or {}
    if effective_transport_mode != TRANSPORT_MODE_WALK:
        transit_override = routing_limits.get("max_exact_poi_evaluations_per_step_transit")
        if transit_override is not None:
            return max(1, int(transit_override))

    configured_limit = routing_limits.get("max_exact_poi_evaluations_per_step")
    if configured_limit is not None:
        return max(1, int(configured_limit))

    if effective_transport_mode != TRANSPORT_MODE_WALK:
        return DEFAULT_MAX_EXACT_POI_EVALUATIONS_PER_STEP_TRANSIT
    return DEFAULT_MAX_EXACT_POI_EVALUATIONS_PER_STEP


def generate_route_leg(request: RouteLegRequest) -> dict:
    city_profile = city_profile_by_token(request.city) or {}
    effective_transport_mode = normalized_transport_mode(request.transport_mode, city_profile)
    departure_dt = parse_start_datetime(request.start_datetime)
    routing_service = get_routing_service()

    start = RoutePoint(lat=request.start_lat, lon=request.start_lon)
    end = RoutePoint(lat=request.end_lat, lon=request.end_lon)
    travel_plan = plan_travel(
        start=start,
        end=end,
        pace=request.pace,
        routing_service=routing_service,
        city_profile=city_profile,
        transport_mode=effective_transport_mode,
        departure_dt=departure_dt,
    )

    from_point = point_dict("start", request.start_lat, request.start_lon)
    to_point = point_dict(
        "poi",
        request.end_lat,
        request.end_lon,
        {
            "id": request.end_poi_id,
            "name": request.end_name,
        },
    )
    return leg_dict(
        order=1,
        from_point=from_point,
        to_point=to_point,
        travel_plan=travel_plan,
    )


def estimated_minutes_for_distance(distance_meters: float, speed_kmh: float) -> int:
    if distance_meters <= 0:
        return 1
    if speed_kmh <= 0:
        return 10**9
    return max(1, round((distance_meters / 1000.0) / speed_kmh * 60))


def approximate_travel_minutes(
    start: RoutePoint,
    end: RoutePoint,
    pace: str,
    city_profile: dict,
    effective_transport_mode: str,
) -> int:
    direct_distance_meters = haversine_km(start.lat, start.lon, end.lat, end.lon) * 1000.0
    inflated_distance_meters = direct_distance_meters * APPROXIMATE_DISTANCE_INFLATION_FACTOR
    walking_minutes = estimated_minutes_for_distance(inflated_distance_meters, walking_speed_kmh(pace))

    if effective_transport_mode == TRANSPORT_MODE_WALK:
        return walking_minutes

    transport_profile = city_profile.get("transport") or {}
    if not transport_profile.get("mhd_enabled"):
        return walking_minutes

    min_direct_distance = float(transport_profile.get("min_direct_distance_meters") or 1200)
    if direct_distance_meters < min_direct_distance:
        return walking_minutes

    average_wait_minutes = float(transport_profile.get("average_wait_minutes") or 4.0)
    transit_speed_kmh = float(transport_profile.get("transit_speed_kmh") or 24.0)
    max_first_mile = float(transport_profile.get("max_first_mile_meters") or 650.0)
    max_last_mile = float(transport_profile.get("max_last_mile_meters") or 650.0)
    max_transfer_distance = max_first_mile + max_last_mile
    transfer_distance_meters = min(max_transfer_distance, inflated_distance_meters * 0.45)
    transit_distance_meters = max(0.0, inflated_distance_meters - transfer_distance_meters)
    transfer_minutes = estimated_minutes_for_distance(transfer_distance_meters, walking_speed_kmh(pace))
    transit_minutes = estimated_minutes_for_distance(transit_distance_meters, transit_speed_kmh)
    optimistic_transit_minutes = average_wait_minutes + transfer_minutes + transit_minutes
    return max(1, min(walking_minutes, optimistic_transit_minutes))


def approximate_candidate_priority(
    poi: dict,
    approx_travel_minutes: int,
    category_counts: dict[str, int],
    preferred_poi_ids: set[int],
) -> float:
    base_component = float(poi["base_score"] or 0.0) * BASE_SCORE_MULTIPLIER
    repeat_penalty = REPEAT_CATEGORY_PENALTY * category_counts.get(poi["category"], 0)
    travel_penalty = approx_travel_minutes * TRAVEL_MINUTE_PENALTY
    preferred_bonus = PREFERRED_POI_BONUS if int(poi["id"]) in preferred_poi_ids else 0.0
    return base_component + preferred_bonus - repeat_penalty - travel_penalty


def shortlist_route_candidates(
    *,
    candidates: list[dict],
    used_ids: set[int],
    current_point: RoutePoint,
    start_point: RoutePoint,
    pace: str,
    return_to_start: bool,
    category_counts: dict[str, int],
    city_profile: dict,
    effective_transport_mode: str,
    exact_limit: int,
    preferred_poi_ids: set[int],
) -> list[dict]:
    remaining_candidates = [poi for poi in candidates if int(poi["id"]) not in used_ids]
    if len(remaining_candidates) <= exact_limit:
        return remaining_candidates

    scored_candidates: list[tuple[float, float, dict]] = []
    for poi in remaining_candidates:
        poi_point = RoutePoint(lat=poi["lat"], lon=poi["lon"])
        approx_travel = approximate_travel_minutes(
            start=current_point,
            end=poi_point,
            pace=pace,
            city_profile=city_profile,
            effective_transport_mode=effective_transport_mode,
        )
        approx_return = 0
        if return_to_start:
            approx_return = approximate_travel_minutes(
                start=poi_point,
                end=start_point,
                pace=pace,
                city_profile=city_profile,
                effective_transport_mode=effective_transport_mode,
            )

        scored_candidates.append(
            (
                approximate_candidate_priority(
                    poi,
                    approx_travel_minutes=approx_travel + approx_return,
                    category_counts=category_counts,
                    preferred_poi_ids=preferred_poi_ids,
                ),
                float(poi["base_score"] or 0.0),
                poi,
            )
        )

    if not scored_candidates:
        return []

    scored_candidates.sort(key=lambda item: (item[0], item[1], -item[2]["id"]), reverse=True)
    preferred_candidates = [poi for poi in remaining_candidates if int(poi["id"]) in preferred_poi_ids]
    preferred_candidate_ids = {int(poi["id"]) for poi in preferred_candidates}
    shortlisted = preferred_candidates + [
        poi
        for _, _, poi in scored_candidates
        if int(poi["id"]) not in preferred_candidate_ids
    ]
    return shortlisted[: max(exact_limit, len(preferred_candidates))]


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
    excluded_poi_ids = {int(poi_id) for poi_id in request.exclude_poi_ids}
    ordered_preferred_poi_ids = list(
        dict.fromkeys(
            int(poi_id)
            for poi_id in request.preferred_poi_ids
            if int(poi_id) not in excluded_poi_ids
        )
    )
    preferred_poi_ids = set(ordered_preferred_poi_ids)
    candidates_by_id = {int(poi["id"]): poi for poi in candidates}
    unavailable_preferred_ids = [poi_id for poi_id in ordered_preferred_poi_ids if poi_id not in candidates_by_id]
    if unavailable_preferred_ids:
        raise HTTPException(
            status_code=422,
            detail=f"Required POIs are not available for this city: {unavailable_preferred_ids}",
        )
    category_counts: dict[str, int] = {}
    route_items: list[dict] = []
    legs: list[dict] = []
    full_geometry: list[dict] = []
    elapsed_minutes = 0
    elapsed_actual_seconds = 0.0
    exact_candidate_limit = max_exact_poi_evaluations_per_step(city_profile, effective_transport_mode)

    while True:
        best_poi = None
        best_travel_leg = None
        best_visit_minutes = None
        best_score_breakdown = None
        best_utility = -10**9
        departure_dt = start_dt + timedelta(seconds=elapsed_actual_seconds)
        forced_preferred_poi = next(
            (
                candidates_by_id[poi_id]
                for poi_id in ordered_preferred_poi_ids
                if poi_id not in used_ids and poi_id in candidates_by_id
            ),
            None,
        )
        if forced_preferred_poi is not None:
            evaluation_candidates = [forced_preferred_poi]
        else:
            evaluation_candidates = shortlist_route_candidates(
                candidates=candidates,
                used_ids=used_ids,
                current_point=current_point,
                start_point=start_point,
                pace=request.pace,
                return_to_start=request.return_to_start,
                category_counts=category_counts,
                city_profile=city_profile,
                effective_transport_mode=effective_transport_mode,
                exact_limit=exact_candidate_limit,
                preferred_poi_ids=preferred_poi_ids,
            )

        if not evaluation_candidates:
            break

        for poi in evaluation_candidates:
            is_forced_preferred = forced_preferred_poi is not None and int(poi["id"]) == int(forced_preferred_poi["id"])
            poi_point = RoutePoint(lat=poi["lat"], lon=poi["lon"])
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

            if not is_forced_preferred and request.respect_opening_hours and not is_poi_open_for_visit(
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
            if not is_forced_preferred and projected_total > request.available_minutes:
                continue

            utility, score_breakdown = score_candidate(
                poi=poi,
                travel_plan=travel_plan,
                category_counts=category_counts,
                feedback_profile=feedback_profile,
                effective_transport_mode=effective_transport_mode,
                preferred_poi_ids=preferred_poi_ids,
            )

            if utility > best_utility:
                best_utility = utility
                best_poi = poi
                best_travel_leg = travel_plan
                best_visit_minutes = visit_minutes
                best_score_breakdown = score_breakdown

        if best_poi is None:
            if forced_preferred_poi is not None:
                used_ids.add(int(forced_preferred_poi["id"]))
                continue
            break

        elapsed_actual_seconds += best_travel_leg.duration_seconds + (best_visit_minutes * 60)
        elapsed_minutes += best_travel_leg.duration_minutes + best_visit_minutes
        used_ids.add(int(best_poi["id"]))
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
    generated_poi_ids = {int(item["poi_id"]) for item in route_items}
    missing_preferred_ids = [poi_id for poi_id in ordered_preferred_poi_ids if poi_id not in generated_poi_ids]
    if missing_preferred_ids:
        raise HTTPException(
            status_code=409,
            detail=f"Required POIs were not included in generated route: {missing_preferred_ids}",
        )

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

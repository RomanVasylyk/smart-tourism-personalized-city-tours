from __future__ import annotations

from datetime import datetime, timedelta

from app.services.feedback_stats import PlannerFeedbackProfile
from app.services.route_planning.models import CandidateEvaluation, CandidatePoi
from app.services.route_planning.opening_hours import is_poi_open_for_visit
from app.services.route_planning.scoring import (
    BASE_SCORE_MULTIPLIER,
    PREFERRED_POI_BONUS,
    REPEAT_CATEGORY_PENALTY,
    TRAVEL_MINUTE_PENALTY,
    score_candidate,
)
from app.services.routing_service import RoutePoint, RoutingService, haversine_km, walking_speed_kmh
from app.services.transport_planner import TRANSPORT_MODE_WALK, plan_travel

DEFAULT_MAX_EXACT_POI_EVALUATIONS_PER_STEP = 60
DEFAULT_MAX_EXACT_POI_EVALUATIONS_PER_STEP_TRANSIT = 28
APPROXIMATE_DISTANCE_INFLATION_FACTOR = 1.2


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


class RouteCandidateEvaluator:
    def __init__(
        self,
        *,
        request,
        city_profile: dict,
        effective_transport_mode: str,
        feedback_profile: PlannerFeedbackProfile,
        routing_service: RoutingService,
        start_point: RoutePoint,
        preferred_poi_ids: set[int],
    ):
        self.request = request
        self.city_profile = city_profile
        self.effective_transport_mode = effective_transport_mode
        self.feedback_profile = feedback_profile
        self.routing_service = routing_service
        self.start_point = start_point
        self.preferred_poi_ids = preferred_poi_ids
        self.exact_candidate_limit = max_exact_poi_evaluations_per_step(city_profile, effective_transport_mode)

    def shortlist(
        self,
        *,
        candidates: list[CandidatePoi],
        used_ids: set[int],
        current_point: RoutePoint,
        category_counts: dict[str, int],
    ) -> list[CandidatePoi]:
        remaining_candidates = [poi for poi in candidates if poi.id not in used_ids]
        if len(remaining_candidates) <= self.exact_candidate_limit:
            return remaining_candidates

        scored_candidates: list[tuple[float, float, CandidatePoi]] = []
        for poi in remaining_candidates:
            approx_travel = approximate_travel_minutes(
                start=current_point,
                end=poi.point,
                pace=self.request.pace,
                city_profile=self.city_profile,
                effective_transport_mode=self.effective_transport_mode,
            )
            approx_return = 0
            if self.request.return_to_start:
                approx_return = approximate_travel_minutes(
                    start=poi.point,
                    end=self.start_point,
                    pace=self.request.pace,
                    city_profile=self.city_profile,
                    effective_transport_mode=self.effective_transport_mode,
                )

            scored_candidates.append(
                (
                    approximate_candidate_priority(
                        poi,
                        approx_travel_minutes=approx_travel + approx_return,
                        category_counts=category_counts,
                        preferred_poi_ids=self.preferred_poi_ids,
                    ),
                    float(poi.base_score or 0.0),
                    poi,
                )
            )

        if not scored_candidates:
            return []

        scored_candidates.sort(key=lambda item: (item[0], item[1], -item[2].id), reverse=True)
        preferred_candidates = [poi for poi in remaining_candidates if poi.id in self.preferred_poi_ids]
        preferred_candidate_ids = {poi.id for poi in preferred_candidates}
        shortlisted = preferred_candidates + [
            poi for _, _, poi in scored_candidates if poi.id not in preferred_candidate_ids
        ]
        return shortlisted[: max(self.exact_candidate_limit, len(preferred_candidates))]

    def best_candidate(
        self,
        *,
        evaluation_candidates: list[CandidatePoi],
        forced_preferred_poi: CandidatePoi | None,
        current_point: RoutePoint,
        elapsed_minutes: int,
        departure_dt: datetime,
        category_counts: dict[str, int],
    ) -> CandidateEvaluation | None:
        best_evaluation: CandidateEvaluation | None = None
        best_utility = -(10**9)

        for poi in evaluation_candidates:
            is_forced_preferred = forced_preferred_poi is not None and poi.id == forced_preferred_poi.id
            travel_plan = plan_travel(
                start=current_point,
                end=poi.point,
                pace=self.request.pace,
                routing_service=self.routing_service,
                city_profile=self.city_profile,
                transport_mode=self.effective_transport_mode,
                departure_dt=departure_dt,
            )
            travel_minutes = travel_plan.duration_minutes
            visit_minutes = poi.visit_duration_min or 20
            arrival_dt = departure_dt + timedelta(seconds=travel_plan.duration_seconds)

            if (
                not is_forced_preferred
                and self.request.respect_opening_hours
                and not is_poi_open_for_visit(
                    poi.opening_hours_raw,
                    arrival_dt,
                    visit_minutes,
                )
            ):
                continue

            return_minutes = 0
            if self.request.return_to_start:
                return_departure_dt = arrival_dt + timedelta(minutes=visit_minutes)
                return_plan = plan_travel(
                    start=poi.point,
                    end=self.start_point,
                    pace=self.request.pace,
                    routing_service=self.routing_service,
                    city_profile=self.city_profile,
                    transport_mode=self.effective_transport_mode,
                    departure_dt=return_departure_dt,
                )
                return_minutes = return_plan.duration_minutes

            projected_total = elapsed_minutes + travel_minutes + visit_minutes + return_minutes
            if not is_forced_preferred and projected_total > self.request.available_minutes:
                continue

            utility, score_breakdown = score_candidate(
                poi=poi.raw,
                travel_plan=travel_plan,
                category_counts=category_counts,
                feedback_profile=self.feedback_profile,
                effective_transport_mode=self.effective_transport_mode,
                preferred_poi_ids=self.preferred_poi_ids,
            )

            if utility > best_utility:
                best_utility = utility
                best_evaluation = CandidateEvaluation(
                    poi=poi,
                    travel_plan=travel_plan,
                    visit_minutes=visit_minutes,
                    utility=utility,
                    score_breakdown=score_breakdown,
                )

        return best_evaluation


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
    poi: CandidatePoi,
    approx_travel_minutes: int,
    category_counts: dict[str, int],
    preferred_poi_ids: set[int],
) -> float:
    base_component = float(poi.base_score or 0.0) * BASE_SCORE_MULTIPLIER
    repeat_penalty = REPEAT_CATEGORY_PENALTY * category_counts.get(poi.category, 0)
    travel_penalty = approx_travel_minutes * TRAVEL_MINUTE_PENALTY
    preferred_bonus = PREFERRED_POI_BONUS if poi.id in preferred_poi_ids else 0.0
    return base_component + preferred_bonus - repeat_penalty - travel_penalty

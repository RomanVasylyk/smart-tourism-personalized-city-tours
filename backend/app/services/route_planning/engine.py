from __future__ import annotations

from datetime import timedelta

from app.schemas.route import RouteGenerateRequest
from app.services.feedback_stats import PlannerFeedbackProfile
from app.services.route_planning.candidate_evaluator import RouteCandidateEvaluator
from app.services.route_planning.local_search import improve_order
from app.services.route_planning.models import CandidateEvaluation, CandidatePoi
from app.services.route_planning.opening_hours import (
    DEFAULT_MAX_OPENING_WAIT_MIN,
    is_poi_open_for_visit,
    resolve_visit_wait,
)
from app.services.route_planning.result import RoutePlanningResult
from app.services.route_planning.scoring import score_candidate
from app.services.route_planning.timeline import RouteTimelineBuilder
from app.services.routing_service import RoutePoint, RoutingService
from app.services.transport_planner import TRANSPORT_MODE_WALK, plan_travel
from fastapi import HTTPException

DEFAULT_VISIT_DURATION_MIN = 20
DEFAULT_LOCAL_SEARCH_MAX_PASSES = 6
DEFAULT_LOCAL_SEARCH_MAX_EVALUATIONS = 600
DEFAULT_LOCAL_SEARCH_MAX_EVALUATIONS_TRANSIT = 80


class RoutePlannerEngine:
    def __init__(
        self,
        *,
        request: RouteGenerateRequest,
        start_dt,
        city_profile: dict,
        effective_transport_mode: str,
        feedback_profile: PlannerFeedbackProfile,
        candidates: list[CandidatePoi],
        routing_service: RoutingService,
    ):
        self.request = request
        self.start_dt = start_dt
        self.city_profile = city_profile
        self.effective_transport_mode = effective_transport_mode
        self.feedback_profile = feedback_profile
        self.candidates = candidates
        self.routing_service = routing_service
        self.start_point = RoutePoint(lat=request.start_lat, lon=request.start_lon)
        self.timeline = RouteTimelineBuilder(start_point=self.start_point)

        excluded_poi_ids = {int(poi_id) for poi_id in request.exclude_poi_ids}
        self.ordered_preferred_poi_ids = list(
            dict.fromkeys(int(poi_id) for poi_id in request.preferred_poi_ids if int(poi_id) not in excluded_poi_ids)
        )
        self.preferred_poi_ids = set(self.ordered_preferred_poi_ids)
        self.candidates_by_id = {poi.id: poi for poi in candidates}
        self.evaluator = RouteCandidateEvaluator(
            request=request,
            city_profile=city_profile,
            effective_transport_mode=effective_transport_mode,
            feedback_profile=feedback_profile,
            routing_service=routing_service,
            start_point=self.start_point,
            preferred_poi_ids=self.preferred_poi_ids,
        )

        routing_limits = city_profile.get("routing_limits") or {}
        self.local_search_enabled = bool(routing_limits.get("local_search_enabled", True))
        self.local_search_max_passes = int(
            routing_limits.get("local_search_max_passes", DEFAULT_LOCAL_SEARCH_MAX_PASSES)
        )
        default_max_evaluations = (
            DEFAULT_LOCAL_SEARCH_MAX_EVALUATIONS
            if effective_transport_mode == TRANSPORT_MODE_WALK
            else DEFAULT_LOCAL_SEARCH_MAX_EVALUATIONS_TRANSIT
        )
        self.local_search_max_evaluations = int(
            routing_limits.get("local_search_max_evaluations", default_max_evaluations)
        )
        self.max_opening_wait_minutes = int(
            routing_limits.get("max_opening_wait_minutes", DEFAULT_MAX_OPENING_WAIT_MIN)
        )

    def plan(self) -> RoutePlanningResult:
        self.ensure_preferred_candidates_available()
        selected_pois = self.construct_greedy_route()
        optimized_pois = self.apply_local_search(selected_pois)
        self.timeline = self.build_timeline_for_order(optimized_pois)
        self.ensure_required_preferred_pois_were_planned()
        return self.result()

    def construct_greedy_route(self) -> list[CandidatePoi]:
        selected_pois: list[CandidatePoi] = []
        used_ids: set[int] = set()
        category_counts: dict[str, int] = {}

        while True:
            departure_dt = self.start_dt + timedelta(seconds=self.timeline.elapsed_actual_seconds)
            forced_preferred_poi = self.next_forced_preferred_poi(used_ids)
            if forced_preferred_poi is not None:
                evaluation_candidates = [forced_preferred_poi]
            else:
                evaluation_candidates = self.evaluator.shortlist(
                    candidates=self.candidates,
                    used_ids=used_ids,
                    current_point=self.timeline.current_point,
                    category_counts=category_counts,
                )

            if not evaluation_candidates:
                break

            best_evaluation = self.evaluator.best_candidate(
                evaluation_candidates=evaluation_candidates,
                forced_preferred_poi=forced_preferred_poi,
                current_point=self.timeline.current_point,
                elapsed_minutes=self.timeline.elapsed_minutes,
                departure_dt=departure_dt,
                category_counts=category_counts,
            )
            if best_evaluation is None:
                if forced_preferred_poi is not None:
                    used_ids.add(forced_preferred_poi.id)
                    continue
                break

            self.timeline.add_stop(best_evaluation)
            selected_pois.append(best_evaluation.poi)
            used_ids.add(best_evaluation.poi.id)
            category_counts[best_evaluation.poi.category] = category_counts.get(best_evaluation.poi.category, 0) + 1

        return selected_pois

    def apply_local_search(self, order: list[CandidatePoi]) -> list[CandidatePoi]:
        if not self.local_search_enabled or len(order) < 3:
            return order
        return improve_order(
            order,
            cost=self.evaluate_order_travel_minutes,
            max_passes=self.local_search_max_passes,
            max_evaluations=self.local_search_max_evaluations,
        )

    def _preserves_preferred_order(self, order: list[CandidatePoi]) -> bool:
        """Keep the user's manual order of required POIs intact during local search."""
        if not self.ordered_preferred_poi_ids:
            return True
        present_ids = {poi.id for poi in order}
        expected_sequence = [poi_id for poi_id in self.ordered_preferred_poi_ids if poi_id in present_ids]
        actual_sequence = [poi.id for poi in order if poi.id in self.preferred_poi_ids]
        return actual_sequence == expected_sequence

    def _travel_plan(self, start: RoutePoint, end: RoutePoint, departure_dt):
        return plan_travel(
            start=start,
            end=end,
            pace=self.request.pace,
            routing_service=self.routing_service,
            city_profile=self.city_profile,
            transport_mode=self.effective_transport_mode,
            departure_dt=departure_dt,
        )

    def evaluate_order_travel_minutes(self, order: list[CandidatePoi]) -> float | None:
        """Total travel minutes for a candidate order, or None if it is infeasible.

        Mirrors the timeline's accounting (per-leg rounded minutes, real-seconds
        timing) so the local search optimises the same quantity it will report.
        Preferred POIs are forced into the route and therefore exempt from the
        opening-hours check, matching the greedy construction.
        """
        if not self._preserves_preferred_order(order):
            return None

        elapsed_seconds = 0.0
        total_travel_minutes = 0
        total_visit_minutes = 0
        total_wait_minutes = 0
        current_point = self.start_point

        for poi in order:
            departure_dt = self.start_dt + timedelta(seconds=elapsed_seconds)
            travel_plan = self._travel_plan(current_point, poi.point, departure_dt)
            visit_minutes = poi.visit_duration_min or DEFAULT_VISIT_DURATION_MIN
            arrival_dt = departure_dt + timedelta(seconds=travel_plan.duration_seconds)

            wait_minutes = resolve_visit_wait(
                poi.opening_hours_raw,
                arrival_dt,
                visit_minutes,
                respect_opening_hours=self.request.respect_opening_hours,
                is_forced=poi.id in self.preferred_poi_ids,
                max_wait_minutes=self.max_opening_wait_minutes,
            )
            if wait_minutes is None:
                return None

            elapsed_seconds += travel_plan.duration_seconds + ((wait_minutes + visit_minutes) * 60)
            total_travel_minutes += travel_plan.duration_minutes
            total_visit_minutes += visit_minutes
            total_wait_minutes += wait_minutes
            current_point = poi.point

        if self.request.return_to_start and order:
            return_departure_dt = self.start_dt + timedelta(seconds=elapsed_seconds)
            return_plan = self._travel_plan(current_point, self.start_point, return_departure_dt)
            total_travel_minutes += return_plan.duration_minutes

        if total_travel_minutes + total_visit_minutes + total_wait_minutes > self.request.available_minutes:
            return None

        return float(total_travel_minutes)

    def build_timeline_for_order(self, order: list[CandidatePoi]) -> RouteTimelineBuilder:
        timeline = RouteTimelineBuilder(start_point=self.start_point)
        category_counts: dict[str, int] = {}

        for poi in order:
            departure_dt = self.start_dt + timedelta(seconds=timeline.elapsed_actual_seconds)
            travel_plan = self._travel_plan(timeline.current_point, poi.point, departure_dt)
            visit_minutes = poi.visit_duration_min or DEFAULT_VISIT_DURATION_MIN
            arrival_dt = departure_dt + timedelta(seconds=travel_plan.duration_seconds)
            wait_minutes = resolve_visit_wait(
                poi.opening_hours_raw,
                arrival_dt,
                visit_minutes,
                respect_opening_hours=self.request.respect_opening_hours,
                is_forced=True,
                max_wait_minutes=self.max_opening_wait_minutes,
            )
            utility, score_breakdown = score_candidate(
                poi=poi.raw,
                travel_plan=travel_plan,
                category_counts=category_counts,
                feedback_profile=self.feedback_profile,
                effective_transport_mode=self.effective_transport_mode,
                preferred_poi_ids=self.preferred_poi_ids,
            )
            timeline.add_stop(
                CandidateEvaluation(
                    poi=poi,
                    travel_plan=travel_plan,
                    visit_minutes=visit_minutes,
                    utility=utility,
                    score_breakdown=score_breakdown,
                    wait_minutes=wait_minutes or 0,
                )
            )
            category_counts[poi.category] = category_counts.get(poi.category, 0) + 1

        if self.request.return_to_start and timeline.route_items:
            return_departure_dt = self.start_dt + timedelta(seconds=timeline.elapsed_actual_seconds)
            return_plan = self._travel_plan(timeline.current_point, self.start_point, return_departure_dt)
            timeline.add_return_to_start(return_plan)

        return timeline

    def ensure_preferred_candidates_available(self) -> None:
        unavailable_preferred_ids = [
            poi_id for poi_id in self.ordered_preferred_poi_ids if poi_id not in self.candidates_by_id
        ]
        if unavailable_preferred_ids:
            raise HTTPException(
                status_code=422,
                detail=f"Required POIs are not available for this city: {unavailable_preferred_ids}",
            )

    def next_forced_preferred_poi(self, used_ids: set[int]) -> CandidatePoi | None:
        return next(
            (
                self.candidates_by_id[poi_id]
                for poi_id in self.ordered_preferred_poi_ids
                if poi_id not in used_ids and poi_id in self.candidates_by_id
            ),
            None,
        )

    def ensure_required_preferred_pois_were_planned(self) -> None:
        generated_poi_ids = {int(item["poi_id"]) for item in self.timeline.route_items}
        missing_preferred_ids = [poi_id for poi_id in self.ordered_preferred_poi_ids if poi_id not in generated_poi_ids]
        if missing_preferred_ids:
            raise HTTPException(
                status_code=409,
                detail=f"Required POIs were not included in generated route: {missing_preferred_ids}",
            )

    def closed_required_poi_ids(self) -> list[int]:
        if not self.request.respect_opening_hours or not self.preferred_poi_ids:
            return []
        closed: list[int] = []
        for item in self.timeline.route_items:
            poi_id = int(item["poi_id"])
            if poi_id not in self.preferred_poi_ids:
                continue
            arrival_dt = self.start_dt + timedelta(minutes=item["arrival_after_min"])
            if not is_poi_open_for_visit(item.get("opening_hours_raw"), arrival_dt, item["visit_duration_min"]):
                closed.append(poi_id)
        return closed

    def result(self) -> RoutePlanningResult:
        total_visit_minutes = sum(item["visit_duration_min"] for item in self.timeline.route_items)
        total_wait_minutes = sum(item.get("wait_minutes", 0) for item in self.timeline.route_items)
        total_walk_minutes = self.timeline.elapsed_minutes - total_visit_minutes - total_wait_minutes
        over_budget = bool(self.preferred_poi_ids) and self.timeline.elapsed_minutes > self.request.available_minutes
        return RoutePlanningResult(
            city=self.request.city,
            start_lat=self.request.start_lat,
            start_lon=self.request.start_lon,
            start_datetime=self.start_dt,
            pace=self.request.pace,
            interests=self.request.interests,
            transport_mode=self.effective_transport_mode,
            return_to_start=self.request.return_to_start,
            respect_opening_hours=self.request.respect_opening_hours,
            available_minutes=self.request.available_minutes,
            used_minutes=self.timeline.elapsed_minutes,
            total_visit_minutes=total_visit_minutes,
            total_walk_minutes=total_walk_minutes,
            total_wait_minutes=total_wait_minutes,
            return_to_start_minutes=self.timeline.return_to_start_minutes,
            route_items=self.timeline.route_items,
            legs=self.timeline.legs,
            full_geometry=self.timeline.full_geometry,
            required_pois_over_budget=over_budget,
            closed_required_poi_ids=self.closed_required_poi_ids(),
        )

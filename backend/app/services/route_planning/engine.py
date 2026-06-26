from __future__ import annotations

from datetime import timedelta

from app.schemas.route import RouteGenerateRequest
from app.services.feedback_stats import PlannerFeedbackProfile
from app.services.route_planning.candidate_evaluator import RouteCandidateEvaluator
from app.services.route_planning.models import CandidatePoi
from app.services.route_planning.result import RoutePlanningResult
from app.services.route_planning.timeline import RouteTimelineBuilder
from app.services.routing_service import RoutePoint, RoutingService
from app.services.transport_planner import plan_travel
from fastapi import HTTPException


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

    def plan(self) -> RoutePlanningResult:
        self.ensure_preferred_candidates_available()

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
            used_ids.add(best_evaluation.poi.id)
            category_counts[best_evaluation.poi.category] = category_counts.get(best_evaluation.poi.category, 0) + 1

        self.add_return_to_start_if_needed()
        self.ensure_required_preferred_pois_were_planned()
        return self.result()

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

    def add_return_to_start_if_needed(self) -> None:
        if not self.request.return_to_start or not self.timeline.route_items:
            return

        return_departure_dt = self.start_dt + timedelta(seconds=self.timeline.elapsed_actual_seconds)
        return_plan = plan_travel(
            start=self.timeline.current_point,
            end=self.start_point,
            pace=self.request.pace,
            routing_service=self.routing_service,
            city_profile=self.city_profile,
            transport_mode=self.effective_transport_mode,
            departure_dt=return_departure_dt,
        )
        self.timeline.add_return_to_start(return_plan)

    def ensure_required_preferred_pois_were_planned(self) -> None:
        generated_poi_ids = {int(item["poi_id"]) for item in self.timeline.route_items}
        missing_preferred_ids = [poi_id for poi_id in self.ordered_preferred_poi_ids if poi_id not in generated_poi_ids]
        if missing_preferred_ids:
            raise HTTPException(
                status_code=409,
                detail=f"Required POIs were not included in generated route: {missing_preferred_ids}",
            )

    def result(self) -> RoutePlanningResult:
        total_visit_minutes = sum(item["visit_duration_min"] for item in self.timeline.route_items)
        total_walk_minutes = self.timeline.elapsed_minutes - total_visit_minutes
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
            return_to_start_minutes=self.timeline.return_to_start_minutes,
            route_items=self.timeline.route_items,
            legs=self.timeline.legs,
            full_geometry=self.timeline.full_geometry,
        )

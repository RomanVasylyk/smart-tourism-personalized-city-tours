from __future__ import annotations

from app.services.route_planning.models import CandidateEvaluation, PlannedStop
from app.services.route_planning.response import append_geometry, leg_dict, point_dict
from app.services.route_planning.scoring import rounded_score_breakdown
from app.services.routing_service import RoutePoint
from app.services.transport_planner import TravelPlan


class RouteTimelineBuilder:
    def __init__(self, *, start_point: RoutePoint):
        self.current_point = start_point
        self.current_endpoint = point_dict("start", start_point.lat, start_point.lon)
        self.start_endpoint = self.current_endpoint
        self.route_items: list[dict] = []
        self.legs: list[dict] = []
        self.full_geometry: list[dict] = []
        self.elapsed_minutes = 0
        self.elapsed_actual_seconds = 0.0
        self.return_to_start_minutes = 0

    def add_stop(self, evaluation: CandidateEvaluation) -> PlannedStop:
        poi = evaluation.poi
        self.elapsed_actual_seconds += evaluation.travel_plan.duration_seconds + (evaluation.visit_minutes * 60)
        self.elapsed_minutes += evaluation.travel_plan.duration_minutes + evaluation.visit_minutes
        next_endpoint = point_dict("poi", poi.lat, poi.lon, poi.raw)
        planned_stop = PlannedStop(
            order=len(self.route_items) + 1,
            poi=poi,
            travel_plan=evaluation.travel_plan,
            visit_minutes=evaluation.visit_minutes,
            arrival_after_min=self.elapsed_minutes - evaluation.visit_minutes,
            departure_after_min=self.elapsed_minutes,
            utility=evaluation.utility,
            score_breakdown=evaluation.score_breakdown,
        )

        self.route_items.append(planned_stop_to_route_item(planned_stop))
        self.legs.append(
            leg_dict(
                order=len(self.legs) + 1,
                from_point=self.current_endpoint,
                to_point=next_endpoint,
                travel_plan=evaluation.travel_plan,
            )
        )
        append_geometry(self.full_geometry, evaluation.travel_plan.geometry)

        self.current_point = poi.point
        self.current_endpoint = next_endpoint
        return planned_stop

    def add_return_to_start(self, return_plan: TravelPlan) -> None:
        self.return_to_start_minutes = return_plan.duration_minutes
        self.elapsed_actual_seconds += return_plan.duration_seconds
        self.elapsed_minutes += self.return_to_start_minutes
        self.legs.append(
            leg_dict(
                order=len(self.legs) + 1,
                from_point=self.current_endpoint,
                to_point=self.start_endpoint,
                travel_plan=return_plan,
            )
        )
        append_geometry(self.full_geometry, return_plan.geometry)


def planned_stop_to_route_item(planned_stop: PlannedStop) -> dict:
    poi = planned_stop.poi
    return {
        "order": planned_stop.order,
        "poi_id": poi.id,
        "name": poi.name,
        "category": poi.category,
        "lat": poi.lat,
        "lon": poi.lon,
        "travel_minutes_from_previous": planned_stop.travel_plan.duration_minutes,
        "travel_distance_meters_from_previous": round(planned_stop.travel_plan.distance_meters, 1),
        "routing_source_from_previous": planned_stop.travel_plan.source,
        "travel_mode_from_previous": planned_stop.travel_plan.mode,
        "visit_duration_min": planned_stop.visit_minutes,
        "arrival_after_min": planned_stop.arrival_after_min,
        "departure_after_min": planned_stop.departure_after_min,
        "base_score": poi.base_score,
        "planner_score": round(planned_stop.utility, 3),
        "planner_score_breakdown": rounded_score_breakdown(planned_stop.score_breakdown),
        "short_description": poi.short_description,
        "wikipedia_url": poi.wikipedia_url,
        "opening_hours_raw": poi.opening_hours_raw,
    }

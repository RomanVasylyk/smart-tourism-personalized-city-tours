from __future__ import annotations

from dataclasses import dataclass

from app.services.routing_service import RoutePoint
from app.services.transport_planner import TravelPlan


@dataclass(frozen=True)
class CandidatePoi:
    id: int
    name: str | None
    category: str
    lat: float
    lon: float
    opening_hours_raw: str | None
    visit_duration_min: int | None
    base_score: float | None
    short_description: str | None
    wikipedia_url: str | None
    raw: dict

    @classmethod
    def from_row(cls, row: dict) -> CandidatePoi:
        return cls(
            id=int(row["id"]),
            name=row.get("name"),
            category=str(row["category"]),
            lat=float(row["lat"]),
            lon=float(row["lon"]),
            opening_hours_raw=row.get("opening_hours_raw"),
            visit_duration_min=row.get("visit_duration_min"),
            base_score=row.get("base_score"),
            short_description=row.get("short_description"),
            wikipedia_url=row.get("wikipedia_url"),
            raw=row,
        )

    @property
    def point(self) -> RoutePoint:
        return RoutePoint(lat=self.lat, lon=self.lon)


@dataclass(frozen=True)
class CandidateEvaluation:
    poi: CandidatePoi
    travel_plan: TravelPlan
    visit_minutes: int
    utility: float
    score_breakdown: dict[str, float]


@dataclass(frozen=True)
class PlannedStop:
    order: int
    poi: CandidatePoi
    travel_plan: TravelPlan
    visit_minutes: int
    arrival_after_min: int
    departure_after_min: int
    utility: float
    score_breakdown: dict[str, float]

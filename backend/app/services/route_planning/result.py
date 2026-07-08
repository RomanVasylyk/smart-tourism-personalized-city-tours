from dataclasses import dataclass, field
from datetime import datetime


@dataclass(frozen=True)
class RoutePlanningResult:
    city: str
    start_lat: float
    start_lon: float
    start_datetime: datetime
    pace: str
    interests: list[str]
    transport_mode: str
    return_to_start: bool
    respect_opening_hours: bool
    available_minutes: int
    used_minutes: int
    total_visit_minutes: int
    total_walk_minutes: int
    return_to_start_minutes: int
    route_items: list[dict]
    legs: list[dict]
    full_geometry: list[dict]
    total_wait_minutes: int = 0
    required_pois_over_budget: bool = False
    closed_required_poi_ids: list[int] = field(default_factory=list)

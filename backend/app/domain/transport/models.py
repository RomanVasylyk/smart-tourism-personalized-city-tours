from dataclasses import dataclass
from datetime import datetime

TRANSPORT_MODE_WALK = "walk"
TRANSPORT_MODE_WALK_OR_MHD = "walk_or_mhd"
STOP_CANDIDATE_LIMIT = 12

SERVICE_BUCKET_WEEKDAYS = {
    "workdays": {0, 1, 2, 3, 4},
    "weekends_holidays": {5, 6},
    "all_days": {0, 1, 2, 3, 4, 5, 6},
}


@dataclass(frozen=True)
class TravelSegment:
    mode: str
    duration_seconds: float
    distance_meters: float
    geometry: list[dict]
    source: str
    line_name: str | None = None
    from_stop_name: str | None = None
    to_stop_name: str | None = None
    departure_time: datetime | None = None
    arrival_time: datetime | None = None
    wait_seconds_before_departure: float = 0.0
    in_vehicle_seconds: float = 0.0

    @property
    def duration_minutes(self) -> int:
        if self.duration_seconds <= 0:
            return 0
        return max(1, round(self.duration_seconds / 60))

    @property
    def wait_minutes_before_departure(self) -> int:
        if self.wait_seconds_before_departure <= 0:
            return 0
        return max(1, round(self.wait_seconds_before_departure / 60))

    @property
    def in_vehicle_minutes(self) -> int:
        if self.in_vehicle_seconds <= 0:
            return 0
        return max(1, round(self.in_vehicle_seconds / 60))


@dataclass(frozen=True)
class TravelPlan:
    mode: str
    duration_seconds: float
    distance_meters: float
    geometry: list[dict]
    source: str
    segments: list[TravelSegment]

    @property
    def duration_minutes(self) -> int:
        if self.duration_seconds <= 0:
            return 0
        return max(1, round(self.duration_seconds / 60))


@dataclass(frozen=True)
class ScheduledRide:
    trip_id: int
    board_index: int
    alight_index: int
    board_time: datetime
    alight_time: datetime


@dataclass(frozen=True)
class ExactTransitSolution:
    rides: list[ScheduledRide]
    start_stop_id: int
    end_stop_id: int

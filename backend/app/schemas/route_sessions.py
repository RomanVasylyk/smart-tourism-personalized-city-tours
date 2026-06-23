from datetime import datetime
from typing import Any
from uuid import UUID

from pydantic import BaseModel, Field

MAX_DEVICE_ID_LENGTH = 128
MAX_DEVICE_TOKEN_LENGTH = 256


class RouteSessionCreateRequest(BaseModel):
    id: UUID | None = None
    device_id: str = Field(min_length=1, max_length=MAX_DEVICE_ID_LENGTH)
    city: str = "nitra"
    status: str = "in_progress"
    start_lat: float
    start_lon: float
    available_minutes: int = Field(ge=1)
    pace: str
    return_to_start: bool
    opening_hours_enabled: bool
    started_at: datetime | None = None
    finished_at: datetime | None = None
    used_minutes: int | None = None
    total_walk_minutes: int | None = None
    total_visit_minutes: int | None = None
    route_snapshot_json: dict[str, Any]


class RouteSessionUpdateRequest(BaseModel):
    status: str | None = None
    finished_at: datetime | None = None
    used_minutes: int | None = None
    total_walk_minutes: int | None = None
    total_visit_minutes: int | None = None
    route_snapshot_json: dict[str, Any] | None = None


class RouteSessionPoiVisitRequest(BaseModel):
    visited_at: datetime | None = None
    skipped: bool = False


class RouteFeedbackRequest(BaseModel):
    rating: int = Field(ge=1, le=5)
    was_convenient: bool
    too_much_walking: bool
    pois_were_interesting: bool
    comment: str | None = None

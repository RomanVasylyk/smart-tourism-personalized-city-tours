from datetime import datetime
from typing import Any
from uuid import UUID

from pydantic import BaseModel, Field, field_validator

MAX_DEVICE_ID_LENGTH = 128
MAX_DEVICE_TOKEN_LENGTH = 256
MAX_SNAPSHOT_ROUTE_ITEMS = 200


def validate_route_snapshot(snapshot: dict[str, Any] | None) -> dict[str, Any] | None:
    if snapshot is None:
        return None

    route_items = snapshot.get("route")
    if route_items is None:
        return snapshot
    if not isinstance(route_items, list):
        raise ValueError("route_snapshot_json.route must be a list")
    if len(route_items) > MAX_SNAPSHOT_ROUTE_ITEMS:
        raise ValueError(f"route_snapshot_json.route can contain at most {MAX_SNAPSHOT_ROUTE_ITEMS} stops")
    for item in route_items:
        if not isinstance(item, dict):
            raise ValueError("route_snapshot_json.route items must be objects")
        poi_id = item.get("poi_id")
        if poi_id is not None and (isinstance(poi_id, bool) or not isinstance(poi_id, int)):
            raise ValueError("route_snapshot_json.route items need an integer poi_id")
    return snapshot


class RouteSessionCreateRequest(BaseModel):
    id: UUID | None = None
    device_id: str = Field(min_length=1, max_length=MAX_DEVICE_ID_LENGTH)
    city: str = Field(default="nitra", min_length=1, max_length=64)
    status: str = Field(default="in_progress", max_length=32)
    start_lat: float = Field(ge=-90, le=90)
    start_lon: float = Field(ge=-180, le=180)
    available_minutes: int = Field(ge=1)
    pace: str = Field(max_length=32)
    return_to_start: bool
    opening_hours_enabled: bool
    started_at: datetime | None = None
    finished_at: datetime | None = None
    used_minutes: int | None = None
    total_walk_minutes: int | None = None
    total_visit_minutes: int | None = None
    route_snapshot_json: dict[str, Any]

    _validate_snapshot = field_validator("route_snapshot_json")(validate_route_snapshot)


class RouteSessionUpdateRequest(BaseModel):
    status: str | None = None
    finished_at: datetime | None = None
    used_minutes: int | None = None
    total_walk_minutes: int | None = None
    total_visit_minutes: int | None = None
    route_snapshot_json: dict[str, Any] | None = None

    _validate_snapshot = field_validator("route_snapshot_json")(validate_route_snapshot)


class RouteSessionPoiVisitRequest(BaseModel):
    visited_at: datetime | None = None
    skipped: bool = False


class RouteFeedbackRequest(BaseModel):
    rating: int = Field(ge=1, le=5)
    was_convenient: bool
    too_much_walking: bool
    pois_were_interesting: bool
    comment: str | None = Field(default=None, max_length=2000)

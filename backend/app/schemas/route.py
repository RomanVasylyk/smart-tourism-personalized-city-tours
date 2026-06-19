from typing import Literal

from pydantic import BaseModel, Field


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
    transport_mode: Literal["walk", "walk_or_mhd"] = "walk"


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
    transport_mode: Literal["walk", "walk_or_mhd"] = "walk"

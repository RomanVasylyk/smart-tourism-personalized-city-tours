from typing import Literal

from pydantic import BaseModel, Field

Pace = Literal["slow", "normal", "fast"]

MAX_INTERESTS = 50
MAX_POI_ID_LIST = 100


class RouteGenerateRequest(BaseModel):
    city: str = Field(default="nitra", min_length=1, max_length=64)
    start_lat: float = Field(ge=-90, le=90)
    start_lon: float = Field(ge=-180, le=180)
    available_minutes: int = Field(ge=30, le=720)
    interests: list[str] = Field(default_factory=list, max_length=MAX_INTERESTS)
    pace: Pace = "normal"
    return_to_start: bool = True
    start_datetime: str | None = None
    respect_opening_hours: bool = True
    exclude_poi_ids: list[int] = Field(default_factory=list, max_length=MAX_POI_ID_LIST)
    preferred_poi_ids: list[int] = Field(default_factory=list, max_length=MAX_POI_ID_LIST)
    transport_mode: Literal["walk", "walk_or_mhd"] = "walk"


class RouteLegRequest(BaseModel):
    city: str = Field(default="nitra", min_length=1, max_length=64)
    start_lat: float = Field(ge=-90, le=90)
    start_lon: float = Field(ge=-180, le=180)
    end_lat: float = Field(ge=-90, le=90)
    end_lon: float = Field(ge=-180, le=180)
    end_poi_id: int
    end_name: str | None = Field(default=None, max_length=256)
    pace: Pace = "normal"
    start_datetime: str | None = None
    transport_mode: Literal["walk", "walk_or_mhd"] = "walk"

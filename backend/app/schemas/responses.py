from datetime import datetime
from uuid import UUID

from pydantic import BaseModel, ConfigDict, Field


class HealthResponse(BaseModel):
    status: str


class CityBboxResponse(BaseModel):
    south: float
    west: float
    north: float
    east: float


class RoutingLimitsResponse(BaseModel):
    max_available_minutes: int | None = None
    max_poi_candidates: int | None = None
    max_exact_poi_evaluations_per_step: int | None = None
    max_exact_poi_evaluations_per_step_transit: int | None = None

    model_config = ConfigDict(extra="allow")


class TransportProfileResponse(BaseModel):
    mhd_enabled: bool | None = None
    provider: str | None = None
    mode: str | None = None

    model_config = ConfigDict(extra="allow")


class CityProfileResponse(BaseModel):
    slug: str | None = None
    bbox: CityBboxResponse | None = None
    available_categories: list[str] = Field(default_factory=list)
    default_zoom: float | None = None
    routing_limits: RoutingLimitsResponse = Field(default_factory=RoutingLimitsResponse)
    transport: TransportProfileResponse = Field(default_factory=TransportProfileResponse)


class CityResponse(BaseModel):
    id: int
    slug: str | None = None
    name: str
    country: str
    center_lat: float | None = None
    center_lon: float | None = None
    bbox: CityBboxResponse | None = None
    available_categories: list[str] = Field(default_factory=list)
    default_zoom: float | None = None
    routing_limits: RoutingLimitsResponse = Field(default_factory=RoutingLimitsResponse)
    transport: TransportProfileResponse = Field(default_factory=TransportProfileResponse)


class PoiResponse(BaseModel):
    id: int
    name: str | None = None
    category: str
    lat: float
    lon: float
    address: str | None = None
    opening_hours_raw: str | None = None
    opening_hours_source: str | None = None
    visit_duration_min: int | None = None
    base_score: float | None = None
    short_description: str | None = None
    website: str | None = None
    image_url: str | None = None
    wikipedia_url: str | None = None


class RouteCoordinateResponse(BaseModel):
    lat: float
    lon: float


class RouteLegEndpointResponse(BaseModel):
    type: str
    poi_id: int | None = None
    name: str | None = None
    lat: float
    lon: float


class RouteSegmentResponse(BaseModel):
    order: int | None = None
    mode: str | None = None
    duration_seconds: float | None = None
    duration_minutes: int | None = None
    distance_meters: float | None = None
    geometry: list[RouteCoordinateResponse] | None = None
    source: str | None = None
    line_name: str | None = None
    from_stop_name: str | None = None
    to_stop_name: str | None = None
    departure_time: str | None = None
    arrival_time: str | None = None
    wait_minutes_before_departure: int | None = None
    in_vehicle_minutes: int | None = None


class RouteLegResponse(BaseModel):
    order: int
    mode: str | None = None
    from_: RouteLegEndpointResponse = Field(alias="from")
    to: RouteLegEndpointResponse
    duration_seconds: float
    duration_minutes: int
    distance_meters: float
    geometry: list[RouteCoordinateResponse]
    routing_source: str | None = None
    departure_time: str | None = None
    arrival_time: str | None = None
    segments: list[RouteSegmentResponse] | None = None

    model_config = ConfigDict(populate_by_name=True)


class PlannerScoreBreakdownResponse(BaseModel):
    base_score: float | None = None
    preferred_bonus: float | None = None
    poi_bonus: float | None = None
    category_bonus: float | None = None
    completion_bonus: float | None = None
    transport_bonus: float | None = None
    travel_penalty: float | None = None
    walking_penalty: float | None = None
    skip_penalty: float | None = None
    repeat_penalty: float | None = None
    final_score: float | None = None


class RouteItemResponse(BaseModel):
    order: int
    poi_id: int
    name: str | None = None
    category: str
    lat: float
    lon: float
    travel_minutes_from_previous: int
    travel_distance_meters_from_previous: float | None = None
    routing_source_from_previous: str | None = None
    travel_mode_from_previous: str | None = None
    visit_duration_min: int
    wait_minutes: int | None = None
    arrival_after_min: int
    departure_after_min: int
    base_score: float | None = None
    planner_score: float | None = None
    planner_score_breakdown: PlannerScoreBreakdownResponse | None = None
    short_description: str | None = None
    wikipedia_url: str | None = None
    address: str | None = None
    website: str | None = None
    image_url: str | None = None
    opening_hours_source: str | None = None
    opening_hours_raw: str | None = None


class RouteStartResponse(BaseModel):
    lat: float
    lon: float


class RouteResponse(BaseModel):
    city: str
    start: RouteStartResponse
    start_datetime: str | None = None
    pace: str
    interests: list[str]
    transport_mode: str | None = None
    return_to_start: bool
    respect_opening_hours: bool
    available_minutes: int
    used_minutes: int
    remaining_minutes: int
    total_visit_minutes: int
    total_walk_minutes: int
    total_wait_minutes: int | None = None
    return_to_start_minutes: int
    poi_count: int
    required_pois_over_budget: bool | None = None
    closed_required_poi_ids: list[int] = Field(default_factory=list)
    routing_leg_count: int | None = None
    routing_fallback_leg_count: int | None = None
    routing_degraded: bool | None = None
    route: list[RouteItemResponse]
    legs: list[RouteLegResponse] | None = None
    full_geometry: list[RouteCoordinateResponse] | None = None


class CuratedRouteSummaryResponse(BaseModel):
    id: int
    slug: str | None = None
    title: str
    summary: str | None = None
    theme: str | None = None
    city: str | None = None
    recommended_duration_min: int | None = None
    pace: str | None = None
    transport_mode: str | None = None
    return_to_start: bool | None = None
    poi_count: int
    stop_names: list[str] = Field(default_factory=list)


class CuratedRouteDetailResponse(BaseModel):
    id: int
    slug: str | None = None
    title: str
    summary: str | None = None
    theme: str | None = None
    city: str | None = None
    recommended_duration_min: int | None = None
    pace: str | None = None
    transport_mode: str | None = None
    return_to_start: bool | None = None
    poi_count: int
    route: RouteResponse


class RouteSnapshotResponse(BaseModel):
    city: str | None = None
    start: RouteStartResponse | None = None
    start_datetime: str | None = None
    pace: str | None = None
    interests: list[str] = Field(default_factory=list)
    transport_mode: str | None = None
    return_to_start: bool | None = None
    respect_opening_hours: bool | None = None
    available_minutes: int | None = None
    used_minutes: int | None = None
    remaining_minutes: int | None = None
    total_visit_minutes: int | None = None
    total_walk_minutes: int | None = None
    return_to_start_minutes: int | None = None
    poi_count: int | None = None
    route: list[RouteItemResponse] = Field(default_factory=list)
    legs: list[RouteLegResponse] = Field(default_factory=list)
    full_geometry: list[RouteCoordinateResponse] = Field(default_factory=list)


class RouteSessionPoiResponse(BaseModel):
    id: int
    session_id: UUID
    poi_id: int
    visit_order: int
    planned_arrival_min: int | None = None
    planned_departure_min: int | None = None
    visited: bool
    visited_at: datetime | None = None
    skipped: bool


class RouteFeedbackResponse(BaseModel):
    id: int
    session_id: UUID
    rating: int
    was_convenient: bool
    too_much_walking: bool
    pois_were_interesting: bool
    comment: str | None = None
    created_at: datetime


class RouteSessionResponse(BaseModel):
    id: UUID
    device_id: str
    city_id: int | None = None
    city_name: str | None = None
    city_country: str | None = None
    status: str
    start_lat: float | None = None
    start_lon: float | None = None
    available_minutes: int | None = None
    pace: str | None = None
    return_to_start: bool | None = None
    opening_hours_enabled: bool | None = None
    started_at: datetime | None = None
    finished_at: datetime | None = None
    used_minutes: int | None = None
    total_walk_minutes: int | None = None
    total_visit_minutes: int | None = None
    route_snapshot_json: RouteSnapshotResponse | None = None
    pois: list[RouteSessionPoiResponse] | None = None
    feedback: list[RouteFeedbackResponse] | None = None
    created_at: datetime | None = None
    updated_at: datetime | None = None

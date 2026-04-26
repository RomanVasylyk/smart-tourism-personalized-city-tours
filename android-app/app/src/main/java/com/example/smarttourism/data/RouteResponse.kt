package com.example.smarttourism.data

data class RouteResponse(
    val city: String,
    val start: RouteStartDto,
    val start_datetime: String?,
    val pace: String,
    val interests: List<String>,
    val transport_mode: String?,
    val return_to_start: Boolean,
    val respect_opening_hours: Boolean,
    val available_minutes: Int,
    val used_minutes: Int,
    val remaining_minutes: Int,
    val total_visit_minutes: Int,
    val total_walk_minutes: Int,
    val return_to_start_minutes: Int,
    val poi_count: Int,
    val route: List<RouteItemDto>,
    val legs: List<RouteLegDto>?,
    val full_geometry: List<RouteCoordinateDto>?
)

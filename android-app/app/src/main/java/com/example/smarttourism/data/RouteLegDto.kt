package com.example.smarttourism.data

data class RouteLegDto(
    val order: Int,
    val mode: String?,
    val from: RouteLegEndpointDto,
    val to: RouteLegEndpointDto,
    val duration_seconds: Double,
    val duration_minutes: Int,
    val distance_meters: Double,
    val geometry: List<RouteCoordinateDto>,
    val routing_source: String?,
    val departure_time: String?,
    val arrival_time: String?,
    val segments: List<RouteSegmentDto>?
)

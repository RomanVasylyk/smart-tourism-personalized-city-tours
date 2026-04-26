package com.example.smarttourism.data

data class RouteSegmentDto(
    val order: Int?,
    val mode: String?,
    val duration_seconds: Double?,
    val duration_minutes: Int?,
    val distance_meters: Double?,
    val geometry: List<RouteCoordinateDto>?,
    val source: String?,
    val line_name: String?,
    val from_stop_name: String?,
    val to_stop_name: String?,
    val departure_time: String?,
    val arrival_time: String?,
    val wait_minutes_before_departure: Int?,
    val in_vehicle_minutes: Int?
)

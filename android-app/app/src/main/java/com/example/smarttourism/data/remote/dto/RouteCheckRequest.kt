package com.example.smarttourism.data.remote.dto

data class RouteCheckStopDto(
    val poi_id: Int,
    val arrival_after_min: Int,
    val visit_duration_min: Int
)

data class RouteCheckRequest(
    val city: String,
    val start_datetime: String?,
    val respect_opening_hours: Boolean,
    val stops: List<RouteCheckStopDto>
)

data class RouteCheckResponse(
    val closed_poi_ids: List<Int>?
)

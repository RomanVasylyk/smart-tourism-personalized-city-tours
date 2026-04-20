package com.example.smarttourism.data

data class RouteItemDto(
    val order: Int,
    val poi_id: Int,
    val name: String,
    val category: String,
    val lat: Double,
    val lon: Double,
    val travel_minutes_from_previous: Int,
    val visit_duration_min: Int,
    val arrival_after_min: Int,
    val departure_after_min: Int,
    val base_score: Double?,
    val wikipedia_url: String?,
    val opening_hours_raw: String?
)

package com.example.smarttourism.data.remote.dto

data class RouteItemDto(
    val order: Int,
    val poi_id: Int,
    val name: String,
    val category: String,
    val lat: Double,
    val lon: Double,
    val travel_minutes_from_previous: Int,
    val visit_duration_min: Int,
    val wait_minutes: Int? = null,
    val arrival_after_min: Int,
    val departure_after_min: Int,
    val base_score: Double?,
    val short_description: String?,
    val wikipedia_url: String?,
    val opening_hours_raw: String?,
    val address: String? = null,
    val website: String? = null,
    val image_url: String? = null,
    val opening_hours_source: String? = null
)

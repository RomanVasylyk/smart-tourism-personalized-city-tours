package com.example.smarttourism.data

data class RouteRequest(
    val city: String = "nitra",
    val start_lat: Double,
    val start_lon: Double,
    val available_minutes: Int,
    val interests: List<String>,
    val pace: String,
    val return_to_start: Boolean,
    val start_datetime: String? = null,
    val respect_opening_hours: Boolean = true
)

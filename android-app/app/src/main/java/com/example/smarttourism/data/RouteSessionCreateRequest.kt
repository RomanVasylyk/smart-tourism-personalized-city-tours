package com.example.smarttourism.data

data class RouteSessionCreateRequest(
    val id: String,
    val device_id: String,
    val city: String,
    val status: String,
    val start_lat: Double,
    val start_lon: Double,
    val available_minutes: Int,
    val pace: String,
    val return_to_start: Boolean,
    val opening_hours_enabled: Boolean,
    val started_at: String,
    val finished_at: String?,
    val used_minutes: Int?,
    val total_walk_minutes: Int?,
    val total_visit_minutes: Int?,
    val route_snapshot_json: RouteResponse
)

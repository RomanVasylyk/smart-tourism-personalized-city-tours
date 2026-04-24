package com.example.smarttourism.data

data class RouteSessionUpdateRequest(
    val status: String? = null,
    val finished_at: String? = null,
    val used_minutes: Int? = null,
    val total_walk_minutes: Int? = null,
    val total_visit_minutes: Int? = null,
    val route_snapshot_json: RouteResponse? = null
)

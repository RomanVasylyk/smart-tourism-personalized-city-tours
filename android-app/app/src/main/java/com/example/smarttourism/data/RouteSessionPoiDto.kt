package com.example.smarttourism.data

data class RouteSessionPoiDto(
    val id: Int,
    val session_id: String,
    val poi_id: Int,
    val visit_order: Int,
    val planned_arrival_min: Int?,
    val planned_departure_min: Int?,
    val visited: Boolean,
    val visited_at: String?,
    val skipped: Boolean
)

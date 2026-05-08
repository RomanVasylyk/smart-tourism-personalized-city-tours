package com.example.smarttourism.data.remote.dto

data class RouteLegRequest(
    val city: String,
    val start_lat: Double,
    val start_lon: Double,
    val end_lat: Double,
    val end_lon: Double,
    val end_poi_id: Int,
    val end_name: String?,
    val pace: String,
    val start_datetime: String?,
    val transport_mode: String
)

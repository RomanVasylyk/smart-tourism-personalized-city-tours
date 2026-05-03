package com.example.smarttourism.data.remote.dto

data class RouteLegEndpointDto(
    val type: String,
    val poi_id: Int?,
    val name: String?,
    val lat: Double,
    val lon: Double
)

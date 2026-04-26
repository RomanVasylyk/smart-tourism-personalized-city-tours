package com.example.smarttourism.data

data class CityDto(
    val id: Int,
    val slug: String,
    val name: String,
    val country: String,
    val center_lat: Double,
    val center_lon: Double,
    val bbox: CityBboxDto?,
    val available_categories: List<String>?,
    val default_zoom: Double?,
    val routing_limits: RoutingLimitsDto?,
    val transport: TransportProfileDto?
)

package com.example.smarttourism.data

data class PoiDto(
    val id: Int,
    val name: String,
    val category: String,
    val lat: Double,
    val lon: Double,
    val opening_hours_raw: String?,
    val visit_duration_min: Int?,
    val base_score: Double?,
    val wikipedia_url: String?
)
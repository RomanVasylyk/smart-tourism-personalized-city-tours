package com.example.smarttourism.data.remote.dto

data class PoiDto(
    val id: Int,
    val name: String,
    val category: String,
    val lat: Double,
    val lon: Double,
    val address: String? = null,
    val opening_hours_raw: String?,
    val opening_hours_source: String? = null,
    val visit_duration_min: Int?,
    val base_score: Double?,
    val short_description: String?,
    val website: String? = null,
    val image_url: String? = null,
    val wikipedia_url: String?
)

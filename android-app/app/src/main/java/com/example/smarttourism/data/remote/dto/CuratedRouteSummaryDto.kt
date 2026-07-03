package com.example.smarttourism.data.remote.dto

data class CuratedRouteSummaryDto(
    val id: Int,
    val slug: String?,
    val title: String,
    val summary: String?,
    val theme: String?,
    val city: String?,
    val recommended_duration_min: Int?,
    val pace: String?,
    val transport_mode: String?,
    val return_to_start: Boolean?,
    val poi_count: Int,
    val stop_names: List<String>?
)

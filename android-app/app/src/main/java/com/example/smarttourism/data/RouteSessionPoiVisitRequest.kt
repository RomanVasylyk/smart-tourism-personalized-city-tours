package com.example.smarttourism.data

data class RouteSessionPoiVisitRequest(
    val visited_at: String,
    val skipped: Boolean = false
)

package com.example.smarttourism.data.remote.dto

data class RouteSessionPoiVisitRequest(
    val visited_at: String,
    val skipped: Boolean = false
)

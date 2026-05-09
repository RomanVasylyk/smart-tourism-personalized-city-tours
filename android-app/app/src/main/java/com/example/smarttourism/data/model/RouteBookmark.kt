package com.example.smarttourism.data.model

data class RouteBookmark(
    val id: String,
    val title: String,
    val citySlug: String,
    val snapshot: SavedRouteSnapshot,
    val createdAtEpochMs: Long,
    val updatedAtEpochMs: Long
)

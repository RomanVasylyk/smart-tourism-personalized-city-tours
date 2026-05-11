package com.example.smarttourism.data.model

data class RouteHistoryEntry(
    val routeId: String,
    val cityName: String,
    val status: String,
    val startedAt: String,
    val finishedAt: String?,
    val availableMinutes: Int,
    val usedMinutes: Int?,
    val totalWalkMinutes: Int?,
    val totalVisitMinutes: Int?,
    val snapshot: SavedRouteSnapshot,
    val visitedPoiIds: List<Int>,
    val skippedPoiIds: List<Int>,
    val feedback: RouteFeedback?,
    val updatedAtEpochMs: Long
)

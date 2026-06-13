package com.example.smarttourism.data.model

import com.example.smarttourism.data.remote.dto.RouteRequest
import com.example.smarttourism.data.remote.dto.RouteResponse

internal data class SavedRouteSnapshotCache(
    val request: RouteRequest,
    val response: RouteResponse
)

internal data class ActiveRouteSessionCache(
    val route_id: String,
    val status: String,
    val started_at: String,
    val current_target_poi_id: Int?,
    val visited_poi_ids: List<Int>,
    val skipped_poi_ids: List<Int>? = null,
    val progress_visited_count: Int,
    val progress_total_count: Int,
    val snapshot: SavedRouteSnapshotCache,
    val feedback: RouteFeedback? = null
)

internal data class RouteHistoryEntryCache(
    val routeId: String,
    val cityName: String,
    val status: String,
    val startedAt: String,
    val finishedAt: String?,
    val availableMinutes: Int,
    val usedMinutes: Int?,
    val totalWalkMinutes: Int?,
    val totalVisitMinutes: Int?,
    val snapshot: SavedRouteSnapshotCache,
    val visitedPoiIds: List<Int>,
    val skippedPoiIds: List<Int>,
    val feedback: RouteFeedback?,
    val updatedAtEpochMs: Long
)

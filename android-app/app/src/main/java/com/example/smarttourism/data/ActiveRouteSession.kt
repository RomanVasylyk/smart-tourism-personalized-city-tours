package com.example.smarttourism.data

data class ActiveRouteSession(
    val route_id: String,
    val status: String,
    val started_at: String,
    val current_target_poi_id: Int?,
    val visited_poi_ids: List<Int>,
    val skipped_poi_ids: List<Int>? = null,
    val progress_visited_count: Int,
    val progress_total_count: Int,
    val snapshot: SavedRouteSnapshot,
    val feedback: RouteFeedback? = null
)

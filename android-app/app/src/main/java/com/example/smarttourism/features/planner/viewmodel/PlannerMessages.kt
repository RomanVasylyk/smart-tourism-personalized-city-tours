package com.example.smarttourism.features.planner.viewmodel

import android.content.Context
import com.example.smarttourism.R

internal data class PlannerMessages(
    val poiPreviewFailed: String,
    val routeGenerationFailed: String,
    val requiredPlacesMissing: String,
    val offlineCitiesFallback: String,
    val offlinePoisFallback: String,
    val offlineRouteGeneration: String,
    val offlineMapDownloadFailed: String,
    val offlineMapDeleteFailed: String,
    val offlineMapDownloaded: String,
    val offlineMapDeleted: String,
    val pendingSyncQueued: String,
    val routeHistoryLoadFailed: String
) {
    companion object {
        fun from(context: Context): PlannerMessages =
            PlannerMessages(
                poiPreviewFailed = context.getString(R.string.error_poi_preview_failed),
                routeGenerationFailed = context.getString(R.string.error_route_generation_failed_default),
                requiredPlacesMissing = context.getString(R.string.error_required_places_missing),
                offlineCitiesFallback = context.getString(R.string.offline_cities_cache_used),
                offlinePoisFallback = context.getString(R.string.offline_pois_cache_used),
                offlineRouteGeneration = context.getString(R.string.offline_route_generation_unavailable),
                offlineMapDownloadFailed = context.getString(R.string.offline_map_download_failed),
                offlineMapDeleteFailed = context.getString(R.string.offline_map_delete_failed),
                offlineMapDownloaded = context.getString(R.string.offline_map_download_complete),
                offlineMapDeleted = context.getString(R.string.offline_map_delete_complete),
                pendingSyncQueued = context.getString(R.string.pending_sync_queued),
                routeHistoryLoadFailed = context.getString(R.string.route_history_load_failed)
            )
    }
}

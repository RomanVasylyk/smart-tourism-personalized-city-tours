package com.example.smarttourism.features.planner.data.sync

import android.content.Context
import com.example.smarttourism.core.platform.NetworkMonitor
import com.example.smarttourism.data.model.RouteFeedback
import com.example.smarttourism.data.model.SavedRouteSnapshot
import com.example.smarttourism.data.remote.dto.RouteFeedbackRequest
import com.example.smarttourism.data.remote.dto.RouteSessionCreateRequest
import com.example.smarttourism.data.remote.dto.RouteSessionPoiVisitRequest
import com.example.smarttourism.data.repository.OfflineCacheStore
import com.example.smarttourism.features.planner.data.mapper.toDto
import com.example.smarttourism.sync.OfflineSyncScheduler
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject

internal interface OfflineSyncRepository {
    fun isNetworkAvailable(): Boolean

    suspend fun getPendingSyncOperationCount(): Int

    suspend fun enqueuePendingRouteSession(
        sessionRouteId: String,
        deviceId: String,
        status: String,
        startedAt: String,
        snapshot: SavedRouteSnapshot,
        finishedAt: String?
    )

    suspend fun enqueuePendingPoiVisit(
        sessionId: String,
        poiId: Int,
        visitedAt: String,
        skipped: Boolean
    )

    suspend fun enqueuePendingFeedback(
        sessionId: String,
        feedback: RouteFeedback
    )

    fun scheduleImmediateSync()
}

internal class DefaultOfflineSyncRepository @Inject constructor(
    @ApplicationContext
    context: Context,
    private val offlineCacheStore: OfflineCacheStore
) : OfflineSyncRepository {
    private val appContext = context.applicationContext

    override fun isNetworkAvailable(): Boolean =
        NetworkMonitor.isNetworkAvailable(appContext)

    override suspend fun getPendingSyncOperationCount(): Int =
        offlineCacheStore.getPendingSyncOperationCount()

    override suspend fun enqueuePendingRouteSession(
        sessionRouteId: String,
        deviceId: String,
        status: String,
        startedAt: String,
        snapshot: SavedRouteSnapshot,
        finishedAt: String?
    ) {
        val response = snapshot.response
        offlineCacheStore.enqueuePendingRouteSession(
            RouteSessionCreateRequest(
                id = sessionRouteId,
                device_id = deviceId,
                city = snapshot.request.city.ifBlank { response.city },
                status = status,
                start_lat = response.start.lat,
                start_lon = response.start.lon,
                available_minutes = response.availableMinutes,
                pace = response.pace,
                return_to_start = response.returnToStart,
                opening_hours_enabled = response.respectOpeningHours,
                started_at = startedAt,
                finished_at = finishedAt,
                used_minutes = response.usedMinutes,
                total_walk_minutes = response.totalWalkMinutes,
                total_visit_minutes = response.totalVisitMinutes,
                route_snapshot_json = response.toDto()
            )
        )
    }

    override suspend fun enqueuePendingPoiVisit(
        sessionId: String,
        poiId: Int,
        visitedAt: String,
        skipped: Boolean
    ) {
        offlineCacheStore.enqueuePendingPoiVisit(
            sessionId = sessionId,
            poiId = poiId,
            request = RouteSessionPoiVisitRequest(
                visited_at = visitedAt,
                skipped = skipped
            )
        )
    }

    override suspend fun enqueuePendingFeedback(
        sessionId: String,
        feedback: RouteFeedback
    ) {
        offlineCacheStore.enqueuePendingFeedback(
            sessionId = sessionId,
            request = RouteFeedbackRequest(
                rating = feedback.rating,
                was_convenient = feedback.route_was_comfortable,
                too_much_walking = feedback.too_much_walking,
                pois_were_interesting = feedback.pois_were_interesting,
                comment = null
            )
        )
    }

    override fun scheduleImmediateSync() {
        OfflineSyncScheduler.scheduleImmediate(appContext)
    }
}

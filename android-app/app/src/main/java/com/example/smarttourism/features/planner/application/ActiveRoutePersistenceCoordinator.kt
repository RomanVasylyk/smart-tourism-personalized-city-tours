package com.example.smarttourism.features.planner.application

import com.example.smarttourism.data.model.ActiveRouteSession
import com.example.smarttourism.data.model.RouteFeedback
import com.example.smarttourism.data.model.SavedRouteSnapshot
import com.example.smarttourism.features.planner.data.session.RouteSessionRepository
import com.example.smarttourism.features.planner.data.sync.OfflineSyncRepository
import com.example.smarttourism.features.planner.domain.history.buildRouteHistoryEntry
import com.example.smarttourism.features.planner.domain.history.routeHistoryTimestamp
import com.example.smarttourism.features.planner.domain.history.upsertRouteHistoryEntry
import com.example.smarttourism.features.planner.domain.route.defaultRouteStartDateTime
import com.example.smarttourism.features.planner.domain.route.nextPendingPoi
import com.example.smarttourism.features.planner.domain.route.progressTotalCount
import com.example.smarttourism.features.planner.state.RouteSessionStatus
import javax.inject.Inject

internal class ActiveRoutePersistenceCoordinator @Inject constructor(
    private val routeSessionRepository: RouteSessionRepository,
    private val offlineSyncRepository: OfflineSyncRepository
) {
    suspend fun enqueueRouteSessionSync(
        deviceId: String,
        sessionRouteId: String,
        status: RouteSessionStatus,
        startedAt: String,
        snapshot: SavedRouteSnapshot,
        finishedAt: String? = null
    ): Int {
        offlineSyncRepository.enqueuePendingRouteSession(
            sessionRouteId = sessionRouteId,
            deviceId = deviceId,
            status = status.rawValue,
            startedAt = startedAt,
            snapshot = snapshot,
            finishedAt = finishedAt
        )
        val pendingCount = offlineSyncRepository.getPendingSyncOperationCount()
        offlineSyncRepository.scheduleImmediateSync()
        return pendingCount
    }

    suspend fun persistRouteSession(input: PersistRouteSessionInput): PersistRouteSessionResult {
        val nextTargetId = nextPendingPoi(
            input.snapshot.response.route,
            input.visitedPoiIds,
            input.skippedPoiIds
        )?.poiId
        val totalCount = progressTotalCount(input.snapshot.response.route, input.skippedPoiIds)
        val existingHistoryEntry = input.currentHistory.firstOrNull { entry -> entry.routeId == input.routeId }
        val finishedAt = if (input.status == RouteSessionStatus.COMPLETED || input.status == RouteSessionStatus.CANCELLED) {
            existingHistoryEntry?.finishedAt ?: defaultRouteStartDateTime().toString()
        } else {
            null
        }
        val historyUpdatedAt = when {
            input.status == RouteSessionStatus.COMPLETED || input.status == RouteSessionStatus.CANCELLED ->
                existingHistoryEntry?.updatedAtEpochMs ?: routeHistoryTimestamp(finishedAt)

            else -> System.currentTimeMillis()
        }
        val historyEntry = buildRouteHistoryEntry(
            routeId = input.routeId,
            cityName = input.snapshot.response.city,
            status = input.status,
            startedAt = input.startedAt,
            finishedAt = finishedAt,
            snapshot = input.snapshot,
            visitedPoiIds = input.visitedPoiIds,
            skippedPoiIds = input.skippedPoiIds,
            feedback = input.feedback,
            updatedAtEpochMs = historyUpdatedAt
        )

        routeSessionRepository.saveActiveSession(
            ActiveRouteSession(
                route_id = input.routeId,
                status = input.status.rawValue,
                started_at = input.startedAt,
                current_target_poi_id = nextTargetId,
                visited_poi_ids = input.visitedPoiIds,
                skipped_poi_ids = input.skippedPoiIds,
                progress_visited_count = input.visitedPoiIds.distinct().size,
                progress_total_count = totalCount,
                snapshot = input.snapshot,
                feedback = input.feedback
            )
        )
        routeSessionRepository.saveHistoryEntry(historyEntry)
        val pendingCount = enqueueRouteSessionSync(
            deviceId = input.deviceId,
            sessionRouteId = input.routeId,
            status = input.status,
            startedAt = input.startedAt,
            snapshot = input.snapshot,
            finishedAt = finishedAt
        )

        return PersistRouteSessionResult(
            nextTargetPoiId = nextTargetId,
            history = upsertRouteHistoryEntry(input.currentHistory, historyEntry),
            pendingSyncOperationCount = pendingCount
        )
    }

    suspend fun syncVisitedPois(
        sessionId: String,
        poiIds: List<Int>
    ): Int =
        syncPoiVisits(sessionId = sessionId, poiIds = poiIds, skipped = false)

    suspend fun syncSkippedPois(
        sessionId: String,
        poiIds: List<Int>
    ): Int =
        syncPoiVisits(sessionId = sessionId, poiIds = poiIds, skipped = true)

    suspend fun syncFeedback(
        sessionId: String,
        feedback: RouteFeedback
    ): Int {
        offlineSyncRepository.enqueuePendingFeedback(
            sessionId = sessionId,
            feedback = feedback
        )
        val pendingCount = offlineSyncRepository.getPendingSyncOperationCount()
        offlineSyncRepository.scheduleImmediateSync()
        return pendingCount
    }

    private suspend fun syncPoiVisits(
        sessionId: String,
        poiIds: List<Int>,
        skipped: Boolean
    ): Int {
        poiIds.distinct().forEach { poiId ->
            offlineSyncRepository.enqueuePendingPoiVisit(
                sessionId = sessionId,
                poiId = poiId,
                visitedAt = defaultRouteStartDateTime().toString(),
                skipped = skipped
            )
        }
        val pendingCount = offlineSyncRepository.getPendingSyncOperationCount()
        offlineSyncRepository.scheduleImmediateSync()
        return pendingCount
    }
}

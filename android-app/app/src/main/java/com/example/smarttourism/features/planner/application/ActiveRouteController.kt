package com.example.smarttourism.features.planner.application

import com.example.smarttourism.data.model.ActiveRouteSession
import com.example.smarttourism.data.model.RouteFeedback
import com.example.smarttourism.data.model.RouteHistoryEntry
import com.example.smarttourism.data.model.SavedRouteSnapshot
import com.example.smarttourism.features.planner.data.PlannerRepository
import com.example.smarttourism.features.planner.domain.model.RoutePlan
import com.example.smarttourism.features.planner.domain.model.RoutePoint
import com.example.smarttourism.features.planner.domain.model.RouteStop
import com.example.smarttourism.features.planner.state.AutoRerouteCooldownMs
import com.example.smarttourism.features.planner.state.OffRouteDistanceMeters
import com.example.smarttourism.features.planner.state.OffRouteSustainDurationMs
import com.example.smarttourism.features.planner.state.RoutePlannerUiState
import com.example.smarttourism.features.planner.state.RouteProgressMetrics
import com.example.smarttourism.features.planner.state.RouteSessionStatus
import com.example.smarttourism.features.planner.viewmodel.buildRouteHistoryEntry
import com.example.smarttourism.features.planner.viewmodel.defaultRouteStartDateTime
import com.example.smarttourism.features.planner.viewmodel.distanceToNextRouteSegmentMeters
import com.example.smarttourism.features.planner.viewmodel.isRestorable
import com.example.smarttourism.features.planner.viewmodel.markNearbyPoisVisited
import com.example.smarttourism.features.planner.viewmodel.nextPendingPoi
import com.example.smarttourism.features.planner.viewmodel.progressTotalCount
import com.example.smarttourism.features.planner.viewmodel.rerouteStartPoint
import com.example.smarttourism.features.planner.viewmodel.routeHistoryTimestamp
import com.example.smarttourism.features.planner.viewmodel.upsertRouteHistoryEntry
import javax.inject.Inject

internal data class PersistRouteSessionInput(
    val deviceId: String,
    val routeId: String,
    val status: RouteSessionStatus,
    val startedAt: String,
    val snapshot: SavedRouteSnapshot,
    val visitedPoiIds: List<Int>,
    val skippedPoiIds: List<Int>,
    val feedback: RouteFeedback?,
    val currentHistory: List<RouteHistoryEntry>
)

internal data class PersistRouteSessionResult(
    val nextTargetPoiId: Int?,
    val history: List<RouteHistoryEntry>,
    val pendingSyncOperationCount: Int
)

internal data class ActiveRouteLifecycleResult(
    val state: RoutePlannerUiState,
    val routeId: String,
    val startedAt: String,
    val status: RouteSessionStatus,
    val feedback: RouteFeedback? = state.routeFeedback
)

internal data class ActiveApproachRefresh(
    val previousResponse: RoutePlan,
    val currentLocation: RoutePoint,
    val nextTarget: RouteStop
)

internal data class RouteStopProgressResult(
    val state: RoutePlannerUiState,
    val statusToPersist: RouteSessionStatus,
    val visitedPoiIds: List<Int>,
    val skippedPoiIds: List<Int>,
    val syncVisitedPoiIds: List<Int> = emptyList(),
    val syncSkippedPoiIds: List<Int> = emptyList(),
    val snapshotToSave: SavedRouteSnapshot? = null,
    val approachRefresh: ActiveApproachRefresh? = null
)

internal data class TrackedLocationResult(
    val state: RoutePlannerUiState,
    val statusToPersist: RouteSessionStatus? = null,
    val visitedPoiIds: List<Int> = state.visitedPoiIds,
    val skippedPoiIds: List<Int> = state.skippedPoiIds,
    val syncVisitedPoiIds: List<Int> = emptyList(),
    val snapshotToSave: SavedRouteSnapshot? = null,
    val approachRefresh: ActiveApproachRefresh? = null,
    val approachRefreshAutoTriggered: Boolean = false
)

internal class ActiveRouteController @Inject constructor(
    private val repository: PlannerRepository
) {
    fun activateRouteTracking(
        state: RoutePlannerUiState,
        newRouteId: String,
        startedAtNow: String
    ): ActiveRouteLifecycleResult? {
        if (state.hasPendingRouteChanges) {
            return null
        }
        val response = state.routeResponse
        if (response?.route.isNullOrEmpty() || state.currentRouteRequest == null) {
            return null
        }

        val shouldStartFreshSession =
            state.routeSessionStatus == RouteSessionStatus.NOT_STARTED ||
                state.routeSessionStatus == RouteSessionStatus.COMPLETED ||
                state.routeSessionStatus == RouteSessionStatus.CANCELLED
        val visitedPoiIds = if (shouldStartFreshSession) emptyList() else state.visitedPoiIds
        val skippedPoiIds = if (shouldStartFreshSession) emptyList() else state.skippedPoiIds
        val activeRouteId = if (shouldStartFreshSession) {
            newRouteId
        } else {
            state.routeId ?: newRouteId
        }
        val activeStartedAt = if (shouldStartFreshSession) {
            startedAtNow
        } else {
            state.routeStartedAt ?: startedAtNow
        }
        val updatedState = state.copy(
            routeId = activeRouteId,
            routeStartedAt = activeStartedAt,
            currentTargetPoiId = nextPendingPoi(response!!.route, visitedPoiIds, skippedPoiIds)?.poiId,
            visitedPoiIds = visitedPoiIds,
            skippedPoiIds = skippedPoiIds,
            routeFeedback = if (shouldStartFreshSession) null else state.routeFeedback,
            trackingError = null,
            offRouteDetectedAtMs = null,
            lastAutoRerouteAtMs = null,
            routeSessionStatus = RouteSessionStatus.IN_PROGRESS
        )

        return ActiveRouteLifecycleResult(
            state = updatedState,
            routeId = activeRouteId,
            startedAt = activeStartedAt,
            status = RouteSessionStatus.IN_PROGRESS,
            feedback = null
        )
    }

    fun pauseRoute(state: RoutePlannerUiState): ActiveRouteLifecycleResult? {
        if (state.routeSessionStatus != RouteSessionStatus.IN_PROGRESS) {
            return null
        }
        val activeRouteId = state.routeId ?: return null
        val activeStartedAt = state.routeStartedAt ?: defaultRouteStartDateTime().toString()
        return ActiveRouteLifecycleResult(
            state = state.copy(routeSessionStatus = RouteSessionStatus.PAUSED),
            routeId = activeRouteId,
            startedAt = activeStartedAt,
            status = RouteSessionStatus.PAUSED
        )
    }

    fun resumeRoute(
        state: RoutePlannerUiState,
        newRouteId: String,
        startedAtNow: String
    ): ActiveRouteLifecycleResult {
        val activeRouteId = state.routeId ?: newRouteId
        val activeStartedAt = state.routeStartedAt ?: startedAtNow
        return ActiveRouteLifecycleResult(
            state = state.copy(
                routeId = activeRouteId,
                routeStartedAt = activeStartedAt,
                routeSessionStatus = RouteSessionStatus.IN_PROGRESS,
                trackingError = null,
                offRouteDetectedAtMs = null,
                lastAutoRerouteAtMs = null
            ),
            routeId = activeRouteId,
            startedAt = activeStartedAt,
            status = RouteSessionStatus.IN_PROGRESS
        )
    }

    fun finishRoute(
        state: RoutePlannerUiState,
        progressMetrics: RouteProgressMetrics
    ): ActiveRouteLifecycleResult? {
        if (!progressMetrics.canComplete) {
            return null
        }
        val activeRouteId = state.routeId ?: return null
        val activeStartedAt = state.routeStartedAt ?: defaultRouteStartDateTime().toString()
        return ActiveRouteLifecycleResult(
            state = state.copy(routeSessionStatus = RouteSessionStatus.COMPLETED),
            routeId = activeRouteId,
            startedAt = activeStartedAt,
            status = RouteSessionStatus.COMPLETED
        )
    }

    fun cancelRoute(state: RoutePlannerUiState): ActiveRouteLifecycleResult? {
        val activeRouteId = state.routeId ?: return null
        val activeStartedAt = state.routeStartedAt ?: defaultRouteStartDateTime().toString()
        return ActiveRouteLifecycleResult(
            state = state.copy(routeSessionStatus = RouteSessionStatus.CANCELLED),
            routeId = activeRouteId,
            startedAt = activeStartedAt,
            status = RouteSessionStatus.CANCELLED
        )
    }

    fun updateFeedback(
        state: RoutePlannerUiState,
        feedback: RouteFeedback
    ): ActiveRouteLifecycleResult? {
        val activeRouteId = state.routeId ?: return null
        val activeStartedAt = state.routeStartedAt ?: defaultRouteStartDateTime().toString()
        return ActiveRouteLifecycleResult(
            state = state.copy(
                routeFeedback = feedback,
                routeSessionStatus = RouteSessionStatus.COMPLETED
            ),
            routeId = activeRouteId,
            startedAt = activeStartedAt,
            status = RouteSessionStatus.COMPLETED,
            feedback = feedback
        )
    }

    fun handleTrackingError(
        state: RoutePlannerUiState,
        message: String
    ): ActiveRouteLifecycleResult? {
        val activeRouteId = state.routeId ?: return null
        val activeStartedAt = state.routeStartedAt ?: defaultRouteStartDateTime().toString()
        return ActiveRouteLifecycleResult(
            state = state.copy(
                routeSessionStatus = RouteSessionStatus.PAUSED,
                trackingError = message,
                offRouteDetectedAtMs = null
            ),
            routeId = activeRouteId,
            startedAt = activeStartedAt,
            status = RouteSessionStatus.PAUSED
        )
    }

    fun resetRouteSession(state: RoutePlannerUiState): RoutePlannerUiState =
        state.copy(
            routeSessionStatus = RouteSessionStatus.NOT_STARTED,
            routeId = null,
            routeStartedAt = null,
            currentTargetPoiId = null,
            currentRouteLocation = null,
            trackingError = null,
            routeFeedback = null,
            offRouteDetectedAtMs = null,
            lastAutoRerouteAtMs = null,
            visitedPoiIds = emptyList(),
            skippedPoiIds = emptyList()
        )

    fun restoreActiveSession(
        state: RoutePlannerUiState,
        session: ActiveRouteSession
    ): RoutePlannerUiState {
        val restoredStatus = RouteSessionStatus.fromRawValue(session.status)
        val visitedPoiIds = session.visited_poi_ids.distinct()
        val skippedPoiIds = session.skipped_poi_ids.orEmpty().distinct()
        val nextPendingPoiId = nextPendingPoi(
            session.snapshot.response.route,
            visitedPoiIds,
            skippedPoiIds
        )?.poiId

        return state.copy(
            routeId = session.route_id,
            routeStartedAt = session.started_at,
            routeFeedback = session.feedback,
            visitedPoiIds = visitedPoiIds,
            skippedPoiIds = skippedPoiIds,
            currentTargetPoiId = nextPendingPoiId ?: session.current_target_poi_id,
            routeSessionStatus = if (restoredStatus.isRestorable() && nextPendingPoiId == null) {
                RouteSessionStatus.COMPLETED
            } else {
                restoredStatus
            }
        )
    }

    fun markRouteStopVisited(
        state: RoutePlannerUiState,
        poiId: Int,
        isNetworkAvailable: Boolean
    ): RouteStopProgressResult? {
        if (poiId in state.visitedPoiIds || poiId in state.skippedPoiIds) {
            return null
        }

        val responseBeforeVisit = state.routeResponse
        val locationAtVisit = state.currentRouteLocation
        val statusAtVisit = state.routeSessionStatus
        val updatedVisited = (state.visitedPoiIds + poiId).distinct()
        val nextPendingPoi = nextPendingPoi(state.routeItems, updatedVisited, state.skippedPoiIds)
        val baseState = state.copy(
            visitedPoiIds = updatedVisited,
            currentTargetPoiId = nextPendingPoi?.poiId
        )

        if (nextPendingPoi == null) {
            val completedState = baseState.copy(routeSessionStatus = RouteSessionStatus.COMPLETED)
            return RouteStopProgressResult(
                state = completedState,
                statusToPersist = RouteSessionStatus.COMPLETED,
                visitedPoiIds = updatedVisited,
                skippedPoiIds = state.skippedPoiIds,
                syncVisitedPoiIds = listOf(poiId)
            )
        }

        val approachRefresh = if (
            statusAtVisit == RouteSessionStatus.IN_PROGRESS &&
            responseBeforeVisit != null &&
            isNetworkAvailable
        ) {
            ActiveApproachRefresh(
                previousResponse = responseBeforeVisit,
                currentLocation = rerouteStartPoint(
                    routeItems = state.routeItems,
                    visitedPoiIds = updatedVisited,
                    currentLocation = locationAtVisit,
                    fallbackStart = state.startPoint
                ),
                nextTarget = nextPendingPoi
            )
        } else {
            null
        }

        return RouteStopProgressResult(
            state = baseState,
            statusToPersist = baseState.routeSessionStatus,
            visitedPoiIds = updatedVisited,
            skippedPoiIds = state.skippedPoiIds,
            syncVisitedPoiIds = listOf(poiId),
            snapshotToSave = if (approachRefresh == null) baseState.currentSnapshot() else null,
            approachRefresh = approachRefresh
        )
    }

    fun skipRouteStop(
        state: RoutePlannerUiState,
        poiId: Int,
        isNetworkAvailable: Boolean
    ): RouteStopProgressResult? {
        if (poiId in state.visitedPoiIds || poiId in state.skippedPoiIds) {
            return null
        }

        val responseBeforeSkip = state.routeResponse
        val locationAtSkip = state.currentRouteLocation
        val statusAtSkip = state.routeSessionStatus
        val updatedSkipped = (state.skippedPoiIds + poiId).distinct()
        val updatedRequest = state.currentRouteRequest?.copy(
            excludedPoiIds = (state.currentRouteRequest.excludedPoiIds.orEmpty() + poiId).distinct(),
            preferredPoiIds = state.currentRouteRequest.preferredPoiIds
                .orEmpty()
                .filterNot { preferredPoiId -> preferredPoiId == poiId }
        )
        val updatedRequiredPoiIds = state.requiredPoiIds.filterNot { requiredPoiId -> requiredPoiId == poiId }
        val nextPendingPoi = nextPendingPoi(state.routeItems, state.visitedPoiIds, updatedSkipped)
        val baseState = state.copy(
            skippedPoiIds = updatedSkipped,
            currentRouteRequest = updatedRequest,
            requiredPoiIds = updatedRequiredPoiIds,
            currentTargetPoiId = nextPendingPoi?.poiId
        )

        if (nextPendingPoi == null) {
            val completedState = baseState.copy(routeSessionStatus = RouteSessionStatus.COMPLETED)
            return RouteStopProgressResult(
                state = completedState,
                statusToPersist = RouteSessionStatus.COMPLETED,
                visitedPoiIds = state.visitedPoiIds,
                skippedPoiIds = updatedSkipped,
                syncSkippedPoiIds = listOf(poiId),
                snapshotToSave = completedState.currentSnapshot()
            )
        }

        val approachRefresh = if (
            statusAtSkip == RouteSessionStatus.IN_PROGRESS &&
            responseBeforeSkip != null &&
            isNetworkAvailable
        ) {
            ActiveApproachRefresh(
                previousResponse = responseBeforeSkip,
                currentLocation = rerouteStartPoint(
                    routeItems = state.routeItems,
                    visitedPoiIds = state.visitedPoiIds,
                    currentLocation = locationAtSkip,
                    fallbackStart = state.startPoint
                ),
                nextTarget = nextPendingPoi
            )
        } else {
            null
        }

        return RouteStopProgressResult(
            state = baseState,
            statusToPersist = baseState.routeSessionStatus,
            visitedPoiIds = state.visitedPoiIds,
            skippedPoiIds = updatedSkipped,
            syncSkippedPoiIds = listOf(poiId),
            snapshotToSave = baseState.currentSnapshot(),
            approachRefresh = approachRefresh
        )
    }

    fun handleTrackedLocation(
        state: RoutePlannerUiState,
        routeLocation: RoutePoint,
        nowMs: Long,
        isNetworkAvailable: Boolean,
        isRerouting: Boolean
    ): TrackedLocationResult {
        var updatedState = state.copy(
            currentRouteLocation = routeLocation,
            trackingError = null
        )
        var approachLegRefreshed = false

        val mutableVisited = updatedState.visitedPoiIds.toMutableList()
        val newlyVisitedPoiIds = markNearbyPoisVisited(
            routeItems = updatedState.routeItems,
            currentLocation = routeLocation,
            visitedPoiIds = mutableVisited,
            skippedPoiIds = updatedState.skippedPoiIds
        )
        val updatedVisitedPoiIds = mutableVisited.distinct()
        val nextTarget = nextPendingPoi(
            updatedState.routeItems,
            updatedVisitedPoiIds,
            updatedState.skippedPoiIds
        )
        val isCurrentlyOffRoute = updatedState.routeResponse != null &&
            nextTarget != null &&
            distanceToNextRouteSegmentMeters(
                updatedState.routeResponse,
                nextTarget.poiId,
                routeLocation
            ) > OffRouteDistanceMeters

        updatedState = updatedState.copy(
            visitedPoiIds = updatedVisitedPoiIds,
            currentTargetPoiId = nextTarget?.poiId,
            offRouteDetectedAtMs = if (isCurrentlyOffRoute) {
                updatedState.offRouteDetectedAtMs ?: nowMs
            } else {
                null
            }
        )

        var statusToPersist: RouteSessionStatus? = null
        var snapshotToSave: SavedRouteSnapshot? = null
        var approachRefresh: ActiveApproachRefresh? = null
        var approachRefreshAutoTriggered = false

        if (nextTarget == null && updatedState.routeSessionStatus != RouteSessionStatus.COMPLETED) {
            updatedState = updatedState.copy(routeSessionStatus = RouteSessionStatus.COMPLETED)
            statusToPersist = RouteSessionStatus.COMPLETED
        } else if (newlyVisitedPoiIds.isNotEmpty()) {
            statusToPersist = RouteSessionStatus.IN_PROGRESS
            if (
                updatedState.routeSessionStatus == RouteSessionStatus.IN_PROGRESS &&
                updatedState.routeResponse != null &&
                nextTarget != null &&
                isNetworkAvailable
            ) {
                approachRefresh = ActiveApproachRefresh(
                    previousResponse = updatedState.routeResponse,
                    currentLocation = routeLocation,
                    nextTarget = nextTarget
                )
                approachLegRefreshed = true
            } else {
                snapshotToSave = updatedState.currentSnapshot()
            }
        }

        val offRouteDetectedAt = updatedState.offRouteDetectedAtMs
        val autoRerouteCooldownElapsed = updatedState.lastAutoRerouteAtMs?.let { lastTriggeredAt ->
            nowMs - lastTriggeredAt >= AutoRerouteCooldownMs
        } ?: true
        val offRouteSustained = offRouteDetectedAt != null &&
            nowMs - offRouteDetectedAt >= OffRouteSustainDurationMs

        if (
            !approachLegRefreshed &&
            updatedState.routeSessionStatus == RouteSessionStatus.IN_PROGRESS &&
            updatedState.routeResponse != null &&
            nextTarget != null &&
            isCurrentlyOffRoute &&
            offRouteSustained &&
            autoRerouteCooldownElapsed &&
            isNetworkAvailable &&
            !isRerouting
        ) {
            approachRefresh = ActiveApproachRefresh(
                previousResponse = updatedState.routeResponse,
                currentLocation = routeLocation,
                nextTarget = nextTarget
            )
            approachRefreshAutoTriggered = true
        }

        return TrackedLocationResult(
            state = updatedState,
            statusToPersist = statusToPersist,
            visitedPoiIds = updatedVisitedPoiIds,
            skippedPoiIds = updatedState.skippedPoiIds,
            syncVisitedPoiIds = newlyVisitedPoiIds,
            snapshotToSave = snapshotToSave,
            approachRefresh = approachRefresh,
            approachRefreshAutoTriggered = approachRefreshAutoTriggered
        )
    }

    suspend fun enqueueRouteSessionSync(
        deviceId: String,
        sessionRouteId: String,
        status: RouteSessionStatus,
        startedAt: String,
        snapshot: SavedRouteSnapshot,
        finishedAt: String? = null
    ): Int {
        repository.enqueuePendingRouteSession(
            sessionRouteId = sessionRouteId,
            deviceId = deviceId,
            status = status.rawValue,
            startedAt = startedAt,
            snapshot = snapshot,
            finishedAt = finishedAt
        )
        val pendingCount = repository.getPendingSyncOperationCount()
        repository.scheduleImmediateSync()
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

        repository.saveActiveSession(
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
        repository.saveRouteHistoryEntry(historyEntry)
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
        repository.enqueuePendingFeedback(
            sessionId = sessionId,
            feedback = feedback
        )
        val pendingCount = repository.getPendingSyncOperationCount()
        repository.scheduleImmediateSync()
        return pendingCount
    }

    private suspend fun syncPoiVisits(
        sessionId: String,
        poiIds: List<Int>,
        skipped: Boolean
    ): Int {
        poiIds.distinct().forEach { poiId ->
            repository.enqueuePendingPoiVisit(
                sessionId = sessionId,
                poiId = poiId,
                visitedAt = defaultRouteStartDateTime().toString(),
                skipped = skipped
            )
        }
        val pendingCount = repository.getPendingSyncOperationCount()
        repository.scheduleImmediateSync()
        return pendingCount
    }

    private fun RoutePlannerUiState.currentSnapshot(): SavedRouteSnapshot? {
        val request = currentRouteRequest
        val response = routeResponse
        return if (request != null && response != null) {
            SavedRouteSnapshot(request = request, response = response)
        } else {
            null
        }
    }
}

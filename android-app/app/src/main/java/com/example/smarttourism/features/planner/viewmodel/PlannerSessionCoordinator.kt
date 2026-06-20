package com.example.smarttourism.features.planner.viewmodel

import com.example.smarttourism.core.logging.AndroidLogErrorReporter
import com.example.smarttourism.core.logging.ErrorReporter
import com.example.smarttourism.data.model.ActiveRouteSession
import com.example.smarttourism.data.model.RouteFeedback
import com.example.smarttourism.data.model.RouteHistoryEntry
import com.example.smarttourism.data.model.SavedRouteSnapshot
import com.example.smarttourism.features.planner.application.ActiveRouteController
import com.example.smarttourism.features.planner.application.PersistRouteSessionInput
import com.example.smarttourism.features.planner.application.RouteHistoryController
import com.example.smarttourism.features.planner.application.toUserMessage
import com.example.smarttourism.features.planner.data.route.RoutePlanningRepository
import com.example.smarttourism.features.planner.data.session.RouteSessionRepository
import com.example.smarttourism.features.planner.data.sync.OfflineSyncRepository
import com.example.smarttourism.features.planner.domain.history.buildRouteHistoryEntry
import com.example.smarttourism.features.planner.domain.history.routeHistoryTimestamp
import com.example.smarttourism.features.planner.domain.history.upsertRouteHistoryEntry
import com.example.smarttourism.features.planner.domain.model.RouteLegQuery
import com.example.smarttourism.features.planner.domain.model.RoutePlan
import com.example.smarttourism.features.planner.domain.model.RoutePoint
import com.example.smarttourism.features.planner.domain.model.RouteStop
import com.example.smarttourism.features.planner.domain.route.defaultRouteStartDateTime
import com.example.smarttourism.features.planner.domain.route.estimateRemainingMinutes
import com.example.smarttourism.features.planner.domain.route.finalizedHandledRoutePlan
import com.example.smarttourism.features.planner.domain.route.mergeReroutedRoutePlan
import com.example.smarttourism.features.planner.domain.route.nextPendingPoi
import com.example.smarttourism.features.planner.domain.route.replaceActiveRouteApproachLeg
import com.example.smarttourism.features.planner.state.RouteSessionStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

internal class PlannerSessionCoordinator(
    private val state: PlannerStateStore,
    private val scope: CoroutineScope,
    private val deviceId: String,
    private val routePlanningRepository: RoutePlanningRepository,
    private val routeSessionRepository: RouteSessionRepository,
    private val offlineSyncRepository: OfflineSyncRepository,
    private val activeRouteController: ActiveRouteController,
    private val routeHistoryController: RouteHistoryController,
    private val messages: PlannerMessages,
    private val errorReporter: ErrorReporter = AndroidLogErrorReporter
) {
    fun currentRouteSnapshot(): SavedRouteSnapshot? =
        state.currentRouteSnapshot()

    fun clearRouteMessages() {
        state.clearRouteMessages()
    }

    fun refreshPendingSyncOperationCount() {
        scope.launch {
            state.pendingSyncOperationCount = offlineSyncRepository.getPendingSyncOperationCount()
        }
    }

    fun saveSnapshot(snapshot: SavedRouteSnapshot) {
        scope.launch {
            routePlanningRepository.saveSnapshot(snapshot)
        }
    }

    fun restoreActiveSession(session: ActiveRouteSession) {
        state.update { currentState ->
            activeRouteController.restoreActiveSession(currentState, session)
        }
    }

    fun resetRouteSession(clearStoredSession: Boolean = true) {
        state.update { currentState ->
            activeRouteController.resetRouteSession(currentState)
        }
        if (clearStoredSession) {
            scope.launch {
                routeSessionRepository.clearActiveSession()
            }
        }
    }

    suspend fun enqueueRouteSessionSync(
        sessionRouteId: String,
        status: RouteSessionStatus,
        startedAtValue: String,
        snapshot: SavedRouteSnapshot,
        finishedAt: String? = null
    ) {
        state.pendingSyncOperationCount = activeRouteController.enqueueRouteSessionSync(
            deviceId = deviceId,
            sessionRouteId = sessionRouteId,
            status = status,
            startedAt = startedAtValue,
            snapshot = snapshot,
            finishedAt = finishedAt
        )
    }

    fun persistRouteSession(
        status: RouteSessionStatus = state.routeSessionStatus,
        routeIdValue: String? = state.routeId,
        startedAtValue: String? = state.routeStartedAt,
        visitedIds: List<Int> = state.visitedPoiIds,
        skippedIds: List<Int> = state.skippedPoiIds,
        feedback: RouteFeedback? = state.routeFeedback,
        snapshotOverride: SavedRouteSnapshot? = null
    ) {
        val snapshot = snapshotOverride ?: currentRouteSnapshot() ?: return
        val savedRouteId = routeIdValue ?: return
        val savedStartedAt = startedAtValue ?: defaultRouteStartDateTime().toString()
        state.currentTargetPoiId = nextPendingPoi(snapshot.response.route, visitedIds, skippedIds)?.poiId
        scope.launch {
            val result = activeRouteController.persistRouteSession(
                PersistRouteSessionInput(
                    deviceId = deviceId,
                    routeId = savedRouteId,
                    status = status,
                    startedAt = savedStartedAt,
                    snapshot = snapshot,
                    visitedPoiIds = visitedIds,
                    skippedPoiIds = skippedIds,
                    feedback = feedback,
                    currentHistory = state.routeHistory
                )
            )
            state.currentTargetPoiId = result.nextTargetPoiId
            state.routeHistory = result.history
            state.pendingSyncOperationCount = result.pendingSyncOperationCount
        }
    }

    suspend fun refreshRouteHistory(forceRefresh: Boolean) {
        state.isRouteHistoryLoading = true
        state.routeHistoryError = null
        val result = routeHistoryController.refreshRouteHistory(
            deviceId = deviceId,
            forceRefresh = forceRefresh,
            currentHistory = state.routeHistory,
            currentEntry = buildCurrentRouteHistoryEntry(),
            routeHistoryLoadFailedMessage = messages.routeHistoryLoadFailed
        )
        state.routeHistory = result.history
        state.routeHistoryError = result.error
        state.isRouteHistoryLoading = false
    }

    fun buildCurrentRouteHistoryEntry(): RouteHistoryEntry? {
        val currentSnapshot = currentRouteSnapshot() ?: return null
        val currentRouteId = state.routeId ?: return null
        val startedAtValue = state.routeStartedAt ?: defaultRouteStartDateTime().toString()
        val existingHistoryEntry = state.routeHistory.firstOrNull { entry -> entry.routeId == currentRouteId }
        val finishedAtValue = if (
            state.routeSessionStatus == RouteSessionStatus.COMPLETED ||
                state.routeSessionStatus == RouteSessionStatus.CANCELLED
        ) {
            existingHistoryEntry?.finishedAt ?: defaultRouteStartDateTime().toString()
        } else {
            null
        }
        val historyUpdatedAt = when {
            state.routeSessionStatus == RouteSessionStatus.COMPLETED ||
                state.routeSessionStatus == RouteSessionStatus.CANCELLED ->
                existingHistoryEntry?.updatedAtEpochMs ?: routeHistoryTimestamp(finishedAtValue)
            else -> System.currentTimeMillis()
        }

        return buildRouteHistoryEntry(
            routeId = currentRouteId,
            cityName = currentSnapshot.response.city,
            status = state.routeSessionStatus,
            startedAt = startedAtValue,
            finishedAt = finishedAtValue,
            snapshot = currentSnapshot,
            visitedPoiIds = state.visitedPoiIds,
            skippedPoiIds = state.skippedPoiIds,
            feedback = state.routeFeedback,
            updatedAtEpochMs = historyUpdatedAt
        )
    }

    fun syncVisitedPoisToBackend(poiIds: List<Int>) {
        val sessionRouteId = state.routeId ?: return
        if (poiIds.isEmpty()) {
            return
        }

        scope.launch {
            state.pendingSyncOperationCount = activeRouteController.syncVisitedPois(
                sessionId = sessionRouteId,
                poiIds = poiIds
            )
        }
    }

    fun syncSkippedPoisToBackend(poiIds: List<Int>) {
        val sessionRouteId = state.routeId ?: return
        if (poiIds.isEmpty()) {
            return
        }

        scope.launch {
            state.pendingSyncOperationCount = activeRouteController.syncSkippedPois(
                sessionId = sessionRouteId,
                poiIds = poiIds
            )
        }
    }

    fun syncFeedbackToBackend(feedback: RouteFeedback) {
        val sessionRouteId = state.routeId ?: return
        if (feedback.rating !in 1..5) {
            return
        }

        scope.launch {
            state.pendingSyncOperationCount = activeRouteController.syncFeedback(
                sessionId = sessionRouteId,
                feedback = feedback
            )
            state.offlineStatusMessage = if (offlineSyncRepository.isNetworkAvailable()) {
                null
            } else {
                messages.pendingSyncQueued
            }
        }
    }

    fun clearDisplayedRoute(cancelActiveSession: Boolean) {
        val snapshot = currentRouteSnapshot()
        val activeRouteId = state.routeId
        val activeStatus = state.routeSessionStatus
        val activeStartedAt = state.routeStartedAt ?: defaultRouteStartDateTime().toString()
        val cancelledFinishedAt = defaultRouteStartDateTime().toString()
        val existingHistoryEntry = activeRouteId?.let { activeId ->
            state.routeHistory.firstOrNull { entry -> entry.routeId == activeId }
        }

        state.activeBookmarkId = null
        state.hasPendingRouteChanges = false
        state.routeResponse = null
        state.currentRouteRequest = null
        clearRouteMessages()

        if (
            cancelActiveSession &&
            activeRouteId != null &&
            snapshot != null &&
            (activeStatus == RouteSessionStatus.IN_PROGRESS || activeStatus == RouteSessionStatus.PAUSED)
        ) {
            val cancelledHistoryEntry = buildRouteHistoryEntry(
                routeId = activeRouteId,
                cityName = snapshot.response.city,
                status = RouteSessionStatus.CANCELLED,
                startedAt = activeStartedAt,
                finishedAt = existingHistoryEntry?.finishedAt ?: cancelledFinishedAt,
                snapshot = snapshot,
                visitedPoiIds = state.visitedPoiIds,
                skippedPoiIds = state.skippedPoiIds,
                feedback = state.routeFeedback,
                updatedAtEpochMs = existingHistoryEntry?.updatedAtEpochMs
                    ?: routeHistoryTimestamp(existingHistoryEntry?.finishedAt ?: cancelledFinishedAt)
            )
            scope.launch {
                routeSessionRepository.saveHistoryEntry(cancelledHistoryEntry)
                state.routeHistory = upsertRouteHistoryEntry(state.routeHistory, cancelledHistoryEntry)
                enqueueRouteSessionSync(
                    sessionRouteId = activeRouteId,
                    status = RouteSessionStatus.CANCELLED,
                    startedAtValue = activeStartedAt,
                    snapshot = snapshot,
                    finishedAt = cancelledFinishedAt
                )
            }
        }

        resetRouteSession()
    }

    fun recalculateRouteFromPoint(
        currentLocation: RoutePoint,
        autoTriggered: Boolean,
        additionalExcludedPoiIds: List<Int>
    ) {
        val baseRequest = state.currentRouteRequest ?: return
        val response = state.routeResponse ?: return

        scope.launch {
            state.isRerouting = true
            state.routeError = null
            state.offRouteDetectedAtMs = null

            val effectiveSkippedPoiIds = (state.skippedPoiIds + additionalExcludedPoiIds).distinct()
            val nextTarget = nextPendingPoi(state.routeItems, state.visitedPoiIds, effectiveSkippedPoiIds)
            val remainingMinutes = estimateRemainingMinutes(
                routeResponse = response,
                routeItems = state.routeItems,
                visitedPoiIds = state.visitedPoiIds,
                skippedPoiIds = effectiveSkippedPoiIds,
                currentLocation = currentLocation,
                nextTarget = nextTarget
            ).coerceIn(30, maxOf(30, baseRequest.availableMinutes))
            val request = baseRequest.copy(
                startLat = currentLocation.lat,
                startLon = currentLocation.lon,
                availableMinutes = remainingMinutes,
                startDateTime = defaultRouteStartDateTime().toString(),
                excludedPoiIds = (state.visitedPoiIds + effectiveSkippedPoiIds).distinct(),
                preferredPoiIds = baseRequest.preferredPoiIds
                    .orEmpty()
                    .filterNot { poiId -> poiId in state.visitedPoiIds || poiId in effectiveSkippedPoiIds },
                transportMode = baseRequest.transportMode ?: "walk"
            )

            try {
                val generatedRoute = routePlanningRepository.generateRoute(request)
                val mergedRoute = mergeReroutedRoutePlan(
                    previousResponse = response,
                    reroutedResponse = generatedRoute,
                    visitedPoiIds = state.visitedPoiIds
                )
                val nextPendingPoiId = nextPendingPoi(
                    mergedRoute.route,
                    state.visitedPoiIds,
                    effectiveSkippedPoiIds
                )?.poiId
                val finalizedRoute = if (nextPendingPoiId == null) {
                    finalizedHandledRoutePlan(
                        previousResponse = mergedRoute,
                        visitedPoiIds = state.visitedPoiIds,
                        skippedPoiIds = effectiveSkippedPoiIds
                    )
                } else {
                    mergedRoute
                }
                val snapshot = SavedRouteSnapshot(
                    request = request,
                    response = finalizedRoute
                )

                state.currentRouteRequest = request
                state.routeResponse = finalizedRoute
                state.startPoint = RoutePoint(currentLocation.lat, currentLocation.lon)
                state.currentTargetPoiId = nextPendingPoiId
                val updatedStatus = if (nextPendingPoiId == null) {
                    RouteSessionStatus.COMPLETED
                } else if (state.routeSessionStatus == RouteSessionStatus.IN_PROGRESS) {
                    RouteSessionStatus.IN_PROGRESS
                } else {
                    state.routeSessionStatus
                }
                state.routeSessionStatus = updatedStatus
                routePlanningRepository.saveSnapshot(snapshot)
                persistRouteSession(
                    status = updatedStatus,
                    skippedIds = effectiveSkippedPoiIds,
                    snapshotOverride = snapshot
                )
            } catch (e: Exception) {
                errorReporter.report(
                    tag = TAG,
                    message = "Failed to reroute active route",
                    throwable = e,
                    metadata = rerouteMetadata(
                        autoTriggered = autoTriggered,
                        routeId = state.routeId,
                        targetPoiId = nextTarget?.poiId
                    )
                )
                state.routeError = e.toUserMessage(messages.routeGenerationFailed)
                state.routeResponse = response
            } finally {
                if (autoTriggered) {
                    state.lastAutoRerouteAtMs = System.currentTimeMillis()
                }
                state.isRerouting = false
            }
        }
    }

    fun refreshActiveRouteApproachLeg(
        previousResponse: RoutePlan,
        currentLocation: RoutePoint,
        nextTarget: RouteStop,
        autoTriggered: Boolean = false
    ) {
        val cityToken = state.currentRouteRequest?.city ?: state.selectedCity?.slug ?: previousResponse.city
        val routeLegRequest = RouteLegQuery(
            city = cityToken,
            startLat = currentLocation.lat,
            startLon = currentLocation.lon,
            endLat = nextTarget.lat,
            endLon = nextTarget.lon,
            endPoiId = nextTarget.poiId,
            endName = nextTarget.name,
            pace = state.currentRouteRequest?.pace ?: previousResponse.pace,
            startDateTime = defaultRouteStartDateTime().toString(),
            transportMode = state.currentRouteRequest?.transportMode ?: previousResponse.transportMode ?: "walk"
        )

        scope.launch {
            state.isRerouting = true
            try {
                val replacementLeg = routePlanningRepository.generateRouteLeg(routeLegRequest)
                val updatedResponse = replaceActiveRouteApproachLeg(
                    previousResponse = previousResponse,
                    nextPoiId = nextTarget.poiId,
                    replacementLeg = replacementLeg
                )
                state.routeResponse = updatedResponse
                val snapshot = currentRouteSnapshot()
                if (snapshot != null) {
                    routePlanningRepository.saveSnapshot(snapshot)
                    persistRouteSession(
                        status = state.routeSessionStatus,
                        skippedIds = state.skippedPoiIds,
                        snapshotOverride = snapshot
                    )
                }
            } catch (error: Exception) {
                errorReporter.report(
                    tag = TAG,
                    message = "Failed to refresh active route approach leg",
                    throwable = error,
                    metadata = rerouteMetadata(
                        autoTriggered = autoTriggered,
                        routeId = state.routeId,
                        targetPoiId = nextTarget.poiId
                    )
                )
                currentRouteSnapshot()?.let { snapshot ->
                    routePlanningRepository.saveSnapshot(snapshot)
                }
            } finally {
                if (autoTriggered) {
                    state.lastAutoRerouteAtMs = System.currentTimeMillis()
                    state.offRouteDetectedAtMs = null
                }
                state.isRerouting = false
            }
        }
    }

    private fun rerouteMetadata(
        autoTriggered: Boolean,
        routeId: String?,
        targetPoiId: Int?
    ): Map<String, String> =
        buildMap {
            put("autoTriggered", autoTriggered.toString())
            routeId?.let { put("routeId", it) }
            targetPoiId?.let { put("targetPoiId", it.toString()) }
        }

    private companion object {
        const val TAG = "PlannerSession"
    }
}

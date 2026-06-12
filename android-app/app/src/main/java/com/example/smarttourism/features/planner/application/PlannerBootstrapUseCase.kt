package com.example.smarttourism.features.planner.application

import com.example.smarttourism.data.model.ActiveRouteSession
import com.example.smarttourism.data.model.RouteBookmark
import com.example.smarttourism.data.model.RouteHistoryEntry
import com.example.smarttourism.data.model.SavedRouteSnapshot
import com.example.smarttourism.features.planner.data.bookmark.RouteBookmarkRepository
import com.example.smarttourism.features.planner.data.route.RoutePlanningRepository
import com.example.smarttourism.features.planner.data.session.RouteSessionRepository
import com.example.smarttourism.features.planner.data.sync.OfflineSyncRepository
import com.example.smarttourism.features.planner.domain.model.RouteSession
import com.example.smarttourism.features.planner.state.RouteSessionStatus
import com.example.smarttourism.features.planner.domain.history.isRestorable
import com.example.smarttourism.features.planner.domain.history.sortRouteHistoryEntries
import javax.inject.Inject

internal data class PlannerBootstrapLocalState(
    val bookmarks: List<RouteBookmark>,
    val history: List<RouteHistoryEntry>,
    val activeSession: ActiveRouteSession?,
    val savedSnapshot: SavedRouteSnapshot?,
    val pendingSyncOperationCount: Int
)

internal class PlannerBootstrapUseCase @Inject constructor(
    private val routeBookmarkRepository: RouteBookmarkRepository,
    private val routePlanningRepository: RoutePlanningRepository,
    private val routeSessionRepository: RouteSessionRepository,
    private val offlineSyncRepository: OfflineSyncRepository
) {
    suspend fun loadLocalState(): PlannerBootstrapLocalState {
        val activeSession = routeSessionRepository.loadActiveSession()
        return PlannerBootstrapLocalState(
            bookmarks = routeBookmarkRepository.loadBookmarks(),
            history = sortRouteHistoryEntries(routeSessionRepository.loadHistoryEntries()),
            activeSession = activeSession,
            savedSnapshot = activeSession?.snapshot ?: routePlanningRepository.loadSnapshot(),
            pendingSyncOperationCount = offlineSyncRepository.getPendingSyncOperationCount()
        )
    }

    suspend fun findRestorableRemoteSession(
        deviceId: String,
        routeId: String?,
        routeSessionStatus: RouteSessionStatus
    ): RouteSession? =
        runCatching {
            if (routeId != null && routeSessionStatus.isRestorable()) {
                routeSessionRepository.getRouteSession(routeId)
            } else {
                routeSessionRepository.getRouteSessions(deviceId)
                    .firstOrNull { session ->
                        RouteSessionStatus.fromRawValue(session.status).isRestorable()
                    }
            }
        }.getOrNull()

    fun scheduleImmediateSync() {
        offlineSyncRepository.scheduleImmediateSync()
    }
}

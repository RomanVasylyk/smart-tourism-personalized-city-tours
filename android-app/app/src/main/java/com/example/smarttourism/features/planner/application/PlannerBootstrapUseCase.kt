package com.example.smarttourism.features.planner.application

import com.example.smarttourism.data.model.ActiveRouteSession
import com.example.smarttourism.data.model.RouteBookmark
import com.example.smarttourism.data.model.RouteHistoryEntry
import com.example.smarttourism.data.model.SavedRouteSnapshot
import com.example.smarttourism.features.planner.data.PlannerRepository
import com.example.smarttourism.features.planner.domain.model.RouteSession
import com.example.smarttourism.features.planner.state.RouteSessionStatus
import com.example.smarttourism.features.planner.viewmodel.isRestorable
import com.example.smarttourism.features.planner.viewmodel.sortRouteHistoryEntries
import javax.inject.Inject

internal data class PlannerBootstrapLocalState(
    val bookmarks: List<RouteBookmark>,
    val history: List<RouteHistoryEntry>,
    val activeSession: ActiveRouteSession?,
    val savedSnapshot: SavedRouteSnapshot?,
    val pendingSyncOperationCount: Int
)

internal class PlannerBootstrapUseCase @Inject constructor(
    private val repository: PlannerRepository
) {
    suspend fun loadLocalState(): PlannerBootstrapLocalState {
        val activeSession = repository.loadActiveSession()
        return PlannerBootstrapLocalState(
            bookmarks = repository.loadRouteBookmarks(),
            history = sortRouteHistoryEntries(repository.loadRouteHistoryEntries()),
            activeSession = activeSession,
            savedSnapshot = activeSession?.snapshot ?: repository.loadSnapshot(),
            pendingSyncOperationCount = repository.getPendingSyncOperationCount()
        )
    }

    suspend fun findRestorableRemoteSession(
        deviceId: String,
        routeId: String?,
        routeSessionStatus: RouteSessionStatus
    ): RouteSession? =
        runCatching {
            if (routeId != null && routeSessionStatus.isRestorable()) {
                repository.getRouteSession(routeId)
            } else {
                repository.getRouteSessions(deviceId)
                    .firstOrNull { session ->
                        RouteSessionStatus.fromRawValue(session.status).isRestorable()
                    }
            }
        }.getOrNull()

    fun scheduleImmediateSync() {
        repository.scheduleImmediateSync()
    }
}

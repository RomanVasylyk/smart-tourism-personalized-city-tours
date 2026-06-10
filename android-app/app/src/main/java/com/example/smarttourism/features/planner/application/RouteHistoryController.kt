package com.example.smarttourism.features.planner.application

import com.example.smarttourism.data.model.RouteHistoryEntry
import com.example.smarttourism.features.planner.data.PlannerRepository
import com.example.smarttourism.features.planner.domain.history.mergeRouteHistoryEntries
import com.example.smarttourism.features.planner.domain.history.sortRouteHistoryEntries
import com.example.smarttourism.features.planner.domain.history.toRouteHistoryEntry
import javax.inject.Inject

internal data class RouteHistoryRefreshResult(
    val history: List<RouteHistoryEntry>,
    val error: String?
)

internal class RouteHistoryController @Inject constructor(
    private val repository: PlannerRepository
) {
    suspend fun refreshRouteHistory(
        deviceId: String,
        forceRefresh: Boolean,
        currentHistory: List<RouteHistoryEntry>,
        currentEntry: RouteHistoryEntry?,
        routeHistoryLoadFailedMessage: String
    ): RouteHistoryRefreshResult {
        val cachedHistory = sortRouteHistoryEntries(repository.loadRouteHistoryEntries())
        if (!forceRefresh && currentHistory.isNotEmpty()) {
            return RouteHistoryRefreshResult(
                history = currentHistory,
                error = null
            )
        }

        if (!repository.isNetworkAvailable()) {
            return RouteHistoryRefreshResult(
                history = currentHistory.ifEmpty { cachedHistory },
                error = null
            )
        }

        return runCatching {
            val remoteEntries = repository.getRouteSessions(deviceId)
                .mapNotNull { session ->
                    runCatching { session.toRouteHistoryEntry() }.getOrNull()
                }
            val mergedEntries = mergeRouteHistoryEntries(
                cachedEntries = cachedHistory,
                remoteEntries = remoteEntries,
                currentEntry = currentEntry
            )
            repository.saveRouteHistoryEntries(mergedEntries)
            RouteHistoryRefreshResult(
                history = mergedEntries,
                error = null
            )
        }.getOrElse { error ->
            RouteHistoryRefreshResult(
                history = cachedHistory,
                error = if (cachedHistory.isEmpty()) {
                    error.toUserMessage(routeHistoryLoadFailedMessage)
                } else {
                    null
                }
            )
        }
    }
}

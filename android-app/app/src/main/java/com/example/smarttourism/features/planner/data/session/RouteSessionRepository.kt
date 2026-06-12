package com.example.smarttourism.features.planner.data.session

import com.example.smarttourism.data.model.ActiveRouteSession
import com.example.smarttourism.data.model.RouteHistoryEntry
import com.example.smarttourism.features.planner.data.local.PlannerLocalDataSource
import com.example.smarttourism.features.planner.data.remote.PlannerRemoteDataSource
import com.example.smarttourism.features.planner.domain.model.RouteSession
import javax.inject.Inject

internal interface RouteSessionRepository {
    fun getOrCreateDeviceId(): String

    suspend fun getRouteSession(routeId: String): RouteSession

    suspend fun getRouteSessions(deviceId: String): List<RouteSession>

    suspend fun saveActiveSession(session: ActiveRouteSession)

    suspend fun loadActiveSession(): ActiveRouteSession?

    suspend fun clearActiveSession()

    suspend fun saveHistoryEntry(entry: RouteHistoryEntry)

    suspend fun saveHistoryEntries(entries: List<RouteHistoryEntry>)

    suspend fun loadHistoryEntries(): List<RouteHistoryEntry>
}

internal class DefaultRouteSessionRepository @Inject constructor(
    private val remoteDataSource: PlannerRemoteDataSource,
    private val localDataSource: PlannerLocalDataSource
) : RouteSessionRepository {
    override fun getOrCreateDeviceId(): String =
        localDataSource.getOrCreateDeviceId()

    override suspend fun getRouteSession(routeId: String): RouteSession =
        remoteDataSource.getRouteSession(routeId)

    override suspend fun getRouteSessions(deviceId: String): List<RouteSession> =
        remoteDataSource.getRouteSessions(deviceId)

    override suspend fun saveActiveSession(session: ActiveRouteSession) {
        localDataSource.saveActiveSession(session)
    }

    override suspend fun loadActiveSession(): ActiveRouteSession? =
        localDataSource.loadActiveSession()

    override suspend fun clearActiveSession() {
        localDataSource.clearActiveSession()
    }

    override suspend fun saveHistoryEntry(entry: RouteHistoryEntry) {
        localDataSource.saveRouteHistoryEntry(entry)
    }

    override suspend fun saveHistoryEntries(entries: List<RouteHistoryEntry>) {
        localDataSource.saveRouteHistoryEntries(entries)
    }

    override suspend fun loadHistoryEntries(): List<RouteHistoryEntry> =
        localDataSource.loadRouteHistoryEntries()
}

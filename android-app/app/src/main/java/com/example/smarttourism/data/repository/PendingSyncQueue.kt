package com.example.smarttourism.data.repository

import com.example.smarttourism.data.local.LocalSyncStatus
import com.example.smarttourism.data.local.OfflineCacheDao
import com.example.smarttourism.data.local.PendingFeedbackEntity
import com.example.smarttourism.data.local.PendingPoiVisitSyncEntity
import com.example.smarttourism.data.local.PendingRouteSessionSyncEntity
import com.example.smarttourism.data.model.CacheEnvelopeType
import com.example.smarttourism.data.remote.api.PoiApi
import com.example.smarttourism.data.remote.dto.RouteFeedbackRequest
import com.example.smarttourism.data.remote.dto.RouteSessionCreateRequest
import com.example.smarttourism.data.remote.dto.RouteSessionPoiVisitRequest

data class OfflineSyncSummary(
    val syncedRouteSessions: Int,
    val failedRouteSessions: Int,
    val syncedPoiVisits: Int,
    val failedPoiVisits: Int,
    val syncedFeedback: Int,
    val failedFeedback: Int
) {
    val hasFailures: Boolean
        get() = failedRouteSessions > 0 || failedPoiVisits > 0 || failedFeedback > 0
}

internal class PendingSyncQueue(
    private val dao: OfflineCacheDao,
    private val serializer: CacheEnvelopeSerializer
) {
    suspend fun enqueuePendingRouteSession(request: RouteSessionCreateRequest) {
        val now = System.currentTimeMillis()
        dao.upsertPendingRouteSessionSync(
            PendingRouteSessionSyncEntity(
                sessionId = request.id,
                requestJson = serializer.toVersionedJson(CacheEnvelopeType.ROUTE_SESSION_CREATE_REQUEST, request),
                syncStatus = LocalSyncStatus.PENDING,
                lastSyncAttemptAtEpochMs = null,
                retryCount = 0,
                createdAtEpochMs = now,
                updatedAtEpochMs = now
            )
        )
    }

    suspend fun enqueuePendingPoiVisit(
        sessionId: String,
        poiId: Int,
        request: RouteSessionPoiVisitRequest
    ) {
        val now = System.currentTimeMillis()
        dao.upsertPendingPoiVisitSync(
            PendingPoiVisitSyncEntity(
                requestKey = "$sessionId:$poiId",
                sessionId = sessionId,
                poiId = poiId,
                requestJson = serializer.toVersionedJson(CacheEnvelopeType.ROUTE_SESSION_POI_VISIT_REQUEST, request),
                syncStatus = LocalSyncStatus.PENDING,
                lastSyncAttemptAtEpochMs = null,
                retryCount = 0,
                createdAtEpochMs = now,
                updatedAtEpochMs = now
            )
        )
    }

    suspend fun enqueuePendingFeedback(
        sessionId: String,
        request: RouteFeedbackRequest
    ) {
        val now = System.currentTimeMillis()
        dao.upsertPendingFeedback(
            PendingFeedbackEntity(
                sessionId = sessionId,
                feedbackJson = serializer.toVersionedJson(CacheEnvelopeType.ROUTE_FEEDBACK_REQUEST, request),
                syncStatus = LocalSyncStatus.PENDING,
                lastSyncAttemptAtEpochMs = null,
                retryCount = 0,
                createdAtEpochMs = now,
                updatedAtEpochMs = now
            )
        )
    }

    suspend fun deletePendingFeedback(sessionId: String) {
        dao.deletePendingFeedback(sessionId)
    }

    suspend fun getPendingFeedbackCount(): Int =
        dao.getPendingFeedbackCount()

    suspend fun getPendingSyncOperationCount(): Int =
        dao.getPendingRouteSessionSyncCount() +
            dao.getPendingPoiVisitSyncCount() +
            dao.getPendingFeedbackCount()

    suspend fun syncPendingOperations(api: PoiApi): OfflineSyncSummary {
        val routeSessionResult = syncPendingRouteSessions(api)
        val poiVisitResult = syncPendingPoiVisits(api)
        val feedbackResult = syncPendingFeedback(api)
        return OfflineSyncSummary(
            syncedRouteSessions = routeSessionResult.syncedCount,
            failedRouteSessions = routeSessionResult.failedCount,
            syncedPoiVisits = poiVisitResult.syncedCount,
            failedPoiVisits = poiVisitResult.failedCount,
            syncedFeedback = feedbackResult.syncedCount,
            failedFeedback = feedbackResult.failedCount
        )
    }

    private suspend fun syncPendingRouteSessions(api: PoiApi): SyncBatchResult {
        var syncedCount = 0
        var failedCount = 0

        dao.getPendingRouteSessionSyncs().forEach { pendingRouteSession ->
            val request = serializer.fromVersionedJsonOrNull(
                rawJson = pendingRouteSession.requestJson,
                expectedType = CacheEnvelopeType.ROUTE_SESSION_CREATE_REQUEST,
                clazz = RouteSessionCreateRequest::class.java
            )
            if (request == null) {
                dao.deletePendingRouteSessionSync(pendingRouteSession.sessionId)
                return@forEach
            }

            val attemptTime = System.currentTimeMillis()
            runCatching {
                api.createRouteSession(request)
            }.onSuccess {
                dao.deletePendingRouteSessionSync(pendingRouteSession.sessionId)
                syncedCount += 1
            }.onFailure {
                dao.upsertPendingRouteSessionSync(
                    pendingRouteSession.copy(
                        syncStatus = LocalSyncStatus.FAILED,
                        lastSyncAttemptAtEpochMs = attemptTime,
                        retryCount = pendingRouteSession.retryCount + 1,
                        updatedAtEpochMs = attemptTime
                    )
                )
                failedCount += 1
            }
        }

        return SyncBatchResult(syncedCount = syncedCount, failedCount = failedCount)
    }

    private suspend fun syncPendingPoiVisits(api: PoiApi): SyncBatchResult {
        var syncedCount = 0
        var failedCount = 0

        dao.getPendingPoiVisitSyncs().forEach { pendingPoiVisit ->
            val request = serializer.fromVersionedJsonOrNull(
                rawJson = pendingPoiVisit.requestJson,
                expectedType = CacheEnvelopeType.ROUTE_SESSION_POI_VISIT_REQUEST,
                clazz = RouteSessionPoiVisitRequest::class.java
            )
            if (request == null) {
                dao.deletePendingPoiVisitSync(pendingPoiVisit.requestKey)
                return@forEach
            }

            val attemptTime = System.currentTimeMillis()
            runCatching {
                api.markRouteSessionPoiVisited(
                    sessionId = pendingPoiVisit.sessionId,
                    poiId = pendingPoiVisit.poiId,
                    request = request
                )
            }.onSuccess {
                dao.deletePendingPoiVisitSync(pendingPoiVisit.requestKey)
                syncedCount += 1
            }.onFailure {
                dao.upsertPendingPoiVisitSync(
                    pendingPoiVisit.copy(
                        syncStatus = LocalSyncStatus.FAILED,
                        lastSyncAttemptAtEpochMs = attemptTime,
                        retryCount = pendingPoiVisit.retryCount + 1,
                        updatedAtEpochMs = attemptTime
                    )
                )
                failedCount += 1
            }
        }

        return SyncBatchResult(syncedCount = syncedCount, failedCount = failedCount)
    }

    private suspend fun syncPendingFeedback(api: PoiApi): SyncBatchResult {
        var syncedCount = 0
        var failedCount = 0

        dao.getPendingFeedback().forEach { pendingFeedback ->
            val request = serializer.fromVersionedJsonOrNull(
                rawJson = pendingFeedback.feedbackJson,
                expectedType = CacheEnvelopeType.ROUTE_FEEDBACK_REQUEST,
                clazz = RouteFeedbackRequest::class.java
            )
            if (request == null) {
                dao.deletePendingFeedback(pendingFeedback.sessionId)
                return@forEach
            }

            val attemptTime = System.currentTimeMillis()
            runCatching {
                api.saveRouteFeedback(
                    sessionId = pendingFeedback.sessionId,
                    request = request
                )
            }.onSuccess {
                dao.deletePendingFeedback(pendingFeedback.sessionId)
                syncedCount += 1
            }.onFailure {
                dao.upsertPendingFeedback(
                    pendingFeedback.copy(
                        syncStatus = LocalSyncStatus.FAILED,
                        lastSyncAttemptAtEpochMs = attemptTime,
                        retryCount = pendingFeedback.retryCount + 1,
                        updatedAtEpochMs = attemptTime
                    )
                )
                failedCount += 1
            }
        }

        return SyncBatchResult(syncedCount = syncedCount, failedCount = failedCount)
    }

    private data class SyncBatchResult(
        val syncedCount: Int,
        val failedCount: Int
    )
}

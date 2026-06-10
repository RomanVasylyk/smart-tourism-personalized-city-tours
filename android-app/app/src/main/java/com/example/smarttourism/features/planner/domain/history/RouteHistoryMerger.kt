package com.example.smarttourism.features.planner.domain.history

import com.example.smarttourism.data.model.RouteFeedback
import com.example.smarttourism.data.model.RouteHistoryEntry
import com.example.smarttourism.data.model.SavedRouteSnapshot
import com.example.smarttourism.features.planner.domain.model.PlannerPreferences
import com.example.smarttourism.features.planner.domain.model.RouteSession
import com.example.smarttourism.features.planner.state.RouteSessionStatus
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId

internal fun RouteSession.toSavedRouteSnapshot(): SavedRouteSnapshot? {
    val response = routeSnapshot ?: return null
    val skippedPoiIds = pois
        .orEmpty()
        .filter { poi -> poi.skipped }
        .map { poi -> poi.poiId }
        .distinct()
    val request = PlannerPreferences(
        city = cityName ?: response.city,
        startLat = startLat,
        startLon = startLon,
        availableMinutes = availableMinutes,
        interests = response.interests,
        pace = pace,
        returnToStart = returnToStart,
        startDateTime = response.startDateTime,
        respectOpeningHours = openingHoursEnabled,
        excludedPoiIds = skippedPoiIds,
        transportMode = response.transportMode ?: "walk"
    )

    return SavedRouteSnapshot(
        request = request,
        response = response
    )
}

internal fun RouteSession.toRouteFeedback(): RouteFeedback? {
    val latestFeedback = feedback.orEmpty().firstOrNull() ?: return null
    return RouteFeedback(
        rating = latestFeedback.rating,
        route_was_comfortable = latestFeedback.wasConvenient ?: false,
        too_much_walking = latestFeedback.tooMuchWalking ?: false,
        pois_were_interesting = latestFeedback.poisWereInteresting ?: false
    )
}

internal fun RouteSession.toRouteHistoryEntry(): RouteHistoryEntry? {
    val snapshot = toSavedRouteSnapshot() ?: return null
    val visitedPoiIds = pois
        .orEmpty()
        .filter { poi -> poi.visited && !poi.skipped }
        .map { poi -> poi.poiId }
        .distinct()
    val skippedPoiIds = pois
        .orEmpty()
        .filter { poi -> poi.skipped }
        .map { poi -> poi.poiId }
        .distinct()

    return RouteHistoryEntry(
        routeId = id,
        cityName = cityName ?: snapshot.response.city,
        status = status,
        startedAt = startedAt,
        finishedAt = finishedAt,
        availableMinutes = availableMinutes,
        usedMinutes = usedMinutes ?: snapshot.response.usedMinutes,
        totalWalkMinutes = totalWalkMinutes ?: snapshot.response.totalWalkMinutes,
        totalVisitMinutes = totalVisitMinutes ?: snapshot.response.totalVisitMinutes,
        snapshot = snapshot,
        visitedPoiIds = visitedPoiIds,
        skippedPoiIds = skippedPoiIds,
        feedback = toRouteFeedback(),
        updatedAtEpochMs = routeHistoryTimestamp(finishedAt ?: startedAt)
    )
}

internal fun buildRouteHistoryEntry(
    routeId: String,
    cityName: String,
    status: RouteSessionStatus,
    startedAt: String,
    finishedAt: String?,
    snapshot: SavedRouteSnapshot,
    visitedPoiIds: List<Int>,
    skippedPoiIds: List<Int>,
    feedback: RouteFeedback?,
    updatedAtEpochMs: Long = System.currentTimeMillis()
): RouteHistoryEntry =
    RouteHistoryEntry(
        routeId = routeId,
        cityName = cityName,
        status = status.rawValue,
        startedAt = startedAt,
        finishedAt = finishedAt,
        availableMinutes = snapshot.request.availableMinutes,
        usedMinutes = snapshot.response.usedMinutes,
        totalWalkMinutes = snapshot.response.totalWalkMinutes,
        totalVisitMinutes = snapshot.response.totalVisitMinutes,
        snapshot = snapshot,
        visitedPoiIds = visitedPoiIds.distinct(),
        skippedPoiIds = skippedPoiIds.distinct(),
        feedback = feedback,
        updatedAtEpochMs = updatedAtEpochMs
    )

internal fun defaultRouteBookmarkTitle(
    snapshot: SavedRouteSnapshot,
    cityName: String? = null
): String {
    val displayCity = (cityName ?: snapshot.response.city).ifBlank { snapshot.response.city }
    val leadingStops = snapshot.response.route.take(2).map { item -> item.name }
    val extraCount = (snapshot.response.route.size - leadingStops.size).coerceAtLeast(0)
    val stopLabel = buildString {
        append(leadingStops.joinToString(" • "))
        if (extraCount > 0) {
            append(" +")
            append(extraCount)
        }
    }.trim()

    return if (stopLabel.isBlank()) {
        displayCity
    } else {
        "$displayCity • $stopLabel"
    }
}

internal fun RouteSessionStatus.isRestorable(): Boolean =
    this == RouteSessionStatus.NOT_STARTED ||
        this == RouteSessionStatus.IN_PROGRESS ||
        this == RouteSessionStatus.PAUSED

internal fun RouteSessionStatus.isTerminal(): Boolean =
    this == RouteSessionStatus.COMPLETED || this == RouteSessionStatus.CANCELLED

internal fun sortRouteHistoryEntries(entries: List<RouteHistoryEntry>): List<RouteHistoryEntry> =
    entries.sortedByDescending { entry -> entry.updatedAtEpochMs }

internal fun mergeRouteHistoryEntries(
    cachedEntries: List<RouteHistoryEntry>,
    remoteEntries: List<RouteHistoryEntry>,
    currentEntry: RouteHistoryEntry?
): List<RouteHistoryEntry> {
    val merged = linkedMapOf<String, RouteHistoryEntry>()
    cachedEntries.forEach { entry ->
        merged[entry.routeId] = entry
    }
    remoteEntries.forEach { entry ->
        val existing = merged[entry.routeId]
        merged[entry.routeId] = if (existing == null) {
            entry
        } else {
            mergeRemoteHistoryEntry(existing, entry)
        }
    }
    if (currentEntry != null) {
        val existing = merged[currentEntry.routeId]
        merged[currentEntry.routeId] = if (existing == null) {
            currentEntry
        } else {
            choosePreferredHistoryEntry(existing, currentEntry)
        }
    }
    return sortRouteHistoryEntries(merged.values.toList())
}

internal fun mergeRemoteHistoryEntry(
    localEntry: RouteHistoryEntry,
    remoteEntry: RouteHistoryEntry
): RouteHistoryEntry =
    remoteEntry.copy(
        feedback = remoteEntry.feedback ?: localEntry.feedback,
        visitedPoiIds = if (
            remoteEntry.visitedPoiIds.isNotEmpty() ||
                remoteEntry.skippedPoiIds.isNotEmpty()
        ) {
            remoteEntry.visitedPoiIds
        } else {
            localEntry.visitedPoiIds
        },
        skippedPoiIds = if (
            remoteEntry.visitedPoiIds.isNotEmpty() ||
                remoteEntry.skippedPoiIds.isNotEmpty()
        ) {
            remoteEntry.skippedPoiIds
        } else {
            localEntry.skippedPoiIds
        }
    )

internal fun upsertRouteHistoryEntry(
    currentEntries: List<RouteHistoryEntry>,
    entry: RouteHistoryEntry
): List<RouteHistoryEntry> =
    sortRouteHistoryEntries(
        buildList {
            val existing = currentEntries.firstOrNull { current -> current.routeId == entry.routeId }
            add(if (existing == null) entry else choosePreferredHistoryEntry(existing, entry))
            addAll(currentEntries.filterNot { existingEntry -> existingEntry.routeId == entry.routeId })
        }
    )

internal fun choosePreferredHistoryEntry(
    existing: RouteHistoryEntry,
    candidate: RouteHistoryEntry
): RouteHistoryEntry {
    val existingStatus = RouteSessionStatus.fromRawValue(existing.status)
    val candidateStatus = RouteSessionStatus.fromRawValue(candidate.status)

    if (existingStatus.isTerminal() && !candidateStatus.isTerminal()) {
        return existing
    }
    if (!existingStatus.isTerminal() && candidateStatus.isTerminal()) {
        return candidate
    }
    if (existing.feedback != null && candidate.feedback == null) {
        return existing
    }
    if (existing.feedback == null && candidate.feedback != null) {
        return candidate
    }

    return if (candidate.updatedAtEpochMs >= existing.updatedAtEpochMs) candidate else existing
}

internal fun routeHistoryTimestamp(rawValue: String?): Long =
    runCatching {
        rawValue?.toInstantEpochMillis()
    }.getOrNull() ?: System.currentTimeMillis()

private fun String.toInstantEpochMillis(): Long? =
    runCatching {
        OffsetDateTime.parse(this).toInstant().toEpochMilli()
    }.recoverCatching {
        LocalDateTime.parse(this)
            .atZone(ZoneId.systemDefault())
            .toInstant()
            .toEpochMilli()
    }.getOrNull()

package com.example.smarttourism.features.planner.domain.history

import com.example.smarttourism.data.model.RouteFeedback
import com.example.smarttourism.features.planner.historyEntry
import com.example.smarttourism.features.planner.state.RouteSessionStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class RouteHistoryMergerTest {
    @Test
    fun mergeRemoteHistoryKeepsLocalProgressWhenRemoteHasNoPoiProgress() {
        val localFeedback = RouteFeedback(
            rating = 5,
            route_was_comfortable = true,
            too_much_walking = false,
            pois_were_interesting = true
        )
        val local = historyEntry(
            routeId = "route-1",
            status = RouteSessionStatus.IN_PROGRESS,
            visitedPoiIds = listOf(1, 2),
            skippedPoiIds = listOf(3),
            feedback = localFeedback,
            updatedAtEpochMs = 100
        )
        val remote = historyEntry(
            routeId = "route-1",
            status = RouteSessionStatus.IN_PROGRESS,
            visitedPoiIds = emptyList(),
            skippedPoiIds = emptyList(),
            feedback = null,
            updatedAtEpochMs = 200
        )

        val merged = mergeRemoteHistoryEntry(local, remote)

        assertEquals(remote.updatedAtEpochMs, merged.updatedAtEpochMs)
        assertEquals(listOf(1, 2), merged.visitedPoiIds)
        assertEquals(listOf(3), merged.skippedPoiIds)
        assertEquals(localFeedback, merged.feedback)
    }

    @Test
    fun choosePreferredHistoryEntryKeepsTerminalEntryOverNewerActiveEntry() {
        val completed = historyEntry(
            routeId = "route-1",
            status = RouteSessionStatus.COMPLETED,
            updatedAtEpochMs = 100
        )
        val newerActive = historyEntry(
            routeId = "route-1",
            status = RouteSessionStatus.IN_PROGRESS,
            updatedAtEpochMs = 500
        )

        assertSame(completed, choosePreferredHistoryEntry(completed, newerActive))
    }

    @Test
    fun mergeRouteHistoryEntriesIncludesCurrentEntryAndSortsByUpdatedTime() {
        val cached = historyEntry(
            routeId = "cached",
            status = RouteSessionStatus.COMPLETED,
            updatedAtEpochMs = 200
        )
        val remote = historyEntry(
            routeId = "remote",
            status = RouteSessionStatus.COMPLETED,
            updatedAtEpochMs = 300
        )
        val current = historyEntry(
            routeId = "current",
            status = RouteSessionStatus.IN_PROGRESS,
            updatedAtEpochMs = 400
        )

        val merged = mergeRouteHistoryEntries(
            cachedEntries = listOf(cached),
            remoteEntries = listOf(remote),
            currentEntry = current
        )

        assertEquals(listOf("current", "remote", "cached"), merged.map { entry -> entry.routeId })
    }
}

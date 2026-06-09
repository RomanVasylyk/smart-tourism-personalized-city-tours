package com.example.smarttourism.features.planner.state

import com.example.smarttourism.data.model.SavedRouteSnapshot
import com.example.smarttourism.features.planner.domain.model.City
import com.example.smarttourism.features.planner.domain.model.PlannerPreferences
import com.example.smarttourism.features.planner.domain.model.RoutePoint
import java.time.LocalDateTime
import java.time.temporal.ChronoUnit

internal object PlannerStateReducer {
    fun updateStartPoint(
        state: RoutePlannerUiState,
        point: RoutePoint
    ): RoutePlannerUiState =
        state.copy(
            startPoint = point,
            hasNoGeneratedStops = false
        ).invalidateRoutePreviewIfAllowed()

    fun updateAvailableMinutes(
        state: RoutePlannerUiState,
        value: Int
    ): RoutePlannerUiState =
        state.copy(
            availableMinutes = clampAvailableMinutes(value, state.selectedCity),
            hasNoGeneratedStops = false
        ).invalidateRoutePreviewIfAllowed()

    fun toggleInterest(
        state: RoutePlannerUiState,
        interest: String,
        checked: Boolean
    ): RoutePlannerUiState {
        val updatedInterests = if (checked) {
            (state.selectedInterests + interest).distinct()
        } else {
            state.selectedInterests.filterNot { selected -> selected == interest }
        }
        return state.copy(
            selectedInterests = updatedInterests,
            hasNoGeneratedStops = false
        ).invalidateRoutePreviewIfAllowed()
    }

    fun updatePace(
        state: RoutePlannerUiState,
        value: String
    ): RoutePlannerUiState =
        state.copy(
            pace = value,
            hasNoGeneratedStops = false
        ).invalidateRoutePreviewIfAllowed()

    fun updateReturnToStart(
        state: RoutePlannerUiState,
        value: Boolean
    ): RoutePlannerUiState =
        state.copy(
            returnToStart = value,
            hasNoGeneratedStops = false
        ).invalidateRoutePreviewIfAllowed()

    fun updateRespectOpeningHours(
        state: RoutePlannerUiState,
        value: Boolean
    ): RoutePlannerUiState =
        state.copy(
            respectOpeningHours = value,
            hasNoGeneratedStops = false
        ).invalidateRoutePreviewIfAllowed()

    fun updateAllowPublicTransport(
        state: RoutePlannerUiState,
        value: Boolean
    ): RoutePlannerUiState =
        state.copy(
            allowPublicTransport = value,
            hasNoGeneratedStops = false
        ).invalidateRoutePreviewIfAllowed()

    fun useCurrentTime(
        state: RoutePlannerUiState,
        now: LocalDateTime
    ): RoutePlannerUiState =
        state.copy(
            startDateTime = now.truncatedTo(ChronoUnit.MINUTES),
            hasNoGeneratedStops = false
        ).invalidateRoutePreviewIfAllowed()

    fun updateStartDateTime(
        state: RoutePlannerUiState,
        value: LocalDateTime
    ): RoutePlannerUiState =
        state.copy(
            startDateTime = value.truncatedTo(ChronoUnit.MINUTES),
            hasNoGeneratedStops = false
        ).invalidateRoutePreviewIfAllowed()

    fun toggleRequiredPoi(
        state: RoutePlannerUiState,
        poiId: Int
    ): RoutePlannerUiState {
        if (state.routeSessionStatus == RouteSessionStatus.IN_PROGRESS ||
            state.routeSessionStatus == RouteSessionStatus.PAUSED
        ) {
            return state
        }

        val updatedIds = if (poiId in state.requiredPoiIds) {
            state.requiredPoiIds.filterNot { selectedPoiId -> selectedPoiId == poiId }
        } else {
            (state.requiredPoiIds + poiId).distinct()
        }

        return state.copy(
            requiredPoiIds = updatedIds,
            currentRouteRequest = state.currentRouteRequest?.copy(preferredPoiIds = updatedIds),
            hasNoGeneratedStops = false
        ).invalidateRoutePreviewIfAllowed()
    }

    fun removeRequiredPoi(
        state: RoutePlannerUiState,
        poiId: Int
    ): RoutePlannerUiState {
        if (poiId !in state.requiredPoiIds) {
            return state
        }

        val updatedIds = state.requiredPoiIds.filterNot { selectedPoiId -> selectedPoiId == poiId }
        return state.copy(
            requiredPoiIds = updatedIds,
            currentRouteRequest = state.currentRouteRequest?.copy(preferredPoiIds = updatedIds),
            hasNoGeneratedStops = false
        ).invalidateRoutePreviewIfAllowed()
    }

    fun moveRequiredPoi(
        state: RoutePlannerUiState,
        poiId: Int,
        direction: Int
    ): RoutePlannerUiState {
        if (state.routeSessionStatus == RouteSessionStatus.IN_PROGRESS ||
            state.routeSessionStatus == RouteSessionStatus.PAUSED
        ) {
            return state
        }

        val currentIndex = state.requiredPoiIds.indexOf(poiId)
        if (currentIndex == -1) {
            return state
        }

        val targetIndex = (currentIndex + direction).coerceIn(0, state.requiredPoiIds.lastIndex)
        if (targetIndex == currentIndex) {
            return state
        }

        val reorderedIds = state.requiredPoiIds.toMutableList()
        val movedPoiId = reorderedIds.removeAt(currentIndex)
        reorderedIds.add(targetIndex, movedPoiId)

        return state.copy(
            requiredPoiIds = reorderedIds,
            currentRouteRequest = state.currentRouteRequest?.copy(preferredPoiIds = reorderedIds),
            hasNoGeneratedStops = false
        ).invalidateRoutePreviewIfAllowed()
    }

    fun clearRequiredPois(state: RoutePlannerUiState): RoutePlannerUiState {
        if (state.requiredPoiIds.isEmpty()) {
            return state
        }

        return state.copy(
            requiredPoiIds = emptyList(),
            currentRouteRequest = state.currentRouteRequest?.copy(preferredPoiIds = emptyList()),
            hasNoGeneratedStops = false
        ).invalidateRoutePreviewIfAllowed()
    }

    fun clearRouteMessages(state: RoutePlannerUiState): RoutePlannerUiState =
        state.copy(
            routeError = null,
            hasNoGeneratedStops = false
        )

    fun restoreSnapshot(
        state: RoutePlannerUiState,
        snapshot: SavedRouteSnapshot,
        parsedStartDateTime: LocalDateTime
    ): RoutePlannerUiState =
        state.copy(
            hasPendingRouteChanges = false,
            hasNoGeneratedStops = false,
            currentRouteRequest = PlannerPreferences(
                city = snapshot.request.city,
                startLat = snapshot.request.startLat,
                startLon = snapshot.request.startLon,
                availableMinutes = snapshot.request.availableMinutes,
                interests = snapshot.request.interests,
                pace = snapshot.request.pace,
                returnToStart = snapshot.request.returnToStart,
                startDateTime = snapshot.request.startDateTime,
                respectOpeningHours = snapshot.request.respectOpeningHours,
                excludedPoiIds = snapshot.request.excludedPoiIds.orEmpty(),
                preferredPoiIds = snapshot.request.preferredPoiIds.orEmpty(),
                transportMode = snapshot.request.transportMode ?: "walk"
            ),
            routeResponse = snapshot.response,
            startPoint = RoutePoint(snapshot.request.startLat, snapshot.request.startLon),
            availableMinutes = clampAvailableMinutes(snapshot.request.availableMinutes, city = null),
            pace = snapshot.request.pace,
            returnToStart = snapshot.request.returnToStart,
            respectOpeningHours = snapshot.request.respectOpeningHours,
            allowPublicTransport = snapshot.request.transportMode == "walk_or_mhd",
            startDateTime = parsedStartDateTime,
            selectedInterests = snapshot.request.interests,
            requiredPoiIds = snapshot.request.preferredPoiIds.orEmpty().distinct()
        )

    private fun RoutePlannerUiState.invalidateRoutePreviewIfAllowed(): RoutePlannerUiState {
        if (routeSessionStatus == RouteSessionStatus.IN_PROGRESS ||
            routeSessionStatus == RouteSessionStatus.PAUSED
        ) {
            return this
        }

        return copy(
            hasPendingRouteChanges = if (routeResponse != null) true else hasPendingRouteChanges,
            routeError = null,
            hasNoGeneratedStops = false
        )
    }
}

internal fun maxAvailableMinutesFor(city: City?): Int =
    city?.routingLimits?.maxAvailableMinutes
        ?.coerceIn(MinimumAvailableMinutes, MaximumAvailableMinutes)
        ?: MaximumAvailableMinutes

internal fun clampAvailableMinutes(value: Int, city: City?): Int {
    val maxAllowedMinutes = maxAvailableMinutesFor(city)
    val clampedValue = value.coerceIn(MinimumAvailableMinutes, maxAllowedMinutes)
    val relativeMinutes = clampedValue - MinimumAvailableMinutes
    val remainder = relativeMinutes % AvailableMinutesStepMinutes

    if (remainder == 0) {
        return clampedValue
    }

    val roundedValue = if (remainder >= AvailableMinutesStepMinutes / 2) {
        clampedValue + (AvailableMinutesStepMinutes - remainder)
    } else {
        clampedValue - remainder
    }

    return roundedValue.coerceIn(MinimumAvailableMinutes, maxAllowedMinutes)
}

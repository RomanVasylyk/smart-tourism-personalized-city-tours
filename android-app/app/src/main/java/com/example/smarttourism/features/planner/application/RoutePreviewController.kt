package com.example.smarttourism.features.planner.application

import com.example.smarttourism.data.model.SavedRouteSnapshot
import com.example.smarttourism.features.planner.data.PlannerRepository
import com.example.smarttourism.features.planner.state.RoutePlannerUiState
import com.example.smarttourism.features.planner.state.RouteSessionStatus
import com.example.smarttourism.features.planner.domain.route.buildPreviewReplacementCandidates
import javax.inject.Inject

internal data class RoutePreviewMutationResult(
    val state: RoutePlannerUiState,
    val error: String? = null
)

internal class RoutePreviewController @Inject constructor(
    private val repository: PlannerRepository,
    private val routePreviewMutationUseCase: RoutePreviewMutationUseCase
) {
    suspend fun removeStop(
        state: RoutePlannerUiState,
        poiId: Int,
        offlineRouteGenerationMessage: String,
        routeGenerationFailedMessage: String
    ): RoutePreviewMutationResult {
        if (state.routeSessionStatus != RouteSessionStatus.NOT_STARTED) {
            return RoutePreviewMutationResult(state)
        }
        val response = state.routeResponse ?: return RoutePreviewMutationResult(state)
        val request = state.currentRouteRequest ?: return RoutePreviewMutationResult(state)
        if (!repository.isNetworkAvailable()) {
            return RoutePreviewMutationResult(state, offlineRouteGenerationMessage)
        }

        val updatedRequiredPoiIds = state.requiredPoiIds.filterNot { requiredPoiId -> requiredPoiId == poiId }
        val updatedRequest = request.copy(
            excludedPoiIds = (request.excludedPoiIds.orEmpty() + poiId).distinct(),
            preferredPoiIds = updatedRequiredPoiIds
        )

        return runCatching {
            val updatedResponse = routePreviewMutationUseCase.removeStop(
                previousResponse = response,
                poiId = poiId,
                context = state.routePreviewMutationContext()
            )
            val updatedState = if (updatedResponse.route.isEmpty()) {
                state.copy(
                    currentRouteRequest = updatedRequest,
                    requiredPoiIds = updatedRequiredPoiIds,
                    routeResponse = null,
                    hasPendingRouteChanges = false,
                    hasNoGeneratedStops = true,
                    routeError = null
                )
            } else {
                repository.saveSnapshot(
                    SavedRouteSnapshot(
                        request = updatedRequest,
                        response = updatedResponse
                    )
                )
                state.copy(
                    currentRouteRequest = updatedRequest,
                    requiredPoiIds = updatedRequiredPoiIds,
                    routeResponse = updatedResponse,
                    hasPendingRouteChanges = false,
                    hasNoGeneratedStops = false,
                    routeError = null
                )
            }
            RoutePreviewMutationResult(updatedState)
        }.getOrElse { error ->
            RoutePreviewMutationResult(
                state = state,
                error = error.toUserMessage(routeGenerationFailedMessage)
            )
        }
    }

    suspend fun moveStop(
        state: RoutePlannerUiState,
        poiId: Int,
        direction: Int,
        offlineRouteGenerationMessage: String,
        routeGenerationFailedMessage: String
    ): RoutePreviewMutationResult {
        if (state.routeSessionStatus != RouteSessionStatus.NOT_STARTED) {
            return RoutePreviewMutationResult(state)
        }
        val response = state.routeResponse ?: return RoutePreviewMutationResult(state)
        val request = state.currentRouteRequest ?: return RoutePreviewMutationResult(state)
        val originalItems = response.route.sortedBy { item -> item.order }
        val currentIndex = originalItems.indexOfFirst { item -> item.poiId == poiId }
        if (currentIndex == -1) {
            return RoutePreviewMutationResult(state)
        }
        val targetIndex = (currentIndex + direction).coerceIn(0, originalItems.lastIndex)
        if (targetIndex == currentIndex) {
            return RoutePreviewMutationResult(state)
        }
        if (!repository.isNetworkAvailable()) {
            return RoutePreviewMutationResult(state, offlineRouteGenerationMessage)
        }

        return runCatching {
            val updatedResponse = routePreviewMutationUseCase.moveStop(
                previousResponse = response,
                poiId = poiId,
                direction = direction,
                context = state.routePreviewMutationContext()
            )
            val updatedRequest = request.copy(preferredPoiIds = state.requiredPoiIds)
            repository.saveSnapshot(
                SavedRouteSnapshot(
                    request = updatedRequest,
                    response = updatedResponse
                )
            )
            RoutePreviewMutationResult(
                state = state.copy(
                    currentRouteRequest = updatedRequest,
                    routeResponse = updatedResponse,
                    hasPendingRouteChanges = false,
                    hasNoGeneratedStops = false,
                    routeError = null
                )
            )
        }.getOrElse { error ->
            RoutePreviewMutationResult(
                state = state,
                error = error.toUserMessage(routeGenerationFailedMessage)
            )
        }
    }

    suspend fun replaceStop(
        state: RoutePlannerUiState,
        poiId: Int,
        preferredPoiId: Int?,
        offlineRouteGenerationMessage: String,
        routeGenerationFailedMessage: String
    ): RoutePreviewMutationResult {
        if (state.routeSessionStatus != RouteSessionStatus.NOT_STARTED) {
            return RoutePreviewMutationResult(state)
        }
        val response = state.routeResponse ?: return RoutePreviewMutationResult(state)
        val request = state.currentRouteRequest ?: return RoutePreviewMutationResult(state)
        if (!repository.isNetworkAvailable()) {
            return RoutePreviewMutationResult(state, offlineRouteGenerationMessage)
        }

        val replacementPoi = if (preferredPoiId != null) {
            state.pois.firstOrNull { poi -> poi.id == preferredPoiId }
        } else {
            buildPreviewReplacementCandidates(
                targetPoiId = poiId,
                routeItems = state.routeItems,
                pois = state.pois,
                selectedInterests = state.selectedInterests,
                excludePoiIds = request.excludedPoiIds.orEmpty()
            ).firstOrNull()
        }
        if (replacementPoi == null) {
            return RoutePreviewMutationResult(state, routeGenerationFailedMessage)
        }

        val updatedRequiredPoiIds = state.requiredPoiIds.filterNot { requiredPoiId -> requiredPoiId == poiId }
        val updatedRequest = request.copy(
            excludedPoiIds = (request.excludedPoiIds.orEmpty() + poiId).distinct(),
            preferredPoiIds = updatedRequiredPoiIds
        )

        return runCatching {
            val updatedResponse = routePreviewMutationUseCase.replaceStop(
                previousResponse = response,
                targetPoiId = poiId,
                replacementPoi = replacementPoi,
                context = state.routePreviewMutationContext()
            )
            repository.saveSnapshot(
                SavedRouteSnapshot(
                    request = updatedRequest,
                    response = updatedResponse
                )
            )
            RoutePreviewMutationResult(
                state = state.copy(
                    currentRouteRequest = updatedRequest,
                    requiredPoiIds = updatedRequiredPoiIds,
                    routeResponse = updatedResponse,
                    hasPendingRouteChanges = false,
                    hasNoGeneratedStops = false,
                    routeError = null
                )
            )
        }.getOrElse { error ->
            RoutePreviewMutationResult(
                state = state,
                error = error.toUserMessage(routeGenerationFailedMessage)
            )
        }
    }

    private fun RoutePlannerUiState.routePreviewMutationContext(): RoutePreviewMutationContext =
        RoutePreviewMutationContext(
            city = currentRouteRequest?.city ?: selectedCity?.slug,
            pace = currentRouteRequest?.pace,
            startDateTime = currentRouteRequest?.startDateTime,
            transportMode = currentRouteRequest?.transportMode
        )
}

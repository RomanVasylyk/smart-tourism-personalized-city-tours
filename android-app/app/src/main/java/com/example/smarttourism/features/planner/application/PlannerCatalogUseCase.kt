package com.example.smarttourism.features.planner.application

import com.example.smarttourism.features.planner.data.PlannerRepository
import com.example.smarttourism.features.planner.domain.model.City
import com.example.smarttourism.features.planner.domain.model.PlannerPreferences
import com.example.smarttourism.features.planner.domain.model.RoutePlan
import com.example.smarttourism.features.planner.domain.model.RoutePoint
import com.example.smarttourism.features.planner.state.DefaultCitySlug
import com.example.smarttourism.features.planner.state.RoutePlannerUiState
import com.example.smarttourism.features.planner.state.clampAvailableMinutes
import com.example.smarttourism.features.planner.domain.city.availableCategories
import com.example.smarttourism.features.planner.domain.city.matchesToken
import com.example.smarttourism.features.planner.domain.city.supportsPublicTransport
import com.example.smarttourism.features.planner.domain.city.toStartPoint
import java.util.Locale
import javax.inject.Inject

internal data class CityCatalogLoadInput(
    val restoredCityToken: String?,
    val currentState: RoutePlannerUiState,
    val offlineCitiesFallbackMessage: String
)

internal data class CityCatalogStage(
    val cities: List<City>,
    val selectedCity: City?,
    val currentRouteRequest: PlannerPreferences?,
    val routeResponse: RoutePlan?,
    val startPoint: RoutePoint,
    val offlineStatusMessage: String?,
    val pendingSyncOperationCount: Int?,
    val shouldLoadPoisForSelectedCity: Boolean
)

internal data class PoiCatalogLoadInput(
    val city: City,
    val currentState: RoutePlannerUiState,
    val poiPreviewFailedMessage: String,
    val offlinePoisFallbackMessage: String
)

internal data class PoiCatalogStage(
    val state: RoutePlannerUiState
)

internal class PlannerCatalogUseCase @Inject constructor(
    private val repository: PlannerRepository,
    private val offlineMapController: OfflineMapController
) {
    suspend fun loadCities(
        input: CityCatalogLoadInput,
        onStage: suspend (CityCatalogStage) -> Unit
    ) {
        var workingState = input.currentState
        val cachedCities = repository.getCachedCities()
        if (cachedCities.isNotEmpty()) {
            val cachedStage = buildCityStage(
                cities = cachedCities,
                currentState = workingState,
                restoredCityToken = input.restoredCityToken,
                offlineStatusMessage = input.offlineCitiesFallbackMessage,
                pendingSyncOperationCount = null,
                shouldLoadPoisForSelectedCity = true
            )
            onStage(cachedStage)
            workingState = cachedStage.applyTo(workingState)
        }

        try {
            val remoteCities = repository.fetchCities()
            repository.cacheCities(remoteCities)
            val remoteStage = buildCityStage(
                cities = remoteCities,
                currentState = workingState,
                restoredCityToken = input.restoredCityToken,
                offlineStatusMessage = null,
                pendingSyncOperationCount = repository.getPendingSyncOperationCount(),
                shouldLoadPoisForSelectedCity = cachedCities.isEmpty()
            )
            onStage(remoteStage)
        } catch (_: Exception) {
            if (cachedCities.isEmpty()) {
                val fallbackCities = repository.getCachedCities()
                val fallbackStage = buildCityStage(
                    cities = fallbackCities,
                    currentState = workingState,
                    restoredCityToken = input.restoredCityToken,
                    offlineStatusMessage = if (fallbackCities.isNotEmpty()) {
                        input.offlineCitiesFallbackMessage
                    } else {
                        null
                    },
                    pendingSyncOperationCount = null,
                    shouldLoadPoisForSelectedCity = fallbackCities.isNotEmpty()
                )
                onStage(fallbackStage)
            } else {
                val fallbackStage = buildCityStage(
                    cities = workingState.cities,
                    currentState = workingState,
                    restoredCityToken = input.restoredCityToken,
                    offlineStatusMessage = if (workingState.cities.isNotEmpty()) {
                        input.offlineCitiesFallbackMessage
                    } else {
                        null
                    },
                    pendingSyncOperationCount = null,
                    shouldLoadPoisForSelectedCity = false
                )
                onStage(fallbackStage)
            }
        }
    }

    suspend fun loadPoisForCity(
        input: PoiCatalogLoadInput,
        onStage: suspend (PoiCatalogStage) -> Unit
    ) {
        var workingState = input.currentState
            .withSelectedPoiCity(input.city)
        onStage(PoiCatalogStage(workingState))

        val cachedPois = repository.getCachedPois(input.city.slug)
        if (cachedPois.isNotEmpty()) {
            workingState = workingState.copy(
                pois = cachedPois,
                poiError = null
            )
            onStage(PoiCatalogStage(workingState))
        }

        val finalState = try {
            val remotePois = repository.fetchPois(input.city.slug)
            repository.cachePois(input.city.slug, remotePois)
            workingState.copy(
                pois = remotePois,
                isPoiLoading = false,
                poiError = null,
                offlineStatusMessage = null,
                pendingSyncOperationCount = repository.getPendingSyncOperationCount(),
                offlineStoredRegion = offlineMapController.findStoredRegion(input.city.slug)
            )
        } catch (error: Exception) {
            workingState.copy(
                isPoiLoading = false,
                poiError = if (cachedPois.isEmpty()) {
                    error.toUserMessage(input.poiPreviewFailedMessage)
                } else {
                    null
                },
                offlineStatusMessage = if (cachedPois.isNotEmpty()) {
                    String.format(
                        Locale.getDefault(),
                        input.offlinePoisFallbackMessage,
                        input.city.name
                    )
                } else {
                    workingState.offlineStatusMessage
                },
                offlineStoredRegion = offlineMapController.findStoredRegion(input.city.slug)
            )
        }
        onStage(PoiCatalogStage(finalState))
    }

    private fun buildCityStage(
        cities: List<City>,
        currentState: RoutePlannerUiState,
        restoredCityToken: String?,
        offlineStatusMessage: String?,
        pendingSyncOperationCount: Int?,
        shouldLoadPoisForSelectedCity: Boolean
    ): CityCatalogStage {
        val selectedCity = cities.firstOrNull { city -> city.matchesToken(restoredCityToken) }
            ?: cities.firstOrNull { city -> city.matchesToken(currentState.routeResponse?.city) }
            ?: currentState.selectedCity?.let { previousCity ->
                cities.firstOrNull { city -> city.matchesToken(previousCity.slug) }
            }
            ?: cities.firstOrNull { city -> city.matchesToken(DefaultCitySlug) }
            ?: cities.firstOrNull()

        return CityCatalogStage(
            cities = cities,
            selectedCity = selectedCity,
            currentRouteRequest = selectedCity?.let { city ->
                currentState.currentRouteRequest?.copy(city = city.slug)
            } ?: currentState.currentRouteRequest,
            routeResponse = selectedCity?.let { city ->
                currentState.routeResponse?.copy(city = city.slug)
            } ?: currentState.routeResponse,
            startPoint = if (currentState.routeResponse == null) {
                selectedCity?.toStartPoint() ?: currentState.startPoint
            } else {
                currentState.startPoint
            },
            offlineStatusMessage = offlineStatusMessage,
            pendingSyncOperationCount = pendingSyncOperationCount,
            shouldLoadPoisForSelectedCity = shouldLoadPoisForSelectedCity && selectedCity != null
        )
    }

    private fun CityCatalogStage.applyTo(state: RoutePlannerUiState): RoutePlannerUiState =
        state.copy(
            cities = cities,
            selectedCity = selectedCity,
            currentRouteRequest = currentRouteRequest,
            routeResponse = routeResponse,
            startPoint = startPoint,
            offlineStatusMessage = offlineStatusMessage,
            pendingSyncOperationCount = pendingSyncOperationCount ?: state.pendingSyncOperationCount
        )

    private fun RoutePlannerUiState.withSelectedPoiCity(city: City): RoutePlannerUiState {
        val cityCategories = city.availableCategories()
        val updatedInterests = if (selectedInterests.isEmpty()) {
            cityCategories
        } else {
            selectedInterests
                .filter { interest -> interest in cityCategories.toSet() }
                .ifEmpty { cityCategories }
        }

        return copy(
            selectedCity = city,
            isPoiLoading = true,
            offlineMapProgress = null,
            availableMinutes = clampAvailableMinutes(availableMinutes, city),
            selectedInterests = updatedInterests,
            startPoint = if (routeResponse == null) city.toStartPoint() else startPoint,
            allowPublicTransport = allowPublicTransport && city.supportsPublicTransport()
        )
    }
}

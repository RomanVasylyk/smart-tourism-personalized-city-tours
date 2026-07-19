package com.example.smarttourism.features.planner.application

import com.example.smarttourism.data.model.SavedRouteSnapshot
import com.example.smarttourism.features.planner.data.route.RoutePlanningRepository
import com.example.smarttourism.features.planner.data.sync.OfflineSyncRepository
import com.example.smarttourism.features.planner.domain.model.City
import com.example.smarttourism.features.planner.domain.model.PlannerPreferences
import com.example.smarttourism.features.planner.domain.model.Poi
import com.example.smarttourism.features.planner.domain.model.RoutePlan
import com.example.smarttourism.features.planner.domain.model.RoutePoint
import com.example.smarttourism.features.planner.domain.model.RouteStop
import com.example.smarttourism.features.planner.state.DefaultCitySlug
import java.time.LocalDateTime
import java.time.temporal.ChronoUnit
import javax.inject.Inject

internal data class RouteGenerationInput(
    val selectedCity: City?,
    val startPoint: RoutePoint,
    val availableMinutes: Int,
    val selectedInterests: List<String>,
    val pace: String,
    val returnToStart: Boolean,
    val startDateTime: LocalDateTime,
    val respectOpeningHours: Boolean,
    val requiredPoiIds: List<Int>,
    val visitedPoiIds: List<Int>,
    val skippedPoiIds: List<Int>,
    val allowPublicTransport: Boolean,
    val isPublicTransportAvailable: Boolean,
    val pois: List<Poi>,
    val offlineRouteGenerationMessage: String,
    val routeGenerationFailedMessage: String,
    val requiredPlacesMissingMessage: String
)

internal sealed interface RouteGenerationResult {
    data class Success(
        val request: PlannerPreferences,
        val response: RoutePlan
    ) : RouteGenerationResult

    data class MissingRequiredPlaces(
        val request: PlannerPreferences,
        val message: String
    ) : RouteGenerationResult

    data class EmptyRoute(
        val request: PlannerPreferences
    ) : RouteGenerationResult

    data class Error(
        val message: String
    ) : RouteGenerationResult
}

internal class RouteGenerationUseCase @Inject constructor(
    private val routePlanningRepository: RoutePlanningRepository,
    private val offlineSyncRepository: OfflineSyncRepository
) {
    suspend fun generateRoute(input: RouteGenerationInput): RouteGenerationResult {
        if (!offlineSyncRepository.isNetworkAvailable()) {
            return RouteGenerationResult.Error(input.offlineRouteGenerationMessage)
        }

        val request = PlannerPreferences(
            city = input.selectedCity?.slug ?: DefaultCitySlug,
            startLat = input.startPoint.lat,
            startLon = input.startPoint.lon,
            availableMinutes = input.availableMinutes,
            interests = input.selectedInterests,
            pace = input.pace,
            returnToStart = input.returnToStart,
            startDateTime = input.startDateTime.truncatedTo(ChronoUnit.MINUTES).toString(),
            respectOpeningHours = input.respectOpeningHours,
            preferredPoiIds = input.requiredPoiIds
                .filterNot { poiId -> poiId in input.visitedPoiIds || poiId in input.skippedPoiIds },
            transportMode = if (input.allowPublicTransport && input.isPublicTransportAvailable) {
                "walk_or_mhd"
            } else {
                "walk"
            }
        )

        return runCatching {
            val generatedRoute = routePlanningRepository
                .generateRoute(request)
                .withCatalogPoiDetails(input.pois)
            val missingRequiredPlaces = missingRequiredPoiLabels(
                request = request,
                response = generatedRoute,
                pois = input.pois
            )
            when {
                missingRequiredPlaces.isNotEmpty() -> RouteGenerationResult.MissingRequiredPlaces(
                    request = request,
                    message = String.format(
                        java.util.Locale.getDefault(),
                        input.requiredPlacesMissingMessage,
                        missingRequiredPlaces.joinToString(", ")
                    )
                )

                generatedRoute.route.isEmpty() -> RouteGenerationResult.EmptyRoute(request)

                else -> {
                    routePlanningRepository.saveSnapshot(
                        SavedRouteSnapshot(
                            request = request,
                            response = generatedRoute
                        )
                    )
                    RouteGenerationResult.Success(
                        request = request,
                        response = generatedRoute
                    )
                }
            }
        }.getOrElse { error ->
            RouteGenerationResult.Error(error.toUserMessage(input.routeGenerationFailedMessage))
        }
    }

    suspend fun addPublicTransport(
        currentRequest: PlannerPreferences,
        orderedPoiIds: List<Int>,
        pois: List<Poi>,
        offlineRouteGenerationMessage: String,
        routeGenerationFailedMessage: String
    ): RouteGenerationResult {
        if (orderedPoiIds.isEmpty()) {
            return RouteGenerationResult.Error(routeGenerationFailedMessage)
        }
        if (!offlineSyncRepository.isNetworkAvailable()) {
            return RouteGenerationResult.Error(offlineRouteGenerationMessage)
        }

        val request = currentRequest.copy(
            transportMode = "walk_or_mhd",
            preferredPoiIds = orderedPoiIds
        )

        return runCatching {
            val generatedRoute = routePlanningRepository
                .generateRoute(request)
                .withCatalogPoiDetails(pois)
            if (generatedRoute.route.isEmpty()) {
                RouteGenerationResult.Error(routeGenerationFailedMessage)
            } else {
                routePlanningRepository.saveSnapshot(
                    SavedRouteSnapshot(
                        request = request,
                        response = generatedRoute
                    )
                )
                RouteGenerationResult.Success(
                    request = request,
                    response = generatedRoute
                )
            }
        }.getOrElse { error ->
            RouteGenerationResult.Error(error.toUserMessage(routeGenerationFailedMessage))
        }
    }

    private fun missingRequiredPoiLabels(
        request: PlannerPreferences,
        response: RoutePlan,
        pois: List<Poi>
    ): List<String> {
        val requiredIds = request.preferredPoiIds.orEmpty().distinct()
        if (requiredIds.isEmpty()) {
            return emptyList()
        }

        val generatedIds = response.route.map { item -> item.poiId }.toSet()
        val poisById = pois.associateBy { poi -> poi.id }
        return requiredIds
            .filterNot { poiId -> poiId in generatedIds }
            .map { poiId -> poisById[poiId]?.name ?: "#$poiId" }
    }

    private fun RoutePlan.withCatalogPoiDetails(pois: List<Poi>): RoutePlan {
        if (pois.isEmpty() || route.isEmpty()) {
            return this
        }

        val poisById = pois.associateBy { poi -> poi.id }
        return copy(
            route = route.map { stop ->
                stop.withCatalogPoiDetails(poisById[stop.poiId])
            }
        )
    }

    private fun RouteStop.withCatalogPoiDetails(poi: Poi?): RouteStop {
        if (poi == null) {
            return this
        }

        return copy(
            shortDescription = shortDescription.ifNotBlank() ?: poi.shortDescription.ifNotBlank(),
            wikipediaUrl = wikipediaUrl.ifNotBlank() ?: poi.wikipediaUrl.ifNotBlank(),
            openingHoursRaw = openingHoursRaw.ifNotBlank() ?: poi.openingHoursRaw.ifNotBlank()
        )
    }

    private fun String?.ifNotBlank(): String? =
        this?.takeIf { value -> value.isNotBlank() }
}

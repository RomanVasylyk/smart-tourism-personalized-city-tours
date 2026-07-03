package com.example.smarttourism.features.planner.application

import com.example.smarttourism.data.model.SavedRouteSnapshot
import com.example.smarttourism.features.planner.data.curated.CuratedRouteRepository
import com.example.smarttourism.features.planner.domain.model.CuratedRoute
import com.example.smarttourism.features.planner.domain.model.PlannerPreferences
import javax.inject.Inject

internal data class CuratedRouteOpenResult(
    val snapshot: SavedRouteSnapshot,
    val citySlug: String
)

internal class CuratedRoutesUseCase @Inject constructor(
    private val curatedRouteRepository: CuratedRouteRepository
) {
    suspend fun loadCuratedRoutes(citySlug: String): List<CuratedRoute> =
        curatedRouteRepository.fetchCuratedRoutes(citySlug)

    suspend fun openCuratedRoute(routeId: Int, startDateTime: String?): CuratedRouteOpenResult {
        val detail = curatedRouteRepository.fetchCuratedRoute(routeId, startDateTime)
        val plan = detail.route
        val request = PlannerPreferences(
            city = plan.city,
            startLat = plan.start.lat,
            startLon = plan.start.lon,
            availableMinutes = plan.availableMinutes,
            interests = plan.interests,
            pace = plan.pace,
            returnToStart = plan.returnToStart,
            startDateTime = plan.startDateTime,
            respectOpeningHours = plan.respectOpeningHours,
            excludedPoiIds = emptyList(),
            // A curated route is shown as-is (like an opened bookmark); its stops are not
            // marked as "required", otherwise every stop renders with the must-see map icon.
            preferredPoiIds = emptyList(),
            transportMode = plan.transportMode ?: "walk"
        )
        return CuratedRouteOpenResult(
            snapshot = SavedRouteSnapshot(request = request, response = plan),
            citySlug = detail.city ?: plan.city
        )
    }
}

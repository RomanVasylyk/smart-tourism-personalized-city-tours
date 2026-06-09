package com.example.smarttourism.features.planner.application

import com.example.smarttourism.features.map.offline.OfflineMapManager
import com.example.smarttourism.features.map.offline.OfflineStoredRegion
import com.example.smarttourism.features.planner.domain.model.City
import com.example.smarttourism.features.planner.viewmodel.toOfflineCityRegion
import javax.inject.Inject

internal class OfflineMapController @Inject constructor(
    private val offlineMapManager: OfflineMapManager
) {
    suspend fun findStoredRegion(citySlug: String): OfflineStoredRegion? =
        offlineMapManager.findRegionBySlug(citySlug)

    fun downloadCityRegion(
        city: City,
        onProgress: (completed: Long, required: Long, percent: Double) -> Unit,
        onComplete: () -> Unit,
        onError: (String) -> Unit
    ): Boolean {
        val offlineRegion = city.toOfflineCityRegion() ?: return false
        offlineMapManager.downloadCityRegion(
            city = offlineRegion,
            onProgress = onProgress,
            onComplete = onComplete,
            onError = onError
        )
        return true
    }

    fun deleteRegion(
        storedRegion: OfflineStoredRegion,
        onComplete: () -> Unit,
        onError: (String) -> Unit
    ) {
        offlineMapManager.deleteRegion(
            region = storedRegion.region,
            onComplete = onComplete,
            onError = onError
        )
    }
}

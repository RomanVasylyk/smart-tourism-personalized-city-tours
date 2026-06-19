package com.example.smarttourism.data.repository

import com.example.smarttourism.data.local.CachedPoiEntity
import com.example.smarttourism.data.local.OfflineCacheDao
import com.example.smarttourism.data.remote.dto.PoiDto

internal class PoiCacheStore(
    private val dao: OfflineCacheDao
) {
    suspend fun cachePois(citySlug: String, pois: List<PoiDto>) {
        val updatedAt = System.currentTimeMillis()
        dao.replaceCachedPois(
            citySlug = citySlug,
            pois = pois.map { poi ->
                CachedPoiEntity(
                    id = poi.id,
                    citySlug = citySlug,
                    name = poi.name,
                    category = poi.category,
                    lat = poi.lat,
                    lon = poi.lon,
                    openingHoursRaw = poi.opening_hours_raw,
                    visitDurationMin = poi.visit_duration_min,
                    baseScore = poi.base_score,
                    wikipediaUrl = poi.wikipedia_url,
                    updatedAtEpochMs = updatedAt
                )
            }
        )
    }

    suspend fun getCachedPois(citySlug: String): List<PoiDto> =
        dao.getCachedPois(citySlug).map { entity ->
            PoiDto(
                id = entity.id,
                name = entity.name,
                category = entity.category,
                lat = entity.lat,
                lon = entity.lon,
                opening_hours_raw = entity.openingHoursRaw,
                visit_duration_min = entity.visitDurationMin,
                base_score = entity.baseScore,
                wikipedia_url = entity.wikipediaUrl
            )
        }
}

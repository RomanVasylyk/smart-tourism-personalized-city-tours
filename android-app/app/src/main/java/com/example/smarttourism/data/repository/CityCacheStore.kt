package com.example.smarttourism.data.repository

import com.example.smarttourism.data.local.CachedCityEntity
import com.example.smarttourism.data.local.OfflineCacheDao
import com.example.smarttourism.data.remote.dto.CityBboxDto
import com.example.smarttourism.data.remote.dto.CityDto
import com.example.smarttourism.data.remote.dto.RoutingLimitsDto
import com.example.smarttourism.data.remote.dto.TransportProfileDto
import com.google.gson.Gson

internal class CityCacheStore(
    private val dao: OfflineCacheDao,
    private val gson: Gson,
    private val serializer: CacheEnvelopeSerializer
) {
    suspend fun cacheCities(cities: List<CityDto>) {
        val updatedAt = System.currentTimeMillis()
        dao.replaceCachedCities(
            cities.map { city ->
                CachedCityEntity(
                    slug = city.slug,
                    cityId = city.id,
                    name = city.name,
                    country = city.country,
                    centerLat = city.center_lat,
                    centerLon = city.center_lon,
                    bboxSouth = city.bbox?.south,
                    bboxWest = city.bbox?.west,
                    bboxNorth = city.bbox?.north,
                    bboxEast = city.bbox?.east,
                    availableCategoriesJson = gson.toJson(city.available_categories.orEmpty()),
                    defaultZoom = city.default_zoom,
                    routingMaxAvailableMinutes = city.routing_limits?.max_available_minutes,
                    routingMaxPoiCandidates = city.routing_limits?.max_poi_candidates,
                    transportEnabled = city.transport?.mhd_enabled == true,
                    transportProvider = city.transport?.provider,
                    transportMode = city.transport?.mode,
                    updatedAtEpochMs = updatedAt
                )
            }
        )
    }

    suspend fun getCachedCities(): List<CityDto> =
        dao.getCachedCities().map { entity ->
            CityDto(
                id = entity.cityId,
                slug = entity.slug,
                name = entity.name,
                country = entity.country,
                center_lat = entity.centerLat,
                center_lon = entity.centerLon,
                bbox = if (
                    entity.bboxSouth != null &&
                    entity.bboxWest != null &&
                    entity.bboxNorth != null &&
                    entity.bboxEast != null
                ) {
                    CityBboxDto(
                        south = entity.bboxSouth,
                        west = entity.bboxWest,
                        north = entity.bboxNorth,
                        east = entity.bboxEast
                    )
                } else {
                    null
                },
                available_categories = serializer.decodeStringList(entity.availableCategoriesJson),
                default_zoom = entity.defaultZoom,
                routing_limits = RoutingLimitsDto(
                    max_available_minutes = entity.routingMaxAvailableMinutes,
                    max_poi_candidates = entity.routingMaxPoiCandidates
                ),
                transport = TransportProfileDto(
                    mhd_enabled = entity.transportEnabled,
                    provider = entity.transportProvider,
                    mode = entity.transportMode
                )
            )
        }
}

package com.example.smarttourism.features.planner.domain.city

import com.example.smarttourism.features.map.StreetStyleUrl
import com.example.smarttourism.features.map.offline.OfflineCityRegion
import com.example.smarttourism.features.planner.domain.model.City
import com.example.smarttourism.features.planner.domain.model.RoutePoint
import com.example.smarttourism.features.planner.state.DefaultInterestCategories
import java.text.Normalizer
import java.util.Locale

internal fun City.matchesToken(token: String?): Boolean {
    val normalizedToken = normalizedCityToken(token)
    return normalizedToken.isNotBlank() &&
        normalizedToken in setOf(normalizedCityToken(slug), normalizedCityToken(name))
}

private fun normalizedCityToken(value: String?): String {
    val asciiValue = Normalizer.normalize(value.orEmpty().trim(), Normalizer.Form.NFD)
        .replace(Regex("\\p{Mn}+"), "")
    return asciiValue
        .lowercase(Locale.getDefault())
        .replace(Regex("[^a-z0-9]+"), "-")
        .trim('-')
}

internal fun City.availableCategories(): List<String> =
    availableCategories
        ?.map(String::trim)
        ?.filter(String::isNotBlank)
        ?.distinct()
        .orEmpty()
        .ifEmpty { DefaultInterestCategories }

internal fun City.supportsPublicTransport(): Boolean =
    transport?.mhdEnabled == true

internal fun City.toStartPoint(): RoutePoint =
    RoutePoint(centerLat, centerLon)

internal fun City.toOfflineCityRegion(): OfflineCityRegion? {
    val cityBbox = bbox ?: return null
    return OfflineCityRegion(
        slug = slug,
        name = name,
        styleUrl = StreetStyleUrl,
        south = cityBbox.south,
        west = cityBbox.west,
        north = cityBbox.north,
        east = cityBbox.east
    )
}

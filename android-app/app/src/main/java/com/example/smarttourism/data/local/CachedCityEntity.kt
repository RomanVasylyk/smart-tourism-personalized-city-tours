package com.example.smarttourism.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "cached_cities")
data class CachedCityEntity(
    @PrimaryKey val slug: String,
    val cityId: Int,
    val name: String,
    val country: String,
    val centerLat: Double,
    val centerLon: Double,
    val bboxSouth: Double?,
    val bboxWest: Double?,
    val bboxNorth: Double?,
    val bboxEast: Double?,
    val availableCategoriesJson: String?,
    val defaultZoom: Double?,
    val routingMaxAvailableMinutes: Int?,
    val routingMaxPoiCandidates: Int?,
    val transportEnabled: Boolean,
    val transportProvider: String?,
    val transportMode: String?,
    val updatedAtEpochMs: Long
)

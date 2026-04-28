package com.example.smarttourism.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "cached_pois")
data class CachedPoiEntity(
    @PrimaryKey val id: Int,
    val citySlug: String,
    val name: String,
    val category: String,
    val lat: Double,
    val lon: Double,
    val openingHoursRaw: String?,
    val visitDurationMin: Int?,
    val baseScore: Double?,
    val wikipediaUrl: String?,
    val updatedAtEpochMs: Long
)

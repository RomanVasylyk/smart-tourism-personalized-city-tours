package com.example.smarttourism.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "cached_last_route")
data class CachedLastRouteEntity(
    @PrimaryKey val cacheKey: String,
    val snapshotJson: String,
    val updatedAtEpochMs: Long
)

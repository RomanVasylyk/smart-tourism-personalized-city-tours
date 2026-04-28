package com.example.smarttourism.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "cached_route_sessions")
data class CachedRouteSessionEntity(
    @PrimaryKey val routeId: String,
    val sessionJson: String,
    val isActive: Boolean,
    val updatedAtEpochMs: Long
)

package com.example.smarttourism.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "route_history_entries")
data class RouteHistoryEntryEntity(
    @PrimaryKey val routeId: String,
    val historyJson: String,
    val updatedAtEpochMs: Long
)

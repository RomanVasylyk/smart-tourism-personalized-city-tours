package com.example.smarttourism.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "pending_poi_visit_sync")
data class PendingPoiVisitSyncEntity(
    @PrimaryKey val requestKey: String,
    val sessionId: String,
    val poiId: Int,
    val requestJson: String,
    val syncStatus: String,
    val lastSyncAttemptAtEpochMs: Long?,
    val retryCount: Int,
    val createdAtEpochMs: Long,
    val updatedAtEpochMs: Long
)

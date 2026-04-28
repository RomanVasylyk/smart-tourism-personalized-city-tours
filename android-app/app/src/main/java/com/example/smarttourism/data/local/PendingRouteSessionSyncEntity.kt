package com.example.smarttourism.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "pending_route_session_sync")
data class PendingRouteSessionSyncEntity(
    @PrimaryKey val sessionId: String,
    val requestJson: String,
    val syncStatus: String,
    val lastSyncAttemptAtEpochMs: Long?,
    val retryCount: Int,
    val createdAtEpochMs: Long,
    val updatedAtEpochMs: Long
)

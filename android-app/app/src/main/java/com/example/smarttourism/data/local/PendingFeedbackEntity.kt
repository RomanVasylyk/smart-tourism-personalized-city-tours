package com.example.smarttourism.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "pending_feedback")
data class PendingFeedbackEntity(
    @PrimaryKey val sessionId: String,
    val feedbackJson: String,
    val syncStatus: String,
    val lastSyncAttemptAtEpochMs: Long?,
    val retryCount: Int,
    val createdAtEpochMs: Long,
    val updatedAtEpochMs: Long
)

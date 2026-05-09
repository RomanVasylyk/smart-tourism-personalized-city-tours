package com.example.smarttourism.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "bookmarked_routes")
data class BookmarkedRouteEntity(
    @PrimaryKey val bookmarkId: String,
    val title: String,
    val citySlug: String,
    val snapshotJson: String,
    val createdAtEpochMs: Long,
    val updatedAtEpochMs: Long
)

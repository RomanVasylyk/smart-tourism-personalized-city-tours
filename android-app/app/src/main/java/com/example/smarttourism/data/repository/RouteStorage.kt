package com.example.smarttourism.data.repository

import android.content.Context
import com.example.smarttourism.data.model.ActiveRouteSession
import com.example.smarttourism.data.model.RouteBookmark
import com.example.smarttourism.data.model.RouteHistoryEntry
import com.example.smarttourism.data.model.SavedRouteSnapshot
import java.util.UUID

object RouteStorage {
    private const val PreferencesName = "route_storage"
    private const val DeviceIdKey = "device_id"

    suspend fun load(context: Context): SavedRouteSnapshot? =
        OfflineCacheRepository.loadLastRoute(context)

    suspend fun save(
        context: Context,
        snapshot: SavedRouteSnapshot
    ) {
        OfflineCacheRepository.saveLastRoute(context, snapshot)
    }

    suspend fun loadActiveSession(context: Context): ActiveRouteSession? =
        OfflineCacheRepository.loadActiveRouteSession(context)

    suspend fun saveActiveSession(
        context: Context,
        session: ActiveRouteSession
    ) {
        OfflineCacheRepository.saveActiveRouteSession(context, session)
    }

    suspend fun clearActiveSession(context: Context) {
        OfflineCacheRepository.clearActiveRouteSession(context)
    }

    suspend fun saveRouteBookmark(
        context: Context,
        bookmark: RouteBookmark
    ) {
        OfflineCacheRepository.saveRouteBookmark(context, bookmark)
    }

    suspend fun loadRouteBookmarks(context: Context): List<RouteBookmark> =
        OfflineCacheRepository.getRouteBookmarks(context)

    suspend fun loadRouteBookmark(
        context: Context,
        bookmarkId: String
    ): RouteBookmark? =
        OfflineCacheRepository.getRouteBookmark(context, bookmarkId)

    suspend fun deleteRouteBookmark(context: Context, bookmarkId: String) {
        OfflineCacheRepository.deleteRouteBookmark(context, bookmarkId)
    }

    suspend fun saveRouteHistoryEntry(
        context: Context,
        entry: RouteHistoryEntry
    ) {
        OfflineCacheRepository.saveRouteHistoryEntry(context, entry)
    }

    suspend fun saveRouteHistoryEntries(
        context: Context,
        entries: List<RouteHistoryEntry>
    ) {
        OfflineCacheRepository.saveRouteHistoryEntries(context, entries)
    }

    suspend fun loadRouteHistoryEntries(context: Context): List<RouteHistoryEntry> =
        OfflineCacheRepository.getRouteHistoryEntries(context)

    fun getOrCreateDeviceId(context: Context): String {
        val preferences = context.getSharedPreferences(PreferencesName, Context.MODE_PRIVATE)
        val existingDeviceId = preferences.getString(DeviceIdKey, null)
        if (!existingDeviceId.isNullOrBlank()) {
            return existingDeviceId
        }

        val deviceId = UUID.randomUUID().toString()
        preferences
            .edit()
            .putString(DeviceIdKey, deviceId)
            .apply()

        return deviceId
    }
}

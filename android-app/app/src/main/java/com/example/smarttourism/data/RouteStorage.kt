package com.example.smarttourism.data

import android.content.Context
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

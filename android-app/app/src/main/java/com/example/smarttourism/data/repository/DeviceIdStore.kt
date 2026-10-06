package com.example.smarttourism.data.repository

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DeviceIdStore @Inject constructor(
    @ApplicationContext context: Context
) {
    private val appContext = context.applicationContext

    @Synchronized
    fun getOrCreateDeviceId(): String {
        val preferences = appContext.getSharedPreferences(PreferencesName, Context.MODE_PRIVATE)
        val existingDeviceId = preferences.getString(DeviceIdKey, null)
        if (!existingDeviceId.isNullOrBlank()) {
            return existingDeviceId
        }

        val deviceId = UUID.randomUUID().toString()
        preferences
            .edit()
            .putString(DeviceIdKey, deviceId)
            .commit()

        return deviceId
    }

    @Synchronized
    fun getOrCreateDeviceToken(): String {
        val preferences = appContext.getSharedPreferences(PreferencesName, Context.MODE_PRIVATE)
        val existingDeviceToken = preferences.getString(DeviceTokenKey, null)
        if (!existingDeviceToken.isNullOrBlank()) {
            return existingDeviceToken
        }

        val deviceToken = UUID.randomUUID().toString()
        preferences
            .edit()
            .putString(DeviceTokenKey, deviceToken)
            .commit()

        return deviceToken
    }

    private companion object {
        const val PreferencesName = "route_storage"
        const val DeviceIdKey = "device_id"
        const val DeviceTokenKey = "device_token"
    }
}

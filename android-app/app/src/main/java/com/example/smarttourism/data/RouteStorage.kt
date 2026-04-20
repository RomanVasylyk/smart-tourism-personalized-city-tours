package com.example.smarttourism.data

import android.content.Context
import com.google.gson.Gson

object RouteStorage {
    private val gson = Gson()
    private const val PreferencesName = "route_storage"
    private const val LastRouteKey = "last_route_snapshot"

    suspend fun load(context: Context): SavedRouteSnapshot? {
        val rawJson = context
            .getSharedPreferences(PreferencesName, Context.MODE_PRIVATE)
            .getString(LastRouteKey, null)

        return rawJson?.let { json ->
            runCatching {
                gson.fromJson(json, SavedRouteSnapshot::class.java)
            }.getOrNull()
        }
    }

    suspend fun save(
        context: Context,
        snapshot: SavedRouteSnapshot
    ) {
        val json = gson.toJson(snapshot)
        context
            .getSharedPreferences(PreferencesName, Context.MODE_PRIVATE)
            .edit()
            .putString(LastRouteKey, json)
            .apply()
    }
}

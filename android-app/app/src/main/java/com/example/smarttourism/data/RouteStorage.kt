package com.example.smarttourism.data

import android.content.Context
import com.google.gson.Gson

object RouteStorage {
    private val gson = Gson()
    private const val PreferencesName = "route_storage"
    private const val LastRouteKey = "last_route_snapshot"
    private const val ActiveRouteSessionKey = "active_route_session"

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

    suspend fun loadActiveSession(context: Context): ActiveRouteSession? {
        val rawJson = context
            .getSharedPreferences(PreferencesName, Context.MODE_PRIVATE)
            .getString(ActiveRouteSessionKey, null)

        return rawJson?.let { json ->
            runCatching {
                gson.fromJson(json, ActiveRouteSession::class.java)
            }.getOrNull()
        }
    }

    suspend fun saveActiveSession(
        context: Context,
        session: ActiveRouteSession
    ) {
        val json = gson.toJson(session)
        context
            .getSharedPreferences(PreferencesName, Context.MODE_PRIVATE)
            .edit()
            .putString(ActiveRouteSessionKey, json)
            .apply()
    }

    suspend fun clearActiveSession(context: Context) {
        context
            .getSharedPreferences(PreferencesName, Context.MODE_PRIVATE)
            .edit()
            .remove(ActiveRouteSessionKey)
            .apply()
    }
}

package com.example.smarttourism.data.model

import com.google.gson.JsonElement
import com.google.gson.annotations.SerializedName

internal const val CurrentCacheEnvelopeVersion = 1

internal object CacheEnvelopeType {
    const val ACTIVE_ROUTE_SESSION = "active_route_session"
    const val ROUTE_FEEDBACK_REQUEST = "route_feedback_request"
    const val ROUTE_HISTORY_ENTRY = "route_history_entry"
    const val ROUTE_SESSION_CREATE_REQUEST = "route_session_create_request"
    const val ROUTE_SESSION_POI_VISIT_REQUEST = "route_session_poi_visit_request"
    const val SAVED_ROUTE_SNAPSHOT = "saved_route_snapshot"
}

internal data class VersionedCacheEnvelope(
    @SerializedName("schema_version")
    val schemaVersion: Int = CurrentCacheEnvelopeVersion,
    val type: String,
    val payload: JsonElement
)

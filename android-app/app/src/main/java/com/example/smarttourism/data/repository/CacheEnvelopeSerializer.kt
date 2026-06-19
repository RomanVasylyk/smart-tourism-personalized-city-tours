package com.example.smarttourism.data.repository

import com.example.smarttourism.data.model.CurrentCacheEnvelopeVersion
import com.example.smarttourism.data.model.VersionedCacheEnvelope
import com.google.gson.Gson
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.reflect.TypeToken

internal class CacheEnvelopeSerializer(
    private val gson: Gson
) {
    fun decodeStringList(rawJson: String?): List<String> {
        if (rawJson.isNullOrBlank()) {
            return emptyList()
        }
        return runCatching {
            gson.fromJson<List<String>>(
                rawJson,
                object : TypeToken<List<String>>() {}.type
            )
        }.getOrDefault(emptyList())
    }

    fun <T> fromJsonOrNull(rawJson: String, clazz: Class<T>): T? =
        runCatching { gson.fromJson(rawJson, clazz) }.getOrNull()

    fun <T> toVersionedJson(type: String, value: T): String =
        gson.toJson(
            VersionedCacheEnvelope(
                type = type,
                payload = gson.toJsonTree(value)
            )
        )

    fun <T> fromVersionedJsonOrNull(
        rawJson: String,
        expectedType: String,
        clazz: Class<T>
    ): T? {
        val root = runCatching { JsonParser.parseString(rawJson) }.getOrNull()
        val envelope = root?.asJsonObjectOrNull()
        if (envelope?.has("payload") == true && envelope.hasSchemaVersion()) {
            val schemaVersion = envelope.schemaVersionOrNull() ?: return null
            val type = envelope.get("type").asStringOrNull() ?: return null
            val payload = envelope.get("payload") ?: return null
            if (schemaVersion != CurrentCacheEnvelopeVersion || type != expectedType) {
                return null
            }
            return runCatching { gson.fromJson(payload, clazz) }.getOrNull()
        }

        return fromJsonOrNull(rawJson, clazz)
    }

    private fun JsonElement.asJsonObjectOrNull(): JsonObject? =
        if (isJsonObject) asJsonObject else null

    private fun JsonObject.hasSchemaVersion(): Boolean =
        has("schema_version") || has("schemaVersion")

    private fun JsonObject.schemaVersionOrNull(): Int? =
        (get("schema_version") ?: get("schemaVersion")).asIntOrNull()

    private fun JsonElement?.asIntOrNull(): Int? =
        runCatching { this?.asInt }.getOrNull()

    private fun JsonElement?.asStringOrNull(): String? =
        runCatching { this?.asString }.getOrNull()
}

package com.example.smarttourism.offline

import android.content.Context
import kotlinx.coroutines.suspendCancellableCoroutine
import org.json.JSONObject
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.offline.OfflineManager
import org.maplibre.android.offline.OfflineRegion
import org.maplibre.android.offline.OfflineTilePyramidRegionDefinition
import kotlin.coroutines.resume

data class OfflineCityRegion(
    val slug: String,
    val name: String,
    val styleUrl: String,
    val south: Double,
    val west: Double,
    val north: Double,
    val east: Double,
    val minZoom: Double = 11.0,
    val maxZoom: Double = 16.0
)

data class OfflineCityRegionMetadata(
    val slug: String,
    val name: String
)

data class OfflineStoredRegion(
    val region: OfflineRegion,
    val metadata: OfflineCityRegionMetadata
)

class OfflineMapManager(private val context: Context) {

    private val offlineManager: OfflineManager by lazy {
        OfflineManager.getInstance(context)
    }

    fun downloadCityRegion(
        city: OfflineCityRegion,
        onProgress: (completed: Long, required: Long, percent: Double) -> Unit,
        onComplete: () -> Unit,
        onError: (String) -> Unit
    ) {
        val bounds = LatLngBounds.from(
            city.north,
            city.east,
            city.south,
            city.west
        )

        val definition = OfflineTilePyramidRegionDefinition(
            city.styleUrl,
            bounds,
            city.minZoom,
            city.maxZoom,
            context.resources.displayMetrics.density
        )

        val metadata = JSONObject()
            .put("slug", city.slug)
            .put("name", city.name)
            .toString()
            .toByteArray(Charsets.UTF_8)

        offlineManager.createOfflineRegion(
            definition,
            metadata,
            object : OfflineManager.CreateOfflineRegionCallback {
                override fun onCreate(region: OfflineRegion) {
                    region.setObserver(object : OfflineRegion.OfflineRegionObserver {
                        override fun onStatusChanged(status: org.maplibre.android.offline.OfflineRegionStatus) {
                            val completed = status.completedResourceCount
                            val required = status.requiredResourceCount
                            val percent = if (required > 0) completed * 100.0 / required else 0.0
                            onProgress(completed, required, percent)

                            if (status.isComplete) {
                                onComplete()
                            }
                        }

                        override fun onError(error: org.maplibre.android.offline.OfflineRegionError) {
                            onError(error.message ?: "Offline region error")
                        }

                        override fun mapboxTileCountLimitExceeded(limit: Long) {
                            onError("Tile count limit exceeded: $limit")
                        }
                    })

                    region.setDownloadState(OfflineRegion.STATE_ACTIVE)
                }

                override fun onError(error: String) {
                    onError(error)
                }
            }
        )
    }

    fun listRegions(
        onSuccess: (List<OfflineRegion>) -> Unit,
        onError: (String) -> Unit
    ) {
        offlineManager.listOfflineRegions(object : OfflineManager.ListOfflineRegionsCallback {
            override fun onList(regions: Array<OfflineRegion>?) {
                onSuccess(regions?.toList().orEmpty())
            }

            override fun onError(error: String) {
                onError(error)
            }
        })
    }

    fun deleteRegion(
        region: OfflineRegion,
        onComplete: () -> Unit,
        onError: (String) -> Unit
    ) {
        region.delete(object : OfflineRegion.OfflineRegionDeleteCallback {
            override fun onDelete() = onComplete()
            override fun onError(error: String) = onError(error)
        })
    }

    suspend fun listStoredRegions(): List<OfflineStoredRegion> =
        suspendCancellableCoroutine { continuation ->
            listRegions(
                onSuccess = { regions ->
                    continuation.resume(
                        regions.mapNotNull { region ->
                            decodeMetadata(region)?.let { metadata ->
                                OfflineStoredRegion(region = region, metadata = metadata)
                            }
                        }
                    )
                },
                onError = { continuation.resume(emptyList()) }
            )
        }

    suspend fun findRegionBySlug(slug: String): OfflineStoredRegion? =
        listStoredRegions().firstOrNull { storedRegion ->
            storedRegion.metadata.slug == slug
        }

    fun decodeMetadata(region: OfflineRegion): OfflineCityRegionMetadata? {
        val rawMetadata = region.metadata ?: return null
        return runCatching {
            val json = JSONObject(String(rawMetadata, Charsets.UTF_8))
            val slug = json.optString("slug").trim()
            val name = json.optString("name").trim()
            if (slug.isBlank()) {
                null
            } else {
                OfflineCityRegionMetadata(
                    slug = slug,
                    name = name.ifBlank { slug }
                )
            }
        }.getOrNull()
    }
}

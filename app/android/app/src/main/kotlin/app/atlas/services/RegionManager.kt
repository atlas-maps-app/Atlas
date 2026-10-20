package app.atlas.services

import app.atlas.geo.BBox
import app.atlas.geo.LatLon
import kotlinx.coroutines.flow.StateFlow
import java.time.Instant

/** What an offline region holds. */
enum class RegionLayer { MAP, PLACES, ROUTING, VOICE }

/** An offline region on the phone (or on its way): its area, its layers, its size and, while downloading, its progress. */
data class OfflineRegion(
    val id: String,
    val name: String,
    val bbox: BBox,
    val layers: Set<RegionLayer>,
    val bytes: Long,
    val updatedAt: Instant?,
    val complete: Boolean,
    /** 0..1 while a download runs, else null. */
    val progress: Float? = null,
    /** Why the last download stopped, when it failed. */
    val error: String? = null,
)

/** A region to take offline: a named area and the layers wanted. */
data class RegionRequest(
    val name: String,
    val bbox: BBox,
    val layers: Set<RegionLayer> = setOf(RegionLayer.MAP),
    val maxZoom: Int = 14,
)

/**
 * The offline regions: what is on the phone, downloads and their progress, deleting. The mine package implements the
 * full manager (WorkManager downloads with notifications, places, routing and voice layers, packs); whatever it uses,
 * it tells `Services.reloadRegions()` when files change so the map, search and routing pick them up at once.
 */
interface RegionManager {
    val regions: StateFlow<List<OfflineRegion>>

    /** Starts (or resumes) taking [request] offline; progress shows in [regions]. Returns the region's id. */
    fun download(request: RegionRequest): String

    /** Stops a running download; what was fetched stays and the next [download] resumes from it. */
    fun cancel(id: String)

    suspend fun delete(id: String)

    /** The complete region that holds [point], if any: the map's "offline ready" sign. */
    fun covering(point: LatLon): OfflineRegion? = regions.value.firstOrNull { it.complete && point in it.bbox && RegionLayer.MAP in it.layers }

    /** Bytes of every region together. */
    val totalBytes: Long get() = regions.value.sumOf { it.bytes }
}

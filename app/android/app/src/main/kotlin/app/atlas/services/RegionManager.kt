package app.atlas.services

import app.atlas.geo.BBox
import app.atlas.geo.LatLon
import app.atlas.tiles.RegionDownloader
import app.atlas.tiles.RegionLibrary
import app.atlas.tiles.RegionManifest
import app.atlas.tiles.RegionPlanner
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

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

/**
 * A plain region manager for map tiles: packs in a [RegionLibrary], downloaded in process with [downloader] while the
 * app runs. It is complete for the MAP layer; other layers are refused clearly until the mine package's manager
 * replaces this one.
 */
class LibraryRegionManager(
    private val library: RegionLibrary,
    private val downloader: () -> RegionDownloader,
    private val scope: CoroutineScope,
    private val onChanged: () -> Unit,
) : RegionManager {
    private val state = MutableStateFlow(emptyList<OfflineRegion>())
    /** The download running per region; guarded by itself, with [progress] and [errors]. */
    private val jobs = HashMap<String, Job>()
    private val progress = ConcurrentHashMap<String, Float>()
    private val errors = ConcurrentHashMap<String, String>()

    override val regions: StateFlow<List<OfflineRegion>> = state.asStateFlow()

    init {
        scope.launch(Dispatchers.IO) { refresh() }
    }

    override fun download(request: RegionRequest): String {
        val unsupported = request.layers - RegionLayer.MAP
        require(unsupported.isEmpty()) { "this region manager downloads map tiles only, not $unsupported" }
        val id = RegionManifest.idFor(request.name)
        synchronized(jobs) {
            if (jobs[id]?.isActive == true) return id
            // A job only starts once it is registered, so it can always find (and only ever clear) its own entry.
            val job = scope.launch(Dispatchers.IO, start = CoroutineStart.LAZY) { run(id, request) }
            jobs[id] = job
            errors.remove(id)
            progress[id] = 0f
            job.start()
        }
        scope.launch(Dispatchers.IO) { refresh() }
        return id
    }

    private suspend fun run(id: String, request: RegionRequest) {
        val me = currentCoroutineContext().job
        try {
            val plan = RegionPlanner.plan(request.bbox, 0, request.maxZoom)
            library.download(downloader(), plan, request.name).collect { p ->
                synchronized(jobs) { if (jobs[id] === me) progress[id] = p.fraction }
                refresh()
            }
            onChanged()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            // Whatever stopped it (no network, a full disk, a broken pack) is shown on the region, not lost.
            synchronized(jobs) { if (jobs[id] === me) errors[id] = e.message ?: e.toString() }
        } finally {
            // A download cancelled and started again has a successor by now: leave its entry and progress alone.
            synchronized(jobs) {
                if (jobs[id] === me) {
                    jobs.remove(id)
                    progress.remove(id)
                }
            }
            refresh()
        }
    }

    override fun cancel(id: String) {
        synchronized(jobs) {
            jobs.remove(id)?.cancel()
            progress.remove(id)
        }
        scope.launch(Dispatchers.IO) { refresh() }
    }

    /** Stops a running download of [id] and waits until it has let go of its pack, then removes the region's files. */
    override suspend fun delete(id: String) {
        val running = synchronized(jobs) { jobs.remove(id).also { progress.remove(id) } }
        running?.cancelAndJoin()
        withContext(Dispatchers.IO) {
            library.delete(id)
            errors.remove(id)
            refresh()
        }
        onChanged()
    }

    /** Re-reads the library; serialised, so a slow, older reading never overwrites a newer one. */
    private fun refresh() = synchronized(state) {
        state.value = library.list().map { m ->
            OfflineRegion(
                id = m.id,
                name = m.name,
                bbox = m.bbox,
                layers = setOf(RegionLayer.MAP),
                bytes = m.bytes,
                updatedAt = Instant.ofEpochMilli(m.updatedAt),
                complete = m.complete,
                progress = progress[m.id],
                error = errors[m.id],
            )
        }
    }
}

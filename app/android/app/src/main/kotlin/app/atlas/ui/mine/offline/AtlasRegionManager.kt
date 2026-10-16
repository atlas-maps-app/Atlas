package app.atlas.ui.mine.offline

import app.atlas.geo.BBox
import app.atlas.geo.LatLon
import app.atlas.services.OfflineRegion
import app.atlas.services.RegionLayer
import app.atlas.services.RegionManager
import app.atlas.services.RegionRequest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.time.Instant

/** Starts and stops the background work that takes a region offline. The app uses WorkManager; tests run it inline. */
interface RegionScheduler {
    /** Queues the download (or update) of region [id]; [wifiOnly] holds it until the phone is on an unmetered network. */
    fun schedule(id: String, wifiOnly: Boolean, update: Boolean)

    /** Stops the download of [id] and drops it from the queue. */
    fun cancel(id: String)
}

/**
 * The full [RegionManager]: regions with four layers (map, places, routing, voice), downloaded by a [RegionScheduler] (in
 * the app, WorkManager workers with a progress notification, Wi-Fi only by default, resumable) through the
 * [RegionEngine]. [regions] says what the shell and the map need to know; [details] is what the Offline screen shows.
 */
class AtlasRegionManager(
    private val files: RegionFiles,
    val engine: RegionEngine,
    private val scheduler: () -> RegionScheduler,
    private val wifiOnly: () -> Boolean,
    private val scope: CoroutineScope,
) : RegionManager {
    private val state = MutableStateFlow(emptyList<OfflineRegion>())

    override val regions: StateFlow<List<OfflineRegion>> = state.asStateFlow()

    /** Every region with its layers, progress and update, for the Offline screen. */
    val details: StateFlow<List<RegionDetail>> get() = engine.details

    private val running = HashMap<String, Job>()

    private companion object {
        const val STOP_WAIT_MS = 5_000L
    }

    init {
        engine.details.map { list -> list.map(::toRegion) }.onEach { state.value = it }.launchIn(scope)
        scope.launch(Dispatchers.IO) { engine.refresh() }
    }

    override fun download(request: RegionRequest): String {
        val id = RegionEngine.idFor(request.name)
        engine.begin(id, request.name, request.bbox, request.layers, request.maxZoom)
        engine.waiting(id)
        scheduler().schedule(id, wifiOnly(), update = false)
        return id
    }

    /** Takes [bbox] offline as [name] with every layer in [layers]. */
    fun download(name: String, bbox: BBox, layers: Set<RegionLayer>): String = download(RegionRequest(name, bbox, layers))

    /** Refreshes the stale layers of [id]. */
    fun update(id: String) {
        val m = files.get(id) ?: return
        engine.begin(id, m.name, m.bbox, m.wanted.ifEmpty { m.layers.keys }, m.maxZoom)
        engine.waiting(id)
        scheduler().schedule(id, wifiOnly(), update = true)
    }

    override fun cancel(id: String) {
        scheduler().cancel(id)
        synchronized(running) { running.remove(id)?.cancel() }
        engine.idle(id)
    }

    override suspend fun delete(id: String) {
        scheduler().cancel(id)
        // Wait for a download in progress to stop, so it cannot write the files again after they are gone.
        val job = synchronized(running) { running.remove(id) }
        job?.cancel()
        withTimeoutOrNull(STOP_WAIT_MS) { job?.join() }
        engine.idle(id)
        withContext(Dispatchers.IO) { engine.delete(id) }
    }

    /** Runs a download here and now (what the worker does, and what tests do): returns when it is done or has failed. */
    suspend fun run(id: String, update: Boolean = false): Result<Unit> {
        val job = kotlin.coroutines.coroutineContext[Job]
        if (job != null) synchronized(running) { running[id] = job }
        return try {
            engine.resume(id, update)
            Result.success(Unit)
        } catch (e: RegionGone) {
            Result.success(Unit)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Throwable) {
            Result.failure(e)
        } finally {
            synchronized(running) { if (running[id] === job) running.remove(id) }
        }
    }

    private fun toRegion(d: RegionDetail) = OfflineRegion(
        id = d.id,
        name = d.name,
        bbox = d.meta.bbox,
        layers = d.meta.layers.keys,
        bytes = d.bytes,
        updatedAt = Instant.ofEpochMilli(d.meta.updatedAt),
        complete = d.complete,
        progress = d.progress?.fraction,
        error = d.error,
    )

    /** The complete region that holds [point] with a map layer, for the map's "offline ready" sign. */
    override fun covering(point: LatLon): OfflineRegion? =
        regions.value.firstOrNull { it.complete && point in it.bbox && RegionLayer.MAP in it.layers }
}

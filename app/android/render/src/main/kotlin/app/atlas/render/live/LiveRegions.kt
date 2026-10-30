package app.atlas.render.live

import app.atlas.geo.BBox
import app.atlas.geo.LatLon
import app.atlas.render.Regions
import app.atlas.render.data.OverpassClient
import app.atlas.render.data.PackReader
import app.atlas.render.data.RegionConfig
import app.atlas.render.py.Dict
import app.atlas.render.py.PyJson
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executor
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min

/**
 * Which region the live map draws for a view, and getting it ready: the packs on the phone, the published ones it
 * downloads when the camera is in their box, and packs built on the phone from Overpass where no pack reaches.
 *
 * Of the packs whose box holds the target, the one whose own views best fit the camera's distance wins (the Westerkerk's
 * living map close up, the canal ring from 2 km); a published pack that fits better than any on the phone is downloaded
 * while the best one on the phone is drawn. Downloads, builds and unpacking run on [worker]; [onChange] is called when
 * one ends, so the map asks again. [stateFor] itself never blocks on the network.
 *
 * A region whose scene would not fit the heap ([heapBudget]) is never loaded: a scene near the heap's limit makes the
 * phone collect garbage for minutes, hot and without a picture, before running out. The map says so instead.
 */
class LiveRegions(
    val library: PackLibrary,
    private val worker: Executor,
    /** False when the map must not touch the network (offline mode, tests). */
    private val online: () -> Boolean = { true },
    /** Builds packs where none reaches; null to never build. */
    private val overpass: OverpassClient? = null,
    private val onChange: () -> Unit = {},
    /** The heap one scene may take: a share of what the runtime gives the app, leaving room for its render and frame. */
    private val heapBudget: () -> Long = { (Runtime.getRuntime().maxMemory() * SCENE_SHARE).toLong() },
    /**
     * Whether a country whose tiles the core draws holds a place (ENGINE.md step 3, one pack per country): there the map
     * is the core's, and no published region is fetched or offered for it (the canal ring's scene, too big for a phone,
     * was offered at the core's edge until 30 October: "the region grachten needs about 332 MB").
     */
    private val engineCountry: (LatLon) -> Boolean = { false },
) {
    /** What the map can draw for a view. */
    sealed interface State {
        /** [region] is ready to draw; [pending] says a better one is on its way. */
        data class Ready(val region: LiveRegion, val pending: Pending? = null) : State

        /** Nothing to draw yet: [id] is downloading ([done] of [bytes]), being built or unpacked. */
        data class Pending(val id: String, val stage: LiveProgress.Stage, val done: Long = 0, val bytes: Long = -1, val building: Boolean = false) : State

        /** No data here: [reason] says why (offline, outside every pack, a failed download). */
        data class Empty(val reason: String) : State
    }

    /** The regions opened, with the stamp of the pack file they were opened from. */
    private val opened = ConcurrentHashMap<String, Pair<LiveRegion, String>>()
    private val jobs = ConcurrentHashMap<String, State.Pending>()
    private val failed = ConcurrentHashMap<String, Pair<Long, String>>()
    private val views = ConcurrentHashMap<String, DoubleArray>()

    @Volatile
    private var manifestTried = 0L

    /** The state for [view], starting whatever work it needs. */
    fun stateFor(view: LiveView): State {
        val p = view.target
        maybeRefresh()
        val inCountry = engineCountry(p)
        val (allHere, published) = library.at(p)
        val allRemote = if (inCountry) emptyList() else published
        val budget = heapBudget()
        val here = allHere.filter { sceneBytes(it.file.length()) <= budget }
        val remote = allRemote.filter { sceneBytes(it.bytes) <= budget }
        val dist = view.distance
        val bestLocal = here.minWithOrNull(compareBy<PackLibrary.Local> { fit(it.id, dist, it.bbox) }.thenBy { area(it.bbox) })
        val bestRemote = if (online()) remote.minWithOrNull(compareBy<PackLibrary.Entry> { fit(it.id, dist, it.bbox) }.thenBy { area(it.bbox) }) else null
        var pending: State.Pending? = null
        if (bestRemote != null && (bestLocal == null || fit(bestRemote.id, dist, bestRemote.bbox) < fit(bestLocal.id, dist, bestLocal.bbox) - 0.05)) {
            pending = download(bestRemote)
        }
        if (bestLocal == null) {
            if (pending != null) return pending
            if (inCountry) return State.Empty("the engine draws this country from ${PackLibrary.ENGINE_REACH_KM} km away and closer: zoom in")
            // a region covers this place but its scene is too big for this phone: a build of the same streets would be too
            val big = allHere.map { it.id to it.file.length() } + allRemote.map { it.id to it.bytes }
            big.maxByOrNull { it.second }?.let { (id, bytes) ->
                return State.Empty(
                    "the region $id needs about ${sceneBytes(bytes) / 1_000_000} MB, more than this phone gives the map " +
                        "(${budget / 1_000_000} MB); smaller scenes are coming",
                )
            }
            if (!online()) return State.Empty("offline, and no region on the phone covers this place")
            val ov = overpass ?: return State.Empty("no region covers this place")
            return build(ov, p, dist)
        }
        val region = opened[bestLocal.id]?.takeIf { it.second == bestLocal.stamp }?.first
        if (region == null) {
            failed[bestLocal.id]?.let { (at, why) -> if (System.currentTimeMillis() - at < RETRY_MS) return pending ?: State.Empty(why) }
            return open(bestLocal)
        }
        if (bestLocal.built) refreshIfOld(bestLocal)
        return State.Ready(region, pending)
    }

    /**
     * Builds [l] (a pack built here) again from Overpass in the background once it is older than [REBUILD_MS], online
     * only: the map's data stays up to date where it is used, and the old pack draws until the new one is whole.
     */
    private fun refreshIfOld(l: PackLibrary.Local) {
        val ov = overpass ?: return
        if (!online() || System.currentTimeMillis() - l.file.lastModified() < REBUILD_MS) return
        val key = "rebuild:${l.id}"
        failed[key]?.let { (at, _) -> if (System.currentTimeMillis() - at < RETRY_MS) return }
        if (jobs.putIfAbsent(key, State.Pending(l.id, LiveProgress.Stage.DOWNLOADING, building = true)) != null) return
        worker.execute {
            try {
                library.build(ov, l.bbox, refresh = true, id = l.id)
            } catch (x: Exception) {
                failed[key] = System.currentTimeMillis() to "Overpass could not refresh this place: ${x.message}"
            } finally {
                jobs.remove(key)
                onChange()
            }
        }
    }

    /** The regions opened so far. */
    val regions: List<LiveRegion> get() = opened.values.map { it.first }

    private fun open(l: PackLibrary.Local): State {
        val key = "open:${l.id}"
        jobs[key]?.let { return it }
        val p = State.Pending(l.id, LiveProgress.Stage.UNPACKING)
        if (jobs.putIfAbsent(key, p) != null) return jobs[key]!!
        worker.execute {
            try {
                opened[l.id] = PackReader.open(l.file).use { LiveEngine.open(it, l.stamp) } to l.stamp
            } catch (e: Exception) {
                failed[l.id] = System.currentTimeMillis() to "the pack ${l.id} could not be read: ${e.message}"
            } finally {
                jobs.remove(key)
                onChange()
            }
        }
        return p
    }

    private fun download(e: PackLibrary.Entry): State.Pending? {
        val key = "get:${e.id}"
        jobs[key]?.let { return it }
        failed[e.id]?.let { (at, _) -> if (System.currentTimeMillis() - at < RETRY_MS) return null }
        val p = State.Pending(e.id, LiveProgress.Stage.DOWNLOADING, 0, e.bytes)
        if (jobs.putIfAbsent(key, p) != null) return jobs[key]
        worker.execute {
            try {
                var last = 0L
                library.download(e) { done, total ->
                    if (done - last >= 1 shl 18 || done == total) {
                        last = done
                        jobs[key] = State.Pending(e.id, LiveProgress.Stage.DOWNLOADING, done, total)
                        onChange()
                    }
                }
            } catch (x: Exception) {
                failed[e.id] = System.currentTimeMillis() to "the download of ${e.id} failed: ${x.message}"
            } finally {
                jobs.remove(key)
                onChange()
            }
        }
        return p
    }

    private fun build(ov: OverpassClient, p: LatLon, dist: Double): State {
        val half = (dist * 0.8).coerceIn(BUILD_MIN_M, BUILD_MAX_M)
        // one box per cell of a grid, so neighbouring views share a build
        val cell = half / 111_320.0
        val c = LatLon(Math.round(p.lat / cell) * cell, Math.round(p.lon / (cell / cos(Math.toRadians(p.lat)))) * (cell / cos(Math.toRadians(p.lat))))
        val box = BBox.around(c, half)
        val id = PackLibrary.buildId(box.center)
        failed[id]?.let { (at, why) -> if (System.currentTimeMillis() - at < RETRY_MS) return State.Empty(why) }
        val key = "build:$id"
        jobs[key]?.let { return it }
        val pend = State.Pending(id, LiveProgress.Stage.DOWNLOADING, building = true)
        if (jobs.putIfAbsent(key, pend) != null) return jobs[key]!!
        worker.execute {
            try {
                library.build(ov, box)
            } catch (x: Exception) {
                failed[id] = System.currentTimeMillis() to "Overpass could not give this place: ${x.message}"
            } finally {
                jobs.remove(key)
                onChange()
            }
        }
        return pend
    }

    private fun maybeRefresh() {
        if (!online() || library.published.isNotEmpty() && System.currentTimeMillis() - manifestTried < MANIFEST_MS) return
        if (System.currentTimeMillis() - manifestTried < RETRY_MS) return
        manifestTried = System.currentTimeMillis()
        worker.execute {
            if (runCatching { library.refresh() }.isSuccess) onChange()
        }
    }

    /** How well a region's views fit a camera [dist] metres from its target: |ln(dist / the nearest view's)|. */
    private fun fit(id: String, dist: Double, bbox: BBox): Double {
        val ds = views.getOrPut(id) { viewDistances(id, bbox) }
        var best = Double.MAX_VALUE
        for (d in ds) best = min(best, abs(ln(dist / d)))
        return best
    }

    private fun viewDistances(id: String, bbox: BBox): DoubleArray {
        val config: Dict? = library.local(id)?.let { l -> runCatching { PackReader.open(l.file).use { PyJson.loads(it.manifest.config.toString()) as Dict } }.getOrNull() }
            ?: Regions.PLACES[id]
        @Suppress("UNCHECKED_CAST")
        val vs = (config?.get("views") as Map<String, Dict>?)
        val list = when {
            vs != null -> vs.values.map { (it["dist"] as Number).toDouble() }
            config != null || library.local(id) != null -> listOf((ViewLadder.livingView()["dist"] as Number).toDouble())
            else -> (PyJson.loads(RegionConfig.views(bbox).toString()) as Map<*, *>).values.map { ((it as Map<*, *>)["dist"] as Number).toDouble() }
        }
        return list.toDoubleArray()
    }

    private fun area(b: BBox): Double = max(0.0, b.width) * max(0.0, b.height)

    /** The heap a pack's scene takes, estimated from the pack's size. */
    private fun sceneBytes(packBytes: Long): Long = packBytes * HEAP_PER_PACK_BYTE

    companion object {
        const val RETRY_MS = 60_000L
        const val MANIFEST_MS = 6 * 3600_000L
        /** How old a pack built here may grow before it is built again from today's data (ms): a week. */
        const val REBUILD_MS = 7 * 24 * 3600_000L
        const val BUILD_MIN_M = 600.0
        const val BUILD_MAX_M = 1500.0

        /**
         * Heap per byte of pack, measured on the JVM (15 October): grachten's 5.2 MB pack makes a 283 MB scene (54), the
         * Westerkerk's 0.63 MB one 40 MB (64). The larger is taken.
         */
        const val HEAP_PER_PACK_BYTE = 64L

        /** The share of the heap one scene may take; the render, the SVG, the frame and the app need the rest. */
        const val SCENE_SHARE = 0.4
    }
}

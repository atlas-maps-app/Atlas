package app.atlas.mapview

import android.graphics.Color
import android.util.Log
import app.atlas.mapview.svg.SvgCamera
import app.atlas.mapview.svg.SvgCanvas
import app.atlas.mapview.svg.SvgFonts
import app.atlas.mapview.svg.SvgParser
import app.atlas.render.RenderOptions
import app.atlas.render.live.LiveEngine
import app.atlas.render.live.LiveProgress
import app.atlas.render.live.LiveRegions
import app.atlas.render.live.LiveView
import app.atlas.render.live.Moment
import app.atlas.render.live.RegionFrame
import kotlin.math.min

/** What the map can say about its picture: drawn, on its way, or no data. */
sealed interface MapStatus {
    /** The picture is the engine's exact frame (or a warp of it while the next one is made). */
    data object Ready : MapStatus

    /** The engine is loading the region ([region]) or making a frame. */
    data class Loading(val region: String?) : MapStatus

    /** A region pack is downloading ([done] of [bytes], −1 when unknown) or being built from OpenStreetMap ([building]). */
    data class Downloading(val region: String, val done: Long, val bytes: Long, val building: Boolean = false) : MapStatus

    /** No map data here, and why. */
    data class NoData(val reason: String) : MapStatus
}

/**
 * Makes the engine's exact frames on a thread of its own: picks the region ([LiveRegions]), renders the view with the
 * 1:1 engine ([LiveEngine]), parses the SVG layers and rasterises them into one bitmap ([EngineFrame]).
 *
 * Requests are conflated: only the newest is made, once the camera has been still for [QUIET_MS] (or [MAX_WAIT_MS]
 * after the first change, so a long gesture still gets frames); requests in between are dropped. One frame is made at
 * a time per process (the engine's state is shared). [onChange] is called on this thread when [latest] or [status]
 * changed. Every frame's timings go to logcat under the tag `AtlasMap`.
 */
internal class EngineFrames(
    private val fonts: () -> SvgFonts,
    private val onChange: () -> Unit,
    private val clock: () -> Long = { System.nanoTime() / 1_000_000 },
) {
    /** What one frame is made for. */
    data class Request(val view: LiveView, val moment: Moment, val density: Float, val opts: RenderOptions, val night: Boolean)

    @Volatile
    var latest: EngineFrame? = null
        private set

    @Volatile
    var status: MapStatus = MapStatus.Loading(null)
        private set

    /** The ground frame of the region being drawn, once one is chosen. */
    @Volatile
    var frame: RegionFrame? = null
        private set

    /** Frames that failed (an engine or drawing fault), for the debug overlay and tests. */
    @Volatile
    var failures = 0
        private set

    @Volatile
    var lastError: Throwable? = null
        private set

    private val lock = Object()
    private var want: Request? = null
    private var last: Request? = null
    private var wantAt = 0L
    private var firstAt = 0L
    private var closed = false

    @Volatile
    private var working = false
    private var number = 0L
    private val thread = Thread({ loop() }, "atlas-engine").apply {
        isDaemon = true
        priority = Thread.NORM_PRIORITY
        start()
    }

    /** True while a request waits or a frame is being made. */
    val busy: Boolean get() = synchronized(lock) { want != null } || working

    /** Asks for a frame of [r]. */
    fun submit(r: Request) = synchronized(lock) {
        val now = clock()
        if (want == null) firstAt = now
        want = r
        wantAt = now
        lock.notifyAll()
    }

    /** Asks again for the last request (the region data or the forecast changed). */
    fun invalidate() {
        val r = synchronized(lock) { want ?: last } ?: return
        synchronized(lock) {
            if (want == null) {
                firstAt = 0
                wantAt = 0
                want = r
            }
            lock.notifyAll()
        }
    }

    fun close() = synchronized(lock) {
        closed = true
        lock.notifyAll()
    }

    private fun loop() {
        while (true) {
            val r: Request
            synchronized(lock) {
                while (!closed && want == null) lock.wait()
                while (!closed) {
                    val now = clock()
                    val until = min(wantAt + QUIET_MS, firstAt + MAX_WAIT_MS)
                    if (now >= until) break
                    lock.wait((until - now).coerceAtLeast(1))
                }
                if (closed) return
                r = want!!
                want = null
                last = r
                working = true
            }
            try {
                make(r)
            } catch (e: OutOfMemoryError) {
                // two big scenes and two frames did not fit: keep one scene and try again with the next request
                failures++
                lastError = e
                LiveEngine.forget()
                status = MapStatus.NoData("not enough memory for this region")
                Log.w(TAG, "out of memory making a frame; scenes dropped", e)
            } catch (e: Exception) {
                failures++
                lastError = e
                // an engine fault (a place it has nothing to stand on, a broken pack) is no picture: say so
                if (latest == null || frame == null) status = MapStatus.NoData("the engine could not draw this place: ${e.message ?: e}")
                Log.w(TAG, "a frame failed: $e", e)
            } finally {
                working = false
                onChange()
            }
        }
    }

    private fun make(r: Request) {
        val regions = MapEngine.regions
        if (regions == null) {
            setStatus(MapStatus.NoData("no map data"))
            return
        }
        val st = regions.stateFor(r.view)
        val pending = when (st) {
            is LiveRegions.State.Empty -> {
                setStatus(MapStatus.NoData(st.reason))
                return
            }
            is LiveRegions.State.Pending -> {
                setStatus(statusOf(st))
                return
            }
            is LiveRegions.State.Ready -> st.pending
        }
        val region = (st as LiveRegions.State.Ready).region
        if (frame != region.frame) {
            frame = region.frame
            onChange()
        }
        val res = LiveEngine.render(
            region, r.view, r.moment, r.opts,
            progress = { p ->
                if (p.stage == LiveProgress.Stage.LOADING) setStatus(MapStatus.Loading(p.region))
            },
            cancelled = { synchronized(lock) { closed } },
        ) ?: return
        val t0 = System.nanoTime()
        val docs = res.layers.map { SvgParser.parse(it) }
        val t1 = System.nanoTime()
        val scale = min(r.density, MapEngine.maxRasterScale)
        // the CPU filters: exact on every Android version (the AGSL ones need a hardware canvas, see the filters screen)
        val image = EngineFrame.rasterise(docs, res.raster, fonts(), scale, SvgCanvas.FilterMode.CPU, background = if (r.night) NIGHT else Color.TRANSPARENT)
        val t2 = System.nanoTime()
        val timings = EngineFrame.Timings(res.loadMillis, res.renderMillis, (t1 - t0) / 1_000_000, (t2 - t1) / 1_000_000, res.layers.sumOf { it.length })
        val n = ++number
        val f = EngineFrame(n, image, scale, cameraOf(res.cfg), res.frame, region.id, res.view, res.moment, res.tolerance, timings)
        latest = f
        status = pending?.let { statusOf(it) } ?: MapStatus.Ready
        Log.i(
            TAG,
            "frame $n ${region.id} view=${res.view ?: "-"} moment=${res.moment} band=${res.tolerance} ${r.view.widthDp}x${r.view.heightDp}dp@${scale}: " +
                "load ${timings.load} ms, render ${timings.render} ms, parse ${timings.parse} ms, raster ${timings.raster} ms, " +
                "total ${timings.total} ms; svg ${timings.svgChars / 1024} KB; scenes ${LiveEngine.sceneCount} (${LiveEngine.sceneBytes / 1_000_000} MB)",
        )
    }

    private fun setStatus(s: MapStatus) {
        if (status != s) {
            status = s
            onChange()
        }
    }

    private fun statusOf(p: LiveRegions.State.Pending): MapStatus = when (p.stage) {
        LiveProgress.Stage.DOWNLOADING -> MapStatus.Downloading(p.id, p.done, p.bytes, p.building)
        else -> MapStatus.Loading(p.id)
    }

    companion object {
        const val TAG = "AtlasMap"

        /** The camera must be still this long before an exact frame is made. */
        const val QUIET_MS = 120L

        /** A frame is made at least this often while the camera keeps moving. */
        const val MAX_WAIT_MS = 1200L

        private const val NIGHT = 0xFF0B0F1A.toInt()

        /** The engine's camera of a render (`amsterdam.py`'s `Camera(cfg['target'], …)`), in CSS pixels. */
        fun cameraOf(cfg: Map<String, Any?>): SvgCamera {
            fun n(v: Any?) = (v as Number).toDouble()
            val t = cfg["target"] as List<*>
            return SvgCamera(
                n(t[0]), n(t[1]), n(t[2]), n(cfg["yaw"]), n(cfg["pitch"]), n(cfg["dist"]), n(cfg["fov"]), n(cfg["W"]), n(cfg["H"]),
                cx = (cfg["cx"] as Number?)?.toDouble(), cy = (cfg["cy"] as Number?)?.toDouble(),
            )
        }
    }
}

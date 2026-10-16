package app.atlas.mapview

import android.graphics.Color
import android.util.Log
import app.atlas.mapview.svg.SvgCamera
import app.atlas.mapview.svg.SvgCanvas
import app.atlas.mapview.svg.SvgDocument
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
 *
 * [close] stops it without waiting: a frame being made stops at its next check (between the stages, and in the raster
 * between elements, filter bands and noise tiles) and is never shown.
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

    /** What the frame being made is doing now ("loading", "rendering", "rasterising"…), for which region, since when (ms). */
    @Volatile
    var stage: String = ""
        private set

    @Volatile
    var stageRegion: String? = null
        private set

    @Volatile
    var stageSince: Long = 0
        private set

    /** When the frame being made was started (ms on [clock]), 0 when none is. */
    @Volatile
    var workSince: Long = 0
        private set

    /** Milliseconds now, on the clock the stages are timed with. */
    fun now(): Long = clock()

    private val lock = Object()
    private var want: Request? = null
    private var last: Request? = null
    private var wantAt = 0L
    private var firstAt = 0L

    /** Set once, by [close] under the lock; the frame being made reads it without the lock at each of its checks. */
    @Volatile
    private var closed = false
    private val cancelled: () -> Boolean = { closed }

    @Volatile
    private var working = false
    private var number = 0L
    private val thread = Thread({ loop() }, "atlas-engine").apply {
        isDaemon = true
        priority = Thread.NORM_PRIORITY
        start()
    }

    // While a frame is being made, a tick a second, so the stage and its seconds on screen keep counting.
    private val ticker = Thread({
        while (!closed) {
            try {
                Thread.sleep(1000)
            } catch (_: InterruptedException) {
                return@Thread
            }
            if (working) changed()
        }
    }, "atlas-engine-tick").apply {
        isDaemon = true
        start()
    }

    /** True while a request waits or a frame is being made. */
    val busy: Boolean get() = synchronized(lock) { want != null } || working

    /** Asks for a frame of [r]; nothing once closed. */
    fun submit(r: Request) {
        synchronized(lock) {
            if (closed) return
            val now = clock()
            if (want == null) firstAt = now
            want = r
            wantAt = now
            lock.notifyAll()
        }
    }

    /** Asks again for the last request (the region data or the forecast changed); nothing once closed. */
    fun invalidate() {
        synchronized(lock) {
            if (closed) return
            val r = want ?: last ?: return
            if (want == null) {
                firstAt = 0
                wantAt = 0
                want = r
            }
            lock.notifyAll()
        }
    }

    /**
     * Stops making frames, without waiting: the request waiting is dropped, and the frame being made stops at its next
     * check and is never shown. Once this returns nothing here changes and [onChange] is not called again (a call under
     * way, a post to the main thread, is waited for). The thread lets go of what it made as it leaves; [awaitStopped]
     * waits for that. Idempotent.
     */
    fun close() {
        synchronized(lock) {
            if (closed) return
            closed = true
            want = null
            lock.notifyAll()
        }
        ticker.interrupt()
    }

    /** Waits up to [timeoutMs] for the threads to end after [close]; true when they have. For tests: no frame outlives its test. */
    fun awaitStopped(timeoutMs: Long): Boolean {
        val end = System.nanoTime() + timeoutMs * 1_000_000
        for (t in arrayOf(thread, ticker)) {
            while (t.isAlive) {
                val left = (end - System.nanoTime()) / 1_000_000
                if (left <= 0) return false
                t.join(left)
            }
        }
        return true
    }

    private fun loop() {
        // the engine works behind the screen: background priority, so a frame being made never starves the UI thread
        runCatching { android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND) }
        while (true) {
            val r = next() ?: return
            try {
                make(r)
            } catch (e: java.util.concurrent.CancellationException) {
                // closed while it was made: the frame is dropped without a word
            } catch (e: Throwable) {
                // a real failure is logged, and out of memory still frees the scenes, even as the map closes; what it
                // would publish is dropped once closed
                failed(e)
            } finally {
                working = false
                workSince = 0
                setStage("", null)
                changed()
            }
        }
    }

    /** The newest request once the camera has been still long enough, or null once closed. */
    private fun next(): Request? {
        synchronized(lock) {
            while (!closed && want == null) lock.wait()
            while (!closed) {
                val now = clock()
                val until = min(wantAt + QUIET_MS, firstAt + MAX_WAIT_MS)
                if (now >= until) break
                lock.wait((until - now).coerceAtLeast(1))
            }
            if (closed) return null
            val r = want!!
            want = null
            last = r
            working = true
            workSince = clock()
            return r
        }
    }

    /** A frame that failed: counted, logged, and said on the map when there is no picture. */
    private fun failed(e: Throwable) {
        failures++
        lastError = e
        when (e) {
            is OutOfMemoryError -> {
                // two big scenes and two frames did not fit: keep one scene and try again with the next request
                LiveEngine.forget()
                publish { status = MapStatus.NoData("not enough memory for this region") }
                Log.w(TAG, "out of memory making a frame; scenes dropped", e)
            }
            is Exception -> {
                // an engine fault (a place it has nothing to stand on, a broken pack) is no picture: say so
                if (latest == null || frame == null) publish { status = MapStatus.NoData("the engine could not draw this place: ${e.message ?: e}") }
                Log.w(TAG, "a frame failed: $e", e)
            }
            else -> {
                // an error that is not memory (a stack overflow, a class that would not load): never a silent stall
                if (latest == null || frame == null) publish { status = MapStatus.NoData("the engine stopped: $e") }
                Log.e(TAG, "a frame failed: $e", e)
            }
        }
    }

    /**
     * Runs [change] to what this publishes, under the lock and unless closed: [close] waits for a change under way, and
     * none runs after it. False when closed.
     */
    private inline fun publish(change: () -> Unit): Boolean {
        synchronized(lock) {
            if (closed) return false
            change()
            return true
        }
    }

    /** Tells the view, unless closed. */
    private fun changed() {
        publish { onChange() }
    }

    private fun make(r: Request) {
        setStage("choosing the region", null)
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
            publish {
                frame = region.frame
                onChange()
            }
        }
        // closed meanwhile: not even the engine's lock is waited for
        if (closed) return
        val res = LiveEngine.render(
            region, r.view, r.moment, r.opts,
            progress = { p ->
                if (p.stage == LiveProgress.Stage.LOADING) setStatus(MapStatus.Loading(p.region))
                setStage(p.stage.name.lowercase(), p.region)
            },
            cancelled = cancelled,
        ) ?: return
        setStage("parsing", region.id)
        val t0 = System.nanoTime()
        val layers = res.layers
        val docs = ArrayList<SvgDocument>(layers.size)
        for (svg in layers) {
            if (closed) return
            docs += SvgParser.parse(svg)
        }
        val t1 = System.nanoTime()
        val scale = min(r.density, MapEngine.maxRasterScale)
        setStage("rasterising", region.id)
        // the CPU filters: exact on every Android version (the AGSL ones need a hardware canvas, see the filters screen)
        val image = EngineFrame.rasterise(
            docs, res.raster, fonts(), scale, SvgCanvas.FilterMode.CPU, background = if (r.night) NIGHT else Color.TRANSPARENT, cancelled = cancelled,
        )
        val t2 = System.nanoTime()
        val timings = EngineFrame.Timings(res.loadMillis, res.renderMillis, (t1 - t0) / 1_000_000, (t2 - t1) / 1_000_000, layers.sumOf { it.length })
        val n = ++number
        val f = EngineFrame(n, image, scale, cameraOf(res.cfg), res.frame, region.id, res.view, res.moment, res.tolerance, timings)
        val shown = publish {
            latest = f
            status = pending?.let { statusOf(it) } ?: MapStatus.Ready
        }
        if (!shown) {
            // closed as it was finished: never shown, so its pixels can go at once
            image.recycle()
            return
        }
        Log.i(
            TAG,
            "frame $n ${region.id} view=${res.view ?: "-"} moment=${res.moment} band=${res.tolerance} ${r.view.widthDp}x${r.view.heightDp}dp@${scale}: " +
                "load ${timings.load} ms, render ${timings.render} ms, parse ${timings.parse} ms, raster ${timings.raster} ms, " +
                "total ${timings.total} ms; svg ${timings.svgChars / 1024} KB; scenes ${LiveEngine.sceneCount} (${LiveEngine.sceneBytes / 1_000_000} MB)",
        )
    }

    private fun setStage(name: String, region: String?) {
        if (stage == name && stageRegion == region) return
        val set = publish {
            stage = name
            stageRegion = region
            stageSince = clock()
            onChange()
        }
        if (set && name.isNotEmpty()) Log.i(TAG, "stage: $name${region?.let { " $it" } ?: ""}")
    }

    private fun setStatus(s: MapStatus) {
        if (status != s) {
            publish {
                status = s
                onChange()
            }
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

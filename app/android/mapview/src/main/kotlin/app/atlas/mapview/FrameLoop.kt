package app.atlas.mapview

import app.atlas.engine.Camera
import app.atlas.engine.Frame
import app.atlas.engine.FrameStyle
import app.atlas.engine.Hit
import app.atlas.engine.Renderer
import app.atlas.engine.TextMeasurer
import app.atlas.engine.TileCover
import app.atlas.geo.TileId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.Executors

/** What one frame is made for: the camera, the look and the per-frame style. */
class RenderInput(val camera: Camera, val look: ResolvedLook, val style: FrameStyle)

/** A frame ready to draw, with what it was made for and how it went. */
class DrawnFrame(
    val number: Long,
    val frame: Frame,
    val input: RenderInput,
    /** Tiles the camera wanted, and scenes the frame drew (fallbacks included). */
    val wanted: Int,
    val drawnTiles: Int,
    /** Scene loads running or queued when the frame was made. */
    val loading: Int,
    /** Labels or symbols were still fading: another frame follows. */
    val fading: Boolean,
    val renderNanos: Long,
) {
    val camera: Camera get() = input.camera
}

/**
 * Makes frames on a thread of its own, so the main thread only draws: covers the camera with tiles, asks the
 * [pipeline] for the scenes, plans fallbacks from what is built, and runs the engine's [Renderer] (projection, culling,
 * ordering, label placement).
 *
 * Requests are conflated: the newest input wins. The renderer reuses its buffers every other frame, so a new frame is
 * made only after the last one was drawn ([drawn]); that also paces the loop to the screen. While labels fade it keeps
 * making frames. [onFrame] is called on the loop's thread when [latest] changed.
 */
class FrameLoop(
    measurer: TextMeasurer,
    private val pipeline: ScenePipeline,
    private val onFrame: () -> Unit,
    private val clock: () -> Long = { System.nanoTime() / 1_000_000 },
) {
    private val renderer = Renderer(measurer)
    private val executor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "atlas-frames").apply { isDaemon = true; priority = Thread.NORM_PRIORITY + 1 }
    }
    private val scope = CoroutineScope(SupervisorJob() + executor.asCoroutineDispatcher())
    private val wake = Channel<Unit>(Channel.CONFLATED)

    @Volatile
    private var input: RenderInput? = null

    @Volatile
    private var dirty = false

    @Volatile
    private var consumed = 0L

    @Volatile
    var latest: DrawnFrame? = null
        private set

    /** True while a frame is being made or one is wanted. */
    val busy: Boolean get() = dirty || making

    @Volatile
    private var making = false

    private var number = 0L

    /** Frames that failed to be made (a fault in a scene or the renderer), for the debug overlay and tests. */
    @Volatile
    var failures = 0
        private set
    private val pinned = HashSet<TileId>()

    init {
        scope.launch {
            while (isActive) {
                wake.receive()
                try {
                    step()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // A frame that cannot be made is skipped; the loop lives on for the next request.
                    failures++
                }
            }
        }
    }

    /** Asks for a frame of [i]. */
    fun submit(i: RenderInput) {
        input = i
        dirty = true
        wake.trySend(Unit)
    }

    /** Asks for a frame of the last input again (scenes arrived). */
    fun invalidate() {
        dirty = true
        wake.trySend(Unit)
    }

    /** The view drew frame [n]: its buffers may be reused from the frame after next. */
    fun drawn(n: Long) {
        if (n > consumed) {
            consumed = n
            if (dirty) wake.trySend(Unit)
        }
    }

    /** What lies under ([x], [y]) in [frame] (the newest, which stays valid until a newer one is drawn). */
    fun hitTest(frame: Frame, x: Float, y: Float): Hit? = renderer.hitTest(frame, x, y)

    private fun step() {
        if (!dirty) return
        val last = latest
        if (last != null && consumed < last.number) return
        // The request is taken before the input is read: a submit that lands in between sets it again (and its input
        // is the one read), so no request is ever lost.
        making = true
        dirty = false
        val inp = input
        if (inp == null) {
            making = false
            return
        }
        try {
            val t0 = System.nanoTime()
            val camera = inp.camera
            val cover = TileCover.cover(camera)
            val loading = pipeline.want(cover, inp.look.look, inp.look.key)
            val cache = pipeline.cache
            val key = inp.look.key
            val plan = TileCover.plan(cover) { id -> cache.get(id, key) ?: cache.any(id) }
            pinned.clear()
            for (t in plan) pinned += t.scene.id
            cache.pinTiles(pinned, key)
            val frame = renderer.frame(camera, plan, clock(), inp.style)
            var fading = false
            for (l in frame.labels) if (l.alpha < 1f) { fading = true; break }
            if (!fading) for (p in frame.pois) if (p.alpha < 1f) { fading = true; break }
            if (fading) dirty = true
            number++
            latest = DrawnFrame(number, frame, inp, cover.size, plan.size, loading, fading, System.nanoTime() - t0)
        } finally {
            making = false
        }
        onFrame()
    }

    fun close() {
        scope.cancel()
        executor.shutdownNow()
    }
}

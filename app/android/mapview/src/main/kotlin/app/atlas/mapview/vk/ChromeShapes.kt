package app.atlas.mapview.vk

import android.util.Log
import android.view.Choreographer
import android.view.View
import android.view.ViewTreeObserver
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.core.Transition
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.toSize
import app.atlas.mapcore.state.CameraWriter
import app.atlas.mapview.EngineFrames
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicLongArray
import kotlin.math.min

/**
 * The chrome's keep-out shapes (plan-vector D5.1, Appendix D, K11b): where Compose laid the app's chrome out over the
 * map, and how its frosted glass blurs, for the core's camera state. On the SurfaceView host the map's window lies under
 * the app's, so HWUI's frosted glass cannot read the map: the core draws the glass under each frosted shape itself
 * (`CP/passes/Frosted.cpp`), and the app's `frosted` draws only the tint and the content (`APP/ui/kit/Backdrop.kt`).
 * The shapes also keep the core's labels clear of the chrome later (D13), frosted or not.
 *
 * Compose places the shapes on the main thread as it lays them out ([chromeShape]), each with the frame time of its
 * layout (a frame callback gives it while shapes move; a still UI is left to sleep, and a move's first frame takes the
 * time it was laid out at); the map's camera source writes them into the camera state on the core's render thread at
 * each paced vsync
 * ([write]), with no lock and no allocation: the shapes are one snapshot behind a sequence number, read again when a
 * placement ran beside the read. The core and the UI wake on the same vsync, and the core may ask before that vsync's
 * layout has run: a shape that moved in each of its last two layouts is then carried on to the vsync, never by more than
 * one frame's movement, so that its glass and its tint land in the same frame; once the UI has laid a frame out at or
 * after the vsync, or a shape's last layout left it where it was, the shape is written exactly where it lies.
 *
 * Each shape also carries the alpha its chrome is drawn at ([fade]: the fades over it, [ChromeFade]), so that the core's
 * glass fades with the tint over it, whether or not the map's SurfaceView follows a fading layer.
 */
@Stable
class ChromeShapes {
    /** Told when a shape moves, comes or goes, the surface moves, or [coreDraws] changes (the map asks for a frame). */
    @Volatile
    var onChange: (() -> Unit)? = null

    private val coreDrawsState = mutableStateOf(false)

    @Volatile
    private var coreDrawsNow = false

    /**
     * Whether the core draws the glass under the frosted shapes: while the map's core draws on a SurfaceView under the
     * chrome (the map sets it). Snapshot state, so the `frosted` surfaces switch between the tint alone and HWUI's glass.
     */
    var coreDraws: Boolean
        get() = coreDrawsState.value
        set(value) {
            if (coreDrawsNow == value) return
            coreDrawsNow = value
            coreDrawsState.value = value
            onChange?.invoke()
        }

    // Each slot's fields, written on the main thread between two increments of [sequence], read on the render thread.
    private val data = AtomicLongArray(MAX_SHAPES * STRIDE)
    private val sequence = AtomicInteger(0)
    private val surface = AtomicLong(packed(0f, 0f))

    @Volatile
    private var laidOutNanos = 0L

    // The render thread's last whole copy of the slots, and the one being read ([write] alone touches them).
    private var seen = LongArray(MAX_SHAPES * STRIDE)
    private var reading = LongArray(MAX_SHAPES * STRIDE)

    /** A slot for a shape, or -1 when all [MAX_SHAPES] are taken. Main thread. */
    fun add(): Int {
        for (slot in 0 until MAX_SHAPES) {
            if (data.get(slot * STRIDE) and IN_USE != 0L) continue
            begin()
            for (i in 0 until STRIDE) data.set(slot * STRIDE + i, 0L)
            data.set(slot * STRIDE + TONE, packed(1f, 1f))
            data.set(slot * STRIDE, IN_USE)
            end()
            return slot
        }
        Log.w(EngineFrames.TAG, "vk chrome: more than $MAX_SHAPES shapes; this one has no glass from the core")
        return -1
    }

    /** Frees [slot]. Main thread. */
    fun remove(slot: Int) {
        if (slot !in 0 until MAX_SHAPES || data.get(slot * STRIDE) and IN_USE == 0L) return
        val placed = data.get(slot * STRIDE) and PLACED != 0L
        begin()
        for (i in 0 until STRIDE) data.set(slot * STRIDE + i, 0L)
        end()
        if (placed) onChange?.invoke()
    }

    /**
     * Shape [slot] lies at [left], [top], [right], [bottom] (root px) with its corner [radius] (px), frosted under it
     * by a Gaussian of σ [blur] (px) saturated by [saturation] when [frosted], as laid out for the frame of
     * [frameNanos] (the Choreographer's frame time). Main thread. Whether it moved, came or changed its look.
     */
    fun place(
        slot: Int, left: Float, top: Float, right: Float, bottom: Float, radius: Float, blur: Float, saturation: Float,
        frosted: Boolean, frameNanos: Long,
    ): Boolean {
        if (slot !in 0 until MAX_SHAPES) return false
        val base = slot * STRIDE
        val flags = data.get(base)
        if (flags and IN_USE == 0L) return false
        val corner = packed(left, top)
        val far = packed(right, bottom)
        val look = packed(radius, blur)
        val tone = (packed(saturation, 0f) and LOW) or (data.get(base + TONE) and LOW.inv())
        val placed = flags and PLACED != 0L
        val moved = !placed || data.get(base + BOX1) != corner || data.get(base + BOX1 + 1) != far
        val styled = !placed || data.get(base + LOOK) != look || data.get(base + TONE) != tone ||
            (flags and FROSTED != 0L) != frosted
        val t1 = data.get(base + T1)
        if (!moved && !styled && frameNanos == t1) return false
        begin()
        var next = IN_USE or PLACED or (if (frosted) FROSTED else 0L)
        when {
            !placed -> {
                data.set(base + BOX0, corner)
                data.set(base + BOX0 + 1, far)
                data.set(base + T0, frameNanos)
            }
            frameNanos == t1 -> {
                // Placed again in the same frame: the frame's last place stands, measured from the layout before.
                if (data.get(base + BOX0) != corner || data.get(base + BOX0 + 1) != far) next = next or MOVED1
                if (flags and MOVED0 != 0L) next = next or MOVED0
            }
            else -> {
                data.set(base + BOX0, data.get(base + BOX1))
                data.set(base + BOX0 + 1, data.get(base + BOX1 + 1))
                data.set(base + T0, t1)
                if (moved) next = next or MOVED1
                if (flags and MOVED1 != 0L) next = next or MOVED0
            }
        }
        data.set(base + BOX1, corner)
        data.set(base + BOX1 + 1, far)
        data.set(base + T1, frameNanos)
        data.set(base + LOOK, look)
        data.set(base + TONE, tone)
        data.set(base, next)
        end()
        if (moved || styled) onChange?.invoke()
        return moved || styled
    }

    /**
     * Shape [slot]'s chrome is drawn at [alpha] now (the fades over it: [LocalChromeAlpha]): the core draws the glass under
     * it at that alpha. Main thread.
     */
    fun fade(slot: Int, alpha: Float) {
        if (slot !in 0 until MAX_SHAPES || alpha.isNaN()) return
        val base = slot * STRIDE
        val flags = data.get(base)
        if (flags and IN_USE == 0L) return
        val old = data.get(base + TONE)
        val tone = (old and LOW) or (packed(0f, alpha.coerceIn(0f, 1f)) and LOW.inv())
        if (tone == old) return
        begin()
        data.set(base + TONE, tone)
        end()
        if (flags and PLACED != 0L) onChange?.invoke()
    }

    /** The map's surface lies at ([left], [top]) in root px: the shapes are written relative to it. Main thread. */
    fun surfaceAt(left: Float, top: Float) {
        val p = packed(left, top)
        if (surface.getAndSet(p) != p) onChange?.invoke()
    }

    /** The UI has laid out the frame of [frameNanos] (every shape it moved has been placed for it). Main thread. */
    fun laidOut(frameNanos: Long) {
        if (frameNanos > laidOutNanos) laidOutNanos = frameNanos
    }

    /**
     * Writes the shapes into the camera state [out] for the frame of [vsyncNanos] (0: as they lie): their count and each
     * shape (CameraWriter.keepOuts, keepOut), in dp from the surface's top left, at the density the camera state already
     * holds (so after CameraWriter.frame), each frosted only while [coreDraws]. Shapes the core's reader would refuse
     * (far off the frame, no area) are left out; a corner wider than half the shape and a blur past 256 dp are cut to
     * what the reader takes; each carries its chrome's alpha ([fade]). On the render thread at each paced vsync;
     * allocates nothing. True when a moving shape was
     * carried on to the vsync: the source asks for one more frame, so that the glass comes to rest where the shape does.
     */
    fun write(out: ByteBuffer, vsyncNanos: Long = 0L): Boolean {
        read()
        val density = out.getDouble(CameraWriter.DENSITY_AT)
        val widthDp = out.getInt(CameraWriter.WIDTH_DP_AT).toFloat()
        val heightDp = out.getInt(CameraWriter.HEIGHT_DP_AT).toFloat()
        if (!(density > 0.0) || widthDp <= 0f || heightDp <= 0f) {
            CameraWriter.keepOuts(out, 0)
            return false
        }
        val d = density.toFloat()
        val origin = surface.get()
        val ox = first(origin)
        val oy = second(origin)
        val frosting = coreDrawsNow
        val laid = laidOutNanos
        var n = 0
        var carried = false
        for (slot in 0 until MAX_SHAPES) {
            val base = slot * STRIDE
            val flags = seen[base]
            if (flags and (IN_USE or PLACED) != (IN_USE or PLACED)) continue
            var left = first(seen[base + BOX1])
            var top = second(seen[base + BOX1])
            var right = first(seen[base + BOX1 + 1])
            var bottom = second(seen[base + BOX1 + 1])
            val t1 = seen[base + T1]
            if (vsyncNanos != 0L && t1 < vsyncNanos && laid < vsyncNanos && flags and MOVED1 != 0L && flags and MOVED0 != 0L) {
                val step = t1 - seen[base + T0]
                val ahead = vsyncNanos - t1
                if (step in 1..MAX_STEP_NANOS && ahead <= step) {
                    val k = ahead.toFloat() / step.toFloat()
                    left += (left - first(seen[base + BOX0])) * k
                    top += (top - second(seen[base + BOX0])) * k
                    right += (right - first(seen[base + BOX0 + 1])) * k
                    bottom += (bottom - second(seen[base + BOX0 + 1])) * k
                    carried = true
                }
            }
            val l = (left - ox) / d
            val t = (top - oy) / d
            val r = (right - ox) / d
            val b = (bottom - oy) / d
            if (!(r > l) || !(b > t) || !(l > -widthDp) || !(r < 2f * widthDp) || !(t > -heightDp) || !(b < 2f * heightDp)) continue
            val radius = (first(seen[base + LOOK]) / d).coerceIn(0f, min(r - l, b - t) / 2f)
            val blur = (second(seen[base + LOOK]) / d).coerceIn(0f, MAX_BLUR_DP)
            val saturation = first(seen[base + TONE]).coerceIn(0f, 2.55f)
            if (radius.isNaN() || blur.isNaN() || saturation.isNaN()) continue
            val alpha = second(seen[base + TONE])
            CameraWriter.keepOut(out, n, l, t, r, b, radius, blur, saturation, frosting && flags and FROSTED != 0L, alpha)
            n++
        }
        CameraWriter.keepOuts(out, n)
        return carried
    }

    /**
     * The shapes as one log line, in root px: `vk chrome <n> shapes: <left> <top> <right> <bottom> r <radius> blur <σ>
     * sat <s> frosted|clear[ alpha <a>]; …; surface <x> <y>; core <draws|does not draw> the glass` (the alpha only while a
     * fade holds it under 1), which CI's pill check reads.
     */
    fun describe(): String {
        val shapes = ArrayList<String>()
        for (slot in 0 until MAX_SHAPES) {
            val base = slot * STRIDE
            val flags = data.get(base)
            if (flags and (IN_USE or PLACED) != (IN_USE or PLACED)) continue
            val c = data.get(base + BOX1)
            val f = data.get(base + BOX1 + 1)
            val alpha = second(data.get(base + TONE))
            shapes += "%.1f %.1f %.1f %.1f r %.1f blur %.1f sat %.2f %s%s".format(
                java.util.Locale.ROOT, first(c), second(c), first(f), second(f), first(data.get(base + LOOK)),
                second(data.get(base + LOOK)), first(data.get(base + TONE)), if (flags and FROSTED != 0L) "frosted" else "clear",
                if (alpha < 1f) " alpha %.2f".format(java.util.Locale.ROOT, alpha) else "",
            )
        }
        val o = surface.get()
        return "vk chrome ${shapes.size} shapes: ${shapes.joinToString("; ")}; surface %.1f %.1f; core %s the glass".format(
            java.util.Locale.ROOT, first(o), second(o), if (coreDrawsNow) "draws" else "does not draw",
        )
    }

    /** Copies the slots into [seen], whole: again while a placement runs beside it (the last whole copy otherwise). */
    private fun read() {
        repeat(READ_TRIES) {
            val s = sequence.get()
            if (s and 1 == 0) {
                for (i in reading.indices) reading[i] = data.get(i)
                if (sequence.get() == s) {
                    val whole = reading
                    reading = seen
                    seen = whole
                    return
                }
            }
        }
    }

    private fun begin() {
        sequence.incrementAndGet()
    }

    private fun end() {
        sequence.incrementAndGet()
    }

    // The pre-draw listeners of the windows whose chrome is placed here, and how many shapes use each.
    private val watched = HashMap<ViewTreeObserver, Int>()

    // The frame being laid out, from a frame callback posted for it (0 outside one, or in a frame none was posted for:
    // a callback is posted for the next frame only after a frame that moved a shape, so a still UI sleeps).
    private var frameTime = 0L
    private var movedThisFrame = false
    private var callbackPosted = false
    private val frameCallback = Choreographer.FrameCallback { t ->
        callbackPosted = false
        frameTime = t
    }
    private val preDraw = ViewTreeObserver.OnPreDrawListener {
        laidOut(if (frameTime != 0L) frameTime else System.nanoTime())
        frameTime = 0L
        if (movedThisFrame && !callbackPosted) {
            callbackPosted = true
            Choreographer.getInstance().postFrameCallback(frameCallback)
        }
        movedThisFrame = false
        true
    }

    /** The frame time of the frame being laid out, when a frame callback gave it; else the time now. Main thread. */
    internal fun frameNanos(): Long = if (frameTime != 0L) frameTime else System.nanoTime()

    /** A shape moved in this frame: the next frame's time is wanted for its layout. Main thread. */
    internal fun moved() {
        movedThisFrame = true
    }
    private var logPending = false
    private var logView: View? = null
    private val logLine = Runnable {
        logPending = false
        Log.i(EngineFrames.TAG, describe())
    }

    /** Hears each frame [view]'s window lays out (and logs the shapes a moment after they change). Main thread. */
    internal fun watch(view: View) {
        val observer = view.viewTreeObserver
        val n = watched[observer] ?: 0
        if (n == 0 && observer.isAlive) observer.addOnPreDrawListener(preDraw)
        watched[observer] = n + 1
        logView = view
    }

    /** Stops hearing [view]'s window when its last shape goes. Main thread. */
    internal fun unwatch(view: View) {
        val observer = view.viewTreeObserver
        val n = watched[observer] ?: return
        if (n <= 1) {
            watched.remove(observer)
            if (observer.isAlive) observer.removeOnPreDrawListener(preDraw)
        } else {
            watched[observer] = n - 1
        }
        if (watched.isEmpty()) {
            logView?.removeCallbacks(logLine)
            logPending = false
            logView = null
            if (callbackPosted) Choreographer.getInstance().removeFrameCallback(frameCallback)
            callbackPosted = false
            frameTime = 0L
        }
    }

    /** Logs the shapes a quarter of a second after they last changed, at most four lines a second. Main thread. */
    internal fun logSoon() {
        val v = logView ?: return
        if (logPending) return
        logPending = true
        v.postDelayed(logLine, LOG_DELAY_MS)
    }

    companion object {
        /** The most shapes a frame carries (the camera state's keep-out slots). */
        const val MAX_SHAPES = CameraWriter.MAX_KEEP_OUTS

        /** The longest blur the core's reader takes, dp. */
        const val MAX_BLUR_DP = 256f

        /** Two layouts further apart than this (ns) are not one movement a frame: nothing is carried on from them. */
        const val MAX_STEP_NANOS = 50_000_000L

        private const val LOG_DELAY_MS = 250L
        private const val READ_TRIES = 64

        // A slot: its flags, the frame times and boxes of its last two layouts, its corner and blur, its saturation.
        private const val STRIDE = 9
        private const val T1 = 1
        private const val T0 = 2
        private const val BOX1 = 3 // left | top, then right | bottom
        private const val BOX0 = 5
        private const val LOOK = 7 // radius | blur
        private const val TONE = 8 // saturation | alpha

        private const val IN_USE = 1L
        private const val PLACED = 2L
        private const val FROSTED = 4L
        private const val MOVED1 = 8L // the last layout moved it
        private const val MOVED0 = 16L // the layout before that moved it too

        // The first float of a packed pair.
        private const val LOW = 0xFFFF_FFFFL

        private fun packed(a: Float, b: Float): Long =
            (a.toRawBits().toLong() and 0xFFFF_FFFFL) or (b.toRawBits().toLong() shl 32)

        private fun first(p: Long): Float = Float.fromBits(p.toInt())

        private fun second(p: Long): Float = Float.fromBits((p ushr 32).toInt())

    }
}

/** The app's chrome shapes (one per app: the root provides them beside its backdrop). */
val LocalChromeShapes = staticCompositionLocalOf<ChromeShapes?> { null }

/**
 * The alpha the chrome here is drawn at now, as the fades over it give it ([ChromeFade] multiplies its scope's fade in;
 * 1 outside every one). A function, read where the alpha is wanted (a layout, a draw), so that a fade recomposes nothing.
 */
val LocalChromeAlpha = staticCompositionLocalOf<() -> Float> { { 1f } }

/**
 * Marks [content] as faded by [scope]'s enter and exit (an AnimatedVisibility's content, or a NavDisplay entry's, from
 * its LocalNavAnimatedContentScope): the chrome's shapes in it ([chromeShape]) give the core the alpha the fade draws
 * them at, so that the core's glass under them fades with their tint, whether or not the map's SurfaceView follows the
 * fading layer. The alpha is the scope's own fade, read from its transition ([fadeAlpha]): any fadeIn and fadeOut, a
 * push's or a pop's, a predictive back's seeking too; 1 without a fade.
 */
@Composable
fun ChromeFade(scope: AnimatedVisibilityScope, content: @Composable () -> Unit) {
    val parent = LocalChromeAlpha.current
    val transition = scope.transition
    val alpha = remember(parent, transition) { { parent() * fadeAlpha(transition) } }
    CompositionLocalProvider(LocalChromeAlpha provides alpha, content = content)
}

/**
 * The alpha [transition]'s fade gives the content it enters or exits now: the value of the animation AnimatedVisibility
 * and AnimatedContent run for fadeIn and fadeOut (Compose's "Built-in alpha", the alpha of the content's graphics layer),
 * or 1 while there is none. A snapshot read: a draw that reads it is drawn again as the fade moves.
 */
internal fun fadeAlpha(transition: Transition<*>): Float {
    val animations = transition.animations
    for (i in animations.indices) {
        val a = animations[i]
        if (a.label == FADE_LABEL) return ((a.value as? Float) ?: 1f).coerceIn(0f, 1f)
    }
    return 1f
}

/** The label of the animation Compose runs for an enter's fadeIn and an exit's fadeOut (EnterExitTransition.kt). */
internal const val FADE_LABEL = "Built-in alpha"

/** Tells [shapes] where the map's surface lies, so the shapes are written relative to it. */
fun Modifier.chromeSurface(shapes: ChromeShapes?): Modifier =
    if (shapes == null) this else onGloballyPositioned { shapes.surfaceAt(it.positionInRoot().x, it.positionInRoot().y) }

/**
 * Makes this composable's box, rounded as [shape], one of the chrome's keep-out shapes in [shapes] while it is
 * composed: frosted under it by a Gaussian of σ [blur] saturated by [saturation] when [frosted], else only kept clear.
 * Each layout that places it publishes its box with its frame's time, and each draw the alpha the fades over it give it
 * ([LocalChromeAlpha]). A shape with corners of several radii takes the smallest, so the glass covers it.
 */
fun Modifier.chromeShape(shapes: ChromeShapes?, shape: Shape, blur: Dp, saturation: Float, frosted: Boolean = true): Modifier =
    if (shapes == null) this else composed {
        val held = remember { IntArray(1) { -1 } }
        val view = LocalView.current
        DisposableEffect(shapes, view) {
            held[0] = shapes.add()
            shapes.watch(view)
            onDispose {
                shapes.remove(held[0])
                held[0] = -1
                shapes.unwatch(view)
            }
        }
        val density = LocalDensity.current
        val direction = LocalLayoutDirection.current
        val alpha = LocalChromeAlpha.current
        onGloballyPositioned { c ->
            val slot = held[0]
            if (slot < 0) return@onGloballyPositioned
            // The fade's alpha as this frame lays it out, before the shape is placed: a shape that comes in with a
            // fade is never written without it.
            shapes.fade(slot, alpha())
            val p = c.positionInRoot()
            val size = c.size.toSize()
            val radius = when (val o = shape.createOutline(size, direction, density)) {
                is Outline.Rounded -> o.roundRect.let {
                    minOf(it.topLeftCornerRadius.x, it.topLeftCornerRadius.y, it.topRightCornerRadius.x, it.topRightCornerRadius.y,
                        it.bottomLeftCornerRadius.x, it.bottomLeftCornerRadius.y, it.bottomRightCornerRadius.x, it.bottomRightCornerRadius.y)
                }
                else -> 0f
            }
            val changed = shapes.place(
                slot, p.x, p.y, p.x + size.width, p.y + size.height, radius, with(density) { blur.toPx() }, saturation,
                frosted, shapes.frameNanos(),
            )
            if (changed) {
                shapes.moved()
                shapes.logSoon()
            }
        }.drawWithContent {
            // Read in the draw: a fade moving draws this again (no layout runs for it), and the alpha goes with each frame.
            val slot = held[0]
            if (slot >= 0) shapes.fade(slot, alpha())
            drawContent()
        }
    }

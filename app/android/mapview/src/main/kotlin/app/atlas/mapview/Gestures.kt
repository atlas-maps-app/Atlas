package app.atlas.mapview

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.sqrt

/** What the map's gestures do; [GestureMachine] calls it. Coordinates are view pixels. */
interface GestureListener {
    /** A finger went down on a still map: stop flings and animations. */
    fun onGestureStart() {}

    /** One finger dragged the ground from ([x0], [y0]) to ([x1], [y1]). */
    fun onPan(x0: Float, y0: Float, x1: Float, y1: Float) {}

    /** Two fingers: their centre moved from ([x0], [y0]) to ([x1], [y1]), their span scaled, the pair turned clockwise. */
    fun onTransform(x0: Float, y0: Float, x1: Float, y1: Float, scale: Float, rotationDeg: Float) {}

    /** Two fingers dragged up or down together by [dy] pixels (up is negative): tilt. */
    fun onTilt(dy: Float) {}

    /** A double tap (zoom in at the tap) or a two-finger tap (zoom out around their centre). */
    fun onZoomStep(x: Float, y: Float, zoomIn: Boolean) {}

    /** Tap, then drag: [dy] pixels down since the last call, zooming around the tap ([x], [y]). */
    fun onDragZoom(x: Float, y: Float, dy: Float) {}

    /** The last finger left a pan at this velocity (pixels per second). */
    fun onFling(vx: Float, vy: Float) {}

    fun onTap(x: Float, y: Float) {}

    fun onLongPress(x: Float, y: Float) {}

    /** Two fingers rest (or move while measuring): the tape runs between them. */
    fun onMeasure(x0: Float, y0: Float, x1: Float, y1: Float) {}

    fun onMeasureEnd() {}

    /** Every finger is up; [rotated] when the gesture turned the map. */
    fun onGestureEnd(rotated: Boolean) {}
}

/** Distances in pixels and times in milliseconds that tell gestures apart. */
data class GestureConfig(
    val touchSlop: Float,
    val doubleTapSlop: Float,
    val doubleTapTimeout: Long = 280,
    val longPressTimeout: Long = 500,
    val twoFingerTapTimeout: Long = 250,
    /** How long two still fingers rest before the measuring tape shows. */
    val measureDelay: Long = 400,
    /** Degrees the fingers must turn before the map rotates. */
    val rotateThreshold: Float = 14f,
    val minFlingVelocity: Float,
    val maxFlingVelocity: Float,
    val measureEnabled: Boolean = true,
) {
    companion object {
        /** The usual values for a screen of [density] pixels per dp. */
        fun forDensity(density: Float, measure: Boolean = true) = GestureConfig(
            touchSlop = 8f * density, doubleTapSlop = 64f * density,
            minFlingVelocity = 60f * density, maxFlingVelocity = 8000f * density, measureEnabled = measure,
        )
    }
}

/**
 * The map's gestures as a state machine fed with pointer snapshots: one-finger pan and fling, two-finger pinch, rotate
 * (past a threshold) and tilt (both fingers dragged vertically, side by side), double tap, two-finger tap, tap-and-drag
 * zoom, long press, tap, and two resting fingers for the measuring tape.
 *
 * Feed it with [down], [move] and [up] (one call per pointer change, then [frame] to apply the moves of an event), and
 * call [timeout] at [deadline]. Not thread-safe: one view, one thread.
 */
class GestureMachine(var config: GestureConfig, private val listener: GestureListener) {
    private enum class State { IDLE, ONE_PENDING, PAN, TAP_WAIT, SECOND_DOWN, DRAG_ZOOM, LONG_PRESSED, TWO_PENDING, TRANSFORM, TILT, MEASURE, DONE }

    private var state = State.IDLE

    // Up to two tracked pointers: ids, where they went down, where they were last applied, where they are now.
    private val ids = LongArray(2)
    private val startX = FloatArray(2)
    private val startY = FloatArray(2)
    private val lastX = FloatArray(2)
    private val lastY = FloatArray(2)
    private val curX = FloatArray(2)
    private val curY = FloatArray(2)
    private var count = 0
    private var downTime = 0L
    private var secondDownTime = 0L
    private var tapX = 0f
    private var tapY = 0f
    private var tapUpTime = 0L
    private var rotated = false
    private var rotationUnlocked = false
    private var rotationSum = 0f
    /** The second finger came while the first was still a plain touch: lifting both quickly is a two-finger tap. */
    private var twoFingerTap = false
    private val velocity = VelocityTracker()

    /** When [timeout] must be called next, or null. */
    var deadline: Long? = null
        private set

    val isIdle: Boolean get() = state == State.IDLE
    val isMeasuring: Boolean get() = state == State.MEASURE

    fun down(id: Long, x: Float, y: Float, time: Long) {
        if (count == 2) return // a third finger is ignored
        val i = count++
        ids[i] = id
        startX[i] = x; startY[i] = y; lastX[i] = x; lastY[i] = y; curX[i] = x; curY[i] = y
        when (state) {
            State.IDLE -> {
                listener.onGestureStart()
                rotated = false
                downTime = time
                velocity.reset()
                velocity.add(time, x, y)
                state = State.ONE_PENDING
                deadline = time + config.longPressTimeout
            }
            State.TAP_WAIT -> {
                listener.onGestureStart()
                rotated = false
                downTime = time
                if (hypot(x - tapX, y - tapY) <= config.doubleTapSlop && time - tapUpTime <= config.doubleTapTimeout) {
                    state = State.SECOND_DOWN
                    deadline = null
                } else {
                    // Too far or too late for a double tap: the first was a tap, this starts afresh.
                    listener.onTap(tapX, tapY)
                    velocity.reset()
                    velocity.add(time, x, y)
                    state = State.ONE_PENDING
                    deadline = time + config.longPressTimeout
                }
            }
            State.ONE_PENDING, State.PAN, State.SECOND_DOWN, State.DRAG_ZOOM, State.LONG_PRESSED -> {
                if (state == State.DRAG_ZOOM || state == State.LONG_PRESSED) {
                    // Those own the gesture to its end.
                    count--
                    return
                }
                secondDownTime = time
                // A finger brushing the screen during a pan or a double tap's drag is not a two-finger tap.
                twoFingerTap = state == State.ONE_PENDING
                rotationUnlocked = false
                rotationSum = 0f
                for (k in 0 until 2) { startX[k] = curX[k]; startY[k] = curY[k]; lastX[k] = curX[k]; lastY[k] = curY[k] }
                state = State.TWO_PENDING
                deadline = if (config.measureEnabled) time + config.measureDelay else null
            }
            else -> {}
        }
    }

    /** Pointer [id] is now at ([x], [y]); apply with [frame]. */
    fun move(id: Long, x: Float, y: Float, time: Long) {
        val i = index(id)
        if (i < 0) return
        curX[i] = x
        curY[i] = y
        if (i == 0 && count == 1) velocity.add(time, x, y)
    }

    /** Applies the moves since the last frame. */
    fun frame(time: Long) {
        when (state) {
            State.ONE_PENDING -> {
                if (hypot(curX[0] - startX[0], curY[0] - startY[0]) > config.touchSlop) {
                    state = State.PAN
                    deadline = null
                    pan()
                }
            }
            State.PAN -> pan()
            State.SECOND_DOWN -> {
                if (abs(curY[0] - startY[0]) > config.touchSlop) {
                    state = State.DRAG_ZOOM
                    lastY[0] = startY[0]
                    dragZoom()
                }
            }
            State.DRAG_ZOOM -> dragZoom()
            State.TWO_PENDING -> classifyTwo()
            State.TRANSFORM -> transform()
            State.TILT -> tilt()
            State.MEASURE -> listener.onMeasure(curX[0], curY[0], curX[1], curY[1])
            else -> {}
        }
    }

    fun up(id: Long, x: Float, y: Float, time: Long) {
        val i = index(id)
        if (i < 0) return
        curX[i] = x
        curY[i] = y
        when (state) {
            State.ONE_PENDING -> {
                if (time - downTime < config.longPressTimeout) {
                    tapX = x; tapY = y; tapUpTime = time
                    state = State.TAP_WAIT
                    deadline = time + config.doubleTapTimeout
                    remove(i)
                    return
                }
            }
            State.PAN -> {
                velocity.add(time, x, y)
                if (count == 1) {
                    val vx = velocity.vx(); val vy = velocity.vy()
                    val v = hypot(vx, vy)
                    if (v >= config.minFlingVelocity) {
                        val k = if (v > config.maxFlingVelocity) config.maxFlingVelocity / v else 1f
                        listener.onFling(vx * k, vy * k)
                    }
                }
            }
            State.SECOND_DOWN -> {
                listener.onZoomStep(tapX, tapY, true)
                state = State.DONE
            }
            State.TWO_PENDING -> {
                deadline = null
                if (twoFingerTap && time - secondDownTime <= config.twoFingerTapTimeout) {
                    listener.onZoomStep((curX[0] + curX[1]) / 2, (curY[0] + curY[1]) / 2, false)
                    state = State.DONE
                } else {
                    // A slow lift, or a finger that only brushed a pan: the finger left behind pans on.
                    state = State.TRANSFORM
                    up(id, x, y, time)
                    return
                }
            }
            State.TRANSFORM, State.TILT -> {
                // One finger left: the other pans on, from where it is.
                remove(i)
                if (count == 1) {
                    state = State.PAN
                    lastX[0] = curX[0]; lastY[0] = curY[0]
                    velocity.reset()
                    velocity.add(time, curX[0], curY[0])
                }
                return
            }
            State.MEASURE -> {
                listener.onMeasureEnd()
                state = State.DONE
            }
            else -> {}
        }
        remove(i)
        if (count == 0) end()
        else if (state != State.DONE && state != State.PAN) state = State.DONE
    }

    /** Every pointer went away without an up (the gesture was taken over). */
    fun cancel() {
        if (state == State.MEASURE) listener.onMeasureEnd()
        val wasActive = state != State.IDLE && state != State.TAP_WAIT
        count = 0
        state = State.IDLE
        deadline = null
        if (wasActive) listener.onGestureEnd(rotated)
    }

    /** The clock reached [deadline]. */
    fun timeout(time: Long) {
        val d = deadline ?: return
        if (time < d) return
        deadline = null
        when (state) {
            State.ONE_PENDING -> {
                listener.onLongPress(curX[0], curY[0])
                state = State.LONG_PRESSED
            }
            State.TAP_WAIT -> {
                state = State.IDLE
                listener.onTap(tapX, tapY)
                listener.onGestureEnd(false)
            }
            State.TWO_PENDING -> {
                if (config.measureEnabled && count == 2) {
                    state = State.MEASURE
                    listener.onMeasure(curX[0], curY[0], curX[1], curY[1])
                }
            }
            else -> {}
        }
    }

    private fun end() {
        deadline = null
        state = State.IDLE
        listener.onGestureEnd(rotated)
    }

    private fun pan() {
        if (curX[0] == lastX[0] && curY[0] == lastY[0]) return
        listener.onPan(lastX[0], lastY[0], curX[0], curY[0])
        lastX[0] = curX[0]; lastY[0] = curY[0]
    }

    private fun dragZoom() {
        val dy = curY[0] - lastY[0]
        if (dy == 0f) return
        listener.onDragZoom(tapX, tapY, dy)
        lastY[0] = curY[0]
    }

    /** Two fingers past the slop: tilt when both drag vertically side by side, else pinch, pan and (later) rotate. */
    private fun classifyTwo() {
        val d0x = curX[0] - startX[0]; val d0y = curY[0] - startY[0]
        val d1x = curX[1] - startX[1]; val d1y = curY[1] - startY[1]
        val m0 = hypot(d0x, d0y)
        val m1 = hypot(d1x, d1y)
        if (m0 <= config.touchSlop && m1 <= config.touchSlop) return
        deadline = null
        val spanStart = hypot(startX[1] - startX[0], startY[1] - startY[0])
        val spanNow = hypot(curX[1] - curX[0], curY[1] - curY[0])
        val sideBySide = abs(startY[1] - startY[0]) < 0.6f * abs(startX[1] - startX[0]) + config.touchSlop
        val vertical = abs(d0y) > 2f * abs(d0x) && abs(d1y) > 2f * abs(d1x)
        val together = d0y * d1y > 0f && abs(d0y - d1y) < 0.5f * maxOf(abs(d0y), abs(d1y)) + config.touchSlop
        if (sideBySide && vertical && together && abs(spanNow - spanStart) < 2f * config.touchSlop) {
            state = State.TILT
            tilt()
            return
        }
        state = State.TRANSFORM
        transform()
    }

    private fun transform() {
        val ox = (lastX[0] + lastX[1]) / 2; val oy = (lastY[0] + lastY[1]) / 2
        val nx = (curX[0] + curX[1]) / 2; val ny = (curY[0] + curY[1]) / 2
        val spanOld = hypot(lastX[1] - lastX[0], lastY[1] - lastY[0])
        val spanNew = hypot(curX[1] - curX[0], curY[1] - curY[0])
        val scale = if (spanOld > 1f && spanNew > 1f) spanNew / spanOld else 1f
        val aOld = atan2(lastY[1] - lastY[0], lastX[1] - lastX[0])
        val aNew = atan2(curY[1] - curY[0], curX[1] - curX[0])
        var da = Math.toDegrees((aNew - aOld).toDouble()).toFloat()
        if (da > 180f) da -= 360f
        if (da < -180f) da += 360f
        var rotation = 0f
        if (rotationUnlocked) {
            rotation = da
        } else {
            rotationSum += da
            if (abs(rotationSum) > config.rotateThreshold) {
                // Unlocked: turn from here on, without jumping by the threshold already turned.
                rotationUnlocked = true
            }
        }
        if (rotation != 0f) rotated = true
        listener.onTransform(ox, oy, nx, ny, scale, rotation)
        for (k in 0 until 2) { lastX[k] = curX[k]; lastY[k] = curY[k] }
    }

    private fun tilt() {
        val dy = ((curY[0] - lastY[0]) + (curY[1] - lastY[1])) / 2
        if (dy != 0f) listener.onTilt(dy)
        for (k in 0 until 2) { lastX[k] = curX[k]; lastY[k] = curY[k] }
    }

    private fun index(id: Long): Int {
        for (i in 0 until count) if (ids[i] == id) return i
        return -1
    }

    private fun remove(i: Int) {
        if (i == 0 && count == 2) {
            ids[0] = ids[1]; startX[0] = startX[1]; startY[0] = startY[1]
            lastX[0] = lastX[1]; lastY[0] = lastY[1]; curX[0] = curX[1]; curY[0] = curY[1]
        }
        count--
    }
}

/** Velocity of one pointer from its last 100 ms of samples, by least squares, without allocating. */
class VelocityTracker {
    private val t = LongArray(SIZE)
    private val x = FloatArray(SIZE)
    private val y = FloatArray(SIZE)
    private var n = 0
    private var head = 0

    fun reset() { n = 0; head = 0 }

    fun add(time: Long, px: Float, py: Float) {
        t[head] = time; x[head] = px; y[head] = py
        head = (head + 1) % SIZE
        if (n < SIZE) n++
    }

    /** Pixels per second along x. */
    fun vx(): Float = slope(x)

    fun vy(): Float = slope(y)

    private fun slope(v: FloatArray): Float {
        if (n < 2) return 0f
        val last = (head - 1 + SIZE) % SIZE
        val tEnd = t[last]
        var sw = 0.0; var st = 0.0; var sv = 0.0; var stt = 0.0; var stv = 0.0
        var used = 0
        for (k in 0 until n) {
            val i = (last - k + SIZE) % SIZE
            val age = tEnd - t[i]
            if (age > WINDOW_MS) break
            // A pause before lifting the finger means no fling: samples older than 40 ms past a pause are dropped.
            if (k > 0 && t[(i + 1) % SIZE] - t[i] > PAUSE_MS) break
            val tt = -age / 1000.0
            sw += 1.0; st += tt; sv += v[i]; stt += tt * tt; stv += tt * v[i]
            used++
        }
        if (used < 2) return 0f
        val den = sw * stt - st * st
        if (abs(den) < 1e-12) return 0f
        return ((sw * stv - st * sv) / den).toFloat()
    }

    private companion object {
        const val SIZE = 20
        const val WINDOW_MS = 100L
        const val PAUSE_MS = 40L
    }
}

/** The distance between two points. */
internal fun dist(ax: Float, ay: Float, bx: Float, by: Float): Float {
    val dx = bx - ax
    val dy = by - ay
    return sqrt(dx * dx + dy * dy)
}

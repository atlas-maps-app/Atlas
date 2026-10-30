package app.atlas.mapview

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/** What the map's gestures do; [GestureMachine] calls it. Coordinates are view pixels. */
interface GestureListener {
    /** A finger went down on a still map: stop flings and animations. */
    fun onGestureStart() {}

    /** One finger dragged the ground from ([x0], [y0]) to ([x1], [y1]). */
    fun onPan(x0: Float, y0: Float, x1: Float, y1: Float) {}

    /** Two fingers: their centre moved from ([x0], [y0]) to ([x1], [y1]), their span scaled, the pair turned clockwise. */
    fun onTransform(x0: Float, y0: Float, x1: Float, y1: Float, scale: Float, rotationDeg: Float) {}

    /**
     * Fingers began to tilt the map: two dragged up or down together, or three on it ([fingers]); where they are,
     * [GestureMachine.fingers] says. The first [onTilt] carries all their move since the second landed.
     */
    fun onTiltStart(fingers: Int) {}

    /** The tilting fingers moved [dy] pixels down on average (up is negative). */
    fun onTilt(dy: Float) {}

    /** The tilt is over: fewer than two fingers are left, or [cancelled] (the system took the touches away). */
    fun onTiltEnd(cancelled: Boolean) {}

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
 * (past a threshold) and tilt (both fingers dragged up or down together, side by side), three-finger tilt, double tap,
 * two-finger tap, tap-and-drag zoom, long press, tap, and two resting fingers for the measuring tape.
 *
 * Two fingers past the touch slop are not judged at once: real fingers start a few pixels apart in time and direction,
 * so until their moves say tilt (the pair's common move up or down, each finger near it and differing only along it)
 * or pinch, pan and turn (the pair spreading, twisting, moving off the vertical, or one finger moving on with the other
 * still) the map waits, then takes the whole move since the second finger landed. Three fingers always tilt, from their
 * first pixel: nothing else takes three. Two fingers that rested into the tape and then drag up or down together, well
 * past the slop, tilt too. The finger left when the others lift pans on once it moves past the slop.
 *
 * Feed it with [down], [move] and [up] (one call per pointer change, then [frame] to apply the moves of an event), and
 * call [timeout] at [deadline]; [cancel] when the touches were taken away. Not thread-safe: one view, one thread.
 */
class GestureMachine(var config: GestureConfig, private val listener: GestureListener) {
    private enum class State { IDLE, ONE_PENDING, PAN, TAP_WAIT, SECOND_DOWN, DRAG_ZOOM, LONG_PRESSED, TWO_PENDING, TRANSFORM, TILT, MEASURE, DONE }

    /** What two fingers past the slop do, as far as their moves tell yet. */
    private enum class Two { TILT, TRANSFORM, UNSURE }

    private var state = State.IDLE

    // Up to three tracked pointers: ids, where they went down, where they were last applied, where they are now.
    private val ids = LongArray(MAX_FINGERS)
    private val startX = FloatArray(MAX_FINGERS)
    private val startY = FloatArray(MAX_FINGERS)
    private val lastX = FloatArray(MAX_FINGERS)
    private val lastY = FloatArray(MAX_FINGERS)
    private val curX = FloatArray(MAX_FINGERS)
    private val curY = FloatArray(MAX_FINGERS)
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
    /** Two fingers went past the slop (at [twoMovedAt]) and have not yet said what they do: no tape, no two-finger tap. */
    private var twoMoved = false
    private var twoMovedAt = 0L

    /**
     * Where the finger left of two (or three) was when it was left to pan alone, until it moves past the slop from there;
     * NaN for a pan begun by one finger, or once it has.
     */
    private var leftX = Float.NaN
    private var leftY = 0f
    private val velocity = VelocityTracker()

    /** When [timeout] must be called next, or null. */
    var deadline: Long? = null
        private set

    val isIdle: Boolean get() = state == State.IDLE
    val isMeasuring: Boolean get() = state == State.MEASURE
    val isTilting: Boolean get() = state == State.TILT

    fun down(id: Long, x: Float, y: Float, time: Long) {
        if (count == MAX_FINGERS) return // a fourth finger is ignored
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
                twoMoved = false
                rotationUnlocked = false
                rotationSum = 0f
                for (k in 0 until 2) { startX[k] = curX[k]; startY[k] = curY[k]; lastX[k] = curX[k]; lastY[k] = curY[k] }
                state = State.TWO_PENDING
                deadline = if (config.measureEnabled) time + config.measureDelay else null
            }
            State.TWO_PENDING, State.TRANSFORM, State.MEASURE -> {
                // A third finger: whatever the two were doing, the three tilt, from their first pixel on.
                if (state == State.MEASURE) listener.onMeasureEnd()
                deadline = null
                startTilt()
            }
            // A third finger joins the two tilting: it tilts with them from where it landed.
            State.TILT -> rebase()
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
                    leftX = Float.NaN
                    deadline = null
                    pan()
                }
            }
            State.PAN -> {
                // A finger left of two pans on once it has moved past the slop on its own, then by all of it: its jitter
                // after a pinch or a tilt moves no map (near the horizon of a steep view, a pixel is metres).
                if (!leftX.isNaN()) {
                    if (hypot(curX[0] - leftX, curY[0] - leftY) <= config.touchSlop) return
                    leftX = Float.NaN
                }
                pan()
            }
            State.SECOND_DOWN -> {
                if (abs(curY[0] - startY[0]) > config.touchSlop) {
                    state = State.DRAG_ZOOM
                    lastY[0] = startY[0]
                    dragZoom()
                }
            }
            State.DRAG_ZOOM -> dragZoom()
            State.TWO_PENDING -> classifyTwo(time)
            State.TRANSFORM -> transform()
            State.TILT -> tilt()
            State.MEASURE -> measure(time)
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
                // A finger left of two flings only once it has moved past the slop on its own: a few jittering samples
                // after a pinch or a tilt are no swipe.
                val still = !leftX.isNaN() && hypot(x - leftX, y - leftY) <= config.touchSlop
                if (count == 1 && !still) {
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
                if (!twoMoved && twoFingerTap && time - secondDownTime <= config.twoFingerTapTimeout) {
                    listener.onZoomStep((curX[0] + curX[1]) / 2, (curY[0] + curY[1]) / 2, false)
                    state = State.DONE
                } else {
                    // A slow lift, a finger that only brushed a pan, or two that moved without saying what: the finger
                    // left behind pans on.
                    state = State.TRANSFORM
                    up(id, x, y, time)
                    return
                }
            }
            State.TRANSFORM, State.TILT -> {
                remove(i)
                if (state == State.TILT && count >= 2) {
                    // One of three left: the other two tilt on, from where they are.
                    rebase()
                    return
                }
                // One finger left: the other pans on from where it is, once it moves past the slop.
                if (count == 1) {
                    if (state == State.TILT) listener.onTiltEnd(cancelled = false)
                    state = State.PAN
                    lastX[0] = curX[0]; lastY[0] = curY[0]
                    leftX = curX[0]; leftY = curY[0]
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

    /**
     * Every pointer went away without an up: the gesture was taken over (the system's own gesture, an OEM's three-finger
     * screenshot, or the view leaving). A tilt under way ends cancelled.
     */
    fun cancel() {
        if (state == State.MEASURE) listener.onMeasureEnd()
        if (state == State.TILT) listener.onTiltEnd(cancelled = true)
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

    /**
     * Two fingers: past the slop, tilt once their moves say so, else pinch, pan and (later) rotate; until either is
     * clear, nothing moves, and the first move applied is the whole move since the second finger landed.
     */
    private fun classifyTwo(time: Long) {
        if (!twoMoved) {
            if (hypot(curX[0] - startX[0], curY[0] - startY[0]) <= config.touchSlop &&
                hypot(curX[1] - startX[1], curY[1] - startY[1]) <= config.touchSlop
            ) {
                return
            }
            // Moving fingers are no tape and no two-finger tap.
            twoMoved = true
            twoMovedAt = time
            deadline = null
        }
        when (two(time)) {
            Two.TILT -> {
                startTilt()
                tilt()
            }
            Two.TRANSFORM -> {
                state = State.TRANSFORM
                transform()
            }
            Two.UNSURE -> {}
        }
    }

    /**
     * What the two fingers do, from where they went down to where they are now ([time]). A tilt: the pair side by side
     * (its line within 60° of level), its common move at least [VERTICAL] times as vertical as horizontal, both fingers
     * moving the same way up or down, each within [TOGETHER] of the common move off it, and that difference along the
     * move (a finger lagging or quicker), not across it (a pinch or twist besides): within a slop, or [STEADY] of the
     * move. Pinch, pan and turn: the pair changing shape more than it moves (a twist, a pinch from both sides), its span
     * changing by a slop and [SPREAD] of the common move with the fingers moving apart along their line (a finger
     * lagging on a slanted pair changes the span too, moving across the line), a common move past the slop off the
     * vertical (or with the fingers one above the other), one finger moving [ANCHOR_MS] past the slop with the other
     * nearly still (a turn or pinch round it), or anything at all past [SURE_SLOPS] slops. Unsure otherwise: a finger
     * ahead of the other, which is about to follow.
     */
    private fun two(time: Long): Two {
        val slop = config.touchSlop
        val d0x = curX[0] - startX[0]; val d0y = curY[0] - startY[0]
        val d1x = curX[1] - startX[1]; val d1y = curY[1] - startY[1]
        val m0 = hypot(d0x, d0y)
        val m1 = hypot(d1x, d1y)
        val lead = max(m0, m1)
        val lag = min(m0, m1)
        val mx = (d0x + d1x) / 2; val my = (d0y + d1y) / 2
        val common = hypot(mx, my)
        val rx = d1x - d0x; val ry = d1y - d0y
        val own = hypot(rx, ry) / 2
        // the fingers' difference across their common move
        val across = if (common > 0f) abs(rx * my - ry * mx) / common else 2 * own
        val sx = startX[1] - startX[0]; val sy = startY[1] - startY[0]
        val spread = abs(hypot(curX[1] - curX[0], curY[1] - curY[0]) - hypot(sx, sy))
        val sideBySide = abs(sy) <= SIDE_BY_SIDE * abs(sx) + slop
        val vertical = abs(my) >= VERTICAL * abs(mx)
        val together = d0y * d1y > 0f && own <= TOGETHER * common
        val steady = across <= max(slop, STEADY * common)
        if (sideBySide && vertical && together && steady) return Two.TILT
        // a finger lagging on a slanted pair changes the span as a pinch does: a pinch moves the fingers along their line
        // more than across it, and is none while both go the same way, the later one already on its way, and differ
        // only along their move
        val span0 = hypot(sx, sy)
        val alongLine = if (span0 > 0f) abs(rx * sx + ry * sy) / span0 else 2 * own
        val acrossLine = if (span0 > 0f) abs(rx * sy - ry * sx) / span0 else 0f
        val lagging = d0y * d1y > 0f && min(abs(d0y), abs(d1y)) >= slop / 4 && steady
        val pinch = spread > slop + SPREAD * common && alongLine >= acrossLine && !lagging
        if (own > common + slop / 2 || pinch || (common > slop && (!vertical || !sideBySide))) return Two.TRANSFORM
        if ((twoMoved && time - twoMovedAt >= ANCHOR_MS && lag < lead / 4) || lead > SURE_SLOPS * slop) return Two.TRANSFORM
        return Two.UNSURE
    }

    /** The tape: both fingers dragged up or down together, [MEASURE_TILT_SLOPS] slops from where they rested, tilt. */
    private fun measure(time: Long) {
        val my = ((curY[0] - startY[0]) + (curY[1] - startY[1])) / 2
        if (abs(my) >= MEASURE_TILT_SLOPS * config.touchSlop && two(time) == Two.TILT) {
            listener.onMeasureEnd()
            startTilt()
            tilt()
            return
        }
        listener.onMeasure(curX[0], curY[0], curX[1], curY[1])
    }

    /** The fingers down tilt from now on. */
    private fun startTilt() {
        state = State.TILT
        listener.onTiltStart(count)
        // Three fingers tilt from where they are; two that said tilt take their whole move since they landed.
        if (count > 2) rebase()
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
                // Unlocked: turn by what the fingers turned past the threshold, and from here on with them, without
                // jumping by the threshold itself (fingers that waited to say what they do bring all of it past at once).
                rotationUnlocked = true
                rotation = if (rotationSum > 0f) rotationSum - config.rotateThreshold else rotationSum + config.rotateThreshold
            }
        }
        if (rotation != 0f) rotated = true
        listener.onTransform(ox, oy, nx, ny, scale, rotation)
        for (k in 0 until 2) { lastX[k] = curX[k]; lastY[k] = curY[k] }
    }

    /** The tilting fingers' mean move down since the last frame. */
    private fun tilt() {
        var dy = 0f
        for (k in 0 until count) dy += curY[k] - lastY[k]
        dy /= count
        if (dy != 0f) listener.onTilt(dy)
        rebase()
    }

    /** The fingers down now, x and y in turn into [out] (six floats hold three); how many there are. */
    fun fingers(out: FloatArray): Int {
        for (k in 0 until count) {
            out[2 * k] = curX[k]
            out[2 * k + 1] = curY[k]
        }
        return count
    }

    /** Every finger applied where it is now. */
    private fun rebase() {
        for (k in 0 until count) { lastX[k] = curX[k]; lastY[k] = curY[k] }
    }

    private fun index(id: Long): Int {
        for (i in 0 until count) if (ids[i] == id) return i
        return -1
    }

    private fun remove(i: Int) {
        for (k in i until count - 1) {
            ids[k] = ids[k + 1]; startX[k] = startX[k + 1]; startY[k] = startY[k + 1]
            lastX[k] = lastX[k + 1]; lastY[k] = lastY[k + 1]; curX[k] = curX[k + 1]; curY[k] = curY[k + 1]
        }
        count--
    }

    private companion object {
        /** Fingers tracked at once: a fourth is ignored. */
        const val MAX_FINGERS = 3

        /** The pair's line may stand this steep (tan 60°) and still be side by side, for a tilt. */
        const val SIDE_BY_SIDE = 1.73f

        /** A tilt's common move is at least this many times as vertical as horizontal (within 34° of up or down). */
        const val VERTICAL = 1.5f

        /** Each tilting finger's move off the pair's common move is at most this share of it. */
        const val TOGETHER = 0.5f

        /** A tilting pair's fingers differ across their common move by at most a slop, or this share of it. */
        const val STEADY = 0.5f

        /** A span changing by a slop and this share of the common move is a pinch. */
        const val SPREAD = 0.5f

        /** One finger moving this long past the slop with the other nearly still is a pinch or turn round it, ms. */
        const val ANCHOR_MS = 80L

        /** Two fingers this many slops on are sure of what they do. */
        const val SURE_SLOPS = 12f

        /** Fingers that rested into the tape tilt once they have dragged this many slops up or down together. */
        const val MEASURE_TILT_SLOPS = 2f
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

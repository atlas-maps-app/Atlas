package app.atlas.mapview

import java.util.Random
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/**
 * Touch streams as a phone's screen reports them, for the gesture tests: fingers land tens of milliseconds apart and
 * settle a few pixels, rest a moment, then move each with its own lag, speed and drift, the pair turning or spreading a
 * little, every sample jittered by about a pixel (120 Hz), and lift one after the other. Each generator draws its
 * figures from the seeded [Random] it is given, so a trace is the same at every run.
 */
internal object TouchTraces {
    enum class Kind { DOWN, MOVE, UP }

    /** What pointer [id] did in an event: went down, moved to or lifted at ([x], [y]). */
    data class Touch(val kind: Kind, val id: Long, val x: Float, val y: Float)

    /** One event (a MotionEvent): every pointer down then, at [time] ms. */
    data class Event(val time: Long, val touches: List<Touch>)

    /** A finger: down at [down] ms, up at [up], at [at] (time to position) between. */
    class Finger(val id: Long, val down: Long, val up: Long, val at: (Long) -> Pair<Float, Float>)

    /** A trace and what it was made as: the figures the test checks it against. */
    class Trace(val events: List<Event>, val what: String, val moveStart: Long, val moveEnd: Long, val fingers: List<Finger>) {
        /** How far the fingers' mean went down (pixels, up negative) from [from] ms to [to] ms, without the jitter. */
        fun meanDy(from: Long, to: Long): Float = fingers.map { it.at(to).second - it.at(from).second }.average().toFloat()

        override fun toString() = what
    }

    /** The events of [fingers]: one every [step] ms while any is down, and one at each down and up; jitter of [noise] px. */
    fun events(fingers: List<Finger>, rng: Random, step: Long = 8, noise: Float = 0.8f): List<Event> {
        val first = fingers.minOf { it.down }
        val last = fingers.maxOf { it.up }
        val times = sortedSetOf<Long>()
        var t = first
        while (t <= last) { times += t; t += step }
        for (f in fingers) { times += f.down; times += f.up }
        val out = ArrayList<Event>()
        for (time in times) {
            val touches = ArrayList<Touch>()
            for (f in fingers) {
                if (time < f.down || time > f.up) continue
                val (x, y) = f.at(time)
                val nx = x + (rng.nextGaussian() * noise).toFloat()
                val ny = y + (rng.nextGaussian() * noise).toFloat()
                val kind = when (time) {
                    f.down -> Kind.DOWN
                    f.up -> Kind.UP
                    else -> Kind.MOVE
                }
                touches += Touch(kind, f.id, nx, ny)
            }
            if (touches.isNotEmpty()) out += Event(time, touches)
        }
        return out
    }

    /** Feeds [trace] to [g] as AtlasMap's pointer loop does, with its timeouts between events; returns the last time. */
    fun play(g: GestureMachine, trace: Trace, until: Long = Long.MAX_VALUE): Long {
        var last = 0L
        for (e in trace.events) {
            if (e.time > until) break
            while (true) {
                val d = g.deadline ?: break
                if (d > e.time) break
                g.timeout(d)
            }
            for (c in e.touches) {
                when (c.kind) {
                    Kind.DOWN -> g.down(c.id, c.x, c.y, e.time)
                    Kind.MOVE -> g.move(c.id, c.x, c.y, e.time)
                    Kind.UP -> g.up(c.id, c.x, c.y, e.time)
                }
            }
            g.frame(e.time)
            last = e.time
        }
        return last
    }

    private fun Random.between(a: Double, b: Double) = a + (b - a) * nextDouble()

    private fun Random.between(a: Long, b: Long) = a + (nextDouble() * (b - a)).toLong()

    private fun Random.sign() = if (nextBoolean()) 1.0 else -1.0

    /** 0 to 1 as a finger speeds up and slows down again. */
    private fun ease(u: Double): Double {
        val c = u.coerceIn(0.0, 1.0)
        return c * c * (3 - 2 * c)
    }

    /** A finger's landing: a few pixels of settling over its first 40 ms. */
    private class Settle(rng: Random) {
        private val a = rng.nextDouble() * 2 * PI
        private val r = rng.between(0.5, 4.0)
        fun dx(t: Long, down: Long) = cos(a) * r * ((t - down) / 40.0).coerceIn(0.0, 1.0)
        fun dy(t: Long, down: Long) = sin(a) * r * ((t - down) / 40.0).coerceIn(0.0, 1.0)
    }

    /** A slow sideways wobble of a moving finger, a few pixels. */
    private class Wobble(rng: Random, amp: Double = 3.0) {
        private val a = rng.between(0.5, amp)
        private val w = rng.between(2.0, 6.0)
        private val p = rng.nextDouble() * 2 * PI
        fun at(t: Long) = a * sin(w * t / 1000.0 + p)
    }

    /** Where two (or three) fingers stand at the start: a pair [spacing] apart on a line [angle] degrees from level. */
    private fun pair(cx: Double, cy: Double, spacing: Double, angle: Double): DoubleArray {
        val a = Math.toRadians(angle)
        val hx = cos(a) * spacing / 2
        val hy = -sin(a) * spacing / 2
        return doubleArrayOf(cx - hx, cy - hy, cx + hx, cy + hy)
    }

    /**
     * Two fingers of a hand dragged up (or down) together to tilt, as people do it: placed 180 to 420 px apart on a line
     * up to [maxAngle]° from level, the second 10 to 90 ms after the first, resting [rest] ms (40 to 250 by default),
     * then moved 180 to 600 px within 22° of straight up or down over 250 to 800 ms, one finger up to [maxLag] ms behind
     * the other and 10 % faster or slower, the pair spreading or closing by up to 8 %.
     */
    fun twoFingerTilt(rng: Random, w: Float, h: Float, up: Boolean, rest: Long? = null, maxAngle: Double = 40.0, maxLag: Long = 45): Trace {
        val spacing = rng.between(180.0, 420.0)
        val angle = rng.between(-maxAngle, maxAngle)
        val cx = rng.between(0.35, 0.65) * w
        val cy = (if (up) rng.between(0.5, 0.72) else rng.between(0.28, 0.5)) * h
        val p = pair(cx, cy, spacing, angle)
        val down0 = 0L
        val down1 = rng.between(10L, 90L)
        val moveStart = down1 + (rest ?: rng.between(40L, 250L))
        val travel = rng.between(180.0, 600.0)
        val duration = rng.between(250L, 800L)
        val dev = Math.toRadians(rng.between(-22.0, 22.0))
        val ex = sin(dev)
        val ey = (if (up) -1.0 else 1.0) * cos(dev)
        val lags = if (rng.nextBoolean()) longArrayOf(0L, rng.between(0L, maxLag)) else longArrayOf(rng.between(0L, maxLag), 0L)
        val ratio = rng.between(0.9, 1.1)
        val travels = doubleArrayOf(travel, travel * ratio)
        val pinch = rng.between(-0.08, 0.08) * spacing
        val la = Math.toRadians(angle)
        val lx = cos(la)
        val ly = -sin(la)
        val moveEnd = moveStart + duration + lags.max()
        val upAt = moveEnd + rng.between(30L, 120L)
        val ups = if (rng.nextBoolean()) longArrayOf(upAt, upAt + rng.between(10L, 60L)) else longArrayOf(upAt + rng.between(10L, 60L), upAt)
        val downs = longArrayOf(down0, down1)
        val fingers = (0..1).map { k ->
            val settle = Settle(rng)
            val wobble = Wobble(rng)
            val side = if (k == 0) -0.5 else 0.5
            Finger(k.toLong() + 1, downs[k], ups[k]) { t ->
                val u = ease((t - moveStart - lags[k]).toDouble() / duration)
                val x = p[2 * k] + settle.dx(t, downs[k]) + ex * travels[k] * u + lx * pinch * side * u + (if (u > 0) wobble.at(t) else 0.0)
                val y = p[2 * k + 1] + settle.dy(t, downs[k]) + ey * travels[k] * u + ly * pinch * side * u
                x.toFloat() to y.toFloat()
            }
        }
        val what = "tilt ${if (up) "up" else "down"}: %.0f px apart at %.0f°, second finger at %d ms, rest to %d, %.0f px in %d ms %.0f° off, lags %s, ratio %.2f, pinch %.0f px"
            .format(spacing, angle, down1, moveStart, travel, duration, Math.toDegrees(dev), lags.toList(), ratio, pinch)
        return Trace(events(fingers, rng), what, moveStart, moveEnd, fingers)
    }

    /** Three fingers dragged up or down: landing within 70 ms, a finger's breadth apart, moving with lags up to 40 ms. */
    fun threeFingerTilt(rng: Random, w: Float, h: Float, up: Boolean): Trace {
        val cx = rng.between(0.35, 0.65) * w
        val cy = (if (up) rng.between(0.55, 0.72) else rng.between(0.28, 0.45)) * h
        val gap = rng.between(110.0, 190.0)
        val angle = Math.toRadians(rng.between(-25.0, 25.0))
        val downs = longArrayOf(0L, rng.between(0L, 40L), rng.between(10L, 70L))
        val moveStart = downs.max() + rng.between(30L, 200L)
        val travel = rng.between(150.0, 500.0)
        val duration = rng.between(250L, 700L)
        val dev = Math.toRadians(rng.between(-20.0, 20.0))
        val ex = sin(dev)
        val ey = (if (up) -1.0 else 1.0) * cos(dev)
        val lags = LongArray(3) { rng.between(0L, 40L) }
        val moveEnd = moveStart + duration + lags.max()
        val upAt = moveEnd + rng.between(30L, 100L)
        val fingers = (0..2).map { k ->
            val settle = Settle(rng)
            val wobble = Wobble(rng)
            // the middle finger reaches further up the screen
            val sx = cx + (k - 1) * gap * cos(angle)
            val sy = cy - (k - 1) * gap * sin(angle) - (if (k == 1) rng.between(10.0, 40.0) else 0.0)
            val ratio = rng.between(0.92, 1.08)
            Finger(k.toLong() + 1, downs[k], upAt + k * rng.between(5L, 30L)) { t ->
                val u = ease((t - moveStart - lags[k]).toDouble() / duration)
                val x = sx + settle.dx(t, downs[k]) + ex * travel * ratio * u + (if (u > 0) wobble.at(t) else 0.0)
                val y = sy + settle.dy(t, downs[k]) + ey * travel * ratio * u
                x.toFloat() to y.toFloat()
            }
        }
        val what = "three-finger tilt ${if (up) "up" else "down"}: %.0f px in %d ms %.0f° off, lags %s".format(travel, duration, Math.toDegrees(dev), lags.toList())
        return Trace(events(fingers, rng), what, moveStart, moveEnd, fingers)
    }

    /** What else two fingers do on a map, with the same landing, lags and jitter as [twoFingerTilt]. */
    enum class Other { PINCH, ANCHORED_PINCH, TWIST, ANCHORED_TURN, PAN }

    /**
     * [kind] of two-finger gesture: a pinch out or in by 1.4 to 2.5 times (both fingers, or one held still), a twist of
     * 25° to 80° (both turning round their middle, or one turning round the other held still), or a pan of 200 to 500 px
     * more than 40° from straight up or down; fingers 160 to 420 px apart on a line up to 70° from level.
     */
    fun other(rng: Random, w: Float, h: Float, kind: Other): Trace {
        val spacing = rng.between(160.0, 420.0)
        val angle = rng.between(-70.0, 70.0)
        val cx = rng.between(0.4, 0.6) * w
        val cy = rng.between(0.4, 0.6) * h
        val p = pair(cx, cy, spacing, angle)
        val downs = longArrayOf(0L, rng.between(10L, 90L))
        val moveStart = downs[1] + rng.between(40L, 250L)
        val duration = rng.between(250L, 800L)
        val lags = if (rng.nextBoolean()) longArrayOf(0L, rng.between(0L, 40L)) else longArrayOf(rng.between(0L, 40L), 0L)
        val held = rng.nextInt(2)
        val la = Math.toRadians(angle)
        val lx = cos(la)
        val ly = -sin(la)
        val factor = if (rng.nextBoolean()) rng.between(1.4, 2.5) else rng.between(0.4, 0.7)
        val turn = rng.sign() * Math.toRadians(rng.between(25.0, 80.0))
        val anchoredTurn = rng.sign() * Math.toRadians(rng.between(25.0, 60.0))
        val panDir = Math.toRadians(rng.sign() * rng.between(40.0, 140.0) + if (rng.nextBoolean()) 180.0 else 0.0)
        val panTravel = rng.between(200.0, 500.0)
        val drift = rng.nextDouble() * 2 * PI
        val driftR = rng.between(0.0, 20.0)
        val moveEnd = moveStart + duration + lags.max()
        val upAt = moveEnd + rng.between(30L, 120L)
        val ups = longArrayOf(upAt, upAt + rng.between(10L, 60L))
        val fingers = (0..1).map { k ->
            val settle = Settle(rng)
            val wobble = Wobble(rng, 2.0)
            val side = if (k == 0) -1.0 else 1.0
            val sx = p[2 * k]
            val sy = p[2 * k + 1]
            val ox = p[2 * (1 - k)]
            val oy = p[2 * (1 - k) + 1]
            Finger(k.toLong() + 1, downs[k], ups[k]) { t ->
                val u = ease((t - moveStart - lags[k]).toDouble() / duration)
                var x = sx + settle.dx(t, downs[k])
                var y = sy + settle.dy(t, downs[k])
                when (kind) {
                    Other.PINCH -> {
                        val d = (factor - 1) * spacing / 2 * u
                        x += lx * side * d + cos(drift) * driftR * u
                        y += ly * side * d + sin(drift) * driftR * u
                    }
                    Other.ANCHORED_PINCH -> if (k != held) {
                        val d = (factor - 1) * spacing * u
                        x += lx * side * d
                        y += ly * side * d
                    }
                    Other.TWIST -> {
                        val a = turn * u
                        val rx = sx - cx
                        val ry = sy - cy
                        x = cx + rx * cos(a) - ry * sin(a) + settle.dx(t, downs[k]) + cos(drift) * driftR * u
                        y = cy + rx * sin(a) + ry * cos(a) + settle.dy(t, downs[k]) + sin(drift) * driftR * u
                    }
                    Other.ANCHORED_TURN -> if (k != held) {
                        val a = anchoredTurn * u
                        val rx = sx - ox
                        val ry = sy - oy
                        x = ox + rx * cos(a) - ry * sin(a) + settle.dx(t, downs[k])
                        y = oy + rx * sin(a) + ry * cos(a) + settle.dy(t, downs[k])
                    }
                    Other.PAN -> {
                        x += sin(panDir) * panTravel * u
                        y += -cos(panDir) * panTravel * u
                    }
                }
                if (u > 0 && !(k == held && (kind == Other.ANCHORED_PINCH || kind == Other.ANCHORED_TURN))) x += wobble.at(t)
                x.toFloat() to y.toFloat()
            }
        }
        val what = when (kind) {
            Other.PINCH -> "pinch ×%.2f".format(factor)
            Other.ANCHORED_PINCH -> "pinch ×%.2f, one finger held".format(factor)
            Other.TWIST -> "twist %.0f°".format(Math.toDegrees(turn))
            Other.ANCHORED_TURN -> "turn %.0f° round a held finger".format(Math.toDegrees(anchoredTurn))
            Other.PAN -> "pan %.0f px at %.0f° from up".format(panTravel, Math.toDegrees(panDir))
        } + ": %.0f px apart at %.0f°, lags %s".format(spacing, angle, lags.toList())
        return Trace(events(fingers, rng), what, moveStart, moveEnd, fingers)
    }

    /** The turn of the line between two fingers from [a] to [b] (each x0, y0, x1, y1), degrees. */
    fun turnOf(a: FloatArray, b: FloatArray): Double {
        var d = Math.toDegrees(kotlin.math.atan2((b[3] - b[1]).toDouble(), (b[2] - b[0]).toDouble()) - kotlin.math.atan2((a[3] - a[1]).toDouble(), (a[2] - a[0]).toDouble()))
        while (d > 180) d -= 360
        while (d < -180) d += 360
        return abs(d)
    }
}

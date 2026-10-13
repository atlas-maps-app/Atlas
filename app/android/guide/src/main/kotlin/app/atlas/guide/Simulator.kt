package app.atlas.guide

import app.atlas.geo.Geo
import app.atlas.geo.LatLon
import app.atlas.model.Fix
import app.atlas.model.Route
import java.time.Instant
import java.util.SplittableRandom
import kotlin.math.atan
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.sqrt

/**
 * A wrong turn while simulating: at [at] metres along the route the traveller leaves it, turning [turn] degrees
 * (positive right), and goes on straight for [length] metres. With [rejoin] they then come back the same way and carry
 * on along the route; without it the drive ends out there (the app or test reroutes).
 */
data class Detour(val at: Double, val turn: Double = 90.0, val length: Double = 300.0, val rejoin: Boolean = false)

/**
 * Replays a [Route] as location fixes, for tests and the app's demo mode.
 *
 * Fixes come every [interval] seconds at [speed] m/s (or, when null, at the route's planned pace step by step), from
 * [start]. GPS noise is realistic rather than white: a position error with [noise] metres standard deviation on each
 * axis that drifts slowly (each fix keeps [correlation] of the last error), a reported accuracy to match, and a Doppler
 * bearing and speed whose error shrinks with speed. The same [seed] gives the same fixes.
 */
class Simulator(
    private val route: Route,
    private val speed: Double? = null,
    private val interval: Double = 1.0,
    private val noise: Double = 0.0,
    private val seed: Long = 1,
    private val start: Instant = Instant.parse("2026-06-21T10:00:00Z"),
    private val detours: List<Detour> = emptyList(),
    private val correlation: Double = 0.85,
    /** Fixes to send standing still at the end, as a phone does after arriving. */
    private val dwell: Int = 2,
) {
    init {
        require(interval > 0) { "interval must be positive" }
        require(speed == null || speed > 0) { "speed must be positive" }
        require(correlation in 0.0..0.999) { "correlation must be in 0..1" }
    }

    private val index = RouteIndex(route, speed ?: 10.0)

    /** The fixes, lazily; each call starts afresh with the same noise. */
    fun fixes(): Sequence<Fix> = sequence {
        val rnd = SplittableRandom(seed)
        val noisy = noise > 0
        var ex = 0.0
        var ey = 0.0
        val k = sqrt(1 - correlation * correlation)
        if (noisy) { ex = gauss(rnd) * noise; ey = gauss(rnd) * noise }
        val accuracy = if (noisy) max(3.0, noise * 1.5) else 5.0
        var t = 0.0
        var along = 0.0
        val pending = detours.sortedBy { it.at }.toMutableList()
        val length = index.length

        fun fix(p: LatLon, bearing: Double, v: Double): Fix {
            var q = p
            var b = bearing
            var s = v
            if (noisy) {
                ex = correlation * ex + k * noise * gauss(rnd)
                ey = correlation * ey + k * noise * gauss(rnd)
                q = LatLon(p.lat + Geo.metresToDegreesLat(ey), p.lon + Geo.metresToDegreesLon(ex, p.lat))
                // Doppler velocity is good to a few tenths of a m/s: the bearing wanders more the slower you go.
                val bearingSigma = Math.toDegrees(atan(0.4 / max(v, 0.3)))
                b = Geo.normalize(bearing + gauss(rnd) * bearingSigma)
                s = max(0.0, v + gauss(rnd) * 0.2)
            }
            return Fix(q, start.plusMillis((t * 1000).toLong()), accuracy, s, if (v >= 0.3) b else null)
        }

        while (true) {
            val v = speed ?: (index.plannedSpeedAt(along) ?: 10.0)
            val d = pending.firstOrNull()
            if (d != null && along >= d.at) {
                pending.removeAt(0)
                val from = index.line.pointAt(d.at)
                val out = Geo.normalize(index.line.bearingAt(d.at) + d.turn)
                var x = 0.0
                while (x < d.length) {
                    x = minOf(d.length, x + v * interval)
                    t += interval
                    yield(fix(Geo.destination(from, out, x), out, v))
                }
                if (!d.rejoin) return@sequence
                val back = Geo.reverseBearing(out)
                while (x > 0) {
                    x = max(0.0, x - v * interval)
                    t += interval
                    yield(fix(Geo.destination(from, out, x), back, v))
                }
                continue
            }
            yield(fix(index.line.pointAt(along), index.line.bearingAt(along), v))
            if (along >= length) break
            along = minOf(length, along + v * interval)
            t += interval
        }
        val end = index.line.pointAt(length)
        val endBearing = index.line.bearingAt(length)
        repeat(dwell) {
            t += interval
            yield(fix(end, endBearing, 0.0))
        }
    }

    private companion object {
        /** A standard normal sample (Box–Muller). */
        fun gauss(r: SplittableRandom): Double {
            val u = 1.0 - r.nextDouble()
            val v = r.nextDouble()
            return sqrt(-2 * ln(u)) * kotlin.math.cos(2 * Math.PI * v)
        }
    }
}

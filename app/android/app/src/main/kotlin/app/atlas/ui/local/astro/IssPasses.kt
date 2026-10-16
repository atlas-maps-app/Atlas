package app.atlas.ui.local.astro

import app.atlas.geo.Sun
import java.time.Duration
import java.time.Instant
import kotlin.math.sqrt

/** One point of the space station's ground track. */
data class TrackPoint(val time: Instant, val latDeg: Double, val lonDeg: Double)

/**
 * A pass of the space station over an observer: when it rises, peaks and sets, and the part of it that can be seen
 * (the station in sunlight, the observer's sky dark, the station at least 10 degrees up).
 */
data class IssPass(
    val rise: Instant,
    val peak: Instant,
    val set: Instant,
    val maxElevationDeg: Double,
    val riseAzimuthDeg: Double,
    val setAzimuthDeg: Double,
    val peakAzimuthDeg: Double,
    /** The visible stretch of the pass, or null when it cannot be seen (in daylight, or in the Earth's shadow). */
    val visibleFrom: Instant?,
    val visibleTo: Instant?,
    /** The ground track from a little before the rise to a little after the set. */
    val track: List<TrackPoint>,
) {
    val visible: Boolean get() = visibleFrom != null

    /** How long it can be seen. */
    val visibleDuration: Duration get() = if (visibleFrom != null && visibleTo != null) Duration.between(visibleFrom, visibleTo) else Duration.ZERO
}

/**
 * Passes of a satellite over an observer, from SGP4. A pass is a stretch with the satellite above the horizon; it is
 * "visible" where it is also above [MIN_VISIBLE_ELEVATION], lit by the sun (a cylindrical Earth shadow) and the sun is
 * more than 6 degrees below the observer's horizon.
 */
class SatellitePasses(private val sgp4: Sgp4) {
    private class Sample(val t: Instant, val az: Double, val el: Double, val visible: Boolean)

    /** The passes that begin between [from] and [from] + [window] (one already under way at [from] counts from [from]). */
    fun passes(from: Instant, window: Duration, latDeg: Double, lonDeg: Double): List<IssPass> {
        val observer = Astro.observerEcef(latDeg, lonDeg)
        val out = ArrayList<IssPass>()
        val step = Duration.ofSeconds(20)
        val end = from.plus(window)
        var prev = sample(from, latDeg, lonDeg, observer) ?: return out
        var rise: Instant? = if (prev.el > 0) from else null
        var riseAz = prev.az
        while (true) {
            val nextT = prev.t.plus(step)
            // A pass that began inside the window is followed to its end, up to half an hour past it.
            if (nextT.isAfter(end) && (rise == null || nextT.isAfter(end.plus(Duration.ofMinutes(30))))) break
            val cur = sample(nextT, latDeg, lonDeg, observer) ?: break
            if (prev.el <= 0 && cur.el > 0) {
                val at = refine(prev.t, cur.t, latDeg, lonDeg, observer) { it.el > 0 }
                rise = at
                riseAz = sample(at, latDeg, lonDeg, observer)?.az ?: cur.az
            } else if (prev.el > 0 && cur.el <= 0 && rise != null) {
                val setAt = refine(prev.t, cur.t, latDeg, lonDeg, observer) { it.el > 0 }
                if (rise.isBefore(end)) build(rise, setAt, riseAz, latDeg, lonDeg, observer)?.let { out += it }
                rise = null
            }
            prev = cur
        }
        return out
    }

    private fun build(rise: Instant, set: Instant, riseAz: Double, lat: Double, lon: Double, obs: Vec3): IssPass? {
        var peak = rise
        var maxEl = -90.0
        var peakAz = 0.0
        var visFrom: Instant? = null
        var visTo: Instant? = null
        var t = rise
        val fine = Duration.ofSeconds(5)
        while (!t.isAfter(set)) {
            val s = sample(t, lat, lon, obs) ?: return null
            if (s.el > maxEl) { maxEl = s.el; peak = t; peakAz = s.az }
            if (s.visible) { if (visFrom == null) visFrom = t; visTo = t }
            t = t.plus(fine)
        }
        val setAz = sample(set, lat, lon, obs)?.az ?: 0.0
        val track = ArrayList<TrackPoint>()
        var g = rise.minus(Duration.ofMinutes(4))
        val trackEnd = set.plus(Duration.ofMinutes(4))
        while (!g.isAfter(trackEnd)) {
            sgp4.propagate(g)?.let { s ->
                val e = Astro.temeToEcef(s.x, s.y, s.z, Astro.gmst(g))
                val (la, lo) = Astro.geodetic(e.x, e.y, e.z)
                track += TrackPoint(g, la, lo)
            }
            g = g.plus(Duration.ofSeconds(20))
        }
        return IssPass(rise, peak, set, maxEl, riseAz, setAz, peakAz, visFrom, visTo, track)
    }

    private fun sample(t: Instant, lat: Double, lon: Double, observer: Vec3): Sample? {
        val s = sgp4.propagate(t) ?: return null
        val g = Astro.gmst(t)
        val ecef = Astro.temeToEcef(s.x, s.y, s.z, g)
        val (az, el, _) = Astro.lookAngles(lat, lon, observer, ecef)
        val visible = el >= MIN_VISIBLE_ELEVATION && Sun.position(t, lat, lon).altitudeDeg < DARK_SUN && sunlit(Vec3(s.x, s.y, s.z), t)
        return Sample(t, az, el, visible)
    }

    private fun refine(a: Instant, b: Instant, lat: Double, lon: Double, obs: Vec3, test: (Sample) -> Boolean): Instant {
        var lo = a.toEpochMilli()
        var hi = b.toEpochMilli()
        val loIn = sample(a, lat, lon, obs)?.let(test) ?: false
        repeat(14) {
            val mid = (lo + hi) / 2
            val m = sample(Instant.ofEpochMilli(mid), lat, lon, obs)?.let(test) ?: false
            if (m == loIn) lo = mid else hi = mid
        }
        return Instant.ofEpochMilli((lo + hi) / 2)
    }

    companion object {
        /** The station must be this high to be seen over roofs and trees. */
        const val MIN_VISIBLE_ELEVATION = 10.0

        /** The sun must be this far below the horizon (civil twilight is over). */
        const val DARK_SUN = -6.0

        /** True when a satellite at [teme] km is in sunlight: outside the Earth's cylindrical shadow. */
        fun sunlit(teme: Vec3, t: Instant): Boolean {
            val sun = Astro.sunEci(t)
            val s = sun * (1.0 / sun.length)
            val along = teme.dot(s)
            if (along >= 0) return true
            val perp = sqrt(maxOf(0.0, teme.dot(teme) - along * along))
            return perp > 6371.0
        }
    }
}

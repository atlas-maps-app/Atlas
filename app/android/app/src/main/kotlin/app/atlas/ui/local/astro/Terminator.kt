package app.atlas.ui.local.astro

import java.time.Instant
import kotlin.math.PI
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

/** A point on the Earth in degrees. */
data class GeoPoint(val latDeg: Double, val lonDeg: Double)

/** Where the sun is overhead, and the lines where its altitude is zero (the terminator) and below: the twilight bands. */
object Terminator {
    /** The sub-solar point at [t]: latitude is the sun's declination, longitude its right ascension less the sidereal time. */
    fun subSolar(t: Instant): GeoPoint {
        val s = Astro.sunEci(t)
        val dec = asin(s.z / s.length) / Astro.DEG
        val ra = atan2(s.y, s.x)
        val lon = Astro.norm180((ra - Astro.gmst(t)) / Astro.DEG)
        return GeoPoint(dec, lon)
    }

    /**
     * The points where the sun's centre stands [altitudeDeg] above the horizon, [count] of them round the circle: the
     * circle of angular radius 90 - altitude around the sub-solar point. Negative altitudes are the twilight lines
     * (-6 civil, -12 nautical, -18 astronomical). The ring runs counter-clockwise seen from outside the Earth when
     * [ccw] is true, round the lit side.
     */
    fun line(t: Instant, altitudeDeg: Double, count: Int = 120, ccw: Boolean = true): List<GeoPoint> =
        circle(subSolar(t), 90.0 - altitudeDeg, count, ccw)

    /** The ring of the area where the sun is below [altitudeDeg]: a circle round the antipode of the sun. */
    fun nightRing(t: Instant, altitudeDeg: Double = 0.0, count: Int = 120): List<GeoPoint> {
        val s = subSolar(t)
        val anti = GeoPoint(-s.latDeg, Astro.norm180(s.lonDeg + 180.0))
        return circle(anti, 90.0 + altitudeDeg, count, ccw = true)
    }

    /** Points at angular distance [radiusDeg] from [c], at bearings turning counter-clockwise (seen from outside) when [ccw]. */
    fun circle(c: GeoPoint, radiusDeg: Double, count: Int, ccw: Boolean): List<GeoPoint> {
        val phi = c.latDeg * Astro.DEG
        val lam = c.lonDeg * Astro.DEG
        val d = radiusDeg * Astro.DEG
        return List(count) { i ->
            val bearing = (if (ccw) -1.0 else 1.0) * 2 * PI * i / count
            val lat = asin(sin(phi) * cos(d) + cos(phi) * sin(d) * cos(bearing))
            val lon = lam + atan2(sin(bearing) * sin(d) * cos(phi), cos(d) - sin(phi) * sin(lat))
            GeoPoint(lat / Astro.DEG, Astro.norm180(lon / Astro.DEG))
        }
    }
}

package app.atlas.geo

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sign
import kotlin.math.sin
import kotlin.math.sqrt

/** Distances and bearings on the sphere. Metres and degrees throughout; bearings clockwise from north in 0..360. */
object Geo {
    const val EARTH_RADIUS = 6_371_008.8
    private const val RAD = PI / 180

    fun distance(a: LatLon, b: LatLon): Double = distance(a.lat, a.lon, b.lat, b.lon)

    fun distance(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val dLat = (lat2 - lat1) * RAD
        val dLon = (lon2 - lon1) * RAD
        val h = sin(dLat / 2).let { it * it } + cos(lat1 * RAD) * cos(lat2 * RAD) * sin(dLon / 2).let { it * it }
        return 2 * EARTH_RADIUS * asin(sqrt(h.coerceIn(0.0, 1.0)))
    }

    fun bearing(a: LatLon, b: LatLon): Double {
        val f1 = a.lat * RAD
        val f2 = b.lat * RAD
        val dl = (b.lon - a.lon) * RAD
        val y = sin(dl) * cos(f2)
        val x = cos(f1) * sin(f2) - sin(f1) * cos(f2) * cos(dl)
        return normalize(atan2(y, x) / RAD)
    }

    fun destination(from: LatLon, bearingDeg: Double, metres: Double): LatLon {
        val d = metres / EARTH_RADIUS
        val t = bearingDeg * RAD
        val f1 = from.lat * RAD
        val l1 = from.lon * RAD
        val f2 = asin(sin(f1) * cos(d) + cos(f1) * sin(d) * cos(t))
        val l2 = l1 + atan2(sin(t) * sin(d) * cos(f1), cos(d) - sin(f1) * sin(f2))
        return LatLon(f2 / RAD, ((l2 / RAD + 540) % 360) - 180)
    }

    fun interpolate(a: LatLon, b: LatLon, t: Double) = LatLon(a.lat + (b.lat - a.lat) * t, a.lon + (b.lon - a.lon) * t)

    /** Signed difference [to] − [from] in −180..180: negative is a left turn. Inputs need not be normalised. */
    fun turnAngle(from: Double, to: Double): Double = normalize(to - from + 180) - 180

    fun normalize(deg: Double): Double = ((deg % 360) + 360) % 360

    fun metresToDegreesLat(m: Double) = m / (EARTH_RADIUS * RAD)
    fun metresToDegreesLon(m: Double, atLat: Double) = m / (EARTH_RADIUS * RAD * cos(atLat * RAD).coerceAtLeast(1e-6))

    /** The point halfway along the great circle from [a] to [b]. */
    fun midpoint(a: LatLon, b: LatLon): LatLon = intermediate(a, b, 0.5)

    /** The point [fraction] (0..1) of the way along the great circle from [a] to [b]. */
    fun intermediate(a: LatLon, b: LatLon, fraction: Double): LatLon {
        val f1 = a.lat * RAD; val l1 = a.lon * RAD
        val f2 = b.lat * RAD; val l2 = b.lon * RAD
        val d = distance(a, b) / EARTH_RADIUS
        if (d == 0.0) return a
        val sd = sin(d)
        val wa = sin((1 - fraction) * d) / sd
        val wb = sin(fraction * d) / sd
        val x = wa * cos(f1) * cos(l1) + wb * cos(f2) * cos(l2)
        val y = wa * cos(f1) * sin(l1) + wb * cos(f2) * sin(l2)
        val z = wa * sin(f1) + wb * sin(f2)
        return LatLon(atan2(z, sqrt(x * x + y * y)) / RAD, atan2(y, x) / RAD)
    }

    /**
     * Metres from [p] to the great circle through [a] and [b]: positive when [p] lies to the right of the
     * direction a → b, negative to the left (as [Snap.side]).
     */
    fun crossTrack(p: LatLon, a: LatLon, b: LatLon): Double {
        val d13 = distance(a, p) / EARTH_RADIUS
        val t13 = bearing(a, p) * RAD
        val t12 = bearing(a, b) * RAD
        return asin((sin(d13) * sin(t13 - t12)).coerceIn(-1.0, 1.0)) * EARTH_RADIUS
    }

    /** Metres from [a] along the great circle towards [b] to the foot of [p]'s perpendicular; negative behind [a]. */
    fun alongTrack(p: LatLon, a: LatLon, b: LatLon): Double {
        val d13 = distance(a, p) / EARTH_RADIUS
        val t13 = bearing(a, p) * RAD
        val t12 = bearing(a, b) * RAD
        val dxt = asin((sin(d13) * sin(t13 - t12)).coerceIn(-1.0, 1.0))
        val c = cos(dxt)
        val dat = if (c == 0.0) 0.0 else acos((cos(d13) / c).coerceIn(-1.0, 1.0))
        return dat * sign(cos(t12 - t13)) * EARTH_RADIUS
    }

    /** The unsigned angle between two bearings, 0..180. */
    fun bearingDelta(a: Double, b: Double): Double = abs(turnAngle(a, b))

    /** Whether [bearing] lies within [tolerance] degrees either side of [target]. */
    fun isBearingWithin(bearing: Double, target: Double, tolerance: Double): Boolean = bearingDelta(bearing, target) <= tolerance

    /** The circular mean of bearings (so 350° and 10° average to 0°), or NaN when they cancel out. */
    fun meanBearing(bearings: DoubleArray): Double {
        var x = 0.0
        var y = 0.0
        for (b in bearings) { x += cos(b * RAD); y += sin(b * RAD) }
        if (abs(x) < 1e-12 && abs(y) < 1e-12) return Double.NaN
        return normalize(atan2(y, x) / RAD)
    }

    /** The bearing pointing the other way. */
    fun reverseBearing(b: Double): Double = normalize(b + 180)
}

/**
 * Flat metres east (x) and north (y) of [origin]: an equirectangular frame, exact enough within a few tens of kilometres.
 * The router, the guide and the search use it for fast local maths.
 */
class LocalFrame(val origin: LatLon) {
    private val kLat = Geo.EARTH_RADIUS * PI / 180
    private val kLon = kLat * cos(origin.lat * PI / 180)

    fun x(lon: Double) = (lon - origin.lon) * kLon
    fun y(lat: Double) = (lat - origin.lat) * kLat
    fun lon(x: Double) = origin.lon + x / kLon
    fun lat(y: Double) = origin.lat + y / kLat
    fun toLatLon(x: Double, y: Double) = LatLon(lat(y), lon(x))
}

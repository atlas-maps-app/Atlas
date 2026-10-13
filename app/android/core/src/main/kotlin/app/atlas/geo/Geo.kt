package app.atlas.geo

import kotlin.math.PI
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
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

    /** Signed difference [to] − [from] in −180..180: negative is a left turn. */
    fun turnAngle(from: Double, to: Double): Double = ((to - from + 540) % 360) - 180

    fun normalize(deg: Double): Double = ((deg % 360) + 360) % 360

    fun metresToDegreesLat(m: Double) = m / (EARTH_RADIUS * RAD)
    fun metresToDegreesLon(m: Double, atLat: Double) = m / (EARTH_RADIUS * RAD * cos(atLat * RAD).coerceAtLeast(1e-6))
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

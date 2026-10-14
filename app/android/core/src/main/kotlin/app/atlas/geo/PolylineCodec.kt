package app.atlas.geo

import kotlin.math.pow
import kotlin.math.roundToLong

/**
 * The Encoded Polyline Algorithm Format: latitude/longitude pairs as deltas of rounded integers, five bits per
 * character. Precision 5 is the classic form; precision 6 ("polyline6") is what OSRM, Valhalla and GraphHopper use.
 */
object PolylineCodec {
    fun encode(points: List<LatLon>, precision: Int = 5): String {
        val factor = factor(precision)
        val sb = StringBuilder(points.size * 8)
        var lastLat = 0L
        var lastLon = 0L
        for (p in points) {
            val lat = (p.lat * factor).roundToLong()
            val lon = (p.lon * factor).roundToLong()
            write(sb, lat - lastLat)
            write(sb, lon - lastLon)
            lastLat = lat
            lastLon = lon
        }
        return sb.toString()
    }

    /** Decodes a polyline; throws [IllegalArgumentException] on a malformed or truncated string. */
    fun decode(encoded: String, precision: Int = 5): List<LatLon> {
        val flat = decodeToArray(encoded, precision)
        return List(flat.size / 2) { LatLon(flat[2 * it], flat[2 * it + 1]) }
    }

    /** Decodes a polyline, or null if it is malformed. */
    fun decodeOrNull(encoded: String, precision: Int = 5): List<LatLon>? =
        try { decode(encoded, precision) } catch (_: IllegalArgumentException) { null }

    /** Decodes into a flat array of lat, lon pairs, without a LatLon per point. */
    fun decodeToArray(encoded: String, precision: Int = 5): DoubleArray {
        val factor = factor(precision)
        val out = DoubleBuffer(encoded.length / 2 + 2)
        var index = 0
        var lat = 0L
        var lon = 0L
        while (index < encoded.length) {
            for (axis in 0 until 2) {
                var result = 0L
                var shift = 0
                var b: Int
                do {
                    require(index < encoded.length) { "Truncated polyline at character $index" }
                    b = encoded[index++].code - 63
                    require(b in 0..63) { "Invalid polyline character at ${index - 1}" }
                    require(shift < 64) { "Polyline value too long at ${index - 1}" }
                    result = result or ((b and 0x1f).toLong() shl shift)
                    shift += 5
                } while (b >= 0x20)
                val delta = if (result and 1L != 0L) (result shr 1).inv() else result shr 1
                if (axis == 0) lat += delta else lon += delta
            }
            out.add(lat / factor)
            out.add(lon / factor)
        }
        return out.toArray()
    }

    private fun factor(precision: Int): Double {
        require(precision in 0..10) { "Precision must be 0..10" }
        return 10.0.pow(precision)
    }

    private fun write(sb: StringBuilder, value: Long) {
        var v = if (value < 0) (value shl 1).inv() else value shl 1
        while (v >= 0x20) {
            sb.append(((0x20 or (v and 0x1f).toInt()) + 63).toChar())
            v = v ushr 5
        }
        sb.append((v.toInt() + 63).toChar())
    }
}

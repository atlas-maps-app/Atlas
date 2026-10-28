package app.atlas.packer.windows

import java.io.File
import java.io.IOException

/**
 * The polygon an extract was cut with: Geofabrik's `.poly` beside each extract (the Osmosis polygon format: a name, then
 * rings, each a name line, a longitude and latitude per line, and END; a ring whose name starts with `!` a hole; END
 * after the last). Osmium keeps every node inside it and every way with a node inside whole, so a window whose box, with
 * a margin, lies inside finds in the extract every node and way Overpass gives it; a relation across the border may
 * lack members, which the cut finds.
 */
class ClipPolygon(val rings: List<Ring>) {
    /** A ring's corners in degrees; the last may repeat the first. */
    class Ring(val hole: Boolean, val lon: DoubleArray, val lat: DoubleArray) {
        val size: Int get() = lon.size
    }

    init {
        require(rings.any { !it.hole }) { "a polygon without an outer ring" }
    }

    /** West, south, east, north of the outer rings. */
    val bbox: DoubleArray = run {
        var w = Double.POSITIVE_INFINITY
        var s = Double.POSITIVE_INFINITY
        var e = Double.NEGATIVE_INFINITY
        var n = Double.NEGATIVE_INFINITY
        for (r in rings) if (!r.hole) for (i in 0 until r.size) {
            w = minOf(w, r.lon[i]); e = maxOf(e, r.lon[i]); s = minOf(s, r.lat[i]); n = maxOf(n, r.lat[i])
        }
        doubleArrayOf(w, s, e, n)
    }

    /** Whether the point lies inside: inside an outer ring and no hole (each ring by its crossings). */
    fun holds(lat: Double, lon: Double): Boolean =
        rings.any { !it.hole && inside(it, lat, lon) } && rings.none { it.hole && inside(it, lat, lon) }

    /** Whether the box lies wholly inside: no ring's edge meets it, and its corner is inside. */
    fun holdsBox(south: Double, west: Double, north: Double, east: Double): Boolean {
        if (west < bbox[0] || south < bbox[1] || east > bbox[2] || north > bbox[3]) return false
        for (r in rings) {
            for (i in 0 until r.size) {
                val j = if (i + 1 == r.size) 0 else i + 1
                if (meets(r.lon[i], r.lat[i], r.lon[j], r.lat[j], south, west, north, east)) return false
            }
        }
        return holds(south, west)
    }

    private fun inside(r: Ring, lat: Double, lon: Double): Boolean {
        var c = false
        var j = r.size - 1
        for (i in 0 until r.size) {
            val yi = r.lat[i]
            val yj = r.lat[j]
            if ((yi > lat) != (yj > lat) && lon < (r.lon[j] - r.lon[i]) * (lat - yi) / (yj - yi) + r.lon[i]) c = !c
            j = i
        }
        return c
    }

    companion object {
        /** Reads a `.poly` file. */
        fun read(file: File): ClipPolygon = parse(file.readLines(), file.name)

        fun parse(lines: List<String>, name: String = "the polygon"): ClipPolygon {
            val rings = ArrayList<Ring>()
            var i = 1 // the first line is the polygon's name
            while (true) {
                if (i >= lines.size) throw IOException("$name ends without END")
                val head = lines[i++].trim()
                if (head == "END") break
                if (head.isEmpty()) continue
                val lon = ArrayList<Double>()
                val lat = ArrayList<Double>()
                while (true) {
                    if (i >= lines.size) throw IOException("$name: the ring $head ends without END")
                    val line = lines[i++].trim()
                    if (line == "END") break
                    if (line.isEmpty()) continue
                    val v = line.split(Regex("\\s+"))
                    val x = v.getOrNull(0)?.toDoubleOrNull()
                    val y = v.getOrNull(1)?.toDoubleOrNull()
                    if (v.size != 2 || x == null || y == null || x !in -180.0..180.0 || y !in -90.0..90.0) {
                        throw IOException("$name, line $i: '$line' is no longitude and latitude")
                    }
                    lon += x
                    lat += y
                }
                if (lon.size < 3) throw IOException("$name: the ring $head has ${lon.size} corners")
                rings += Ring(head.startsWith("!"), lon.toDoubleArray(), lat.toDoubleArray())
            }
            if (rings.none { !it.hole }) throw IOException("$name has no outer ring")
            return ClipPolygon(rings)
        }

        /** Whether the segment meets the box (closed), by clipping it to the box (Liang and Barsky). */
        internal fun meets(x0: Double, y0: Double, x1: Double, y1: Double, south: Double, west: Double, north: Double, east: Double): Boolean {
            var t0 = 0.0
            var t1 = 1.0
            val dx = x1 - x0
            val dy = y1 - y0
            val p = doubleArrayOf(-dx, dx, -dy, dy)
            val q = doubleArrayOf(x0 - west, east - x0, y0 - south, north - y0)
            for (k in 0 until 4) {
                if (p[k] == 0.0) {
                    if (q[k] < 0) return false
                } else {
                    val t = q[k] / p[k]
                    if (p[k] < 0) {
                        if (t > t1) return false
                        if (t > t0) t0 = t
                    } else {
                        if (t < t0) return false
                        if (t < t1) t1 = t
                    }
                }
            }
            return true
        }
    }
}

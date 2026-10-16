package app.atlas.ui.local.astro

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * An orthographic view of the globe, as the Globe board draws it: the Earth is a unit disc, x to the right and y
 * downwards (screen order); [centreLonDeg] is the longitude facing us and [tiltDeg] the latitude above which we look
 * (20 degrees on the board, so the north pole leans towards us). Polygons crossing the limb are cut there and closed
 * along it, the way the board's `projectLand` does.
 */
class OrthoView(val centreLonDeg: Double, val tiltDeg: Double = 20.0) {
    private val sinT = sin(tiltDeg * D)
    private val cosT = cos(tiltDeg * D)

    /** Writes the view-space coordinates of a point into [out]: x right, y up, z towards us (negative: behind the Earth). */
    fun project(latDeg: Double, lonDeg: Double, out: DoubleArray) {
        val l = (lonDeg - centreLonDeg) * D
        val p = latDeg * D
        val cp = cos(p)
        val sp = sin(p)
        out[0] = cp * sin(l)
        out[1] = cosT * sp - sinT * cp * cos(l)
        out[2] = sinT * sp + cosT * cp * cos(l)
    }

    /** True when a point faces us. */
    fun faces(latDeg: Double, lonDeg: Double): Boolean {
        val v = DoubleArray(3)
        project(latDeg, lonDeg, v)
        return v[2] > 0
    }

    /** The disc position (x right, y down, unit disc) of a point, or null when it is behind the Earth. */
    fun screen(latDeg: Double, lonDeg: Double): Pair<Double, Double>? {
        val v = DoubleArray(3)
        project(latDeg, lonDeg, v)
        return if (v[2] > 0) v[0] to -v[1] else null
    }

    /**
     * One closed ring (`lon, lat` pairs) as disc coordinates `x, y` pairs, cut at the limb and closed along it; null if
     * none of it faces us. The limb is followed the short way round unless [ccw] says the ring runs counter-clockwise
     * seen from outside (true) or clockwise (false), in which case the long way may be right.
     */
    fun ring(lonLat: DoubleArray, ccw: Boolean? = null): FloatArray? {
        val n = lonLat.size / 2
        if (n < 3) return null
        val px = DoubleArray(n)
        val py = DoubleArray(n)
        val pz = DoubleArray(n)
        val v = DoubleArray(3)
        var first = -1
        for (i in 0 until n) {
            project(lonLat[2 * i + 1], lonLat[2 * i], v)
            px[i] = v[0]; py[i] = v[1]; pz[i] = v[2]
            if (first < 0 && v[2] > 0) first = i
        }
        if (first < 0) return null
        val out = FloatList()
        out.add(px[first], -py[first])
        var exit = Double.NaN
        for (k in 1..n) {
            val a = (first + k - 1) % n
            val b = (first + k) % n
            val av = pz[a] > 0
            val bv = pz[b] > 0
            if (av && bv) out.add(px[b], -py[b])
            else if (av) {
                exit = edge(px[a], py[a], pz[a], px[b], py[b], pz[b])
                out.add(cos(exit), -sin(exit))
            } else if (bv) {
                val entry = edge(px[b], py[b], pz[b], px[a], py[a], pz[a])
                if (!exit.isNaN()) limbArc(out, exit, entry, ccw)
                out.add(px[b], -py[b])
                exit = Double.NaN
            }
        }
        return out.toArray()
    }

    /** The full disc as a ring, for an area that covers everything we see. */
    fun disc(): FloatArray {
        val out = FloatList()
        for (i in 0 until 72) out.add(cos(2 * PI * i / 72), -sin(2 * PI * i / 72))
        return out.toArray()
    }

    /**
     * The visible part of the cap of angular radius [radiusDeg] round a point (a circle on the globe, filled): the area
     * where the sun is below an altitude is such a cap round the sun's antipode. Null when none of it faces us.
     */
    fun cap(centreLat: Double, centreLon: Double, radiusDeg: Double, count: Int = 180): FloatArray? {
        val ring = Terminator.circle(GeoPoint(centreLat, centreLon), radiusDeg, count, ccw = true)
        val flat = DoubleArray(ring.size * 2)
        for ((i, p) in ring.withIndex()) { flat[2 * i] = p.lonDeg; flat[2 * i + 1] = p.latDeg }
        ring(flat, ccw = true)?.let { return it }
        // No point of the ring faces us: the cap covers the whole view, or none of it.
        val c = DoubleArray(3)
        project(centreLat, centreLon, c)
        val dist = Math.toDegrees(kotlin.math.acos(c[2].coerceIn(-1.0, 1.0)))
        return if (dist < radiusDeg) disc() else null
    }

    /** The graticule: meridians every [lonStep] and parallels every [latStep], as polylines of the visible parts. */
    fun graticule(lonStep: Int = 30, latStep: Int = 30, latMax: Int = 60): List<FloatArray> {
        val lines = ArrayList<FloatArray>()
        val v = DoubleArray(3)
        fun trace(points: List<Pair<Double, Double>>) {
            var cur = FloatList()
            for ((lon, lat) in points) {
                project(lat, lon, v)
                if (v[2] > 0) cur.add(v[0], -v[1]) else if (cur.size > 0) { lines += cur.toArray(); cur = FloatList() }
            }
            if (cur.size > 0) lines += cur.toArray()
        }
        var lon = -180
        while (lon < 180) {
            trace((-80..80 step 5).map { lon.toDouble() to it.toDouble() })
            lon += lonStep
        }
        var lat = -latMax
        while (lat <= latMax) {
            trace((-180..180 step 6).map { it.toDouble() to lat.toDouble() })
            lat += latStep
        }
        return lines
    }

    private fun edge(ax: Double, ay: Double, az: Double, bx: Double, by: Double, bz: Double): Double {
        val t = az / (az - bz)
        return atan2(ay + t * (by - ay), ax + t * (bx - ax))
    }

    private fun limbArc(out: FloatList, a0: Double, a1: Double, ccw: Boolean?) {
        var d = a1 - a0
        when (ccw) {
            true -> while (d < 0) d += 2 * PI
            false -> while (d > 0) d -= 2 * PI
            null -> { while (d > PI) d -= 2 * PI; while (d < -PI) d += 2 * PI }
        }
        val n = max(1, ceil(abs(d) / (6 * D)).toInt())
        for (i in 1..n) {
            val a = a0 + d * i / n
            out.add(cos(a), -sin(a))
        }
    }

    /** A growing array of floats. */
    private class FloatList {
        private var a = FloatArray(64)
        var size = 0
            private set

        fun add(x: Double, y: Double) {
            if (size + 2 > a.size) a = a.copyOf(a.size * 2)
            a[size++] = x.toFloat()
            a[size++] = y.toFloat()
        }

        fun toArray(): FloatArray = a.copyOf(size)
    }

    companion object {
        private const val D = PI / 180.0

        /** The disc position of a point for the cities' lights: whether it is near enough the middle to be drawn well. */
        fun inView(z: Double) = min(1.0, z * 3)
    }
}

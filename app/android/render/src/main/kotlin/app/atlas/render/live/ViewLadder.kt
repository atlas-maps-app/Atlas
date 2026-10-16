package app.atlas.render.live

import app.atlas.render.py.Dict
import kotlin.math.abs
import kotlin.math.hypot

/**
 * The engine's camera settings for any view of a region, from the region's own views (`data/regions/places.py`, or the
 * living map's camera in `amsterdam.py`'s main).
 *
 * A view that matches one of the region's views (target within [SNAP_M], yaw and pitch within [SNAP_DEG], distance
 * within [SNAP_DIST] and the principal point within [SNAP_CY]) takes that view's own settings as `cfg_for` makes them,
 * so a board's camera renders the board byte for byte. Any other view gets the distance-driven settings (`radius`,
 * `ground_r`, `win_d`, `haze0`, `haze1`) interpolated in distance between the views, scaled with the distance beyond
 * the nearest and the farthest, so detail and haze change smoothly as the map zooms.
 */
class ViewLadder(
    /** The region's name: its overview renders as this, any other view as `<region>_<key>`. */
    val region: String,
    views: Map<String, Dict>,
    /** True for the living map, whose camera has no `cfg_for` (no split limit, no haze from distance). */
    private val living: Boolean = false,
) {
    /** One of the region's views. */
    class Knot(val name: String, val cam: Dict) {
        val dist: Double = num(cam["dist"])
        val target: List<Double> = (cam["target"] as List<*>).map { num(it) }
        val yaw: Double = num(cam["yaw"])
        val pitch: Double = num(cam["pitch"])
        val cy: Double = (cam["cy"] as Number?)?.toDouble() ?: Double.NaN
    }

    /** The engine's camera for a view, and the region view it matched (null when it is between views). */
    class Placed(val cfg: Dict, val view: String?, val dist: Double)

    val knots: List<Knot> = views.entries.map { (k, c) -> Knot(if (k.isEmpty()) region else "${region}_$k", c) }.sortedBy { it.dist }

    init {
        require(knots.isNotEmpty()) { "region $region has no views" }
    }

    /** The region view [v] matches, or null. */
    fun snap(frame: RegionFrame, v: LiveView): Knot? {
        val tx = frame.x(v.lat, v.lon)
        val tz = frame.z(v.lat, v.lon)
        val dist = v.distance
        val pitch = LiveCamera.enginePitch(v.pitch)
        return knots.firstOrNull { k ->
            hypot(tx - k.target[0], tz - k.target[2]) <= SNAP_M &&
                abs(turn(v.bearing, k.yaw)) <= SNAP_DEG && abs(pitch - k.pitch) <= SNAP_DEG &&
                abs(dist / k.dist - 1) <= SNAP_DIST && (k.cy.isNaN() || abs(v.cyDp - k.cy) <= SNAP_CY)
        }
    }

    /** The engine's camera for [v] in [frame]; [groundY] gives the ground's height under the target (terrain regions). */
    fun place(frame: RegionFrame, v: LiveView, groundY: ((Double, Double) -> Double?)? = null): Placed {
        snap(frame, v)?.let { return Placed(exact(it, v.widthDp, v.heightDp), it.name, it.dist) }
        val dist = v.distance
        val tx = frame.x(v.lat, v.lon)
        val tz = frame.z(v.lat, v.lon)
        val ty = groundY?.invoke(tx, tz) ?: 0.0
        val c: Dict = LinkedHashMap()
        c["target"] = listOf(tx, ty, tz)
        c["yaw"] = LiveCamera.bearing(v.bearing)
        c["pitch"] = LiveCamera.enginePitch(v.pitch)
        c["dist"] = dist
        c["fov"] = LiveCamera.FOV
        c["cy"] = v.cyDp
        for (key in SCALED) c[key] = scaled(key, dist)
        c["win_d"] = between(dist) { valueOf(it, "win_d") }
        // what is not a matter of distance (paving detail, furniture size, terrain cells) comes from the nearest view
        val near = knots.minBy { abs(it.dist - dist) }
        for ((k, value) in near.cam) if (k !in OWN && k !in c) c[k] = value
        c["W"] = v.widthDp
        c["H"] = v.heightDp
        if (!living) {
            c["no_fin"] = true
            c["obj_split"] = OBJ_SPLIT
        }
        return Placed(c, null, dist)
    }

    /** A region view's own camera with the frame size of the screen: regions.py's `cfg_for` (amsterdam.py's main for the living map). */
    fun exact(k: Knot, widthDp: Int, heightDp: Int): Dict {
        val c: Dict = LinkedHashMap(k.cam)
        c["W"] = widthDp
        c["H"] = heightDp
        if (living) return c
        c["no_fin"] = true
        c["obj_split"] = OBJ_SPLIT
        if ("haze0" !in c) c["haze0"] = num(c["dist"]) * 0.85
        if ("haze1" !in c) c["haze1"] = num(c["dist"]) * 2.2
        return c
    }

    /** A view's value of [key] as the engine reads it: its own, or the engine's default for its kind of camera. */
    private fun valueOf(k: Knot, key: String): Double {
        (k.cam[key] as Number?)?.let { return it.toDouble() }
        return when (key) {
            "haze0" -> if (living) LIVING_HAZE0 else k.dist * 0.85
            "haze1" -> if (living) LIVING_HAZE1 else k.dist * 2.2
            "ground_r" -> DEFAULT_GROUND_R
            "radius" -> k.dist
            "win_d" -> DEFAULT_WIN_D
            else -> error(key)
        }
    }

    /** [key] at [dist]: between two views linearly, beyond the ends in proportion to the distance. */
    private fun scaled(key: String, dist: Double): Double {
        val first = knots.first()
        val last = knots.last()
        if (dist <= first.dist) return valueOf(first, key) * dist / first.dist
        if (dist >= last.dist) return valueOf(last, key) * dist / last.dist
        return between(dist) { valueOf(it, key) }
    }

    /** Linear interpolation in distance between the views around [dist]; the end views' values beyond them. */
    private fun between(dist: Double, f: (Knot) -> Double): Double {
        if (dist <= knots.first().dist) return f(knots.first())
        if (dist >= knots.last().dist) return f(knots.last())
        for (i in 1 until knots.size) {
            val a = knots[i - 1]
            val b = knots[i]
            if (dist <= b.dist) {
                if (b.dist == a.dist) return f(b)
                val t = (dist - a.dist) / (b.dist - a.dist)
                return f(a) + (f(b) - f(a)) * t
            }
        }
        return f(knots.last())
    }

    companion object {
        const val SNAP_M = 1.0
        const val SNAP_DEG = 0.05
        const val SNAP_DIST = 0.002
        const val SNAP_CY = 0.75

        /** regions.py's cfg_for: objects split into two images above this size. */
        const val OBJ_SPLIT = 1900000

        /** amsterdam.py's render defaults when a camera leaves them out. */
        const val LIVING_HAZE0 = 380.0
        const val LIVING_HAZE1 = 800.0
        const val DEFAULT_GROUND_R = 1500.0
        const val DEFAULT_WIN_D = 390.0

        private val SCALED = listOf("radius", "ground_r", "haze0", "haze1")
        private val OWN = setOf("target", "yaw", "pitch", "dist", "fov", "cy", "radius", "ground_r", "win_d", "haze0", "haze1", "W", "H", "cx")

        /** amsterdam.py's main camera: the living map at the Westerkerk. */
        fun livingView(): Dict = linkedMapOf(
            "W" to 412, "H" to 915, "target" to listOf(-10, 0, -30), "yaw" to 52, "pitch" to 38, "dist" to 430, "fov" to 36, "cy" to 440, "radius" to 380,
        )

        private fun num(v: Any?): Double = (v as Number).toDouble()

        /** The shortest signed turn from [from] to [to] degrees. */
        fun turn(from: Double, to: Double): Double {
            var d = (to - from) % 360.0
            if (d > 180.0) d -= 360.0
            if (d < -180.0) d += 360.0
            return d
        }
    }
}

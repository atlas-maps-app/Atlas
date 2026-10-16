package app.atlas.ui.local

import app.atlas.geo.Geo
import app.atlas.geo.LatLon
import app.atlas.geo.LocalFrame
import app.atlas.model.Place
import app.atlas.places.OpeningHours
import java.time.LocalDateTime
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.tan

/**
 * The street ahead as a stand-in: the mapped buildings extruded to their mapped height (or 9 m where none is mapped) and seen from
 * eye height through a pinhole camera, so what stands to the left on the map stands to the left in the picture.
 */
object LookScene {
    /** Horizontal field of view of the stand-in, degrees. */
    const val FOV = 70.0
    const val EYE_HEIGHT = 1.6

    /** Buildings farther than this are left out, metres. */
    const val FAR = 220.0

    /** What a building with no mapped height is drawn as, metres. */
    const val DEFAULT_HEIGHT = 9.0
    private const val NEAR = 1.5

    /** One wall seen from the camera: its four corners on a [width] x [height] screen, how lit it is and how far (metres). */
    class Wall(val xs: FloatArray, val ys: FloatArray, val light: Float, val depth: Float, val footprint: Footprint, val heightM: Double)

    /** The focal length in pixels of a [width] wide picture. */
    fun focal(width: Float): Float = (width / 2f / tan(Math.toRadians(FOV / 2))).toFloat()

    /** The walls of [buildings] seen from [at] looking along [heading], farthest first, with the horizon at [horizon] on a [width] wide screen. */
    fun walls(at: LatLon, heading: Double, buildings: List<Footprint>, width: Float, horizon: Float): List<Wall> {
        val frame = LocalFrame(at)
        val h = Math.toRadians(heading)
        val fx = sin(h)
        val fy = cos(h)
        val rx = cos(h)
        val ry = -sin(h)
        val f = focal(width)
        val out = ArrayList<Wall>()
        for (b in buildings) {
            val west = frame.x(b.box.west); val east = frame.x(b.box.east)
            val south = frame.y(b.box.south); val north = frame.y(b.box.north)
            // Standing inside a building's box: it is not in the picture.
            if (0.0 in west..east && 0.0 in south..north) continue
            val height = b.heightM ?: DEFAULT_HEIGHT
            // Corners in order, with the outward direction of each edge from it to the next.
            val corners = listOf(west to south, east to south, east to north, west to north)
            val normals = listOf(0.0 to -1.0, 1.0 to 0.0, 0.0 to 1.0, -1.0 to 0.0)
            val lights = floatArrayOf(0.9f, 0.72f, 0.8f, 0.62f)
            for (i in 0 until 4) {
                val (ax, ay) = corners[i]
                val (bx, by) = corners[(i + 1) % 4]
                val (nx, ny) = normals[i]
                // Only the walls that face the camera.
                if (-(nx * (ax + bx) / 2 + ny * (ay + by) / 2) <= 0) continue
                var ar = ax * rx + ay * ry; var az = ax * fx + ay * fy
                var br = bx * rx + by * ry; var bz = bx * fx + by * fy
                if (az < NEAR && bz < NEAR) continue
                if (az < NEAR) { val t = (NEAR - az) / (bz - az); ar += (br - ar) * t; az = NEAR.toDouble() }
                if (bz < NEAR) { val t = (NEAR - bz) / (az - bz); br += (ar - br) * t; bz = NEAR.toDouble() }
                if (minOf(az, bz) > FAR) continue
                fun sx(r: Double, z: Double) = (width / 2 + f * r / z).toFloat()
                fun sy(z: Double, up: Double) = (horizon - f * (up - EYE_HEIGHT) / z).toFloat()
                out += Wall(
                    floatArrayOf(sx(ar, az), sx(br, bz), sx(br, bz), sx(ar, az)),
                    floatArrayOf(sy(az, 0.0), sy(bz, 0.0), sy(bz, height), sy(az, height)),
                    lights[i], ((az + bz) / 2).toFloat(), b, height,
                )
            }
        }
        return out.sortedByDescending { it.depth }
    }

    /** A place in view: where it is from the camera and when it opens or closes. */
    class Mark(val place: Place, val name: String, val distanceM: Double, val bearing: Double, val relative: Double, val hours: String?)

    /** The named [places] within [maxM] of [at] and within [half] degrees of [heading], nearest first. */
    fun marks(at: LatLon, heading: Double, places: List<Place>, name: (Place) -> String?, maxM: Double = 150.0, half: Double = 45.0): List<Mark> =
        places.mapNotNull { p ->
            val n = name(p) ?: return@mapNotNull null
            val d = Geo.distance(at, p.location)
            if (d > maxM || d < 2) return@mapNotNull null
            val bearing = Geo.bearing(at, p.location)
            val rel = Geo.turnAngle(heading, bearing)
            if (abs(rel) > half) null else Mark(p, n, d, bearing, rel, p.tags["opening_hours"])
        }.sortedBy { it.distanceM }

    /** "closes 18:30", "open until 20:00", "opens 09:00" in [now]'s zone clock, from [hours]; null when the hours are missing, unclear or 24/7. */
    fun status(hours: String?, now: LocalDateTime): Pair<Boolean, LocalDateTime>? {
        val parsed = OpeningHours.parseOrNull(hours) ?: return null
        if (parsed.isAlwaysOpen) return null
        val s = parsed.status(now)
        if (s.unknown) return null
        val at = s.changesAt ?: return null
        return s.isOpen to at
    }

    /** The mark that is the closest within a narrow cone ahead: what "60 m ahead, left" speaks of. */
    fun ahead(marks: List<Mark>, cone: Double = 30.0): Mark? = marks.filter { abs(it.relative) <= cone }.minByOrNull { it.distanceM }

    /** The point [metres] along [heading] from [at]. */
    fun step(at: LatLon, heading: Double, metres: Double): LatLon = Geo.destination(at, heading, metres)

    /** The street at [at]: the named street whose box holds the point and is the smallest, else the nearest; null when no street is given. */
    fun streetAt(at: LatLon, streets: List<Place>, name: (Place) -> String?): String? {
        fun gap(p: Place): Double {
            val b = p.bbox ?: return Geo.distance(at, p.location)
            val dLat = if (at.lat < b.south) b.south - at.lat else if (at.lat > b.north) at.lat - b.north else 0.0
            val dLon = if (at.lon < b.west) b.west - at.lon else if (at.lon > b.east) at.lon - b.east else 0.0
            return (dLat * 110_574.0).let { la -> kotlin.math.hypot(la, dLon * 111_320.0 * cos(Math.toRadians(at.lat))) }
        }
        fun area(p: Place): Double = p.bbox?.let { (it.north - it.south) * (it.east - it.west) } ?: Double.MAX_VALUE
        return streets.filter { name(it) != null }.minWithOrNull(compareBy({ (gap(it) / 15).toInt() }, { area(it) }))?.let(name)
    }
}

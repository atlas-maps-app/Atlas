package app.atlas.ui.go

import app.atlas.geo.LatLon
import app.atlas.model.Route
import app.atlas.route.OsmUnits
import app.atlas.route.HazardKind
import app.atlas.search.PlaceSource
import app.atlas.geo.Mercator
import app.atlas.tiles.GeomType
import app.atlas.tiles.Mvt
import app.atlas.tiles.TileProjection
import app.atlas.tiles.TileSource
import app.atlas.tiles.TileStack
import kotlin.math.abs

/** A bridge a boat passes under: where along the route, its name, and the clearance (and width) when mapped. */
data class RouteBridge(val along: Double, val location: LatLon, val name: String?, val clearance: Double?, val width: Double?)

/**
 * The bridges over a boat's route: the route's own bridge hazards (the router's checks against the boat's air draft,
 * with the clearance mapped for boats), every bridge with a clearance in the offline place store that the route passes
 * under, and every bridge the map tiles draw across it (where only the place is known), in order along it.
 */
object RouteBridges {
    /** How close (metres) a bridge's middle must be to the route to count as crossing it. */
    const val NEAR_M = 30.0

    /** Two bridges closer than this along the route are one (a bridge mapped as several ways, or twice). */
    const val SAME_M = 15.0

    fun find(route: Route, places: PlaceSource?, crossings: DoubleArray = DoubleArray(0)): List<RouteBridge> {
        val out = ArrayList<RouteBridge>()
        val cum = route.line.cumulative
        for (h in route.hazards) {
            if (h.kind != HazardKind.BRIDGE) continue
            val along = cum[h.pointIndex.coerceIn(0, cum.size - 1)]
            out += RouteBridge(along, h.location, h.tags["name"] ?: h.text, h.value, null)
        }
        val box = route.line.bbox
        if (places != null && places.size > 0 && box != null) {
            for (r in places.inBox(box.buffer(NEAR_M))) {
                val t = r.tags
                val clearance = clearanceOf(t)
                val mapped = t["seamark:type"] == "bridge" || clearance != null
                if (!mapped) continue
                val at = r.place.bbox?.center ?: r.location
                val snap = route.line.snap(at) ?: continue
                if (snap.offset > NEAR_M) continue
                if (out.any { abs(it.along - snap.along) < SAME_M }) continue
                out += RouteBridge(snap.along, at, t["seamark:name"] ?: t["bridge:name"] ?: t["name"], clearance, OsmUnits.metres(t["seamark:bridge:clearance_width"]))
            }
        }
        for (a in crossings) {
            if (out.any { abs(it.along - a) < SAME_M }) continue
            out += RouteBridge(a, route.line.pointAt(a), null, null, null)
        }
        return out.sortedBy { it.along }
    }

    /** The clearance under a bridge from its tags: the seamark's (closed, for a bridge that opens), else `maxheight`. */
    fun clearanceOf(t: Map<String, String>): Double? =
        OsmUnits.metres(t["seamark:bridge:clearance_height"])
            ?: OsmUnits.metres(t["seamark:bridge:clearance_height_closed"])
            ?: t["maxheight:physical"]?.let(OsmUnits::metres)

    /** The next bridge at or after [along] metres (a bridge just passed under still counts for 10 m), with its distance. */
    fun next(bridges: List<RouteBridge>, along: Double): Pair<RouteBridge, Double>? =
        bridges.firstOrNull { it.along >= along - 10 }?.let { it to (it.along - along).coerceAtLeast(0.0) }
}

/**
 * Where a route passes under the bridges the map tiles draw: the `transportation` lines with `brunnel=bridge` that cross
 * it, as metres along it. The tiles know where a bridge is, not how high; the clearance comes from elsewhere.
 */
object TileBridges {
    private const val ZOOM = 14
    private const val MAX_TILES = 256

    suspend fun crossings(route: Route, tiles: TileSource): DoubleArray {
        val pts = route.points
        if (pts.size < 2) return DoubleArray(0)
        val box = route.line.bbox ?: return DoubleArray(0)
        val ids = Mercator.tilesIn(box, ZOOM)
        if (ids.size > MAX_TILES) return DoubleArray(0)
        val cum = route.line.cumulative
        val found = ArrayList<Double>()
        val rx = DoubleArray(pts.size)
        val ry = DoubleArray(pts.size)
        for (id in ids) {
            val hit = (tiles as? TileStack)?.bestTile(id)
            val tileId = hit?.source ?: id
            val data = hit?.data ?: runCatching { tiles.tile(id) }.getOrNull() ?: continue
            val layer = runCatching { Mvt.decode(data, setOf("transportation"))["transportation"] }.getOrNull() ?: continue
            val proj = TileProjection(tileId, layer.extent)
            for (i in pts.indices) {
                val p = proj.toTile(pts[i])
                rx[i] = p[0]
                ry[i] = p[1]
            }
            for (f in layer.features) {
                if (f.type != GeomType.LINE || f.string("brunnel") != "bridge") continue
                val g = f.geometry
                g.forEachPart { _, start, end ->
                    for (k in start until end - 1) {
                        val ax = g.x(k).toDouble(); val ay = g.y(k).toDouble(); val bx = g.x(k + 1).toDouble(); val by = g.y(k + 1).toDouble()
                        for (j in 0 until pts.size - 1) {
                            val t = intersect(rx[j], ry[j], rx[j + 1], ry[j + 1], ax, ay, bx, by)
                            if (t >= 0) found += cum[j] + t * (cum[j + 1] - cum[j])
                        }
                    }
                }
            }
        }
        found.sort()
        val out = ArrayList<Double>()
        for (a in found) if (out.isEmpty() || a - out.last() >= RouteBridges.SAME_M) out += a
        return out.toDoubleArray()
    }

    /** Where segment p (p1→p2) crosses segment q (q1→q2), as a fraction 0..1 of p; −1 when they do not cross. */
    fun intersect(p1x: Double, p1y: Double, p2x: Double, p2y: Double, q1x: Double, q1y: Double, q2x: Double, q2y: Double): Double {
        val rx = p2x - p1x; val ry = p2y - p1y
        val sx = q2x - q1x; val sy = q2y - q1y
        val d = rx * sy - ry * sx
        if (d == 0.0) return -1.0
        val qpx = q1x - p1x; val qpy = q1y - p1y
        val t = (qpx * sy - qpy * sx) / d
        val u = (qpx * ry - qpy * rx) / d
        return if (t in 0.0..1.0 && u in 0.0..1.0) t else -1.0
    }
}

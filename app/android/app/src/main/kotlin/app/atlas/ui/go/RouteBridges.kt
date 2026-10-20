package app.atlas.ui.go

import app.atlas.geo.LatLon
import app.atlas.model.Route
import app.atlas.route.OsmUnits
import app.atlas.route.HazardKind
import app.atlas.search.PlaceSource
import app.atlas.services.LocalBridge
import kotlin.math.abs

/** A bridge a boat passes under: where along the route, its name, and the clearance (and width) when mapped. */
data class RouteBridge(val along: Double, val location: LatLon, val name: String?, val clearance: Double?, val width: Double?)

/**
 * The bridges over a boat's route: the route's own bridge hazards (the router's checks against the boat's air draft,
 * with the clearance mapped for boats), every bridge with a clearance in the offline place store that the route passes
 * under, and every bridge OpenStreetMap maps across it ([LocalData.bridges]: the packs, else Overpass along the route;
 * with its name and clearance when mapped), in order along it.
 */
object RouteBridges {
    /** How close (metres) a bridge's middle must be to the route to count as crossing it. */
    const val NEAR_M = 30.0

    /** Two bridges closer than this along the route are one (a bridge mapped as several ways, or twice). */
    const val SAME_M = 15.0

    fun find(route: Route, places: PlaceSource?, mapped: List<LocalBridge> = emptyList()): List<RouteBridge> {
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
        for (b in mapped) {
            if (out.any { abs(it.along - b.along) < SAME_M }) continue
            out += RouteBridge(b.along, b.at, b.name, b.clearance, b.width)
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

package app.atlas.route

import app.atlas.geo.BBox
import app.atlas.geo.LatLon
import app.atlas.geo.Polyline
import app.atlas.model.Mode
import app.atlas.model.OsmId
import app.atlas.model.Vehicle

/**
 * A legal or physical limit on a way or a point, as OpenStreetMap tags it. Null means no limit of that kind.
 * Road limits: height, weight, width, length, axle load (metres and tonnes), hazmat and hgv access.
 * Water limits: bridge clearance (air draught), depth (draught) and width (beam).
 */
data class Restriction(
    val id: OsmId?,
    /** The way's points, or a single point for a node. */
    val geometry: List<LatLon>,
    val maxHeight: Double? = null,
    val maxWeight: Double? = null,
    val maxWidth: Double? = null,
    val maxLength: Double? = null,
    val maxAxleLoad: Double? = null,
    /** False when dangerous goods are banned here (hazmat=no and its tunnel-category forms). */
    val hazmatAllowed: Boolean = true,
    /** hgv=* when it limits trucks: "no", "destination", "delivery", "local", "private", "agricultural". */
    val hgv: String? = null,
    val bridgeClearance: Double? = null,
    val maxDraught: Double? = null,
    val maxBeam: Double? = null,
    /** True when the route passes under or through this feature rather than along it (a bridge over a waterway). */
    val crossing: Boolean = false,
    /** True for limits on boats (waterways, locks, bridge clearances), false for limits on road vehicles. */
    val water: Boolean = false,
    val tags: Map<String, String> = emptyMap(),
) {
    init {
        require(geometry.isNotEmpty()) { "a restriction needs a location" }
    }

    val isPoint: Boolean get() = geometry.size == 1
}

/** A stretch of the route to search for restrictions: the line and how far either side of it. */
data class Corridor(val line: List<LatLon>, val bufferMetres: Double = 25.0) {
    val bbox: BBox get() = (BBox.of(line) ?: throw IllegalStateException("empty corridor")).buffer(bufferMetres)
}

/** Where restrictions come from: Overpass online, or the app's offline map data. */
fun interface RestrictionSource {
    suspend fun restrictionsAlong(corridor: Corridor): List<Restriction>
}

/** Which limit a restriction sets for a vehicle, and by how much it is broken. */
data class Conflict(val restriction: Restriction, val kind: String, val limit: Double?, val actual: Double?)

/** Decides which restrictions a vehicle in a mode breaks. */
object RestrictionRules {

    /** True when [mode] and [vehicle] are worth checking at all. */
    fun applies(mode: Mode, vehicle: Vehicle): Boolean = when {
        mode == Mode.TRUCK || mode == Mode.CAMPER -> true
        mode.water -> vehicle.airDraftM != null || vehicle.draftM != null || vehicle.beamM != null || vehicle.lengthM != null
        mode.motor -> vehicle.heightM != null || vehicle.weightT != null || vehicle.widthM != null || vehicle.lengthM != null
        else -> false
    }

    /**
     * The first limit [r] sets that [vehicle] breaks in [mode], or null. [nearEnds] says the restriction lies close to the
     * start or the destination, where hgv=destination and delivery allow trucks.
     */
    fun conflict(r: Restriction, mode: Mode, vehicle: Vehicle, nearEnds: Boolean = false): Conflict? {
        if (!relevant(r, mode)) return null
        if (mode.water) {
            val air = vehicle.airDraftM
            if (air != null && r.bridgeClearance != null && air > r.bridgeClearance) return Conflict(r, HazardKind.BRIDGE, r.bridgeClearance, air)
            if (air != null && r.maxHeight != null && air > r.maxHeight) return Conflict(r, HazardKind.BRIDGE, r.maxHeight, air)
            val draft = vehicle.draftM
            if (draft != null && r.maxDraught != null && draft > r.maxDraught) return Conflict(r, HazardKind.MAXDRAUGHT, r.maxDraught, draft)
            val beam = vehicle.beamM ?: vehicle.widthM
            val width = r.maxBeam ?: r.maxWidth
            if (beam != null && width != null && beam > width) return Conflict(r, HazardKind.MAXWIDTH, width, beam)
            val length = vehicle.lengthM
            if (length != null && r.maxLength != null && length > r.maxLength) return Conflict(r, HazardKind.MAXLENGTH, r.maxLength, length)
            return null
        }
        if (!mode.motor) return null
        val height = vehicle.heightM ?: defaultHeight(mode)
        if (height != null && r.maxHeight != null && height > r.maxHeight) return Conflict(r, HazardKind.MAXHEIGHT, r.maxHeight, height)
        val weight = vehicle.weightT ?: defaultWeight(mode)
        if (weight != null && r.maxWeight != null && weight > r.maxWeight) return Conflict(r, HazardKind.MAXWEIGHT, r.maxWeight, weight)
        val width = vehicle.widthM ?: defaultWidth(mode)
        if (width != null && r.maxWidth != null && width > r.maxWidth) return Conflict(r, HazardKind.MAXWIDTH, r.maxWidth, width)
        val length = vehicle.lengthM ?: defaultLength(mode)
        if (length != null && r.maxLength != null && length > r.maxLength) return Conflict(r, HazardKind.MAXLENGTH, r.maxLength, length)
        val axle = vehicle.axleLoadT
        if (axle != null && r.maxAxleLoad != null && axle > r.maxAxleLoad) return Conflict(r, HazardKind.MAXAXLELOAD, r.maxAxleLoad, axle)
        if (vehicle.hazmat && !r.hazmatAllowed) return Conflict(r, HazardKind.HAZMAT, null, null)
        val isHgv = mode == Mode.TRUCK || (mode == Mode.CAMPER && (vehicle.weightT ?: 0.0) > 3.5)
        if (isHgv && r.hgv != null) {
            val banned = r.hgv in setOf("no", "private", "agricultural", "forestry")
            val local = r.hgv in setOf("destination", "delivery", "local")
            if (banned || (local && !nearEnds)) return Conflict(r, HazardKind.HGV, null, null)
        }
        return null
    }

    /** Whether [r] is about [mode] at all: boat limits for boats, road limits for road vehicles. */
    fun relevant(r: Restriction, mode: Mode): Boolean = if (mode.water) r.water else !r.water && mode.motor

    // defaults when the user has not entered dimensions: a typical 40 t articulated truck, a 3.5 t camper
    private fun defaultHeight(mode: Mode) = when (mode) { Mode.TRUCK -> 4.0; Mode.CAMPER -> 3.0; else -> null }
    private fun defaultWeight(mode: Mode) = when (mode) { Mode.TRUCK -> 40.0; Mode.CAMPER -> 3.5; else -> null }
    private fun defaultWidth(mode: Mode) = when (mode) { Mode.TRUCK -> 2.55; Mode.CAMPER -> 2.3; else -> null }
    private fun defaultLength(mode: Mode) = when (mode) { Mode.TRUCK -> 16.5; Mode.CAMPER -> 7.0; else -> null }

    /** The hazard kind a restriction is reported as when nothing is broken (the first limit it sets). */
    fun kindOf(r: Restriction): String = when {
        r.bridgeClearance != null -> HazardKind.BRIDGE
        r.maxHeight != null -> HazardKind.MAXHEIGHT
        r.maxWeight != null -> HazardKind.MAXWEIGHT
        r.maxWidth != null -> HazardKind.MAXWIDTH
        r.maxLength != null -> HazardKind.MAXLENGTH
        r.maxAxleLoad != null -> HazardKind.MAXAXLELOAD
        r.maxDraught != null -> HazardKind.MAXDRAUGHT
        r.maxBeam != null -> HazardKind.MAXWIDTH
        !r.hazmatAllowed -> HazardKind.HAZMAT
        r.hgv != null -> HazardKind.HGV
        r.tags["lock"] == "yes" || r.tags["waterway"] == "lock_gate" -> HazardKind.LOCK
        else -> "restriction"
    }

    fun limitOf(r: Restriction): Double? = r.bridgeClearance ?: r.maxHeight ?: r.maxWeight ?: r.maxWidth ?: r.maxLength ?: r.maxAxleLoad ?: r.maxDraught ?: r.maxBeam
}

/**
 * Where restrictions touch a route: a restriction way is "on" the route when its segments run along the route within
 * [tolerance] metres in the same or the opposite direction; a point is on it within [tolerance]; a crossing feature
 * (a bridge over a canal) is on it when it crosses the route line.
 */
class RouteMatcher(route: List<LatLon>, private val tolerance: Double = 4.0) {
    private val line = Polyline(route)
    private val index = SegmentIndex(route, cell = 60.0)

    /** Where [r] touches the route: the distance along the route and the bearing there, or null. */
    data class Touch(val along: Double, val bearing: Double, val point: LatLon, val segment: Int)

    fun touch(r: Restriction): Touch? {
        val g = r.geometry
        if (g.size == 1) {
            val near = index.nearest(g[0], tolerance * 2) ?: return null
            if (near.distance > tolerance * 2) return null
            return touchAt(near.segment, near.t)
        }
        if (r.crossing) {
            for (i in 0 until g.size - 1) {
                val hit = index.crossing(g[i], g[i + 1]) ?: continue
                return touchAt(hit.segment, hit.t)
            }
            return null
        }
        // along: the longest restriction segment whose middle lies on the route, running parallel to it
        var best: Touch? = null
        var bestLength = 0.0
        for (i in 0 until g.size - 1) {
            val a = g[i]
            val b = g[i + 1]
            val len = app.atlas.geo.Geo.distance(a, b)
            if (len < 0.5) continue
            // a third of the way along the piece, away from the junctions and from a bridge crossing its middle
            val probe = app.atlas.geo.Geo.interpolate(a, b, 1.0 / 3.0)
            val near = index.nearest(probe, tolerance) ?: continue
            if (near.distance > tolerance) continue
            val routeBearing = line.bearingAt(line.cumulative[near.segment] + near.t * (line.cumulative[near.segment + 1] - line.cumulative[near.segment]))
            val wayBearing = app.atlas.geo.Geo.bearing(a, b)
            val diff = kotlin.math.abs(app.atlas.geo.Geo.turnAngle(routeBearing, wayBearing))
            if (diff > 30 && diff < 150) continue
            if (len > bestLength) {
                bestLength = len
                best = touchAt(near.segment, near.t)
            }
        }
        return best
    }

    private fun touchAt(segment: Int, t: Double): Touch {
        val c = line.cumulative
        val along = c[segment] + t * (c[segment + 1] - c[segment])
        return Touch(along, line.bearingAt(along), line.pointAt(along), segment)
    }

    /** Index of the route point nearest to [along] metres. */
    fun pointIndex(along: Double): Int {
        val i = line.segmentAt(along)
        val c = line.cumulative
        return if (i + 1 < c.size && c[i + 1] - along < along - c[i]) i + 1 else i
    }

    val length: Double get() = line.length
}

package app.atlas.guide

import app.atlas.geo.Geo
import app.atlas.geo.LatLon
import app.atlas.geo.LocalFrame
import app.atlas.model.Landmark
import app.atlas.model.ManeuverType
import app.atlas.model.Place
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sin

/** Finds map features near a point; the app fills it from the vector tiles and the place store. */
fun interface FeatureLookup {
    fun near(point: LatLon, radiusM: Double): List<Place>
}

/** How a landmark relates to a maneuver: you turn at it, after passing it, or before reaching it. */
enum class Relation { AT, AFTER, BEFORE }

/**
 * A landmark chosen for a maneuver. [kind] is one of the phrase kinds (traffic_signals, bridge, tunnel, church, mosque,
 * synagogue, temple, station, roundabout, fuel, crossing, stop) or "named" for a named place such as a brand store;
 * [nth] counts traffic lights ("at the second traffic lights").
 */
data class LandmarkRef(
    val kind: String,
    val name: String?,
    val relation: Relation,
    val side: Side,
    val distance: Double,
    val score: Double,
    val nth: Int = 0,
)

/**
 * Picks the landmark that makes a maneuver easy: visible, distinctive and named, close to the junction, and unambiguous,
 * preferring one on the side you turn to. Uses the step's own landmarks when the router supplied them, else asks
 * [lookup] around the maneuver.
 */
class Landmarks(private val lookup: FeatureLookup? = null) {

    /** The best landmark for step [stepIndex] of [index], or null when nothing helps. */
    fun forStep(index: RouteIndex, stepIndex: Int, group: ModeGroup): LandmarkRef? {
        val step = index.steps.getOrNull(stepIndex) ?: return null
        if (step.maneuver.type == ManeuverType.DEPART) return null
        val radius = radiusFor(group)
        val candidates = ArrayList<Candidate>()
        for (lm in step.landmarks) classify(lm)?.let { candidates += Candidate(it.first, it.second, lm.location, lm.tags, bonus = 1.3) }
        if (lookup != null) {
            for (p in lookup.near(step.location, radius)) classify(p)?.let { candidates += Candidate(it.first, it.second, p.location, p.tags, bonus = 1.0) }
        }
        if (candidates.isEmpty()) return null
        val s = index.stepAlong[stepIndex]
        val inBearing = index.line.bearingAt((s - 8).coerceAtLeast(0.0))
        val atRadius = atRadiusFor(group)
        var best: LandmarkRef? = null
        for (c in candidates) {
            if (!suits(c.kind, step.maneuver.type, group)) continue
            val ref = relate(c, step.location, inBearing, index, s, atRadius, radius) ?: continue
            // One on the corner you turn round is the easier to see and to trust.
            val turn = step.maneuver.turn
            val scored = if (turn.left && ref.side == Side.LEFT || turn.right && ref.side == Side.RIGHT) ref.copy(score = ref.score * TURN_SIDE_BONUS) else ref
            if (best == null || scored.score > best.score) best = scored
        }
        val chosen = best ?: return null
        if (chosen.kind == "traffic_signals" && chosen.relation == Relation.AT) {
            val nth = signalsBefore(index, stepIndex, atRadius) + 1
            if (nth > 3) return null
            if (nth > 1) return chosen.copy(nth = nth)
        }
        return chosen
    }

    /** The phrase for a landmark ("after the bridge", "at the 2nd traffic lights", "at Albert Heijn"), or null. */
    fun phrase(ref: LandmarkRef, book: Phrasebook): String? {
        val rel = ref.relation.name.lowercase()
        if (ref.nth > 1) return book.ownPhrase("lm.at.traffic_signals_nth", "nth" to Instr(book.osrm.ordinalize(ref.nth)))?.let { lower(it) }
        val key = if (ref.kind == "named") "lm.$rel.named" else "lm.$rel.${ref.kind}"
        val instr = if (ref.kind == "named") book.ownPhrase(key, "name" to (ref.name ?: return null)) else book.ownPhrase(key)
        return instr?.let { lower(it) }
    }

    // The landmark phrase sits inside a sentence: undo the sentence capital the phrase table added.
    private fun lower(i: Instr): String =
        if (i.startsWithName || i.text.isEmpty()) i.text else i.text.substring(0, 1).lowercase() + i.text.substring(1)

    private class Candidate(val kind: String, val name: String?, val location: LatLon, val tags: Map<String, String>, val bonus: Double)

    private fun relate(c: Candidate, junction: LatLon, inBearing: Double, index: RouteIndex, s: Double, atRadius: Double, radius: Double): LandmarkRef? {
        val f = LocalFrame(junction)
        val x = f.x(c.location.lon)
        val y = f.y(c.location.lat)
        val dist = Geo.distance(junction, c.location)
        if (dist > radius) return null
        val b = Math.toRadians(inBearing)
        val forward = x * sin(b) + y * cos(b)
        val lateral = x * cos(b) - y * sin(b) // positive: right of the direction of travel
        val side = when {
            abs(lateral) < 6 -> Side.AHEAD
            lateral > 0 -> Side.RIGHT
            else -> Side.LEFT
        }
        // Features on the route itself (signals, bridges) are placed by their distance along it.
        val snap = index.line.snap(c.location, index.line.segmentAt((s - radius * 1.5).coerceAtLeast(0.0)), index.line.segmentAt(s + radius))
        val onRoute = snap != null && snap.offset < 12
        val relation = when {
            onRoute && abs(snap.along - s) <= atRadius -> Relation.AT
            onRoute && snap.along < s -> Relation.AFTER
            onRoute -> return null // on the stretch after the turn: you see it only once you have turned
            abs(forward) <= atRadius * 1.5 -> Relation.AT
            forward < 0 -> Relation.AFTER
            else -> Relation.BEFORE
        }
        val weight = WEIGHTS[c.kind] ?: 3.0
        val named = if (c.name != null) 1.2 else 1.0
        val near = exp(-dist / 60.0)
        val atBonus = if (relation == Relation.AT) 1.2 else 1.0
        return LandmarkRef(c.kind, c.name, relation, side, dist, weight * named * near * atBonus * c.bonus)
    }

    /** Traffic signals on the route between the previous maneuver and this one: the 2nd, 3rd lights. */
    private fun signalsBefore(index: RouteIndex, stepIndex: Int, atRadius: Double): Int {
        val s = index.stepAlong[stepIndex]
        val from = if (stepIndex > 0) index.stepAlong[stepIndex - 1] + atRadius else 0.0
        if (s - from < 30) return 0
        val alongs = ArrayList<Double>()
        val step = index.steps[stepIndex]
        for (lm in step.landmarks) if (classify(lm)?.first == "traffic_signals") alongs += lm.location.let { alongOf(index, it, from, s) ?: Double.NaN }
        if (lookup != null) {
            val mid = index.line.pointAt((from + s) / 2)
            val r = ((s - from) / 2 + 20).coerceAtMost(400.0)
            for (p in lookup.near(mid, r)) if (classify(p)?.first == "traffic_signals") alongs += alongOf(index, p.location, from, s) ?: Double.NaN
        }
        // Signals of one junction (one node per approach) count once: cluster by 30 m along the route.
        val sorted = alongs.filter { !it.isNaN() && it > from && it < s - atRadius }.sorted()
        var count = 0
        var last = Double.NEGATIVE_INFINITY
        for (a in sorted) {
            if (a - last > 30) count++
            last = a
        }
        return count
    }

    private fun alongOf(index: RouteIndex, p: LatLon, from: Double, to: Double): Double? {
        val snap = index.line.snap(p, index.line.segmentAt(from), index.line.segmentAt(to)) ?: return null
        return if (snap.offset < 15) snap.along else null
    }

    private fun suits(kind: String, type: ManeuverType, group: ModeGroup): Boolean = when {
        kind == "roundabout" && (type == ManeuverType.ROUNDABOUT || type == ManeuverType.ROUNDABOUT_EXIT) -> false
        kind == "bridge" && type == ManeuverType.BRIDGE -> false
        kind == "crossing" -> group == ModeGroup.WALK || group == ModeGroup.BIKE
        else -> true
    }

    companion object {
        private val WEIGHTS = mapOf(
            "traffic_signals" to 10.0, "bridge" to 8.0, "tunnel" to 7.0, "church" to 7.0, "mosque" to 7.0, "synagogue" to 7.0,
            "temple" to 7.0, "station" to 7.0, "roundabout" to 6.0, "fuel" to 6.0, "named" to 5.0, "stop" to 5.0, "crossing" to 4.0,
        )
        private const val TURN_SIDE_BONUS = 1.15
        private val BIG_SHOPS = setOf("supermarket", "department_store", "doityourself", "hardware", "mall", "furniture", "electronics", "car")

        private fun radiusFor(g: ModeGroup) = when (g) {
            ModeGroup.WALK -> 40.0
            ModeGroup.BIKE -> 60.0
            ModeGroup.CAR -> 80.0
            ModeGroup.TRUCK -> 100.0
            ModeGroup.BOAT -> 150.0
        }

        private fun atRadiusFor(g: ModeGroup) = when (g) {
            ModeGroup.WALK -> 10.0
            ModeGroup.BIKE -> 12.0
            ModeGroup.BOAT -> 40.0
            else -> 18.0
        }

        /** The phrase kind and name of a router-supplied landmark, or null when it is not one the guide can name. */
        fun classify(lm: Landmark): Pair<String, String?>? {
            val k = lm.kind.lowercase()
            val direct = when (k) {
                "traffic_signals", "traffic_lights", "signals" -> "traffic_signals"
                "bridge" -> "bridge"
                "tunnel" -> "tunnel"
                "church", "mosque", "synagogue", "temple", "station", "roundabout", "fuel", "crossing", "stop" -> k
                "place_of_worship" -> religion(lm.tags["religion"])
                "brand", "shop", "poi", "named" -> if (lm.name != null) "named" else null
                else -> null
            }
            // A place of worship of no known religion has no word of its own: only its name can be said.
            if (direct == "place_of_worship") return lm.name?.let { "named" to it }
            if (direct != null) return direct to lm.name
            return classifyTags(lm.tags, lm.name)
        }

        /** The phrase kind and name of a map feature, or null. */
        fun classify(p: Place): Pair<String, String?>? = classifyTags(p.tags, p.name())

        private fun classifyTags(t: Map<String, String>, name: String?): Pair<String, String?>? {
            val kind = when {
                t["highway"] == "traffic_signals" || t["crossing"] == "traffic_signals" && t["highway"] != "crossing" -> "traffic_signals"
                t["man_made"] == "bridge" || t["bridge"] == "yes" -> "bridge"
                t["tunnel"] == "yes" -> "tunnel"
                t["amenity"] == "place_of_worship" -> religion(t["religion"])
                t["railway"] == "station" || t["railway"] == "halt" || (t["public_transport"] == "station" && t["railway"] != null) -> "station"
                t["junction"] == "roundabout" || t["highway"] == "mini_roundabout" -> "roundabout"
                t["amenity"] == "fuel" -> if (t["brand"] != null) "named" else "fuel"
                t["highway"] == "stop" -> "stop"
                t["highway"] == "crossing" -> "crossing"
                t["shop"] in BIG_SHOPS && (t["brand"] != null || name != null) -> "named"
                else -> null
            } ?: return null
            val label = if (kind == "named") t["brand"] ?: name else name
            if (kind == "named" && label == null) return null
            if (kind == "place_of_worship") return if (name != null) "named" to name else null
            return kind to label
        }

        private fun religion(r: String?) = when (r) {
            "christian" -> "church"
            "muslim" -> "mosque"
            "jewish" -> "synagogue"
            "buddhist", "hindu", "shinto", "sikh", "jain", "taoist" -> "temple"
            else -> "place_of_worship"
        }
    }
}

package app.atlas.guide

import app.atlas.geo.Geo
import app.atlas.geo.LatLon
import app.atlas.geo.Polyline
import app.atlas.model.Fix
import app.atlas.model.Hazard
import app.atlas.model.Lane
import app.atlas.model.Landmark
import app.atlas.model.Maneuver
import app.atlas.model.ManeuverType
import app.atlas.model.Mode
import app.atlas.model.Route
import app.atlas.model.Span
import app.atlas.model.Step
import app.atlas.model.Turn

/** The Westerkerk, Amsterdam: where the hand-built test routes start. */
val WESTERKERK = LatLon(52.37452, 4.88377)

/** One straight stretch of a sketched route and the maneuver that starts it. */
data class Leg(
    val bearing: Double,
    val length: Double,
    val street: String? = null,
    val type: ManeuverType? = null,
    val turn: Turn? = null,
    val exit: Int? = null,
    val ref: String? = null,
    val destinations: String? = null,
    val lanes: List<Lane> = emptyList(),
    val landmarks: List<Landmark> = emptyList(),
)

/**
 * A route drawn by hand: straight legs from [origin], points every [spacing] metres, a DEPART, one maneuver per corner
 * (a TURN by the angle unless the leg says otherwise) and the ARRIVE. Durations come from [speed].
 */
class Sketch(val mode: Mode, val origin: LatLon = WESTERKERK, val speed: Double = 10.0, val spacing: Double = 10.0) {
    private val points = arrayListOf(origin)
    private val legs = ArrayList<Leg>()
    private val starts = ArrayList<Int>()
    private val hazards = ArrayList<Hazard>()
    private val spans = ArrayList<Span>()
    private var elevation: ((Double) -> Double)? = null

    fun leg(leg: Leg): Sketch {
        starts += points.size - 1
        legs += leg
        val from = points.last()
        val n = kotlin.math.ceil(leg.length / spacing).toInt().coerceAtLeast(1)
        for (i in 1..n) points += Geo.destination(from, leg.bearing, leg.length * i / n)
        return this
    }

    fun leg(bearing: Double, length: Double, street: String? = null) = leg(Leg(bearing, length, street))

    val line: Polyline get() = Polyline(points.toList())

    /** The point [along] metres from the start. */
    fun at(along: Double): LatLon = line.pointAt(along)

    /** The index of the point nearest [along]. */
    fun indexAt(along: Double): Int {
        val c = line.cumulative
        var best = 0
        for (i in c.indices) if (kotlin.math.abs(c[i] - along) < kotlin.math.abs(c[best] - along)) best = i
        return best
    }

    /** A point [offset] metres to the side of the route (positive right) at [along]. */
    fun beside(along: Double, offset: Double): LatLon {
        val b = line.bearingAt(along)
        return Geo.destination(at(along), b + if (offset >= 0) 90.0 else -90.0, kotlin.math.abs(offset))
    }

    fun hazard(along: Double, kind: String, value: Double? = null, tags: Map<String, String> = emptyMap(), text: String? = null, location: LatLon? = null): Sketch {
        val i = indexAt(along)
        hazards += Hazard(kind, i, location ?: points[i], value, text, tags)
        return this
    }

    fun span(from: Double, to: Double, key: String, value: String): Sketch {
        spans += Span(indexAt(from), indexAt(to), key, value)
        return this
    }

    fun elevations(profile: (Double) -> Double): Sketch {
        elevation = profile
        return this
    }

    fun route(arrive: Turn = Turn.STRAIGHT, arriveLandmarks: List<Landmark> = emptyList()): Route {
        val line = line
        val steps = ArrayList<Step>()
        val corners = starts.map { line.cumulative[it] }
        for ((k, leg) in legs.withIndex()) {
            val i = starts[k]
            val legEnd = if (k + 1 < legs.size) corners[k + 1] else line.length
            val dist = legEnd - corners[k]
            val maneuver = if (k == 0) {
                Maneuver(leg.type ?: ManeuverType.DEPART, leg.turn ?: Turn.STRAIGHT, leg.exit, null, leg.bearing)
            } else {
                val angle = Geo.turnAngle(legs[k - 1].bearing, leg.bearing)
                val turn = leg.turn ?: Turn.of(angle)
                val type = leg.type ?: if (turn == Turn.STRAIGHT) ManeuverType.CONTINUE else ManeuverType.TURN
                Maneuver(type, turn, leg.exit, legs[k - 1].bearing, leg.bearing)
            }
            steps += Step(maneuver, i, points[i], dist, dist / speed, leg.street, leg.ref, leg.destinations, leg.lanes, leg.landmarks)
        }
        val last = points.size - 1
        steps += Step(Maneuver(ManeuverType.ARRIVE, arrive, null, legs.last().bearing, null), last, points[last], 0.0, 0.0, landmarks = arriveLandmarks)
        val elev = elevation?.let { f -> DoubleArray(points.size) { f(line.cumulative[it]) } } ?: DoubleArray(0)
        return Route(mode, points.toList(), elev, line.length, line.length / speed, steps, spans.sortedBy { it.from }, hazards.sortedBy { it.pointIndex }, source = "sketch")
    }
}

/** A spoken cue with when and where it was said. */
data class Said(val t: Double, val along: Double, val toGo: Double, val text: String, val id: String, val priority: Priority, val pan: Float)

/** Everything a drive produced. */
class Drive(val said: List<Said>, val cues: List<Pair<Double, Cue>>, val states: List<NavState>) {
    val texts: List<String> get() = said.map { it.text }
    val reroutes: List<Pair<Double, Cue.Reroute>> get() = cues.filter { it.second is Cue.Reroute }.map { it.first to it.second as Cue.Reroute }
    val arrivals: Int get() = cues.count { it.second == Cue.Arrived }
    fun first(fragment: String): Said = said.first { fragment in it.text }
    override fun toString() = said.joinToString("\n") { "%6.1f s %7.1f m  %s".format(it.t, it.along, it.text) }
}

/** Drives [nav] with [fixes]; stops after the arrival unless [untilEnd]. */
fun drive(nav: Navigator, fixes: Sequence<Fix>, untilEnd: Boolean = false): Drive {
    val said = ArrayList<Said>()
    val cues = ArrayList<Pair<Double, Cue>>()
    val states = ArrayList<NavState>()
    var t0: java.time.Instant? = null
    for (f in fixes) {
        val g = nav.update(f)
        val start = t0 ?: f.time.also { t0 = it }
        val t = java.time.Duration.between(start, f.time).toMillis() / 1000.0
        states += g.state
        for (c in g.cues) {
            cues += t to c
            if (c is Cue.Speak) said += Said(t, g.state.along, g.state.distanceToManeuver, c.text, c.id, c.priority, c.pan)
        }
        if (g.state.arrived && !untilEnd) break
    }
    return Drive(said, cues, states)
}

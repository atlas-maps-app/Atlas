package app.atlas.route

import app.atlas.geo.Geo
import app.atlas.geo.LatLon
import app.atlas.model.Hazard
import app.atlas.model.ManeuverType
import app.atlas.model.Maneuver
import app.atlas.model.Mode
import app.atlas.model.Route
import app.atlas.model.Span
import app.atlas.model.Step
import app.atlas.model.Turn
import kotlin.math.max
import kotlin.math.min

/**
 * Turns a [RawTrack] into a [Route]: the geometry and elevations, steps from the engine's hints (with DEPART, ARRIVE,
 * via points, ferries, locks and roundabout exits added), spans from the way tags, hazards, and climb and descent.
 */
class TrackConverter(private val namer: WayNamer? = null) {

    fun convert(raw: RawTrack, mode: Mode, extraHazards: List<Hazard> = emptyList()): Route {
        val n = raw.size
        require(n >= 2) { "a route needs two points" }
        val points = raw.points()
        val cumulative = raw.cumulative()
        val reader = TagReader()
        val time = monotonic(raw.time)
        if (mode.water) addLockDelays(raw.wayTags, raw.nodeTags, cumulative, time, reader)
        val elevations = filledElevations(raw.ele, cumulative)

        val steps = steps(raw, mode, points, cumulative, time, reader)
        val spans = spans(raw.wayTags, reader)
        val finder = HazardFinder(mode, points, cumulative, reader)
        if (elevations != null) finder.slopes(elevations)
        finder.ways(raw.wayTags)
        finder.nodes(raw.nodeTags)
        val hazards = ArrayList(finder.result())
        for (h in raw.hints) {
            if (h.giveWay && (mode.motor || mode.cycling) && h.index in 1 until n) {
                hazards += Hazard(HazardKind.GIVE_WAY, h.index, points[h.index], tags = mapOf("sign" to "give_way"))
            }
        }
        for (h in extraHazards) mergeOrAdd(hazards, h, cumulative)
        hazards.sortBy { it.pointIndex }

        val climb: Double
        val descent: Double
        if (elevations != null) {
            val net = elevations.last() - elevations.first()
            climb = if (!raw.filteredAscend.isNaN()) raw.filteredAscend else filteredAscent(elevations)
            descent = max(0.0, climb - net)
        } else {
            climb = 0.0
            descent = 0.0
        }
        return Route(
            mode = mode,
            points = points,
            elevations = elevations ?: DoubleArray(0),
            distance = cumulative.last(),
            duration = time.last(),
            steps = steps,
            spans = spans,
            hazards = hazards,
            climb = climb,
            descent = descent,
            source = raw.source,
        )
    }

    // ---------------------------------------------------------------- steps

    private class Mark(val index: Int, var maneuver: Maneuver, val rank: Int)

    private fun steps(raw: RawTrack, mode: Mode, points: List<LatLon>, cum: DoubleArray, time: DoubleArray, reader: TagReader): List<Step> {
        val n = points.size
        val marks = HashMap<Int, Mark>()
        fun mark(index: Int, maneuver: Maneuver, rank: Int) {
            if (index <= 0 || index >= n - 1) return
            val old = marks[index]
            if (old == null || rank > old.rank) {
                // keep the turn of a hint when a more important maneuver (via point, ferry) lands on the same point
                val turn = if (old != null && maneuver.turn == Turn.STRAIGHT) old.maneuver.turn else maneuver.turn
                val exit = maneuver.exit ?: old?.maneuver?.exit
                marks[index] = Mark(index, maneuver.copy(turn = turn, exit = exit), rank)
            }
        }
        for (h in raw.hints) {
            val m = maneuverOf(h) ?: continue
            mark(h.index, m, RANK_HINT)
            if (m.type == ManeuverType.ROUNDABOUT && (mode.motor || mode.cycling)) {
                roundaboutExit(raw.wayTags, h.index, reader)?.let { mark(it, Maneuver(ManeuverType.ROUNDABOUT_EXIT, m.turn, m.exit), RANK_HINT) }
            }
        }
        for (w in raw.waypointIndices) if (w in 1 until n - 1) mark(w, Maneuver(ManeuverType.WAYPOINT), RANK_WAYPOINT)
        // ferries and locks from the tags of the links
        var wasFerry = false
        var wasLock = false
        for (i in 1 until n) {
            val t = reader.tags(raw.wayTags[i])
            val ferry = t["route"] == "ferry"
            if (ferry && !wasFerry) mark(i - 1, Maneuver(ManeuverType.FERRY_BOARD), RANK_FERRY)
            if (!ferry && wasFerry) mark(i - 1, Maneuver(ManeuverType.FERRY_LEAVE), RANK_FERRY)
            wasFerry = ferry
            if (mode.water) {
                val lock = t["waterway"] == "lock"
                if (lock && !wasLock) mark(i - 1, Maneuver(ManeuverType.LOCK), RANK_FERRY)
                wasLock = lock
            }
        }
        if (mode.water) {
            var lastGate = -1.0e9
            for (i in 1 until n - 1) {
                val w = reader[raw.nodeTags[i], "waterway"]
                if ((w == "lock_gate" || w == "lock") && cum[i] - lastGate > 400) {
                    if (marks[i]?.maneuver?.type != ManeuverType.LOCK && (1..8).none { k -> marks[i - k]?.maneuver?.type == ManeuverType.LOCK && cum[i] - cum[i - k] < 400 }) {
                        mark(i, Maneuver(ManeuverType.LOCK), RANK_FERRY)
                    }
                    lastGate = cum[i]
                }
            }
        }

        val ordered = ArrayList<Mark>(marks.size + 2)
        ordered += Mark(0, Maneuver(ManeuverType.DEPART), RANK_FERRY)
        ordered += marks.values.sortedBy { it.index }
        ordered += Mark(n - 1, Maneuver(ManeuverType.ARRIVE), RANK_FERRY)

        // where each step's street is read, and the way of travel there: a little past the maneuver (before the arrival)
        val asks = ordered.mapIndexed { k, mk ->
            val i = mk.index
            val next = if (k + 1 < ordered.size) ordered[k + 1].index else i
            if (i < n - 1) {
                val along = min(cum[n - 1], cum[i] + min(15.0, (cum[next] - cum[i]) / 2))
                pointAt(points, cum, along) to bearingBetween(points, cum, indexBefore(cum, along), +1)
            } else {
                pointAt(points, cum, max(0.0, cum[i] - 5.0)) to bearingBetween(points, cum, i, -1)
            }
        }
        val routeNamer = namer?.forPoints(asks.map { it.first })
        val steps = ArrayList<Step>(ordered.size)
        for ((k, mk) in ordered.withIndex()) {
            val i = mk.index
            val next = if (k + 1 < ordered.size) ordered[k + 1].index else i
            val before = if (i > 0) bearingBetween(points, cum, i, -1) else null
            val after = if (i < n - 1) bearingBetween(points, cum, i, +1) else null
            val m = mk.maneuver.copy(bearingBefore = before, bearingAfter = after)
            val street = routeNamer?.name(asks[k].first, asks[k].second)
            steps += Step(
                maneuver = m,
                pointIndex = i,
                location = points[i],
                distance = cum[next] - cum[i],
                duration = time[next] - time[i],
                street = street,
            )
        }
        return steps
    }

    /** The point where the route leaves the roundabout entered at [entry], from junction=roundabout on the links. */
    private fun roundaboutExit(wayTags: Array<String?>, entry: Int, reader: TagReader): Int? {
        var i = entry + 1
        if (i >= wayTags.size || reader[wayTags[i], "junction"] != "roundabout") return null
        while (i + 1 < wayTags.size && reader[wayTags[i + 1], "junction"] == "roundabout") i++
        return if (i < wayTags.size - 1) i else null
    }

    // ---------------------------------------------------------------- spans

    private fun spans(wayTags: Array<String?>, reader: TagReader): List<Span> {
        val n = wayTags.size
        val out = ArrayList<Span>()
        for (key in SPAN_KEYS) {
            var start = -1
            var current: String? = null
            for (seg in 0 until n - 1) {
                val v = spanValue(key, reader.tags(wayTags[seg + 1]))
                if (v != current) {
                    if (current != null) out += Span(start, seg, key, current)
                    current = v
                    start = seg
                }
            }
            if (current != null) out += Span(start, n - 1, key, current)
        }
        out.sortWith(compareBy({ it.from }, { it.key }))
        return out
    }

    private fun spanValue(key: String, t: Map<String, String>): String? = when (key) {
        "route" -> if (t["route"] == "ferry") "ferry" else null
        "toll" -> if (t["toll"] == "yes") "yes" else null
        "cycleway" -> t["cycleway"] ?: t["cycleway:both"] ?: t[if (t["reversedirection"] == "yes") "cycleway:left" else "cycleway:right"]
        "maxspeed" -> (if (t["reversedirection"] == "yes") t["maxspeed:backward"] else t["maxspeed:forward"]) ?: t["maxspeed"]
        else -> t[key]
    }

    // ---------------------------------------------------------------- helpers

    private fun maneuverOf(h: RawHint): Maneuver? {
        // BRouter sums the turns through a roundabout, which can pass ±180°: fold it back to a direction
        val angleTurn = if (h.angle.isNaN()) Turn.STRAIGHT else Turn.of(Geo.turnAngle(0.0, h.angle))
        val fromMotorway = h.previousPriority >= MOTORWAY_PRIORITY && !h.previousLink
        val toMotorway = h.priority >= MOTORWAY_PRIORITY && !h.link
        return when (h.command) {
            HintCommand.END, HintCommand.OFF_ROUTE -> null
            HintCommand.CONTINUE -> when {
                h.previousLink && toMotorway -> Maneuver(ManeuverType.MERGE, Turn.STRAIGHT)
                else -> Maneuver(ManeuverType.CONTINUE, Turn.STRAIGHT)
            }
            HintCommand.LEFT, HintCommand.SLIGHT_LEFT, HintCommand.SHARP_LEFT,
            HintCommand.RIGHT, HintCommand.SLIGHT_RIGHT, HintCommand.SHARP_RIGHT -> {
                val turn = when (h.command) {
                    HintCommand.LEFT -> Turn.LEFT
                    HintCommand.SLIGHT_LEFT -> Turn.SLIGHT_LEFT
                    HintCommand.SHARP_LEFT -> Turn.SHARP_LEFT
                    HintCommand.RIGHT -> Turn.RIGHT
                    HintCommand.SLIGHT_RIGHT -> Turn.SLIGHT_RIGHT
                    else -> Turn.SHARP_RIGHT
                }
                Maneuver(rampType(h, fromMotorway, toMotorway) ?: ManeuverType.TURN, turn)
            }
            HintCommand.KEEP_LEFT, HintCommand.KEEP_RIGHT -> {
                val turn = if (h.command == HintCommand.KEEP_LEFT) Turn.SLIGHT_LEFT else Turn.SLIGHT_RIGHT
                Maneuver(rampType(h, fromMotorway, toMotorway) ?: ManeuverType.KEEP, turn)
            }
            HintCommand.U_TURN_LEFT, HintCommand.U_TURN_RIGHT, HintCommand.U_TURN -> Maneuver(ManeuverType.U_TURN, Turn.U_TURN)
            HintCommand.ROUNDABOUT, HintCommand.ROUNDABOUT_LEFT -> Maneuver(ManeuverType.ROUNDABOUT, angleTurn, kotlin.math.abs(h.exit).takeIf { it > 0 })
            HintCommand.BEELINE -> Maneuver(ManeuverType.CONTINUE, angleTurn)
            HintCommand.EXIT_LEFT -> Maneuver(ManeuverType.RAMP_OFF, Turn.SLIGHT_LEFT)
            HintCommand.EXIT_RIGHT -> Maneuver(ManeuverType.RAMP_OFF, Turn.SLIGHT_RIGHT)
        }
    }

    /** Ramps and merges, from the class of the ways before and after a turn. */
    private fun rampType(h: RawHint, fromMotorway: Boolean, toMotorway: Boolean): ManeuverType? = when {
        fromMotorway && h.link -> ManeuverType.RAMP_OFF
        !h.previousLink && h.link && h.priority >= MOTORWAY_PRIORITY - 1 && !fromMotorway -> ManeuverType.RAMP_ON
        h.previousLink && toMotorway -> ManeuverType.MERGE
        else -> null
    }

    private fun bearingBetween(points: List<LatLon>, cum: DoubleArray, i: Int, dir: Int): Double {
        // look at least 8 m away so tiny kinks at the junction do not decide the bearing
        var j = i + dir
        while (j in points.indices && kotlin.math.abs(cum[j] - cum[i]) < 8.0 && j + dir in points.indices) j += dir
        j = j.coerceIn(0, points.size - 1)
        if (j == i) return 0.0
        return if (dir > 0) Geo.bearing(points[i], points[j]) else Geo.bearing(points[j], points[i])
    }

    private fun indexBefore(cum: DoubleArray, x: Double): Int {
        var lo = 0
        var hi = cum.size - 2
        while (lo < hi) {
            val mid = (lo + hi + 1) ushr 1
            if (cum[mid] <= x) lo = mid else hi = mid - 1
        }
        return lo.coerceAtLeast(0)
    }

    private fun pointAt(points: List<LatLon>, cum: DoubleArray, x: Double): LatLon {
        val i = indexBefore(cum, x)
        val span = cum[i + 1] - cum[i]
        val t = if (span <= 0) 0.0 else ((x - cum[i]) / span).coerceIn(0.0, 1.0)
        return Geo.interpolate(points[i], points[i + 1], t)
    }

    /**
     * Adds [h], unless it is a lock the tags already showed within [LOCK_MERGE_METRES]: then that lock gets its tags
     * (its name, VHF channel or opening hours from the restriction source).
     */
    private fun mergeOrAdd(hazards: MutableList<Hazard>, h: Hazard, cum: DoubleArray) {
        if (h.kind == HazardKind.LOCK) {
            val k = hazards.indices.filter { hazards[it].kind == HazardKind.LOCK }
                .minByOrNull { kotlin.math.abs(cum[hazards[it].pointIndex] - cum[h.pointIndex]) }
            if (k != null && kotlin.math.abs(cum[hazards[k].pointIndex] - cum[h.pointIndex]) <= LOCK_MERGE_METRES) {
                hazards[k] = hazards[k].copy(tags = h.tags + hazards[k].tags, value = hazards[k].value ?: h.value)
                return
            }
        }
        hazards += h
    }

    /** Boats wait at locks: BRouter's time knows nothing of it, so each lock adds [LOCK_SECONDS]. */
    private fun addLockDelays(wayTags: Array<String?>, nodeTags: Array<String?>, cum: DoubleArray, time: DoubleArray, reader: TagReader) {
        var lastLock = -1.0e9
        var shift = 0.0
        for (i in time.indices) {
            time[i] += shift
            // the wait counts from the first gate (or the start of the lock way) onwards
            val enteringLockWay = i + 1 < time.size && reader[wayTags[i + 1], "waterway"] == "lock" && reader[wayTags[i], "waterway"] != "lock"
            val gate = reader[nodeTags[i], "waterway"].let { it == "lock_gate" || it == "lock" }
            if (enteringLockWay || gate) {
                if (cum[i] - lastLock > 400) shift += LOCK_SECONDS
                lastLock = cum[i]
            }
        }
    }

    companion object {
        /** The way tags reported as spans. */
        val SPAN_KEYS = listOf(
            "highway", "surface", "smoothness", "tracktype", "cycleway", "bicycle", "maxspeed", "incline", "lit",
            "waterway", "route", "toll", "bridge", "tunnel",
        )
        const val LOCK_SECONDS = 900.0
        private const val LOCK_MERGE_METRES = 400.0
        private const val MOTORWAY_PRIORITY = 28
        private const val RANK_HINT = 1
        private const val RANK_WAYPOINT = 2
        private const val RANK_FERRY = 3

        internal fun monotonic(t: DoubleArray): DoubleArray {
            val out = t.copyOf()
            // the start first, so a missing first time does not spread along the whole line
            if (out.isNotEmpty() && out[0].isNaN()) out[0] = 0.0
            for (i in 1 until out.size) if (out[i] < out[i - 1] || out[i].isNaN()) out[i] = out[i - 1]
            return out
        }

        /** Elevations with gaps filled by interpolation along the line; null when none is known. */
        internal fun filledElevations(ele: DoubleArray, cum: DoubleArray): DoubleArray? {
            val n = ele.size
            val nextKnown = IntArray(n)
            var nk = -1
            for (i in n - 1 downTo 0) {
                if (!ele[i].isNaN()) nk = i
                nextKnown[i] = nk
            }
            if (nextKnown[0] < 0) return null
            val out = ele.copyOf()
            var prev = -1
            for (i in 0 until n) {
                if (!ele[i].isNaN()) {
                    prev = i; continue
                }
                val next = nextKnown[i]
                out[i] = when {
                    prev < 0 -> ele[next]
                    next < 0 -> ele[prev]
                    else -> {
                        val span = cum[next] - cum[prev]
                        val t = if (span <= 0) 0.0 else (cum[i] - cum[prev]) / span
                        ele[prev] + (ele[next] - ele[prev]) * t
                    }
                }
            }
            return out
        }

        /** Ascent with a 5 m hysteresis, so noise in the elevation data does not add up. */
        internal fun filteredAscent(ele: DoubleArray): Double {
            if (ele.isEmpty()) return 0.0
            val threshold = 5.0
            var ascent = 0.0
            var low = ele[0]
            var high = ele[0]
            var climbing = false
            for (e in ele) {
                if (climbing) {
                    if (e > high) high = e
                    else if (e < high - threshold) {
                        ascent += high - low
                        low = e; high = e; climbing = false
                    }
                } else if (e < low) {
                    low = e
                } else if (e > low + threshold) {
                    climbing = true; high = e
                }
            }
            if (climbing) ascent += high - low
            return ascent
        }
    }
}

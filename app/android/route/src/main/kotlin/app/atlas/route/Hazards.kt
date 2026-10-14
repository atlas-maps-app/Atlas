package app.atlas.route

import app.atlas.geo.LatLon
import app.atlas.model.Hazard
import app.atlas.model.Mode
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * The kinds of [Hazard] the router reports. Stretch hazards carry their length in metres in tags["length"]
 * and their last point in tags["end"]; restriction hazards carry the limit in [Hazard.value] and tags["avoided"] = "yes"
 * when the route goes around them.
 */
object HazardKind {
    const val CLIMB = "climb"
    const val DESCENT = "descent"
    const val UNPAVED = "unpaved"
    const val COBBLES = "cobbles"
    const val STEPS = "steps"
    const val FERRY = "ferry"
    const val TOLL = "toll"
    const val TUNNEL = "tunnel"
    const val TRAM_TRACKS = "tram_tracks"
    const val UNLIT = "unlit"
    const val FORD = "ford"
    const val HEIGHT_RESTRICTOR = "height_restrictor"
    const val SPEED_CHANGE = "speed_change"
    const val SPEED_CAMERA = "speed_camera"
    const val SPEED_BUMP = "speed_bump"
    const val LEVEL_CROSSING = "level_crossing"
    const val CROSSING = "crossing"
    const val GIVE_WAY = "give_way"
    const val LOCK = "lock"
    const val WEIR = "weir"
    const val BRIDGE = "bridge"
    const val OPEN_WATER = "open_water"
    const val MAXHEIGHT = "maxheight"
    const val MAXWEIGHT = "maxweight"
    const val MAXWIDTH = "maxwidth"
    const val MAXLENGTH = "maxlength"
    const val MAXAXLELOAD = "maxaxleload"
    const val HAZMAT = "hazmat"
    const val HGV = "hgv"
    const val MAXDRAUGHT = "maxdraught"
    const val BATTERY = "battery"
    /** The vehicle's limits could not be checked (the restriction source failed); value-free, at the start. */
    const val UNCHECKED = "restrictions_unchecked"
    /** The route starts or ends this many metres ([Hazard.value]) from the asked point: no usable way was nearer. */
    const val OFF_NETWORK = "off_network"
}

/** When a slope counts as steep for a mode: over a window of [window] metres, at least [grade] percent, for at least [minLength] metres. */
internal data class SteepRule(val window: Double, val grade: Double, val minLength: Double, val minRise: Double) {
    companion object {
        fun forMode(mode: Mode): SteepRule? = when (mode) {
            Mode.WHEELCHAIR -> SteepRule(50.0, 5.0, 50.0, 3.0)
            Mode.WALK -> SteepRule(100.0, 10.0, 100.0, 8.0)
            Mode.HIKE -> SteepRule(200.0, 15.0, 200.0, 30.0)
            Mode.BIKE, Mode.ROAD_BIKE, Mode.CARGO_BIKE -> SteepRule(100.0, 5.0, 150.0, 8.0)
            Mode.EBIKE -> SteepRule(100.0, 8.0, 150.0, 10.0)
            Mode.MTB -> SteepRule(100.0, 10.0, 150.0, 12.0)
            Mode.MOPED, Mode.MOTORCYCLE, Mode.CAR, Mode.CAMPER -> SteepRule(200.0, 8.0, 300.0, 20.0)
            Mode.TRUCK -> SteepRule(200.0, 5.0, 500.0, 20.0)
            Mode.BOAT, Mode.SAIL, Mode.TRANSIT -> null
        }
    }
}

/** Finds the hazards of a converted track: slopes from the elevations, and what the way and node tags say. */
internal class HazardFinder(
    private val mode: Mode,
    private val points: List<LatLon>,
    private val cumulative: DoubleArray,
    private val reader: TagReader,
) {
    private val out = ArrayList<Hazard>()

    fun result(): List<Hazard> {
        val sorted = out.sortedWith(compareBy({ it.pointIndex }, { it.kind }))
        // a lock way and its gates are one lock
        var lastLock = Double.NEGATIVE_INFINITY
        return sorted.filter { h ->
            if (h.kind != HazardKind.LOCK) return@filter true
            val at = cumulative[h.pointIndex]
            val keep = at - lastLock > 400
            val end = h.tags["end"]?.toIntOrNull()?.let { cumulative[it] } ?: at
            lastLock = max(lastLock, end)
            keep
        }
    }

    private fun add(kind: String, index: Int, value: Double? = null, tags: Map<String, String> = emptyMap(), text: String? = null) {
        val i = index.coerceIn(0, points.size - 1)
        out += Hazard(kind, i, points[i], value, text, tags)
    }

    private fun stretch(kind: String, from: Int, to: Int, extra: Map<String, String> = emptyMap()) {
        val length = cumulative[to] - cumulative[from]
        add(kind, from, length, extra + mapOf("length" to fmt(length), "end" to to.toString()))
    }

    /** Steep climbs and descents from [elevations] (metres, gaps already filled) with a sliding window over a 10 m resampling. */
    fun slopes(elevations: DoubleArray) {
        val rule = SteepRule.forMode(mode) ?: return
        val total = cumulative.lastOrNull() ?: return
        if (elevations.size != points.size || total < rule.window * 2) return
        val step = 10.0
        val k = (total / step).toInt()
        val samples = DoubleArray(k + 1)
        var seg = 0
        for (s in 0..k) {
            val x = s * step
            while (seg < cumulative.size - 2 && cumulative[seg + 1] < x) seg++
            val span = cumulative[seg + 1] - cumulative[seg]
            val t = if (span <= 0) 0.0 else ((x - cumulative[seg]) / span).coerceIn(0.0, 1.0)
            samples[s] = elevations[seg] + (elevations[seg + 1] - elevations[seg]) * t
        }
        // smooth over ±20 m so single noisy samples do not make a hill
        val smooth = DoubleArray(samples.size)
        for (s in samples.indices) {
            var sum = 0.0
            var c = 0
            for (j in max(0, s - 2)..min(samples.size - 1, s + 2)) {
                sum += samples[j]; c++
            }
            smooth[s] = sum / c
        }
        val w = (rule.window / step).toInt().coerceAtLeast(1)
        if (smooth.size <= w) return
        val grades = DoubleArray(smooth.size - w) { (smooth[it + w] - smooth[it]) / rule.window * 100 }
        for (sign in intArrayOf(1, -1)) {
            var s = 0
            while (s < grades.size) {
                if (grades[s] * sign < rule.grade) {
                    s++; continue
                }
                var e = s
                var gap = 0
                var j = s + 1
                while (j < grades.size && gap <= w) {
                    if (grades[j] * sign >= rule.grade) {
                        e = j; gap = 0
                    } else gap++
                    j++
                }
                // the windows reach past the slope on both sides: trim to where the ground really rises (or falls)
                var a = s
                var b = min(smooth.size - 1, e + w)
                val local = rule.grade / 2 / 100 * (2 * step)
                while (a + 2 <= b && (smooth[a + 2] - smooth[a]) * sign < local) a++
                while (b - 2 >= a && (smooth[b] - smooth[b - 2]) * sign < local) b--
                if (b <= a) {
                    a = s; b = min(smooth.size - 1, e + w)
                }
                val startX = a * step
                val endX = min(total, b * step)
                val rise = (smooth[b] - smooth[a]) * sign
                val length = endX - startX
                var maxGrade = 0.0
                for (g in s..e) maxGrade = max(maxGrade, grades[g] * sign)
                if (length >= rule.minLength && rise >= rule.minRise && rise / length * 100 >= rule.grade * 0.75) {
                    val from = indexAt(startX)
                    val to = max(from, indexAt(endX))
                    add(
                        if (sign > 0) HazardKind.CLIMB else HazardKind.DESCENT, from, rise / length * 100,
                        mapOf("length" to fmt(length), "end" to to.toString(), "rise" to fmt(rise), "max_grade" to fmt(maxGrade)),
                    )
                }
                s = e + w
            }
        }
    }

    /** Stretch hazards from way tags; [wayTags] index i is the link ending at point i. */
    fun ways(wayTags: Array<String?>) {
        val n = points.size
        if (n < 2) return
        val land = !mode.water
        val cycling = mode.cycling
        val onFoot = mode.onFoot
        val motor = mode.motor
        runs(wayTags, { t -> OsmValues.isUnpaved(t) && t["highway"] != null }) { a, b, t ->
            if (land && mode != Mode.HIKE && mode != Mode.MTB) stretch(HazardKind.UNPAVED, a, b, surface(t))
        }
        runs(wayTags, { t -> OsmValues.isCobbles(t) }) { a, b, t ->
            if (cycling || mode == Mode.WHEELCHAIR || mode == Mode.MOPED || mode == Mode.MOTORCYCLE) stretch(HazardKind.COBBLES, a, b, surface(t))
        }
        runs(wayTags, { t -> t["highway"] == "steps" }) { a, b, _ -> if (land) stretch(HazardKind.STEPS, a, b) }
        runs(wayTags, { t -> t["route"] == "ferry" }) { a, b, _ -> stretch(HazardKind.FERRY, a, b) }
        runs(wayTags, { t -> t["toll"] == "yes" }) { a, b, _ -> if (motor) stretch(HazardKind.TOLL, a, b) }
        runs(wayTags, { t -> t["tunnel"] == "yes" }) { a, b, _ -> if (motor) stretch(HazardKind.TUNNEL, a, b) }
        runs(wayTags, { t -> t["embedded_rails"] != null || t["railway"] == "tram" }) { a, b, _ ->
            if (cycling || mode == Mode.MOPED || mode == Mode.MOTORCYCLE) stretch(HazardKind.TRAM_TRACKS, a, b)
        }
        runs(wayTags, { t -> t["lit"] == "no" }) { a, b, _ -> if (onFoot || cycling) stretch(HazardKind.UNLIT, a, b) }
        runs(wayTags, { t -> t["ford"] == "yes" || t["ford"] == "stepping_stones" }) { a, b, _ -> if (land) stretch(HazardKind.FORD, a, b) }
        runs(wayTags, { t -> t["waterway"] == "lock" }) { a, b, _ -> if (mode.water) stretch(HazardKind.LOCK, a, b) }
        runs(wayTags, { t -> t["direct_segment"] != null || t["atlas"] == "open_water" }) { a, b, _ ->
            stretch(HazardKind.OPEN_WATER, a, b)
        }
        // explicit inclines, for those who feel them
        if (cycling || mode == Mode.WHEELCHAIR || mode == Mode.TRUCK) {
            runs(wayTags, { t -> (OsmValues.inclinePercent(t["incline"], t["reversedirection"] == "yes")?.let { abs(it) } ?: 0.0) >= 8.0 }) { a, b, t ->
                val pct = OsmValues.inclinePercent(t["incline"], t["reversedirection"] == "yes") ?: 0.0
                add(
                    if (pct > 0) HazardKind.CLIMB else HazardKind.DESCENT, a, abs(pct),
                    mapOf("length" to fmt(cumulative[b] - cumulative[a]), "end" to b.toString(), "source" to "incline"),
                )
            }
        }
        if (motor) speedChanges(wayTags)
    }

    private fun speedChanges(wayTags: Array<String?>) {
        var last: Double? = null
        var lastRaw: String? = null
        for (i in 1 until points.size) {
            val t = reader.tags(wayTags[i])
            val reversed = t["reversedirection"] == "yes"
            val raw = (if (reversed) t["maxspeed:backward"] else t["maxspeed:forward"]) ?: t["maxspeed"] ?: continue
            val v = OsmValues.speedKmh(raw) ?: continue
            if (last != null && v != last) {
                add(HazardKind.SPEED_CHANGE, i - 1, v, mapOf("from" to lastRaw!!, "to" to raw))
            }
            last = v
            lastRaw = raw
        }
    }

    /** Point hazards from node tags. */
    fun nodes(nodeTags: Array<String?>) {
        val motor = mode.motor
        val land = !mode.water
        var lastLockGate = -1
        for (i in nodeTags.indices) {
            val t = reader.tags(nodeTags[i])
            if (t.isEmpty()) continue
            val barrier = t["barrier"]
            val highway = t["highway"]
            if (motor && barrier == "height_restrictor") add(HazardKind.HEIGHT_RESTRICTOR, i)
            if (motor && (barrier == "toll_booth" || highway == "toll_gantry")) add(HazardKind.TOLL, i, tags = mapOf("kind" to (barrier ?: highway!!)))
            if (motor && highway == "speed_camera") add(HazardKind.SPEED_CAMERA, i)
            if (land && !mode.onFoot && t["railway"] == "level_crossing") add(HazardKind.LEVEL_CROSSING, i)
            if (motor && t["traffic_calming"] != null) add(HazardKind.SPEED_BUMP, i, tags = mapOf("traffic_calming" to t["traffic_calming"]!!))
            if (land && t["ford"] != null) add(HazardKind.FORD, i)
            if (mode.onFoot && (highway == "crossing" || t["crossing"] != null)) {
                val signals = t["crossing"] == "traffic_signals" || highway == "traffic_signals"
                add(HazardKind.CROSSING, i, tags = mapOf("crossing" to (t["crossing"] ?: "yes"), "signals" to if (signals) "yes" else "no"))
            }
            if (mode.water && t["waterway"] == "weir") add(HazardKind.WEIR, i)
            if (mode.water && (t["waterway"] == "lock_gate" || t["waterway"] == "lock")) {
                if (lastLockGate < 0 || cumulative[i] - cumulative[lastLockGate] > 400) add(HazardKind.LOCK, i, tags = mapOf("gate" to "yes"))
                lastLockGate = i
            }
        }
    }

    /** Index of the point nearest to [x] metres along. */
    fun indexAt(x: Double): Int {
        var lo = 0
        var hi = cumulative.size - 1
        while (lo < hi) {
            val mid = (lo + hi + 1) ushr 1
            if (cumulative[mid] <= x) lo = mid else hi = mid - 1
        }
        return if (lo + 1 < cumulative.size && cumulative[lo + 1] - x < x - cumulative[lo]) lo + 1 else lo
    }

    /** Calls [block] for each maximal run of links matching [test], as point range [from, to]. */
    private inline fun runs(wayTags: Array<String?>, test: (Map<String, String>) -> Boolean, block: (Int, Int, Map<String, String>) -> Unit) {
        var start = -1
        var first: Map<String, String> = emptyMap()
        for (i in 1 until points.size) {
            val t = reader.tags(wayTags[i])
            val hit = test(t)
            if (hit && start < 0) {
                start = i - 1; first = t
            } else if (!hit && start >= 0) {
                block(start, i - 1, first); start = -1
            }
        }
        if (start >= 0) block(start, points.size - 1, first)
    }

    private fun surface(t: Map<String, String>): Map<String, String> {
        val m = HashMap<String, String>(2)
        t["surface"]?.let { m["surface"] = it }
        t["tracktype"]?.let { m["tracktype"] = it }
        return m
    }

    private fun fmt(v: Double): String = if (abs(v - v.roundToInt()) < 1e-9 || abs(v) >= 100) v.roundToInt().toString() else String.format(Locale.ROOT, "%.1f", v)
}

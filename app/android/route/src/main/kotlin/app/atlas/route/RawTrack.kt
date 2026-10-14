package app.atlas.route

import app.atlas.geo.Geo
import app.atlas.geo.LatLon

/** The turn an engine announces at a track point, in BRouter's vocabulary. */
enum class HintCommand {
    CONTINUE, LEFT, SLIGHT_LEFT, SHARP_LEFT, RIGHT, SLIGHT_RIGHT, SHARP_RIGHT, KEEP_LEFT, KEEP_RIGHT,
    U_TURN_LEFT, U_TURN_RIGHT, U_TURN, OFF_ROUTE,
    /** Counter-clockwise roundabout (right-hand traffic) and clockwise roundabout (left-hand traffic). */
    ROUNDABOUT, ROUNDABOUT_LEFT,
    /** A straight line off the network, e.g. open water. */
    BEELINE,
    EXIT_LEFT, EXIT_RIGHT, END,
}

/**
 * One engine hint at track index [index]. [exit] is the roundabout exit (1-based), [angle] the turn in degrees
 * (negative left, NaN when unknown); the priorities and link flags describe the way before and after, for ramps and merges.
 */
data class RawHint(
    val index: Int,
    val command: HintCommand,
    val exit: Int = 0,
    val angle: Double = Double.NaN,
    val giveWay: Boolean = false,
    val priority: Int = 0,
    val previousPriority: Int = 0,
    val link: Boolean = false,
    val previousLink: Boolean = false,
)

/**
 * A routed line as an engine produced it, before it becomes a [app.atlas.model.Route]: arrays per point and the engine's hints.
 * Link data ([wayTags]) at index i describes the stretch from point i-1 to point i, so index 0 holds nothing.
 */
class RawTrack(
    val lat: DoubleArray,
    val lon: DoubleArray,
    /** Metres above sea level, NaN where unknown. */
    val ele: DoubleArray,
    /** Seconds from the start. */
    val time: DoubleArray,
    /** Joules from the start; all zero when the engine has no energy model. */
    val energy: DoubleArray,
    /** Tags of the link ending at each point as "k=v k=v", null where unknown. */
    val wayTags: Array<String?>,
    /** Tags of the node at each point, null when it has none. */
    val nodeTags: Array<String?>,
    val hints: List<RawHint>,
    /** Track indices of the via points and the end, in order. */
    val waypointIndices: IntArray = IntArray(0),
    /** The engine's noise-filtered ascent in metres, NaN when it has none. */
    val filteredAscend: Double = Double.NaN,
    /** Which engine and profile made it, e.g. "brouter:atlas-bike". */
    val source: String = "",
) {
    init {
        val n = lat.size
        require(lon.size == n && ele.size == n && time.size == n && energy.size == n && wayTags.size == n && nodeTags.size == n) {
            "RawTrack arrays differ in length"
        }
    }

    val size: Int get() = lat.size

    fun point(i: Int) = LatLon(lat[i], lon[i])

    fun points(): List<LatLon> = List(size) { LatLon(lat[it], lon[it]) }

    /** Metres from the start to each point. */
    fun cumulative(): DoubleArray {
        val c = DoubleArray(size)
        for (i in 1 until size) c[i] = c[i - 1] + Geo.distance(lat[i - 1], lon[i - 1], lat[i], lon[i])
        return c
    }

    val length: Double get() = cumulative().let { if (it.isEmpty()) 0.0 else it.last() }
    val duration: Double get() = if (size == 0) 0.0 else time[size - 1]

    /**
     * This track followed by [next], which must start where this one ends (its first point is dropped).
     * Times, energies and indices of [next] are shifted; the joint becomes a via point.
     */
    fun append(next: RawTrack): RawTrack {
        if (size == 0) return next
        if (next.size == 0) return this
        val off = size - 1
        val n = size + next.size - 1
        val t0 = time[size - 1]
        val e0 = energy[size - 1]
        fun d(a: DoubleArray, b: DoubleArray, shift: Double) = DoubleArray(n) { if (it < size) a[it] else b[it - off] + shift }
        val ways = arrayOfNulls<String>(n)
        val nodes = arrayOfNulls<String>(n)
        for (i in 0 until n) {
            ways[i] = if (i < size) wayTags[i] else next.wayTags[i - off]
            nodes[i] = if (i < off) nodeTags[i] else if (i == off) nodeTags[i] ?: next.nodeTags[0] else next.nodeTags[i - off]
        }
        val hs = ArrayList<RawHint>(hints.size + next.hints.size)
        hints.filterTo(hs) { it.command != HintCommand.END }
        next.hints.mapTo(hs) { it.copy(index = it.index + off) }
        val wp = IntArray(waypointIndices.size + next.waypointIndices.size)
        var k = 0
        for (w in waypointIndices) wp[k++] = w
        for (w in next.waypointIndices) wp[k++] = w + off
        val wps = if (wp.contains(off)) wp else (wp + off).sortedArray()
        val asc = if (filteredAscend.isNaN() || next.filteredAscend.isNaN()) Double.NaN else filteredAscend + next.filteredAscend
        return RawTrack(
            d(lat, next.lat, 0.0), d(lon, next.lon, 0.0), d(ele, next.ele, 0.0), d(time, next.time, t0), d(energy, next.energy, e0),
            ways, nodes, hs, wps, asc, if (source == next.source) source else "$source+${next.source}",
        )
    }

    companion object {
        /**
         * A straight line (the great circle) from [a] to [b] at [speedKmh], split every [stepMetres] so it draws well on a globe,
         * tagged as [tags] (e.g. "atlas=open_water").
         */
        fun straight(a: LatLon, b: LatLon, speedKmh: Double, tags: String, source: String, stepMetres: Double = 2000.0): RawTrack {
            val dist = Geo.distance(a, b)
            val parts = maxOf(1, kotlin.math.ceil(dist / stepMetres).toInt())
            val n = parts + 1
            val lat = DoubleArray(n)
            val lon = DoubleArray(n)
            val time = DoubleArray(n)
            val ways = arrayOfNulls<String>(n)
            for (i in 0 until n) {
                val p = if (i == 0) a else if (i == parts) b else Geo.intermediate(a, b, i.toDouble() / parts)
                lat[i] = p.lat
                lon[i] = p.lon
                time[i] = dist * i / parts / (speedKmh / 3.6)
                if (i > 0) ways[i] = tags
            }
            return RawTrack(
                lat, lon, DoubleArray(n) { Double.NaN }, time, DoubleArray(n), ways, arrayOfNulls(n),
                listOf(RawHint(0, HintCommand.BEELINE, angle = 0.0)), intArrayOf(n - 1), 0.0, source,
            )
        }
    }
}

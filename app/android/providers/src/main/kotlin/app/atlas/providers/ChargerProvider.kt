package app.atlas.providers

import app.atlas.geo.Geo
import app.atlas.geo.LatLon
import app.atlas.geo.Polyline
import app.atlas.geo.Shapes
import app.atlas.geo.Snap
import app.atlas.model.Place
import java.time.Instant

/** What chargers to look for. Empty [connectors] means any; [minKw] 0 means any power. */
data class ChargerFilters(
    val minKw: Double = 0.0,
    /** OSM socket names, as in `EvSpec.connectors`. */
    val connectors: Set<String> = emptySet(),
    /** Only this operator or network (matched loosely). */
    val operator: String? = null,
    /** Keep chargers reported broken, planned or removed. */
    val includeNotWorking: Boolean = false,
    /** Only chargers a lorry can use (`hgv=yes` / `designated`, or a megawatt socket). */
    val hgv: Boolean = false,
    /** The most chargers [ChargerProvider.near] returns, and the most one request to a source's server asks for. */
    val maxResults: Int = 250,
) {
    // Normalised once, not once per charger tested.
    private val wantOperator: String? = operator?.let(NameMatch::normalise)?.takeIf { it.isNotEmpty() }

    /** True if the charger [place] passes these filters. */
    fun accepts(place: Place): Boolean {
        if (!Sockets.accepts(place.tags, connectors, minKw)) return false
        val want = wantOperator
        if (want != null) {
            val have = listOfNotNull(place["operator"], place["network"], place["brand"]).map(NameMatch::normalise)
            if (have.none { it.contains(want) || want.contains(it) && it.length >= 3 }) return false
        }
        if (hgv && place["hgv"] !in HGV && "socket:mcs" !in place.tags) return false
        if (!includeNotWorking && place.extra["status"] in NOT_WORKING) return false
        return true
    }

    private companion object {
        val HGV = setOf("yes", "designated")
        val NOT_WORKING = setOf(ChargerStatus.State.OUT_OF_ORDER.tag, ChargerStatus.State.PLANNED.tag, ChargerStatus.State.REMOVED.tag)
    }
}

/** Live (or last known) state of one charger. Counts are of charge points; null when the source does not say. */
data class ChargerStatus(
    val id: String,
    val state: State,
    val available: Int? = null,
    val total: Int? = null,
    val updated: Instant? = null,
    /** Per socket type: available and total. */
    val sockets: Map<String, Pair<Int, Int>> = emptyMap(),
) {
    /** [OPERATIONAL] means working, with no live availability; [AVAILABLE] and [OCCUPIED] come from live data. */
    enum class State(val tag: String) {
        AVAILABLE("available"), OCCUPIED("occupied"), OPERATIONAL("operational"), OUT_OF_ORDER("out_of_order"),
        PLANNED("planned"), REMOVED("removed"), UNKNOWN("unknown"),
    }

    /** The status as `extra` entries on a charger place: "status", "status:available", "status:total", "status:<socket>". */
    fun toExtra(): Map<String, String> = buildMap {
        put("status", state.tag)
        available?.let { put("status:available", it.toString()) }
        total?.let { put("status:total", it.toString()) }
        updated?.let { put("status:updated", it.toString()) }
        for ((type, at) in sockets) put("status:$type", "${at.first}/${at.second}")
    }
}

/**
 * A source of charging stations. Places come back in OSM's terms (`amenity=charging_station`, `socket:<type>=n`,
 * `socket:<type>:output`, `operator`, `network`, `fee`, `capacity`, plus [Sockets.POWER_KW]) with the source's own id in
 * `extra["charger:id"]` (for example "ocm:125731") and any live status in `extra` ([ChargerStatus.toExtra]).
 */
interface ChargerProvider : Provider {
    /** Chargers within [radiusM] of [center] that pass [filters], nearest first. */
    suspend fun near(center: LatLon, radiusM: Double, filters: ChargerFilters = ChargerFilters()): List<Place>

    /** Live status for chargers by their `charger:id`; ids this source does not know are left out. */
    suspend fun status(ids: List<String>): Map<String, ChargerStatus>

    /** Whether it answers with no network: its chargers are on the phone (a national register downloaded whole). */
    fun answersOffline(): Boolean = false

    /**
     * Every charger within [corridorM] of the line through [points] that passes [filters], in order along it. Its
     * [ChargerFilters.maxResults] caps only what a source's server is asked for in one request (Open Charge Map, AFDC),
     * never the corridor's answer: a long route can pass thousands, and the far end needs them as much as the start.
     * The default asks [near], uncapped, around points spaced along the line and keeps those inside the corridor;
     * sources with a real corridor search override it.
     */
    suspend fun along(points: List<LatLon>, corridorM: Double, filters: ChargerFilters = ChargerFilters()): List<Place> {
        if (points.isEmpty()) return emptyList()
        val step = (corridorM * 1.6).coerceAtLeast(1_000.0)
        val centres = ArrayList<LatLon>()
        centres.add(points.first())
        var carried = 0.0
        for (i in 1 until points.size) {
            val a = points[i - 1]
            val b = points[i]
            val d = Geo.distance(a, b)
            var t = step - carried
            while (t <= d) {
                centres.add(Geo.interpolate(a, b, t / d))
                t += step
            }
            carried = (carried + d) % step
        }
        if (centres.last() != points.last()) centres.add(points.last())
        // Circles this wide cover the whole corridor, even halfway between two centres.
        val radius = kotlin.math.hypot(corridorM, step / 2)
        val found = ArrayList<Place>()
        val each = filters.copy(maxResults = Int.MAX_VALUE)
        for (c in centres) found += near(c, radius, each)
        // The circles reach past the corridor between centres; keep only what lies within it.
        return inCorridor(points, corridorM, found)
    }
}

/** The common `charger:id` for a place from [source] with its own [id]. */
internal fun chargerId(source: String, id: String) = "$source:$id"

/**
 * The [places] within [corridorM] of the line through [points], each once (by `charger:id`), in order along the line:
 * what every [ChargerProvider.along] answers, however wide a net its source had to cast. A place is measured against a
 * segment ([measure]: the line's snap to it) only where the segment's box, grown by the corridor, holds the place, so
 * a long route's thousands of segments cost each place a comparison apiece, and a measure only near it.
 */
internal fun inCorridor(
    points: List<LatLon>,
    corridorM: Double,
    places: Iterable<Place>,
    measure: (Polyline, LatLon, Int) -> Snap? = { line, at, i -> line.snap(at, i, i) },
): List<Place> {
    if (points.isEmpty()) return emptyList()
    val line = if (points.size >= 2) Polyline(points) else null
    // Each segment's box grown by the corridor: a route of thousands of points is measured only where a charger is near.
    val boxes = line?.let { segmentBoxes(points, corridorM) }
    val seen = HashSet<String>()
    val kept = ArrayList<Pair<Double, Place>>()
    for (p in places) {
        if (!seen.add(p.extra["charger:id"] ?: p.key)) continue
        val at = p.location
        if (line == null || boxes == null) {
            val d = Geo.distance(points[0], at)
            if (d <= corridorM) kept.add(d to p)
            continue
        }
        var best = Double.POSITIVE_INFINITY
        var along = 0.0
        for (i in 0 until points.size - 1) {
            val b = 4 * i
            if (at.lat < boxes[b] || at.lat > boxes[b + 1] || at.lon < boxes[b + 2] || at.lon > boxes[b + 3]) continue
            val s = measure(line, at, i) ?: continue
            if (s.offset < best) {
                best = s.offset
                along = s.along
            }
        }
        if (best <= corridorM) kept.add(along to p)
    }
    kept.sortBy { it.first }
    return kept.map { it.second }
}

/** South, north, west and east of each segment of [points], grown by [metres] on every side. */
private fun segmentBoxes(points: List<LatLon>, metres: Double): DoubleArray {
    val out = DoubleArray(4 * (points.size - 1))
    val dLat = Geo.metresToDegreesLat(metres)
    for (i in 0 until points.size - 1) {
        val a = points[i]
        val b = points[i + 1]
        val south = minOf(a.lat, b.lat) - dLat
        val north = maxOf(a.lat, b.lat) + dLat
        val dLon = Geo.metresToDegreesLon(metres, maxOf(kotlin.math.abs(south), kotlin.math.abs(north)))
        out[4 * i] = south
        out[4 * i + 1] = north
        out[4 * i + 2] = minOf(a.lon, b.lon) - dLon
        out[4 * i + 3] = maxOf(a.lon, b.lon) + dLon
    }
    return out
}

/**
 * A line made lighter for a request: [points] simplified within [toleranceM], and how much wider a corridor around it
 * must be asked for ([extraM]) so that it still covers the corridor around the original line.
 */
internal class RequestLine(val points: List<LatLon>, val extraM: Double) {
    companion object {
        fun of(points: List<LatLon>, toleranceM: Double): RequestLine {
            if (points.size <= 2) return RequestLine(points, 0.0)
            val simple = Shapes.simplify(points, toleranceM)
            return RequestLine(simple, if (simple.size < points.size) toleranceM else 0.0)
        }
    }
}

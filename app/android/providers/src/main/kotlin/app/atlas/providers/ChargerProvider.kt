package app.atlas.providers

import app.atlas.geo.Geo
import app.atlas.geo.LatLon
import app.atlas.geo.Polyline
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
    val maxResults: Int = 250,
) {
    /** True if the charger [place] passes these filters. */
    fun accepts(place: Place): Boolean {
        if (!Sockets.accepts(place.tags, connectors, minKw)) return false
        if (operator != null) {
            val want = NameMatch.normalise(operator)
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

    /**
     * Chargers within [corridorM] of the line through [points], in order along it. The default asks [near] around
     * points spaced along the line and keeps those inside the corridor; sources with a real corridor search override it.
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
        val seen = LinkedHashMap<String, Place>()
        for (c in centres) for (p in near(c, radius, filters)) seen.putIfAbsent(p.extra["charger:id"] ?: p.key, p)
        // The circles reach past the corridor between centres; keep only what lies within it.
        val line = Polyline(points)
        return seen.values.filter { p -> (line.snap(p.location)?.offset ?: Geo.distance(points[0], p.location)) <= corridorM }
    }
}

/** The common `charger:id` for a place from [source] with its own [id]. */
internal fun chargerId(source: String, id: String) = "$source:$id"

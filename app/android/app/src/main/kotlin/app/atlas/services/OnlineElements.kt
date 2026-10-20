package app.atlas.services

import app.atlas.geo.Geo
import app.atlas.geo.LatLon
import app.atlas.geo.Shapes
import app.atlas.providers.Overpass
import app.atlas.providers.OverpassArea
import app.atlas.render.data.MemoryBoxes
import app.atlas.render.data.OsmElement
import app.atlas.render.data.OverpassJsonText
import app.atlas.render.data.PackFeatures
import kotlinx.coroutines.CancellationException
import java.util.Locale

/**
 * OpenStreetMap elements asked of Overpass for the features where no pack on the phone reaches: the named streets and
 * waterways at a route's steps or around a point, and the bridges along a line. The answers are whole elements with
 * their geometry, held as [MemoryBoxes], so [PackFeatures]' own street namer and bridge finder run on them exactly as on
 * a pack: online and offline answer alike. The last few answers are kept for [KEEP_MS], so asking again (a route
 * planned again, the same pin) costs no request. Overpass is the app's own client: four servers, one query at a time.
 */
class OnlineElements(
    private val overpass: () -> Overpass?,
    private val isOnline: () -> Boolean,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private class Kept(val at: Long, val features: PackFeatures)

    private val kept = object : LinkedHashMap<String, Kept>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Kept>?) = size > MAX_KEPT
    }

    /** True when asking can work: a client, and the phone online. */
    fun available(): Boolean = overpass() != null && isOnline()

    /**
     * The named streets and waterways within [metres] of each of [points] (a route's steps, a pin), as features; null
     * offline or when Overpass cannot answer. Points closer than half of [metres] to one asked already are covered by
     * its circle, widened to match.
     */
    suspend fun streetsAt(points: List<LatLon>, metres: Double): PackFeatures? {
        if (points.isEmpty()) return null
        val asked = spread(points, metres / 2)
        val radius = metres * 1.5
        val key = "streets/${num(radius)}/" + asked.joinToString(";") { "${num(it.lat)},${num(it.lon)}" }
        return ask(key) { op ->
            val parts = asked.chunked(POINTS_PER_QUERY).map { chunk ->
                buildString {
                    append("[out:json][timeout:60];\n(\n")
                    for (p in chunk) {
                        val area = OverpassArea.Around(p, radius).clause()
                        for (s in STREETS) append("  way").append(s).append(area).append(";\n")
                    }
                    append(");\nout tags geom;")
                }
            }
            parts.flatMap { ql -> OverpassJsonText(op.raw(ql)).elements().toList() }
        }
    }

    /**
     * The bridges within [metres] of [line] (bridge ways and `man_made=bridge` outlines), as features; null offline or
     * when Overpass cannot answer. The line is simplified for the request and the corridor widened by as much.
     */
    suspend fun bridgesAlong(line: List<LatLon>, metres: Double): PackFeatures? {
        if (line.size < 2) return null
        val tolerance = metres / 4
        val simple = Shapes.simplify(line, tolerance)
        val radius = metres + if (simple.size < line.size) tolerance else 0.0
        val key = "bridges/${num(radius)}/" + simple.joinToString(";") { "${num(it.lat)},${num(it.lon)}" }
        return ask(key) { op ->
            // one statement per stretch, the stretches sharing their ends, so no part of the line is left out
            val stretches = (0 until simple.size - 1 step POINTS_PER_LINE - 1).map { simple.subList(it, minOf(simple.size, it + POINTS_PER_LINE)) }
            val ql = buildString {
                append("[out:json][timeout:90];\n(\n")
                for (s in stretches) {
                    val area = OverpassArea.Along(s, radius).clause()
                    for (b in BRIDGES) append("  ").append(b).append(area).append(";\n")
                }
                append(");\nout tags geom;")
            }
            OverpassJsonText(op.raw(ql)).elements().toList()
        }
    }

    private suspend fun ask(key: String, fetch: suspend (Overpass) -> List<OsmElement>): PackFeatures? {
        val now = clock()
        synchronized(kept) { kept[key] }?.takeIf { now - it.at < KEEP_MS }?.let { return it.features }
        val op = overpass()?.takeIf { isOnline() } ?: return null
        val elements = try {
            fetch(op)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // no server could answer, or an answer that does not parse (a captive portal's page): nothing to name with
            return null
        }
        val features = PackFeatures(listOf(MemoryBoxes(elements)))
        synchronized(kept) { kept[key] = Kept(now, features) }
        return features
    }

    /** [points] without those closer than [apart] metres to one kept before them. */
    private fun spread(points: List<LatLon>, apart: Double): List<LatLon> {
        val out = ArrayList<LatLon>()
        for (p in points) if (out.none { Geo.distance(it, p) < apart }) out += p
        return out
    }

    private fun num(v: Double) = String.format(Locale.ROOT, "%.5f", v)

    companion object {
        /** How long an answer is kept (ms): a route planned again within the hour asks nothing. */
        const val KEEP_MS = 3_600_000L
        private const val MAX_KEPT = 24

        /** Points per request when naming a route's steps, and points per stretch of a line. */
        private const val POINTS_PER_QUERY = 150
        private const val POINTS_PER_LINE = 200

        /** What [PackFeatures.isStreet] names: highways with a name or a ref, and named waterways. */
        private val STREETS = listOf("[\"highway\"][\"name\"]", "[\"highway\"][\"ref\"]", "[\"waterway\"][\"name\"]")

        /** What [PackFeatures.isBridge] finds: bridge ways and bridge outlines. */
        private val BRIDGES = listOf("way[\"bridge\"]", "way[\"man_made\"=\"bridge\"]", "relation[\"man_made\"=\"bridge\"]")
    }
}

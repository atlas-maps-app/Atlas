package app.atlas.providers

import app.atlas.geo.BBox
import app.atlas.geo.Geo
import app.atlas.geo.PointIndex
import app.atlas.model.Place

/**
 * Merges chargers from several sources into one list without doubles: two records are the same charger when they lie
 * within [radiusM] of each other, come from different sources, and have the same operator (names compared loosely:
 * "Fastned" is "Fastned B.V."). When only one side names an operator, they must be within a third of the radius.
 *
 * The record from the most trusted source ([priority]: OSM first, for its mapped position and full tags) leads; the
 * others fill in what it lacks: missing tags, sockets when it has none, and live status, the freshest live one winning.
 * The merged place lists its sources in `extra["sources"]` and every source id in `extra["charger:ids"]`.
 */
object ChargerMerge {
    /** Sources in order of trust: the first one present leads a merged charger. */
    val PRIORITY = listOf("osm", "ndw", "bnetza", "afdc", "ocm", "chargemap")

    fun merge(sources: List<List<Place>>, radiusM: Double = 30.0, priority: List<String> = PRIORITY): List<Place> {
        val all = sources.flatten()
        if (all.size < 2) return all
        val rank = { p: Place -> priority.indexOf(p.source).let { if (it < 0) priority.size else it } }
        val order = all.indices.sortedWith(compareBy<Int> { rank(all[it]) }.thenBy { it })
        // Indexed in degrees and measured on the sphere: one flat frame would stretch distances far from its origin.
        val index = PointIndex(all.size).apply { for (p in all) add(p.location.lon, p.location.lat) }.finish()
        val taken = BooleanArray(all.size)
        val out = ArrayList<Place>(all.size)
        for (i in order) {
            if (taken[i]) continue
            taken[i] = true
            val lead = all[i]
            val group = ArrayList<Place>()
            val sourcesInGroup = hashSetOf(lead.source)
            val box = BBox.around(lead.location, radiusM)
            val near = index.range(box.west, box.south, box.east, box.north)
                .filter { !taken[it] && all[it].source != lead.source }
                .map { it to Geo.distance(lead.location, all[it].location) }
                .filter { (_, d) -> d <= radiusM }
                .sortedWith(compareBy<Pair<Int, Double>> { rank(all[it.first]) }.thenBy { it.second })
            for ((j, d) in near) {
                val other = all[j]
                // One record per source joins a group: two NDW locations 20 m apart are two chargers.
                if (other.source in sourcesInGroup) continue
                if (!sameOperator(lead, other, d, radiusM)) continue
                taken[j] = true
                sourcesInGroup += other.source
                group += other
            }
            out += if (group.isEmpty()) lead else combine(lead, group)
        }
        return out
    }

    /** True when [a] and [b] name the same operator, network or brand; with a name on one side only, closeness decides. */
    fun sameOperator(a: Place, b: Place, distanceM: Double, radiusM: Double = 30.0): Boolean {
        val na = names(a)
        val nb = names(b)
        if (na.isEmpty() || nb.isEmpty()) return distanceM <= radiusM / 3
        for (x in na) for (y in nb) {
            if (x == y) return true
            val short = if (x.length <= y.length) x else y
            val long = if (short === x) y else x
            if (short.length >= 3 && long.split(' ').any { it == short || it.startsWith(short) && short.length >= 5 }) return true
            if (NameMatch.similarity(x, y) >= 0.85) return true
        }
        return false
    }

    private fun names(p: Place): List<String> =
        listOfNotNull(p["operator"], p["network"], p["brand"]).map { strip(NameMatch.normalise(it)) }.filter { it.isNotEmpty() }.distinct()

    /** Drops legal forms and filler so "Fastned B.V." meets "Fastned". */
    private fun strip(s: String): String = s.split(' ').filter { it !in LEGAL }.joinToString(" ")

    private val LEGAL = setOf("bv", "b", "v", "nv", "gmbh", "ag", "co", "kg", "ltd", "inc", "llc", "sa", "sas", "srl", "spa", "ab", "as", "oy", "the", "charging", "netherlands", "nederland", "deutschland")

    private fun combine(lead: Place, others: List<Place>): Place {
        val tags = LinkedHashMap(lead.tags)
        val leadHasSockets = tags.keys.any { it.startsWith("socket:") }
        for (o in others) for ((k, v) in o.tags) {
            if (k.startsWith("socket:") && leadHasSockets) continue
            if (k == Sockets.POWER_KW && leadHasSockets) continue
            tags.putIfAbsent(k, v)
        }
        if (!leadHasSockets) others.firstOrNull { o -> o.tags.keys.any { it.startsWith("socket:") } }?.let { o ->
            o.tags.filterKeys { it.startsWith("socket:") || it == Sockets.POWER_KW }.forEach { (k, v) -> tags[k] = v }
        }
        val extra = LinkedHashMap(lead.extra)
        for (o in others) for ((k, v) in o.extra) if (!k.startsWith("status")) extra.putIfAbsent(k, v)
        // Live status beats a static one; between live ones the most recently updated wins.
        val withStatus = (listOf(lead) + others).filter { it.extra["status"] != null }
        val best = withStatus.maxWithOrNull(compareBy<Place> { if (it.extra["status"] in LIVE) 1 else 0 }.thenBy { it.extra["status:updated"].orEmpty() })
        if (best != null) {
            extra.keys.removeAll { it.startsWith("status") }
            best.extra.filterKeys { it.startsWith("status") }.forEach { (k, v) -> extra[k] = v }
            extra["status:source"] = best.source
        }
        val all = listOf(lead) + others
        extra["sources"] = all.joinToString(",") { it.source }
        extra["charger:ids"] = all.mapNotNull { it.extra["charger:id"] }.joinToString(",")
        all.mapNotNull { it.extra["attribution"] }.distinct().takeIf { it.size > 1 }?.let { extra["attribution"] = it.joinToString(" · ") }
        return lead.copy(tags = tags, extra = extra, id = lead.id ?: others.firstNotNullOfOrNull { it.id })
    }

    private val LIVE = setOf(ChargerStatus.State.AVAILABLE.tag, ChargerStatus.State.OCCUPIED.tag, ChargerStatus.State.OUT_OF_ORDER.tag)
}

package app.atlas.packer

import app.atlas.geo.BBox
import app.atlas.packer.pbf.OsmHandler
import app.atlas.packer.pbf.PbfNode
import app.atlas.packer.pbf.PbfReader
import app.atlas.packer.pbf.PbfRelation
import app.atlas.packer.pbf.PbfWay
import app.atlas.render.data.Bounds
import app.atlas.render.data.ElementSource
import app.atlas.render.data.ElementType
import app.atlas.render.data.ListElementSource
import app.atlas.render.data.Member
import app.atlas.render.data.OsmElement
import app.atlas.render.data.Points
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.File
import java.time.Instant

/**
 * A box cut from a Geofabrik extract (an OSM PBF file) as the elements Overpass would answer with the query of
 * `fetch/regions.py` (`out geom`): every tagged node in the box; every way with a node in the box, whole, with all its
 * nodes and their positions; every multipolygon relation, national park and protected area with a member in the box,
 * its member ways whole. Elements come nodes, ways, relations, each by ascending id; tags in key order; coordinates
 * at 1e-7 degrees. A reference the extract does not hold (a way cut at the extract's edge) is left out of the way.
 *
 * Four passes, memory proportional to the box and not to the file: (1) nodes, ways and relations in file order, keeping
 * what touches the box; (2) the ways, for the member ways of kept relations that do not themselves touch the box;
 * (3) the nodes, for the positions of every node the kept ways and relations name. A way that crosses the box with no
 * node inside it is found when the segment's two ends lie within 0.1 degrees of the box; a longer segment (a ferry
 * line, a power line) is missed.
 */
object PbfRegion {
    /** What was cut: the elements and the header an Overpass answer would carry. */
    class Extract(val header: JsonObject, val elements: List<OsmElement>) {
        fun source(): ElementSource = ListElementSource(elements, header)
    }

    private class KeptNode(val id: Long, val latE7: Int, val lonE7: Int, val tags: Map<String, String>?)
    private class KeptWay(val id: Long, val refs: LongArray, val tags: Map<String, String>?)
    private class KeptRelation(val id: Long, val tags: Map<String, String>?, val types: IntArray, val refs: LongArray, val roles: Array<String>)

    fun extract(pbf: File, box: BBox, threads: Int = PbfReader.defaultThreads(), log: (String) -> Unit = {}): Extract {
        val started = System.nanoTime()
        fun seconds() = (System.nanoTime() - started) / 1_000_000_000
        val reader = PbfReader(pbf, threads)
        val header = reader.header

        // pass 1
        val first = FirstPass(box)
        reader.read(first)
        first.finishNodes()
        log("pass 1: ${first.nodesRead} nodes (${first.keptNodes.size} tagged in the box), ${first.keptWays.size} ways and ${first.keptRelations.size} relations kept (${seconds()} s)")

        // pass 2: member ways that do not touch the box
        val wayRefs = HashMap<Long, LongArray>()
        for (w in first.keptWays) wayRefs[w.id] = w.refs
        val missing = HashSet<Long>()
        for (r in first.keptRelations) for (i in r.refs.indices) if (r.types[i] == PbfRelation.WAY && r.refs[i] !in wayRefs) missing += r.refs[i]
        if (missing.isNotEmpty()) {
            reader.read(object : OsmHandler {
                override fun way(way: PbfWay) {
                    if (way.id in missing) wayRefs[way.id] = LongArray(way.refCount) { way.ref(it) }
                }
            }, PbfReader.WAYS)
        }
        log("pass 2: ${missing.size} member ways outside the box, ${missing.count { it in wayRefs }} found (${seconds()} s)")

        // pass 3: positions of every node named
        val need = LongSet()
        for (refs in wayRefs.values) for (id in refs) need.add(id)
        for (r in first.keptRelations) for (i in r.refs.indices) if (r.types[i] == PbfRelation.NODE) need.add(r.refs[i])
        val ids = need.sorted()
        val lats = IntArray(ids.size)
        val lons = IntArray(ids.size)
        val found = BooleanArray(ids.size)
        if (ids.isNotEmpty()) {
            reader.read(object : OsmHandler {
                override fun node(node: PbfNode) {
                    val id = node.id
                    if (id > ids[ids.size - 1] || id < ids[0]) return
                    val at = java.util.Arrays.binarySearch(ids, id)
                    if (at >= 0) {
                        lats[at] = node.latE7
                        lons[at] = node.lonE7
                        found[at] = true
                    }
                }
            }, PbfReader.NODES)
        }
        log("pass 3: ${ids.size} node positions, ${found.count { it }} found (${seconds()} s)")

        fun at(id: Long): Int = java.util.Arrays.binarySearch(ids, id).let { if (it >= 0 && found[it]) it else -1 }

        val out = ArrayList<OsmElement>(first.keptNodes.size + first.keptWays.size + first.keptRelations.size)
        for (n in first.keptNodes.sortedBy { it.id }) out += OsmElement(ElementType.NODE, n.id, n.latE7 / E7, n.lonE7 / E7, n.tags)

        fun geometry(refs: LongArray): Pair<LongArray, Points>? {
            var n = 0
            for (id in refs) if (at(id) >= 0) n++
            if (n == 0) return null
            val nodes = LongArray(n)
            val la = DoubleArray(n)
            val lo = DoubleArray(n)
            var k = 0
            for (id in refs) {
                val i = at(id)
                if (i < 0) continue
                nodes[k] = id
                la[k] = lats[i] / E7
                lo[k] = lons[i] / E7
                k++
            }
            return nodes to Points(la, lo)
        }

        val geometries = HashMap<Long, Pair<LongArray, Points>?>()
        fun wayGeometry(id: Long): Pair<LongArray, Points>? = geometries.getOrPut(id) { wayRefs[id]?.let { geometry(it) } }

        for (w in first.keptWays.sortedBy { it.id }) {
            val g = wayGeometry(w.id) ?: continue
            out += OsmElement(ElementType.WAY, w.id, tags = w.tags, bounds = boundsOf(g.second), nodes = g.first, geometry = g.second)
        }
        for (r in first.keptRelations.sortedBy { it.id }) {
            val members = ArrayList<Member>(r.refs.size)
            var minLat = Double.POSITIVE_INFINITY; var minLon = minLat; var maxLat = Double.NEGATIVE_INFINITY; var maxLon = maxLat
            fun add(la: Double, lo: Double) { if (la < minLat) minLat = la; if (la > maxLat) maxLat = la; if (lo < minLon) minLon = lo; if (lo > maxLon) maxLon = lo }
            for (i in r.refs.indices) {
                val role = r.roles[i]
                when (r.types[i]) {
                    PbfRelation.WAY -> {
                        val g = wayGeometry(r.refs[i])?.second
                        members += Member(ElementType.WAY, r.refs[i], role, geometry = g)
                        if (g != null) for (j in 0 until g.size) add(g.lat[j], g.lon[j])
                    }
                    PbfRelation.NODE -> {
                        val j = at(r.refs[i])
                        if (j >= 0) {
                            members += Member(ElementType.NODE, r.refs[i], role, lats[j] / E7, lons[j] / E7)
                            add(lats[j] / E7, lons[j] / E7)
                        } else members += Member(ElementType.NODE, r.refs[i], role)
                    }
                    else -> members += Member(ElementType.RELATION, r.refs[i], role)
                }
            }
            val bounds = if (minLat <= maxLat) Bounds(minLat, minLon, maxLat, maxLon) else null
            out += OsmElement(ElementType.RELATION, r.id, tags = r.tags, bounds = bounds, members = members)
        }
        log("${out.size} elements (${seconds()} s)")
        return Extract(headerOf(header.replicationTime), out)
    }

    /** The header an Overpass answer has, for an extract current to [time]. */
    fun headerOf(time: Instant?): JsonObject {
        val m = LinkedHashMap<String, kotlinx.serialization.json.JsonElement>()
        m["version"] = JsonPrimitive(0.6)
        m["generator"] = JsonPrimitive("Atlas packer from an OpenStreetMap PBF extract")
        val osm3s = LinkedHashMap<String, kotlinx.serialization.json.JsonElement>()
        if (time != null) osm3s["timestamp_osm_base"] = JsonPrimitive(time.toString())
        osm3s["copyright"] = JsonPrimitive("The data included in this document is from www.openstreetmap.org. The data is made available under ODbL.")
        m["osm3s"] = JsonObject(osm3s)
        return JsonObject(m)
    }

    private fun boundsOf(p: Points): Bounds {
        var a = Double.POSITIVE_INFINITY; var b = a; var c = Double.NEGATIVE_INFINITY; var d = c
        for (i in 0 until p.size) {
            if (p.lat[i] < a) a = p.lat[i]; if (p.lat[i] > c) c = p.lat[i]
            if (p.lon[i] < b) b = p.lon[i]; if (p.lon[i] > d) d = p.lon[i]
        }
        return Bounds(a, b, c, d)
    }

    private const val E7 = 1e7

    /** Overpass keeps tags in key order. */
    private fun sortedTags(t: Map<String, String>): Map<String, String>? {
        if (t.isEmpty()) return null
        val keys = t.keys.toTypedArray()
        java.util.Arrays.sort(keys, ::compareUtf8)
        val m = LinkedHashMap<String, String>(keys.size * 2)
        for (k in keys) m[k] = t.getValue(k)
        return m
    }

    /** Strings in the byte order of their UTF-8, as the database sorts them. */
    internal fun compareUtf8(a: String, b: String): Int {
        val x = a.toByteArray(Charsets.UTF_8)
        val y = b.toByteArray(Charsets.UTF_8)
        val n = minOf(x.size, y.size)
        for (i in 0 until n) {
            val d = (x[i].toInt() and 0xff) - (y[i].toInt() and 0xff)
            if (d != 0) return d
        }
        return x.size - y.size
    }

    private fun isKeptRelation(t: Map<String, String>): Boolean {
        if (t["type"] == "multipolygon") return true
        val b = t["boundary"]
        return b == "national_park" || b == "protected_area"
    }

    /** Nodes this far outside the box (degrees) keep their positions, so a way crossing the box between two of them is seen. */
    private const val PAD = 0.1

    private class FirstPass(val box: BBox) : OsmHandler {
        val keptNodes = ArrayList<KeptNode>()
        val keptWays = ArrayList<KeptWay>()
        val keptRelations = ArrayList<KeptRelation>()
        var nodesRead = 0L

        // the nodes in the padded box, ascending by id as the file lists them, with their positions
        private var padIds = LongArray(1024)
        private var padLat = IntArray(1024)
        private var padLon = IntArray(1024)
        private var padCount = 0
        private var padSorted = true
        private var lastId = Long.MIN_VALUE
        private var ready = false
        private val wayIds = LongSet()

        private val padSouth = box.south - PAD
        private val padNorth = box.north + PAD
        private val padWest = box.west - PAD
        private val padEast = box.east + PAD

        private fun fix() {
            if (ready) return
            ready = true
            if (padSorted) return
            val order = (0 until padCount).sortedBy { padIds[it] }
            val ids = LongArray(padCount) { padIds[order[it]] }
            val lat = IntArray(padCount) { padLat[order[it]] }
            val lon = IntArray(padCount) { padLon[order[it]] }
            padIds = ids
            padLat = lat
            padLon = lon
        }

        fun finishNodes() = fix()

        private fun slot(id: Long): Int {
            fix()
            return java.util.Arrays.binarySearch(padIds, 0, padCount, id)
        }

        private fun within(la: Double, lo: Double) = la >= box.south && la <= box.north && lo >= box.west && lo <= box.east

        private fun inBox(id: Long): Boolean {
            val i = slot(id)
            return i >= 0 && within(padLat[i] / E7, padLon[i] / E7)
        }

        override fun node(node: PbfNode) {
            nodesRead++
            val lat = node.latE7 / E7
            val lon = node.lonE7 / E7
            if (lat < padSouth || lat > padNorth || lon < padWest || lon > padEast) return
            if (padCount == padIds.size) {
                padIds = padIds.copyOf(padCount * 2)
                padLat = padLat.copyOf(padCount * 2)
                padLon = padLon.copyOf(padCount * 2)
            }
            if (node.id < lastId) padSorted = false
            lastId = node.id
            padIds[padCount] = node.id
            padLat[padCount] = node.latE7
            padLon[padCount] = node.lonE7
            padCount++
            if (node.tags.isNotEmpty() && within(lat, lon)) keptNodes += KeptNode(node.id, node.latE7, node.lonE7, sortedTags(node.tags.toMap()))
        }

        override fun way(way: PbfWay) {
            var hit = false
            var previous = -1
            for (i in 0 until way.refCount) {
                val k = slot(way.ref(i))
                if (k >= 0 && within(padLat[k] / E7, padLon[k] / E7)) { hit = true; break }
                // a segment between two nearby nodes that passes through the box
                if (k >= 0 && previous >= 0 && crosses(padLon[previous] / E7, padLat[previous] / E7, padLon[k] / E7, padLat[k] / E7)) { hit = true; break }
                previous = k
            }
            if (!hit) return
            keptWays += KeptWay(way.id, LongArray(way.refCount) { way.ref(it) }, sortedTags(way.tags.toMap()))
            wayIds.add(way.id)
        }

        /** Whether the segment from (x0, y0) to (x1, y1) meets the box (Liang and Barsky). */
        private fun crosses(x0: Double, y0: Double, x1: Double, y1: Double): Boolean {
            var t0 = 0.0
            var t1 = 1.0
            val dx = x1 - x0
            val dy = y1 - y0
            val p = doubleArrayOf(-dx, dx, -dy, dy)
            val q = doubleArrayOf(x0 - box.west, box.east - x0, y0 - box.south, box.north - y0)
            for (i in 0 until 4) {
                if (p[i] == 0.0) {
                    if (q[i] < 0) return false
                } else {
                    val r = q[i] / p[i]
                    if (p[i] < 0) { if (r > t1) return false; if (r > t0) t0 = r } else { if (r < t0) return false; if (r < t1) t1 = r }
                }
            }
            return true
        }

        override fun relation(relation: PbfRelation) {
            val tags = relation.tags.toMap()
            if (!isKeptRelation(tags)) return
            var hit = false
            for (i in 0 until relation.memberCount) {
                val id = relation.memberId(i)
                when (relation.memberType(i)) {
                    PbfRelation.WAY -> if (id in wayIds) hit = true
                    PbfRelation.NODE -> if (inBox(id)) hit = true
                }
                if (hit) break
            }
            if (!hit) return
            val n = relation.memberCount
            keptRelations += KeptRelation(
                relation.id, sortedTags(tags),
                IntArray(n) { relation.memberType(it) }, LongArray(n) { relation.memberId(it) }, Array(n) { relation.role(it) },
            )
        }
    }

    /** A growable set of longs. */
    private class LongSet {
        private val map = LongLongMap(64)
        operator fun contains(id: Long) = id in map
        fun add(id: Long) = map.put(id, 1)
        fun sorted(): LongArray {
            val a = LongArray(map.size)
            var n = 0
            map.forEachKey { a[n++] = it }
            a.sort()
            return a
        }
    }
}

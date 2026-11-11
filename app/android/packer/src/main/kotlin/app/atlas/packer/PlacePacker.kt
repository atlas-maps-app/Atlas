package app.atlas.packer

import app.atlas.geo.BBox
import app.atlas.geo.LatLon
import app.atlas.model.OsmId
import app.atlas.model.OsmType
import app.atlas.packer.pbf.OsmHandler
import app.atlas.packer.pbf.PbfElement
import app.atlas.packer.pbf.PbfFormatException
import app.atlas.packer.pbf.PbfHeader
import app.atlas.packer.pbf.PbfNode
import app.atlas.packer.pbf.PbfReader
import app.atlas.packer.pbf.PbfRelation
import app.atlas.packer.pbf.PbfWay
import app.atlas.providers.Http
import app.atlas.providers.OpenAddresses
import app.atlas.providers.OverpassElement
import app.atlas.search.AddressFill
import app.atlas.search.ImportStats
import app.atlas.search.Outlines
import app.atlas.search.PlaceStore
import app.atlas.search.Selection
import app.atlas.search.StoreWriter
import kotlinx.coroutines.runBlocking
import java.io.File

/** How [PlacePacker] builds a store. */
data class PackOptions(
    /** Languages whose kind names go into the text index, as [PlaceStore.writer] takes them. */
    val langs: List<String> = PlaceStore.DEFAULT_LANGS,
    /** Keep only places whose pin lies in this box (a region cut from a bigger extract). */
    val clip: BBox? = null,
    /** Keep each element's last edit time, for "newer:" searches offline (a slightly bigger store). */
    val editTimes: Boolean = false,
    /** Inflating threads; 1 reads on the calling thread only. */
    val threads: Int = PbfReader.defaultThreads(),
    /** Fill in the house numbers OpenStreetMap lacks in this box (the region's) from OpenAddresses, over the network. */
    val addresses: BBox? = null,
)

/** What a pack run read and kept. */
data class PackStats(
    val header: PbfHeader,
    val nodes: Long,
    val ways: Long,
    val relations: Long,
    /** Kept elements that could not be placed: none of their nodes are in the extract. */
    val unlocated: Long,
    /** Kept elements whose pin fell outside the clip box. */
    val clipped: Long,
    /** Way node references the extract does not have (ways cut at its edge). */
    val missingNodes: Long,
    val store: ImportStats,
    val millis: Long,
) {
    val places: Int get() = store.places
    val trees: Int get() = store.trees

    /** Address points, kept apart from the places in the store's address table. */
    val addresses: Int get() = store.addresses

    /** The outlines of parks, reserves and national parks kept beside their places ([Outlines]). */
    val outlines: Int get() = store.outlines

    /** The pieces of path kept in the outlined areas whose plan draws them ([Outlines.keepsPathsOf]). */
    val paths: Int get() = store.paths
}

/**
 * Builds a region's [PlaceStore] from an OSM PBF extract, taking what [app.atlas.search.RegionImport] takes from
 * Overpass ([Selection]: points of interest, named features, addresses, stops; trees in their own table), through the
 * store's own [StoreWriter], so the file is the same one an Overpass import makes.
 *
 * Two passes. The first reads only relations, to learn which ways the kept relations need. The second keeps every
 * node's location in [NodeLocations] (12 bytes a node, about 2 GB for the Netherlands), writes kept nodes as they come,
 * places kept ways at their area centroid (closed) or halfway point (open) with their box, keeps the needed ways' points,
 * and places kept relations last: at a `label` member, else the centroid of their assembled rings (multipolygons and
 * boundaries), else the middle of their box. The parks, reserves and national parks ([Outlines.keeps]) keep their
 * outline too, for their page offline: a closed way's ring, a relation's assembled rings, where the extract holds every
 * point of them. A third pass reads the ways again for the paths in those whose plan draws them ([Outlines.keepsPathsOf]).
 */
class PlacePacker(private val options: PackOptions = PackOptions()) {

    /** Fills [store] from [pbf]; [name] is the region's name in the store's metadata. */
    fun pack(pbf: File, store: PlaceStore, name: String? = null, log: (String) -> Unit = {}): PackStats {
        val started = System.nanoTime()
        val reader = PbfReader(pbf, options.threads)
        val header = reader.header
        if (!header.sortedByTypeThenId) log("note: ${pbf.name} does not declare Sort.Type_then_ID; nodes must still come before ways")

        val relations = RelationIndex()
        reader.read(object : OsmHandler {
            override fun relation(relation: PbfRelation) = relations.add(relation, keeps(relation.tags, isRelation = true))
        }, PbfReader.RELATIONS)
        val needed = relations.neededWays()
        log("pass 1: ${relations.size} relations, ${needed.size} ways needed by kept relations (${seconds(started)} s)")

        val pass = Pass(store.writer(options.langs, name), relations, needed) { log("$it (${seconds(started)} s)") }
        pass.writer.use { w ->
            reader.read(pass)
            log("pass 2: ${pass.nodes} nodes (${pass.locations.bytes shr 20} MiB of locations), ${pass.ways} ways, ${pass.relationsRead} relations (${seconds(started)} s)")
            pass.placeRelations()
            pass.keepPaths(reader)
            pass.locations.clear()
            options.addresses?.let { box ->
                val fill = runBlocking { AddressFill(OpenAddresses(Http(userAgent = PACKS_USER_AGENT))).fill(box, w) }
                val stopped = fill.failure?.let { "; stopped: $it" } ?: ""
                log("addresses: ${fill.added} house numbers from OpenAddresses (${fill.sources} sources, ${fill.read} of ${fill.tiles} tiles read, ${fill.complete} complete, ${fill.bytes shr 20} MiB)$stopped (${seconds(started)} s)")
            }
            val imported = w.finish()
            log("store: ${imported.places} places, ${imported.addresses} address points, ${imported.trees} trees, ${imported.outlines} outlines, ${imported.paths} pieces of path (${seconds(started)} s)")
            return PackStats(
                header, pass.nodes, pass.ways, pass.relationsRead, pass.unlocated, pass.clipped, pass.missingNodes,
                imported, (System.nanoTime() - started) / 1_000_000,
            )
        }
    }

    private class PendingRelation(
        val id: Long,
        val tags: Map<String, String>,
        val types: ByteArray,
        val ids: LongArray,
        val roles: Array<String>,
        val timestamp: String?,
    )

    private inner class Pass(
        val writer: StoreWriter,
        private val index: RelationIndex,
        private val needed: LongArray,
        private val log: (String) -> Unit,
    ) : OsmHandler {
        val locations = NodeLocations()
        private val wayPoints = HashMap<Long, IntArray>()

        /** Of [wayPoints], the ways some of whose nodes the extract lacks: no outline is drawn from them. */
        private val cut = HashSet<Long>()

        /** The outlined areas whose plan draws paths, for [keepPaths]. */
        private val walked = ArrayList<Walked>()
        private val pending = ArrayList<PendingRelation>()
        private var buf = IntArray(8192)
        private var sawWays = false

        var nodes = 0L
        var ways = 0L
        var relationsRead = 0L
        var unlocated = 0L
        var clipped = 0L
        var missingNodes = 0L

        override fun node(node: PbfNode) {
            if (sawWays) throw PbfFormatException("a node after the ways: the file must be sorted by type then id (osmium sort)")
            if (++nodes % NODES_PER_LOG == 0L) log("$nodes nodes")
            locations.put(node.id, node.latE7, node.lonE7)
            val tags = node.tags
            if (tags.isEmpty() || !keeps(tags, isRelation = false)) return
            val lat = node.latE7 / E7
            val lon = node.lonE7 / E7
            if (outside(LatLon(lat, lon))) return
            writer.add(OverpassElement(OsmType.NODE, node.id, tags.toMap(), lat, lon, timestamp = time(node)))
        }

        override fun way(way: PbfWay) {
            sawWays = true
            if (++ways % WAYS_PER_LOG == 0L) log("$ways ways, ${writer.places} places so far")
            val keep = way.tags.isNotEmpty() && keeps(way.tags, isRelation = false)
            val wanted = needed.isNotEmpty() && java.util.Arrays.binarySearch(needed, way.id) >= 0
            if (!keep && !wanted) return
            if (buf.size < way.refCount * 2) buf = IntArray(way.refCount * 2 + 64)
            var n = 0
            // a file with locations on ways carries them here (its untagged nodes may be gone); else the node table
            val onWay = way.hasLocations
            for (i in 0 until way.refCount) {
                if (onWay && way.hasLocation(i)) {
                    buf[2 * n] = way.latE7(i)
                    buf[2 * n + 1] = way.lonE7(i)
                    n++
                    continue
                }
                val at = locations.indexOf(way.ref(i))
                if (at < 0) { missingNodes++; continue }
                buf[2 * n] = locations.lat(at)
                buf[2 * n + 1] = locations.lon(at)
                n++
            }
            if (n == 0) {
                if (keep) unlocated++
                return
            }
            if (wanted) {
                wayPoints[way.id] = buf.copyOf(2 * n)
                if (n < way.refCount) cut += way.id
            }
            if (!keep) return
            val centre = Shapes.wayCentre(buf, n)
            if (outside(centre)) return
            val tags = way.tags.toMap()
            writer.add(OverpassElement(OsmType.WAY, way.id, tags, center = centre, bounds = Shapes.box(buf, n), timestamp = time(way)))
            // a park's ring, whole and closed
            if (n == way.refCount && n >= 4 && buf[0] == buf[2 * n - 2] && buf[1] == buf[2 * n - 1] && Outlines.keeps(tags)) {
                outline(OsmId(OsmType.WAY, way.id), listOf(buf.copyOf(2 * n)), listOf(false))
            }
        }

        /** Keeps [id]'s outline, and where its plan draws paths ([Outlines.keepsPathsOf]) the area for [keepPaths]. */
        private fun outline(id: OsmId, rings: List<IntArray>, inner: List<Boolean>) {
            if (writer.putOutline(id, rings, inner) && Outlines.keepsPathsOf(rings)) walked += Walked(id, rings)
        }

        override fun relation(relation: PbfRelation) {
            relationsRead++
            if (!keeps(relation.tags, isRelation = true)) return
            val n = relation.memberCount
            pending += PendingRelation(
                relation.id,
                relation.tags.toMap(),
                ByteArray(n) { relation.memberType(it).toByte() },
                LongArray(n) { relation.memberId(it) },
                Array(n) { relation.role(it) },
                time(relation),
            )
        }

        /** Places the kept relations, once every way they need has been read. */
        fun placeRelations() {
            for (r in pending) {
                var box: BBox? = null
                var label: LatLon? = null
                val rings = if (r.tags["type"] in RING_TYPES) Rings() else null
                for (i in r.ids.indices) {
                    when (r.types[i].toInt()) {
                        PbfRelation.NODE -> nodeAt(r.ids[i])?.let { p ->
                            box = box?.extend(p) ?: BBox(p.lon, p.lat, p.lon, p.lat)
                            if (label == null && r.roles[i] == "label") label = p
                        }
                        PbfRelation.WAY -> wayPoints[r.ids[i]]?.let { pts ->
                            val b = Shapes.box(pts, pts.size / 2)
                            box = box?.union(b) ?: b
                            rings?.add(pts, isInner = r.roles[i] == "inner")
                        }
                        PbfRelation.RELATION -> {
                            val seen = hashSetOf(index.slot(r.id))
                            nestedBox(r.ids[i], 1, seen)?.let { b -> box = box?.union(b) ?: b }
                        }
                    }
                }
                val b = box
                if (b == null) { unlocated++; continue }
                val centre = label ?: rings?.centroid() ?: b.center
                if (outside(centre)) continue
                writer.add(OverpassElement(OsmType.RELATION, r.id, r.tags, center = centre, bounds = b, timestamp = r.timestamp))
                if (rings != null && Outlines.keeps(r.tags) && whole(r)) {
                    val (outs, ins) = rings.rings()
                    if (outs.isNotEmpty()) outline(OsmId(OsmType.RELATION, r.id), outs + ins, outs.map { false } + ins.map { true })
                }
            }
            if (pending.isNotEmpty()) log("relations: ${pending.size} kept; ${writer.outlines} outlines in all")
            pending.clear()
            wayPoints.clear()
            cut.clear()
        }

        /**
         * The third pass, over the ways alone, while [locations] still holds every node: the ways a park's plan draws
         * ([Outlines.PATHS]) read again, and the pieces of them in each area of [walked] kept beside its outline
         * ([StoreWriter.putPaths]). An area is found by the cells of a coarse grid its box meets; a node the extract lacks
         * breaks a way in two.
         */
        fun keepPaths(reader: PbfReader) {
            if (walked.isEmpty()) return
            val cells = HashMap<Long, MutableList<Int>>()
            for ((i, w) in walked.withIndex()) {
                for (cy in cellOf(w.south)..cellOf(w.north)) for (cx in cellOf(w.west)..cellOf(w.east)) cells.getOrPut(cellKey(cy, cx)) { ArrayList(2) } += i
            }
            // which run last looked at each area, so a run meeting it in two cells is taken once
            val looked = IntArray(walked.size) { -1 }
            var runs = 0
            fun offer(p: IntArray, n: Int) {
                val run = runs++
                var south = Int.MAX_VALUE; var north = Int.MIN_VALUE; var west = Int.MAX_VALUE; var east = Int.MIN_VALUE
                for (k in 0 until n) {
                    south = minOf(south, p[2 * k]); north = maxOf(north, p[2 * k])
                    west = minOf(west, p[2 * k + 1]); east = maxOf(east, p[2 * k + 1])
                }
                for (cy in cellOf(south)..cellOf(north)) for (cx in cellOf(west)..cellOf(east)) {
                    val here = cells[cellKey(cy, cx)] ?: continue
                    for (i in here) {
                        if (looked[i] == run) continue
                        looked[i] = run
                        val a = walked[i]
                        if (east >= a.west && west <= a.east && north >= a.south && south <= a.north) a.take(p, n)
                    }
                }
            }
            var line = IntArray(512)
            reader.read(object : OsmHandler {
                override fun way(way: PbfWay) {
                    if (way.tags["highway"] !in Outlines.PATHS) return
                    var n = 0
                    val onWay = way.hasLocations
                    for (i in 0 until way.refCount) {
                        var at = -1L
                        if (!(onWay && way.hasLocation(i))) {
                            at = locations.indexOf(way.ref(i))
                            if (at < 0) {
                                if (n >= 2) offer(line, n)
                                n = 0
                                continue
                            }
                        }
                        if (line.size < 2 * n + 2) line = line.copyOf(line.size * 2)
                        line[2 * n] = if (at < 0) way.latE7(i) else locations.lat(at)
                        line[2 * n + 1] = if (at < 0) way.lonE7(i) else locations.lon(at)
                        n++
                    }
                    if (n >= 2) offer(line, n)
                }
            }, PbfReader.WAYS)
            for (a in walked) writer.putPaths(a.id, a.lines, a.tolerance)
            log("paths: ${writer.paths} pieces in ${walked.size} areas")
            walked.clear()
        }

        private fun cellOf(e7: Int): Int = Math.floorDiv(e7, CELL_E7)

        private fun cellKey(cy: Int, cx: Int): Long = (cy.toLong() shl 32) or (cx.toLong() and 0xffffffffL)

        /** Whether the extract holds every point of [r]'s rings: its every way an outer or inner one, read in full. */
        private fun whole(r: PendingRelation): Boolean = r.ids.indices.all { i ->
            r.types[i].toInt() != PbfRelation.WAY || (r.roles[i] in AREA_ROLES && r.ids[i] in wayPoints && r.ids[i] !in cut)
        }

        private fun nestedBox(id: Long, depth: Int, seen: HashSet<Int>): BBox? {
            val slot = index.slot(id)
            if (slot < 0 || !seen.add(slot)) return null
            var box: BBox? = null
            for (i in 0 until index.memberCount(slot)) {
                val m = index.memberId(slot, i)
                val b = when (index.memberType(slot, i)) {
                    PbfRelation.NODE -> nodeAt(m)?.let { BBox(it.lon, it.lat, it.lon, it.lat) }
                    PbfRelation.WAY -> wayPoints[m]?.let { Shapes.box(it, it.size / 2) }
                    else -> if (depth < RelationIndex.MAX_DEPTH) nestedBox(m, depth + 1, seen) else null
                }
                if (b != null) box = box?.union(b) ?: b
            }
            return box
        }

        private fun nodeAt(id: Long): LatLon? {
            val at = locations.indexOf(id)
            return if (at < 0) null else LatLon(locations.lat(at) / E7, locations.lon(at) / E7)
        }

        private fun outside(p: LatLon): Boolean {
            val c = options.clip ?: return false
            if (p in c) return false
            clipped++
            return true
        }

        private fun time(e: PbfElement): String? = if (options.editTimes) e.timestamp?.toString() else null
    }

    /**
     * An outlined area whose plan draws paths ([Outlines.keepsPathsOf]) and the pieces of path found in it so far: the runs
     * of a way's segments with an end in it or crossing its edge, which its plan clips to its lawn.
     */
    private class Walked(val id: OsmId, private val rings: List<IntArray>) {
        val tolerance = Outlines.toleranceOf(rings)
        val lines = ArrayList<IntArray>()
        val south: Int
        val west: Int
        val north: Int
        val east: Int

        init {
            var s = Int.MAX_VALUE; var w = Int.MAX_VALUE; var n = Int.MIN_VALUE; var e = Int.MIN_VALUE
            for (r in rings) {
                for (k in 0 until r.size / 2) {
                    s = minOf(s, r[2 * k]); n = maxOf(n, r[2 * k])
                    w = minOf(w, r[2 * k + 1]); e = maxOf(e, r[2 * k + 1])
                }
            }
            south = s; west = w; north = n; east = e
        }

        /** Takes the runs of the first [n] points of [p] ((lat, lon) pairs in 1e-7 degree) whose segments meet the area. */
        fun take(p: IntArray, n: Int) {
            var start = -1
            var fromIn = inside(p[0], p[1])
            for (k in 0 until n - 1) {
                val toIn = inside(p[2 * k + 2], p[2 * k + 3])
                if (fromIn || toIn || crosses(p[2 * k], p[2 * k + 1], p[2 * k + 2], p[2 * k + 3])) {
                    if (start < 0) start = k
                } else if (start >= 0) {
                    lines += p.copyOfRange(2 * start, 2 * k + 2)
                    start = -1
                }
                fromIn = toIn
            }
            if (start >= 0) lines += p.copyOfRange(2 * start, 2 * n)
        }

        /** Whether the point lies in the area: inside an odd number of its rings, so a hole's inside is out. */
        private fun inside(lat: Int, lon: Int): Boolean {
            if (lat < south || lat > north || lon < west || lon > east) return false
            val y = lat.toDouble()
            val x = lon.toDouble()
            var odd = false
            for (r in rings) {
                var j = r.size / 2 - 1
                for (i in 0 until r.size / 2) {
                    val yi = r[2 * i].toDouble()
                    val yj = r[2 * j].toDouble()
                    if ((yi > y) != (yj > y)) {
                        val xi = r[2 * i + 1].toDouble()
                        val xj = r[2 * j + 1].toDouble()
                        if (x < (xj - xi) * (y - yi) / (yj - yi) + xi) odd = !odd
                    }
                    j = i
                }
            }
            return odd
        }

        /** Whether the segment from a to b crosses an edge of the area's rings. */
        private fun crosses(aLat: Int, aLon: Int, bLat: Int, bLon: Int): Boolean {
            if (maxOf(aLat, bLat) < south || minOf(aLat, bLat) > north || maxOf(aLon, bLon) < west || minOf(aLon, bLon) > east) return false
            for (r in rings) {
                for (i in 0 until r.size / 2 - 1) {
                    val cLat = r[2 * i]; val cLon = r[2 * i + 1]; val dLat = r[2 * i + 2]; val dLon = r[2 * i + 3]
                    val d1 = side(cLat, cLon, dLat, dLon, aLat, aLon)
                    val d2 = side(cLat, cLon, dLat, dLon, bLat, bLon)
                    if (d1 == 0 || d2 == 0 || d1 == d2) continue
                    val d3 = side(aLat, aLon, bLat, bLon, cLat, cLon)
                    val d4 = side(aLat, aLon, bLat, bLon, dLat, dLon)
                    if (d3 != 0 && d4 != 0 && d3 != d4) return true
                }
            }
            return false
        }

        /** Which side of the line from c to d the point p lies on: 1, -1, or 0 on it. */
        private fun side(cLat: Int, cLon: Int, dLat: Int, dLon: Int, pLat: Int, pLon: Int): Int =
            java.lang.Long.signum((dLon.toLong() - cLon) * (pLat.toLong() - cLat) - (dLat.toLong() - cLat) * (pLon.toLong() - cLon))
    }

    companion object {
        /** What the packs' requests say they are, as .github/workflows/packs.yml's own. */
        private const val PACKS_USER_AGENT = "Atlas-packs/1 (+https://github.com/atlas-maps-app/Atlas)"

        /** 1e-7 degree integers become degrees by division: the double nearest OSM's decimal, as Overpass gives it. */
        private const val E7 = 1e7
        private const val NODES_PER_LOG = 50_000_000L
        private const val WAYS_PER_LOG = 5_000_000L

        /** Relation types whose ways form rings with an area. */
        private val RING_TYPES = setOf("multipolygon", "boundary")

        /** The roles of a multipolygon's ways: its outline, its holes, and an old relation's unnamed ones. */
        private val AREA_ROLES = setOf("outer", "inner", "")

        /** The cells [Pass.keepPaths] finds an area by: a hundredth of a degree, some 1.1 by 0.7 km in the Netherlands. */
        private const val CELL_E7 = 100_000

        /** True when the store keeps an element with [tags] as a place or a tree. */
        fun keeps(tags: Map<String, String>, isRelation: Boolean): Boolean =
            tags.isNotEmpty() && (Selection.keeps(tags, isRelation = isRelation) || (!isRelation && Selection.isTree(tags)))

        private fun seconds(since: Long) = (System.nanoTime() - since) / 1_000_000_000
    }
}

package app.atlas.packer

import app.atlas.geo.BBox
import app.atlas.geo.LatLon
import app.atlas.model.OsmType
import app.atlas.packer.pbf.OsmHandler
import app.atlas.packer.pbf.PbfElement
import app.atlas.packer.pbf.PbfFormatException
import app.atlas.packer.pbf.PbfHeader
import app.atlas.packer.pbf.PbfNode
import app.atlas.packer.pbf.PbfReader
import app.atlas.packer.pbf.PbfRelation
import app.atlas.packer.pbf.PbfWay
import app.atlas.providers.OverpassElement
import app.atlas.search.ImportStats
import app.atlas.search.PlaceStore
import app.atlas.search.Selection
import app.atlas.search.StoreWriter
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
 * boundaries), else the middle of their box.
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
            pass.locations.clear()
            val imported = w.finish()
            log("store: ${imported.places} places, ${imported.trees} trees (${seconds(started)} s)")
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
            if (wanted) wayPoints[way.id] = buf.copyOf(2 * n)
            if (!keep) return
            val centre = Shapes.wayCentre(buf, n)
            if (outside(centre)) return
            writer.add(OverpassElement(OsmType.WAY, way.id, way.tags.toMap(), center = centre, bounds = Shapes.box(buf, n), timestamp = time(way)))
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
            }
            if (pending.isNotEmpty()) log("relations: ${pending.size} kept")
            pending.clear()
            wayPoints.clear()
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

    companion object {
        /** 1e-7 degree integers become degrees by division: the double nearest OSM's decimal, as Overpass gives it. */
        private const val E7 = 1e7
        private const val NODES_PER_LOG = 50_000_000L
        private const val WAYS_PER_LOG = 5_000_000L

        /** Relation types whose ways form rings with an area. */
        private val RING_TYPES = setOf("multipolygon", "boundary")

        /** True when the store keeps an element with [tags] as a place or a tree. */
        fun keeps(tags: Map<String, String>, isRelation: Boolean): Boolean =
            tags.isNotEmpty() && (Selection.keeps(tags, isRelation = isRelation) || (!isRelation && Selection.isTree(tags)))

        private fun seconds(since: Long) = (System.nanoTime() - since) / 1_000_000_000
    }
}

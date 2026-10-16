package app.atlas.packer

import app.atlas.geo.BBox
import app.atlas.packer.pbf.OsmHandler
import app.atlas.packer.pbf.PbfNode
import app.atlas.packer.pbf.PbfReader
import app.atlas.packer.pbf.PbfRelation
import app.atlas.packer.pbf.PbfWay
import app.atlas.packer.pbf.TagView
import app.atlas.render.data.Bounds
import app.atlas.render.data.ByteSink
import app.atlas.render.data.ByteSource
import app.atlas.render.data.ElementType
import app.atlas.render.data.ListElementSource
import app.atlas.render.data.Member
import app.atlas.render.data.OsmElement
import app.atlas.render.data.PackStats
import app.atlas.render.data.PackTiles
import app.atlas.render.data.PackWriter
import app.atlas.render.data.Points
import app.atlas.render.data.RegionBuilder
import app.atlas.render.data.RegionConfig
import app.atlas.render.data.TiledPackWriter
import java.io.File
import java.io.RandomAccessFile
import java.lang.management.ManagementFactory
import java.lang.management.MemoryType

/**
 * A whole Geofabrik extract (an OSM PBF file) as a tiled (format 2) region pack: the elements the engine's Overpass
 * query gives, exactly as [PbfRegion] cuts them for a box holding the whole file (every tagged node; every way with a
 * node in the file, whole, with its nodes' positions; every multipolygon, national park and protected area relation
 * with a member in the file, its member ways whole), but streamed into a [TiledPackWriter] as they are made.
 *
 * Three passes over the file: (1) relations, to learn the kept ones and the ways they name; (2) nodes, every position
 * into [NodeLocations] (12 bytes a node: about 2 GB for the Netherlands) and every tagged node written; (3) ways, each
 * written with its geometry, the member ways' points also kept on disk for the relations; the relations come last.
 * Memory holds the node locations, the kept relations and an index of their member ways, never the elements.
 */
object TiledPack {
    /**
     * What [build] made. [peakHeap] is the old generation's peak in bytes (what stays alive across collections: the
     * node locations, the kept relations and the spill buffers), when the JVM reports one.
     */
    class Result(val file: File, val stats: PackStats, val bbox: BBox, val millis: Long, val peakHeap: Long?, val spilledBytes: Long)

    private class KeptRelation(val id: Long, val tags: Map<String, String>?, val types: ByteArray, val refs: LongArray, val roles: Array<String>)

    private const val E7 = 1e7

    /**
     * Builds the pack of [pbf] into [out]. [work] holds the spill files while it runs; [memory] is how many bytes of
     * coded elements wait in memory before going there; [log] hears progress.
     */
    fun build(
        pbf: File,
        out: File,
        id: String,
        name: String,
        country: String? = null,
        work: File = File(out.absolutePath + ".work"),
        threads: Int = PbfReader.defaultThreads(),
        memory: Long = TiledPackWriter.DEFAULT_MEMORY,
        log: (String) -> Unit = {},
    ): Result {
        val started = System.nanoTime()
        fun seconds() = (System.nanoTime() - started) / 1_000_000_000
        val pools = ManagementFactory.getMemoryPoolMXBeans().filter { it.type == MemoryType.HEAP && it.isValid && ("Old" in it.name || "Tenured" in it.name) }
        for (p in pools) runCatching { p.resetPeakUsage() }
        val reader = PbfReader(pbf, threads)
        val header = reader.header
        if (!header.sortedByTypeThenId) log("note: ${pbf.name} does not declare Sort.Type_then_ID")
        work.mkdirs()
        val writer = TiledPackWriter(File(work, "tiles"), levels = PackTiles.LEVELS, memory = memory)
        val memberFile = File(work, "member-ways.bin")
        try {
            // pass 1: the kept relations and what they name
            val kept = ArrayList<KeptRelation>()
            reader.read(object : OsmHandler {
                override fun relation(relation: PbfRelation) {
                    if (!isKeptRelation(relation.tags)) return
                    val n = relation.memberCount
                    kept += KeptRelation(
                        relation.id, sortedTags(relation.tags),
                        ByteArray(n) { relation.memberType(it).toByte() }, LongArray(n) { relation.memberId(it) }, Array(n) { relation.role(it) },
                    )
                }
            }, PbfReader.RELATIONS)
            val memberWays = sortedUnique(kept) { r, add -> for (i in r.refs.indices) if (r.types[i].toInt() == PbfRelation.WAY) add(r.refs[i]) }
            log("pass 1: ${kept.size} kept relations naming ${memberWays.size} ways (${seconds()} s)")

            // pass 2: every node's position; tagged nodes are elements
            val locations = NodeLocations()
            var south = Double.POSITIVE_INFINITY
            var west = Double.POSITIVE_INFINITY
            var north = Double.NEGATIVE_INFINITY
            var east = Double.NEGATIVE_INFINITY
            reader.read(object : OsmHandler {
                override fun node(node: PbfNode) {
                    val la = node.latE7
                    val lo = node.lonE7
                    locations.put(node.id, la, lo)
                    val lat = la / E7
                    val lon = lo / E7
                    if (lat < south) south = lat
                    if (lat > north) north = lat
                    if (lon < west) west = lon
                    if (lon > east) east = lon
                    if (node.tags.isNotEmpty()) writer.add(OsmElement(ElementType.NODE, node.id, lat, lon, sortedTags(node.tags)))
                }
            }, PbfReader.NODES)
            log("pass 2: ${locations.size} nodes (${locations.bytes shr 20} MiB of locations), ${writer.nodes} tagged (${seconds()} s)")

            // pass 3: ways, whole; the member ways' points kept for the relations
            val memberIndex = LongLongMap((memberWays.size + 16).coerceAtMost(1 shl 28))
            val points = ByteSink(1 shl 14)
            RandomAccessFile(memberFile, "rw").use { members ->
                members.setLength(0)
                var memberBytes = 0L
                var refs = LongArray(256)
                var at = LongArray(256)
                reader.read(object : OsmHandler {
                    override fun way(way: PbfWay) {
                        val count = way.refCount
                        if (refs.size < count) {
                            refs = LongArray(count * 2)
                            at = LongArray(count * 2)
                        }
                        var n = 0
                        for (i in 0 until count) {
                            val ref = way.ref(i)
                            val k = locations.indexOf(ref)
                            if (k < 0) continue
                            refs[n] = ref
                            at[n] = k
                            n++
                        }
                        if (n == 0) return
                        val nodes = refs.copyOf(n)
                        val la = DoubleArray(n)
                        val lo = DoubleArray(n)
                        var a = Double.POSITIVE_INFINITY
                        var b = Double.POSITIVE_INFINITY
                        var c = Double.NEGATIVE_INFINITY
                        var d = Double.NEGATIVE_INFINITY
                        for (i in 0 until n) {
                            val y = locations.lat(at[i]) / E7
                            val x = locations.lon(at[i]) / E7
                            la[i] = y
                            lo[i] = x
                            if (y < a) a = y
                            if (y > c) c = y
                            if (x < b) b = x
                            if (x > d) d = x
                        }
                        val geometry = Points(la, lo)
                        writer.add(OsmElement(ElementType.WAY, way.id, tags = sortedTags(way.tags), bounds = Bounds(a, b, c, d), nodes = nodes, geometry = geometry))
                        if (java.util.Arrays.binarySearch(memberWays, way.id) >= 0) {
                            points.reset()
                            points.varInt(n)
                            var lastLat = 0L
                            var lastLon = 0L
                            for (i in 0 until n) {
                                val y = locations.lat(at[i]).toLong()
                                val x = locations.lon(at[i]).toLong()
                                points.zigzag(y - lastLat)
                                points.zigzag(x - lastLon)
                                lastLat = y
                                lastLon = x
                            }
                            members.write(points.unsafeArray(), 0, points.size)
                            memberIndex.put(way.id, (memberBytes shl 20) or points.size.toLong())
                            memberBytes += points.size
                        }
                    }
                }, PbfReader.WAYS)
                log("pass 3: ${writer.ways} ways, ${memberIndex.size} member ways (${memberBytes shr 20} MiB of their points) (${seconds()} s)")

                // the relations, by id, with their members' geometry
                kept.sortBy { it.id }
                var buf = ByteArray(1 shl 14)
                for (r in kept) {
                    val ms = ArrayList<Member>(r.refs.size)
                    var a = Double.POSITIVE_INFINITY
                    var b = Double.POSITIVE_INFINITY
                    var c = Double.NEGATIVE_INFINITY
                    var d = Double.NEGATIVE_INFINITY
                    fun box(y: Double, x: Double) {
                        if (y < a) a = y
                        if (y > c) c = y
                        if (x < b) b = x
                        if (x > d) d = x
                    }
                    for (i in r.refs.indices) {
                        val ref = r.refs[i]
                        val role = r.roles[i]
                        when (r.types[i].toInt()) {
                            PbfRelation.WAY -> {
                                val slot = memberIndex.get(ref)
                                var g: Points? = null
                                if (slot >= 0) {
                                    val len = (slot and 0xFFFFF).toInt()
                                    if (buf.size < len) buf = ByteArray(len * 2)
                                    members.seek(slot ushr 20)
                                    members.readFully(buf, 0, len)
                                    val src = ByteSource(buf, 0, len)
                                    val n = src.varInt()
                                    val la = DoubleArray(n)
                                    val lo = DoubleArray(n)
                                    var y = 0L
                                    var x = 0L
                                    for (j in 0 until n) {
                                        y += src.zigzag()
                                        x += src.zigzag()
                                        la[j] = y / E7
                                        lo[j] = x / E7
                                        box(la[j], lo[j])
                                    }
                                    g = Points(la, lo)
                                }
                                ms += Member(ElementType.WAY, ref, role, geometry = g)
                            }
                            PbfRelation.NODE -> {
                                val k = locations.indexOf(ref)
                                if (k >= 0) {
                                    val y = locations.lat(k) / E7
                                    val x = locations.lon(k) / E7
                                    ms += Member(ElementType.NODE, ref, role, y, x)
                                    box(y, x)
                                } else ms += Member(ElementType.NODE, ref, role)
                            }
                            else -> ms += Member(ElementType.RELATION, ref, role)
                        }
                    }
                    // a relation none of whose members is in the file is not in the box Overpass would answer for
                    if (a > c) continue
                    writer.add(OsmElement(ElementType.RELATION, r.id, tags = r.tags, bounds = Bounds(a, b, c, d), members = ms))
                }
                log("relations: ${writer.relations} written (${seconds()} s)")
            }
            locations.clear()
            kept.clear()
            memberFile.delete()

            val bbox = header.bbox ?: if (south <= north) BBox(west, south, east, north) else BBox(0.0, 0.0, 0.0, 0.0)
            val spec = RegionBuilder.spec(
                id, RegionConfig.forBox(name, bbox, country), ListElementSource(emptyList(), PbfRegion.headerOf(header.replicationTime)),
                listOf(pbf.name + " (Geofabrik, OpenStreetMap contributors, ODbL 1.0)"),
            )
            val spilled = writer.spilledBytes
            val stats = writer.finish(spec, out) { log("$it (${seconds()} s)") }
            val peak = pools.sumOf { runCatching { it.peakUsage?.used ?: 0L }.getOrDefault(0L) }.takeIf { it > 0 }
            return Result(out, stats, bbox, (System.nanoTime() - started) / 1_000_000, peak, spilled)
        } finally {
            writer.close()
            memberFile.delete()
            File(work, "tiles").delete()
            work.delete()
        }
    }

    private inline fun sortedUnique(kept: List<KeptRelation>, each: (KeptRelation, (Long) -> Unit) -> Unit): LongArray {
        var out = LongArray(1024)
        var n = 0
        for (r in kept) each(r) { v ->
            if (n == out.size) out = out.copyOf(n * 2)
            out[n++] = v
        }
        val a = out.copyOf(n)
        a.sort()
        var m = 0
        for (i in a.indices) if (i == 0 || a[i] != a[i - 1]) a[m++] = a[i]
        return a.copyOf(m)
    }

    private fun isKeptRelation(t: TagView): Boolean {
        if (t["type"] == "multipolygon") return true
        val b = t["boundary"]
        return b == "national_park" || b == "protected_area"
    }

    /** The tags in key order (UTF-8 byte order, as Overpass and [PbfRegion] keep them), a later repeat of a key winning; null for none. */
    internal fun sortedTags(t: TagView): Map<String, String>? {
        val n = t.size
        if (n == 0) return null
        val order = IntArray(n) { it }
        // insertion sort: stable, and tag lists are short
        for (i in 1 until n) {
            val v = order[i]
            val key = t.key(v)
            var j = i - 1
            while (j >= 0 && utf8Order(t.key(order[j]), key) > 0) {
                order[j + 1] = order[j]
                j--
            }
            order[j + 1] = v
        }
        val m = LinkedHashMap<String, String>(n * 2)
        for (i in order) m[t.key(i)] = t.value(i)
        return m
    }

    /**
     * Compares strings in the byte order of their UTF-8 (which is code point order) without encoding them: UTF-16
     * order agrees except where a surrogate meets a character from U+E000 up.
     */
    internal fun utf8Order(a: String, b: String): Int {
        val n = minOf(a.length, b.length)
        for (i in 0 until n) {
            val x = a[i]
            val y = b[i]
            if (x == y) continue
            val sx = x.isSurrogate()
            val sy = y.isSurrogate()
            if (sx != sy) {
                val other = if (sx) y else x
                if (other.code >= 0xE000) return if (sx) 1 else -1
            }
            return x.compareTo(y)
        }
        return a.length - b.length
    }

    private const val USAGE = "packer pbf <in.osm.pbf> [W,S,E,N] --tiled [--name NAME] [--id ID] [--out FILE] [--country XX] [--work DIR] [--threads N] [--memory MiB]"

    /**
     * The command `packer pbf … --tiled`: the whole extract as a tiled pack, or with a box, the box [PbfRegion] cuts.
     * [args] is the whole command line from `pbf` on; [out] gets the report lines. Returns the exit code.
     */
    fun run(args: List<String>, out: (String) -> Unit): Int {
        if (args.firstOrNull() != "pbf") throw UsageException("usage: $USAGE")
        val positional = ArrayList<String>()
        val options = LinkedHashMap<String, String?>()
        val valued = setOf("--name", "--id", "--out", "--country", "--work", "--threads", "--memory")
        var i = 1
        while (i < args.size) {
            val s = args[i]
            if (s.startsWith("--")) {
                val eq = s.indexOf('=')
                when {
                    eq > 0 -> options[s.substring(0, eq)] = s.substring(eq + 1)
                    s in valued -> {
                        if (i + 1 >= args.size) throw UsageException("$s needs a value")
                        options[s] = args[++i]
                    }
                    else -> options[s] = null
                }
            } else positional += s
            i++
        }
        val unknown = options.keys - valued - "--tiled"
        if (unknown.isNotEmpty()) throw UsageException("unknown option ${unknown.joinToString()}; usage: $USAGE")
        if ("--tiled" !in options) throw UsageException("usage: $USAGE")
        if (positional.size !in 1..2) throw UsageException("pbf --tiled takes the extract and an optional box; usage: $USAGE")
        val pbf = File(positional[0])
        if (!pbf.isFile) throw UsageException("no file $pbf")
        val box = positional.getOrNull(1)?.let(::parseBox)
        val name = options["--name"] ?: pbf.name.removeSuffix(".osm.pbf").removeSuffix(".pbf")
        val id = options["--id"] ?: RegionBuilder.slug(name)
        val file = File(options["--out"] ?: "$id.atlas")
        val country = options["--country"]
        val work = options["--work"]?.let(::File) ?: File(file.absolutePath + ".work")
        val threads = options["--threads"]?.let { it.toIntOrNull()?.takeIf { n -> n in 1..64 } ?: throw UsageException("bad --threads '$it': want 1 to 64") }
            ?: PbfReader.defaultThreads()
        val memory = options["--memory"]?.let { it.toLongOrNull()?.takeIf { n -> n in 1..65536 } ?: throw UsageException("bad --memory '$it': MiB, 1 to 65536") }
            ?.let { it shl 20 } ?: TiledPackWriter.DEFAULT_MEMORY
        val sources = listOf(pbf.name + " (Geofabrik, OpenStreetMap contributors, ODbL 1.0)")
        if (box != null) {
            val extract = PbfRegion.extract(pbf, box, threads) { out("  $it") }
            val spec = RegionBuilder.spec(id, RegionConfig.forBox(name, box, country), extract.source(), sources)
            val stats = PackWriter.writeTiled(spec, file, work = work) { out("  $it") }
            out("${file.path}: ${stats.elements} elements (${stats.nodes} nodes, ${stats.ways} ways, ${stats.relations} relations) in ${stats.chunks} tiles, ${file.length() shr 10} KiB in ${stats.millis} ms")
        } else {
            val r = build(pbf, file, id, name, country, work, threads, memory) { out("  $it") }
            val s = r.stats
            out("${file.path}: ${s.elements} elements (${s.nodes} nodes, ${s.ways} ways, ${s.relations} relations) in ${s.chunks} tiles, ${file.length() shr 20} MiB in ${r.millis / 1000} s; old generation peak ${r.peakHeap?.let { "${it shr 20} MiB" } ?: "unknown"}, ${r.spilledBytes shr 20} MiB spilled")
        }
        return 0
    }
}

package app.atlas.render.data

import app.atlas.geo.BBox
import app.atlas.geo.LatLon
import app.atlas.geo.PackedRTree
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import java.io.Closeable
import java.io.File
import java.io.RandomAccessFile
import java.util.zip.CRC32

/** Where a pack's bytes come from: a file read in ranges (the phone) or an array (tests). */
interface PackInput : Closeable {
    val size: Long
    fun read(offset: Long, length: Int): ByteArray
}

class BytesPackInput(private val bytes: ByteArray) : PackInput {
    override val size: Long get() = bytes.size.toLong()
    override fun read(offset: Long, length: Int): ByteArray {
        if (offset < 0 || offset + length > bytes.size) throw PackFormatException("a read past the end of the pack")
        return bytes.copyOfRange(offset.toInt(), offset.toInt() + length)
    }
    override fun close() {}
}

class FilePackInput(file: File) : PackInput {
    private val raf = RandomAccessFile(file, "r")
    override val size: Long = raf.length()

    @Synchronized
    override fun read(offset: Long, length: Int): ByteArray {
        if (offset < 0 || offset + length > size) throw PackFormatException("a read past the end of the pack")
        val b = ByteArray(length)
        raf.seek(offset)
        raf.readFully(b)
        return b
    }

    override fun close() = raf.close()
}

/**
 * One region's data as the engine reads it: its configuration, the elements, the height grids and the small JSON
 * documents (weather, tide). A `.atlas` pack ([PackReader]) and a folder of the renders' own files
 * ([DirectoryRegion]) both are one.
 */
interface RegionData {
    val id: String

    /** The region's `places.py` entry as JSON: bbox `[s, w, n, e]`, origin, title, tz, tradition, moments, views. */
    val config: JsonObject
    val elements: ElementSource
    fun dem(name: String): Dem?
    fun aux(name: String): JsonElement?
}

/**
 * Reads a `.atlas` region pack, format 1 (the renders' packs: elements in the extract's order, a per-element index) or
 * format 2 (tiled: [TiledPackWriter], for cities and countries, elements in canonical order). Opening reads the
 * manifest and the directory; the tables load on first use; elements decode a chunk at a time, so [query] touches only
 * the chunks near the view. A [PackInput] that reads ranges over the network can open a remote pack the same way.
 */
class PackReader private constructor(private val input: PackInput) : RegionData, ElementSource, Closeable {
    private class Entry(val name: String, val offset: Long, val stored: Long, val raw: Long, val crc: Long, val method: Int)

    /** The chunk table of a tiled pack: chunks in file order, and their tiles sorted for lookups. */
    private class TileTable(
        val levels: IntArray,
        val chunks: Array<TileChunk>,
        /** Index of the first chunk of each level, and the end. */
        val levelStart: IntArray,
        /** [PackTiles.pack] of every chunk's tile, sorted, with the chunk it names. */
        val keys: LongArray,
        val chunkOfKey: IntArray,
    ) {
        fun find(li: Int, x: Int, y: Int): Int {
            val i = java.util.Arrays.binarySearch(keys, PackTiles.pack(li, x, y))
            return if (i >= 0) chunkOfKey[i] else -1
        }
    }

    private class Index(
        val count: Int,
        val chunkSize: Int,
        val scale: Scale,
        /** Extract-order position of the element stored at each place. */
        val ordinal: IntArray,
        /** Storage places of the elements that have a box, in the order they were added to [tree]. */
        val boxed: IntArray,
        val minLat: DoubleArray,
        val minLon: DoubleArray,
        val maxLat: DoubleArray,
        val maxLon: DoubleArray,
        val tree: PackedRTree,
    )

    val manifest: PackManifest

    /** The pack's format: [PackManifest.VERSION] (1) or [PackManifest.TILED] (2). */
    val version: Int
    private val entries: Map<String, Entry>
    private val dictionary: Dictionary by lazy {
        Dictionary.read(ByteSource(section("strings")), ByteSource(section("pairs")))
    }
    private val index: Index by lazy { readIndex() }
    private val chunkTable: IntArray by lazy { readChunkTable() }
    private val chunkOffsets: LongArray by lazy {
        val t = chunkTable
        val o = LongArray(t.size / 2 + 1)
        for (i in 0 until t.size / 2) o[i + 1] = o[i] + t[2 * i]
        o
    }
    private val cache = object : LinkedHashMap<Int, Array<OsmElement>>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Int, Array<OsmElement>>): Boolean = version == PackManifest.VERSION && size > CACHE_CHUNKS
    }

    /** Inflated bytes of the chunks held in [cache] (format 2 bounds the cache by size, its chunks differing widely). */
    private var cachedBytes = 0L

    private val tiles: TileTable by lazy { readTileTable() }
    private val tiledScale: Scale by lazy { Scale(manifest.json["coord_digits"]?.let { (it as? kotlinx.serialization.json.JsonPrimitive)?.content?.toIntOrNull() } ?: Scale.MIN_DIGITS) }

    init {
        val head = ByteSource(input.read(0, PackWriter.HEADER))
        val magic = String(head.bytes(8), Charsets.US_ASCII)
        if (magic != PackWriter.MAGIC) throw PackFormatException("not an Atlas region pack")
        head.byte()
        version = head.byte()
        if (version < PackManifest.VERSION || version > PackManifest.NEWEST) throw PackFormatException("this pack is format $version; this reader knows formats ${PackManifest.VERSION} to ${PackManifest.NEWEST}")
        head.byte(); head.byte()
        val manifestLength = head.u32().toInt()
        val directoryLength = head.u32().toInt()
        manifest = PackManifest.parse(String(input.read(PackWriter.HEADER.toLong(), manifestLength), Charsets.UTF_8))
        if (manifest.version != version) throw PackFormatException("the header says format $version, the manifest ${manifest.version}")
        val dir = ByteSource(input.read((PackWriter.HEADER + manifestLength).toLong(), directoryLength))
        val m = LinkedHashMap<String, Entry>()
        while (dir.remaining > 0) {
            val name = dir.string()
            val offset = dir.u64()
            // format 2 has 64-bit lengths: a country's chunks pass 4 GB
            val stored = if (version >= PackManifest.TILED) dir.u64() else dir.u32()
            val raw = if (version >= PackManifest.TILED) dir.u64() else dir.u32()
            val crc = dir.u32()
            val method = dir.byte()
            if (offset < 0 || stored < 0 || offset + stored > input.size) throw PackFormatException("the section '$name' runs past the end of the pack")
            m[name] = Entry(name, offset, stored, raw, crc, method)
        }
        entries = m
        if (version >= PackManifest.TILED) PackTiles.checkLevels(manifest.levels)
    }

    override val id: String get() = manifest.id
    override val config: JsonObject get() = manifest.config
    override val elements: ElementSource get() = this
    override val header: JsonObject get() = manifest.header
    override val elementCount: Int get() = manifest.elementCount

    /** Total size of the pack in bytes. */
    val size: Long get() = input.size

    /** Names of the sections in the file. */
    val sectionNames: Set<String> get() = entries.keys

    val demNames: List<String> get() = entries.keys.filter { it.startsWith("dem:") }.map { it.substring(4) }
    val auxNames: List<String> get() = entries.keys.filter { it.startsWith("aux:") }.map { it.substring(4) }

    /** True for format 2, the tile pyramid. */
    val isTiled: Boolean get() = version >= PackManifest.TILED

    private fun section(name: String): ByteArray {
        val e = entries[name] ?: throw PackFormatException("the pack has no '$name' section")
        if (e.stored > Int.MAX_VALUE || e.raw > Int.MAX_VALUE) throw PackFormatException("the section '$name' is too big to read whole")
        val stored = input.read(e.offset, e.stored.toInt())
        return if (e.method == 0) stored else Zip.inflate(stored, e.raw.toInt())
    }

    override fun dem(name: String): Dem? = if ("dem:$name" in entries) DemCodec.decode(ByteSource(section("dem:$name"))) else null

    override fun aux(name: String): JsonElement? =
        if ("aux:$name" in entries) Json.parseToJsonElement(ByteSource(section("aux:$name")).string()) else null

    private fun readIndex(): Index {
        val src = ByteSource(section("index"))
        val n = src.varInt()
        val k = src.varInt()
        val scale = Scale(src.byte())
        val noBoxCount = src.varInt()
        val noBox = BooleanArray(n)
        var prev = -1
        repeat(noBoxCount) {
            prev += src.varInt() + 1
            noBox[prev] = true
        }
        val ordinal = IntArray(n)
        val boxed = IntArray(n - noBoxCount)
        val minLat = DoubleArray(boxed.size)
        val minLon = DoubleArray(boxed.size)
        val maxLat = DoubleArray(boxed.size)
        val maxLon = DoubleArray(boxed.size)
        val tree = PackedRTree(boxed.size)
        var lastOrdinal = 0L
        var lastLat = 0L
        var lastLon = 0L
        var b = 0
        for (s in 0 until n) {
            lastOrdinal += src.zigzag()
            ordinal[s] = lastOrdinal.toInt()
            if (noBox[s]) continue
            val la = lastLat + src.zigzag()
            val lo = lastLon + src.zigzag()
            val xa = la + src.varLong()
            val xo = lo + src.varLong()
            lastLat = la
            lastLon = lo
            boxed[b] = s
            minLat[b] = scale.degrees(la)
            minLon[b] = scale.degrees(lo)
            maxLat[b] = scale.degrees(xa)
            maxLon[b] = scale.degrees(xo)
            tree.add(minLon[b], minLat[b], maxLon[b], maxLat[b])
            b++
        }
        tree.finish()
        return Index(n, k, scale, ordinal, boxed, minLat, minLon, maxLat, maxLon, tree)
    }

    private fun readChunkTable(): IntArray {
        val src = ByteSource(section("chtab"))
        val n = src.varInt()
        val t = IntArray(n * 2)
        for (i in 0 until n) {
            t[2 * i] = src.varInt()
            t[2 * i + 1] = src.varInt()
        }
        return t
    }

    private fun readTileTable(): TileTable {
        val levels = manifest.levels
        val src = ByteSource(section("tiles"))
        val base = entries["chunks"]?.offset ?: throw PackFormatException("the pack has no chunks")
        val n = src.varInt()
        if (n < 0) throw PackFormatException("a bad chunk count")
        var offset = 0L
        val chunks = arrayOfNulls<TileChunk>(n)
        val levelStart = IntArray(levels.size + 1)
        var level = 0
        for (i in 0 until n) {
            val z = src.byte()
            val x = src.varInt()
            val y = src.varInt()
            val stored = src.varInt()
            val raw = src.varInt()
            val count = src.varInt()
            val hash = src.bytes(32)
            while (level < levels.size && levels[level] != z) levelStart[++level] = i
            if (level == levels.size) throw PackFormatException("chunk $i is at zoom $z, not one of the pack's levels in order")
            if (x < 0 || y < 0 || x >= (1 shl z) || y >= (1 shl z)) throw PackFormatException("chunk $i has no tile $z/$x/$y")
            chunks[i] = TileChunk(z, x, y, base + offset, stored, raw, count, hash)
            offset += stored
        }
        while (level < levels.size) levelStart[++level] = n
        val chunksEntry = entries.getValue("chunks")
        if (offset != chunksEntry.stored) throw PackFormatException("the chunk table covers $offset bytes of a ${chunksEntry.stored} byte section")
        @Suppress("UNCHECKED_CAST")
        val all = chunks as Array<TileChunk>
        val li = IntArray(n)
        for (l in levels.indices) for (i in levelStart[l] until levelStart[l + 1]) li[i] = l
        val keys = LongArray(n) { PackTiles.pack(li[it], all[it].x, all[it].y) }
        val order = TiledPackWriter.sortIndices(n) { a, b -> keys[a].compareTo(keys[b]) }
        val sorted = LongArray(n) { keys[order[it]] }
        for (i in 1 until n) if (sorted[i] == sorted[i - 1]) throw PackFormatException("two chunks for one tile")
        return TileTable(levels, all, levelStart, sorted, order)
    }

    private fun decodeTile(c: Int): Array<OsmElement> {
        val t = tiles.chunks[c]
        val raw = Zip.inflate(input.read(t.offset, t.stored), t.raw)
        val src = ByteSource(raw)
        val dict = Dictionary.read(src, src)
        val els = ElementDecoder(dict, tiledScale).decodeChunk(src)
        if (els.size != t.count || src.remaining != 0) throw PackFormatException("chunk $t does not hold what its table says")
        return els
    }

    private fun tile(c: Int): Array<OsmElement> {
        synchronized(cache) { cache[c]?.let { return it } }
        val decoded = decodeTile(c)
        val weight = tiles.chunks[c].raw.toLong()
        if (weight <= CACHE_BYTES / 4) synchronized(cache) {
            if (cache.put(c, decoded) == null) cachedBytes += weight
            val it = cache.entries.iterator()
            while (cachedBytes > CACHE_BYTES && it.hasNext()) {
                val eldest = it.next()
                if (eldest.key == c) continue
                cachedBytes -= tiles.chunks[eldest.key].raw
                it.remove()
            }
        }
        return decoded
    }

    /** The tiles a query of the box reads at each level: those within one tile of it, which hold every element meeting it. */
    private fun candidates(south: Double, west: Double, north: Double, east: Double): IntArray {
        val t = tiles
        val out = ArrayList<Int>()
        val w = minOf(west, east)
        val e = maxOf(west, east)
        val s = minOf(south, north)
        val n = maxOf(south, north)
        for (li in t.levels.indices) {
            val z = t.levels[li]
            val last = (1 shl z) - 1
            val x0 = (PackTiles.tileX(w, z) - 1).coerceAtLeast(0)
            val x1 = (PackTiles.tileX(e, z) + 1).coerceAtMost(last)
            val y0 = (PackTiles.tileY(n, z) - 1).coerceAtLeast(0)
            val y1 = (PackTiles.tileY(s, z) + 1).coerceAtMost(last)
            val from = t.levelStart[li]
            val to = t.levelStart[li + 1]
            if ((x1 - x0 + 1).toLong() * (y1 - y0 + 1) > to - from) {
                for (c in from until to) {
                    val ch = t.chunks[c]
                    if (ch.x in x0..x1 && ch.y in y0..y1) out += c
                }
            } else {
                for (x in x0..x1) for (y in y0..y1) {
                    val c = t.find(li, x, y)
                    if (c >= 0) out += c
                }
            }
        }
        return out.toIntArray().also { it.sort() }
    }

    private fun tiledQuery(south: Double, west: Double, north: Double, east: Double, keep: ((OsmElement) -> Boolean)?): List<OsmElement> {
        val found = ArrayList<OsmElement>()
        val box = DoubleArray(4)
        for (c in candidates(south, west, north, east)) {
            for (el in tile(c)) {
                if (!boxOf(el, box)) continue
                // the index's own test (PackedRTree): edges touching count
                if (east >= box[1] && north >= box[0] && west <= box[3] && south <= box[2] && (keep == null || keep(el))) found += el
            }
        }
        found.sortWith(OsmElement.CANONICAL)
        return found
    }

    private fun decodeChunk(c: Int): Array<OsmElement> {
        val idx = index
        val table = chunkTable
        if (c < 0 || c >= table.size / 2) throw PackFormatException("no chunk $c")
        val e = entries["chunks"] ?: throw PackFormatException("the pack has no chunks")
        val stored = input.read(e.offset + chunkOffsets[c], table[2 * c])
        val raw = Zip.inflate(stored, table[2 * c + 1])
        return ElementDecoder(dictionary, idx.scale).decodeChunk(raw)
    }

    private fun chunk(c: Int): Array<OsmElement> {
        synchronized(cache) { cache[c]?.let { return it } }
        val decoded = decodeChunk(c)
        synchronized(cache) { cache[c] = decoded }
        return decoded
    }

    /**
     * Every element, in the order of the extract the pack was made from: for a tiled pack the canonical order (nodes,
     * ways, relations, each by id), all decoded at once, so a country's pack should be read by [tileChunks] instead.
     */
    override fun elements(): Sequence<OsmElement> {
        if (isTiled) {
            val all = ArrayList<OsmElement>(manifest.elementCount)
            for (c in tiles.chunks.indices) all.addAll(decodeTile(c))
            all.sortWith(OsmElement.CANONICAL)
            return all.asSequence()
        }
        val idx = index
        val all = arrayOfNulls<OsmElement>(idx.count)
        val chunks = chunkTable.size / 2
        for (c in 0 until chunks) {
            val els = decodeChunk(c)
            for ((j, el) in els.withIndex()) all[idx.ordinal[c * idx.chunkSize + j]] = el
        }
        return all.asSequence().map { it ?: throw PackFormatException("an element is missing from the chunks") }
    }

    /**
     * The elements whose box meets the given one (edges touching count), in the extract's order (canonical for a tiled
     * pack). An element's box is its `bounds` when it has them, else the extent of its positions.
     */
    fun query(south: Double, west: Double, north: Double, east: Double): List<OsmElement> {
        if (isTiled) return tiledQuery(south, west, north, east, null)
        val idx = index
        if (idx.boxed.isEmpty()) return emptyList()
        val hits = idx.tree.search(west, south, east, north)
        val places = IntArray(hits.size) { idx.boxed[hits[it]] }
        places.sort()
        val found = ArrayList<Pair<Int, OsmElement>>(places.size)
        var i = 0
        while (i < places.size) {
            val c = places[i] / idx.chunkSize
            val els = chunk(c)
            while (i < places.size && places[i] / idx.chunkSize == c) {
                found += idx.ordinal[places[i]] to els[places[i] - c * idx.chunkSize]
                i++
            }
        }
        found.sortBy { it.first }
        return found.map { it.second }
    }

    fun query(box: BBox): List<OsmElement> = query(box.south, box.west, box.north, box.east)

    /** [query] keeping only the elements [keep] accepts; a tiled pack drops the others as it decodes. */
    fun query(box: BBox, keep: (OsmElement) -> Boolean): List<OsmElement> =
        if (isTiled) tiledQuery(box.south, box.west, box.north, box.east, keep) else query(box).filter(keep)

    /** A tiled pack's chunks, in file order (empty for format 1). */
    fun tileChunks(): List<TileChunk> = if (isTiled) tiles.chunks.asList() else emptyList()

    /** The zoom levels of a tiled pack, finest first (empty for format 1). */
    val levels: IntArray get() = if (isTiled) tiles.levels.copyOf() else IntArray(0)

    /**
     * The chunks a [query] of the box reads, in file order: what a phone fetches to hold an area, and what a scene
     * window built from the area depends on. Empty for format 1.
     */
    fun chunksFor(box: BBox): List<TileChunk> = if (isTiled) candidates(box.south, box.west, box.north, box.east).map { tiles.chunks[it] } else emptyList()

    /** The elements of one chunk of a tiled pack, in canonical order. */
    fun chunkElements(chunk: TileChunk): List<OsmElement> {
        check(isTiled) { "a format 1 pack has no tile chunks" }
        val c = tiles.find(tiles.levels.indexOf(chunk.z), chunk.x, chunk.y)
        if (c < 0 || tiles.chunks[c] != chunk) throw PackFormatException("chunk $chunk is not in this pack")
        return java.util.Collections.unmodifiableList(tile(c).asList())
    }

    /** The elements within [metres] of [centre] (their box meets the circle's bounding box), in the extract's order. */
    fun around(centre: LatLon, metres: Double): List<OsmElement> = query(BBox.around(centre, metres))

    /** The box of every element that has one, with its extract position; for tests of a format 1 index. */
    internal fun boxes(): List<DoubleArray> {
        check(!isTiled) { "a tiled pack has no element index" }
        val idx = index
        return List(idx.boxed.size) { doubleArrayOf(idx.minLat[it], idx.minLon[it], idx.maxLat[it], idx.maxLon[it], idx.ordinal[idx.boxed[it]].toDouble()) }
    }

    /**
     * Checks every section's CRC-32; returns the names of the damaged sections. When a tiled pack's `chunks` fail, each
     * chunk's SHA-256 is checked too and the damaged ones are named `tile z/x/y` (a partly fetched copy names the chunks
     * it lacks).
     */
    fun verify(): List<String> {
        val bad = ArrayList<String>()
        for (e in entries.values) {
            val crc = CRC32()
            var at = 0L
            while (at < e.stored) {
                val n = minOf(e.stored - at, 1L shl 20).toInt()
                crc.update(input.read(e.offset + at, n))
                at += n
            }
            if (crc.value != e.crc) bad += e.name
        }
        if (isTiled && "chunks" in bad) {
            val sha = java.security.MessageDigest.getInstance("SHA-256")
            for (t in tiles.chunks) {
                val ok = try {
                    sha.reset()
                    t.hashEquals(sha.digest(Zip.inflate(input.read(t.offset, t.stored), t.raw)))
                } catch (_: PackFormatException) {
                    false
                }
                if (!ok) bad += "tile ${t.z}/${t.x}/${t.y}"
            }
        }
        return bad
    }

    /** Chunks decoded and kept for the next query. */
    val cachedChunks: Int get() = synchronized(cache) { cache.size }

    val chunkCount: Int get() = if (isTiled) tiles.chunks.size else chunkTable.size / 2

    override fun close() = input.close()

    companion object {
        private const val CACHE_CHUNKS = 128

        /**
         * Inflated bytes of chunks a tiled pack keeps decoded for the next query (the objects take a few times that); a
         * chunk bigger than a quarter of it is decoded for each query and not kept.
         */
        private const val CACHE_BYTES = 16L shl 20

        /** The box an index keeps for [e]: its bounds, else the extent of its positions; false when it has none. */
        internal fun boxOf(e: OsmElement, out: DoubleArray): Boolean {
            e.bounds?.let {
                out[0] = it.minLat; out[1] = it.minLon; out[2] = it.maxLat; out[3] = it.maxLon
                return it.minLat <= it.maxLat
            }
            var a = Double.POSITIVE_INFINITY
            var b = Double.POSITIVE_INFINITY
            var c = Double.NEGATIVE_INFINITY
            var d = Double.NEGATIVE_INFINITY
            fun add(la: Double, lo: Double) {
                if (la < a) a = la
                if (la > c) c = la
                if (lo < b) b = lo
                if (lo > d) d = lo
            }
            if (!e.lat.isNaN()) add(e.lat, e.lon)
            e.geometry?.let { g -> for (i in 0 until g.size) if (!g.isNull(i)) add(g.lat[i], g.lon[i]) }
            e.members?.forEach { m ->
                if (m.hasPosition) add(m.lat, m.lon)
                m.geometry?.let { g -> for (i in 0 until g.size) if (!g.isNull(i)) add(g.lat[i], g.lon[i]) }
            }
            out[0] = a; out[1] = b; out[2] = c; out[3] = d
            return a <= c
        }

        fun open(file: File): PackReader = PackReader(FilePackInput(file))

        fun open(bytes: ByteArray): PackReader = PackReader(BytesPackInput(bytes))

        fun open(input: PackInput): PackReader = PackReader(input)
    }
}

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
 * Reads a `.atlas` region pack. Opening reads the manifest and the directory; the shared tables and the index load on
 * first use; elements decode a chunk at a time, so [query] touches only the chunks near the view.
 */
class PackReader private constructor(private val input: PackInput) : RegionData, ElementSource, Closeable {
    private class Entry(val name: String, val offset: Long, val stored: Int, val raw: Int, val crc: Long, val method: Int)

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
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Int, Array<OsmElement>>): Boolean = size > CACHE_CHUNKS
    }

    init {
        val head = ByteSource(input.read(0, PackWriter.HEADER))
        val magic = String(head.bytes(8), Charsets.US_ASCII)
        if (magic != PackWriter.MAGIC) throw PackFormatException("not an Atlas region pack")
        head.byte()
        val version = head.byte()
        if (version > PackManifest.VERSION) throw PackFormatException("this pack is format $version; this reader knows up to ${PackManifest.VERSION}")
        head.byte(); head.byte()
        val manifestLength = head.u32().toInt()
        val directoryLength = head.u32().toInt()
        manifest = PackManifest.parse(String(input.read(PackWriter.HEADER.toLong(), manifestLength), Charsets.UTF_8))
        val dir = ByteSource(input.read((PackWriter.HEADER + manifestLength).toLong(), directoryLength))
        val m = LinkedHashMap<String, Entry>()
        while (dir.remaining > 0) {
            val name = dir.string()
            val offset = dir.u64()
            val stored = dir.u32().toInt()
            val raw = dir.u32().toInt()
            val crc = dir.u32()
            val method = dir.byte()
            m[name] = Entry(name, offset, stored, raw, crc, method)
        }
        entries = m
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

    private fun section(name: String): ByteArray {
        val e = entries[name] ?: throw PackFormatException("the pack has no '$name' section")
        val stored = input.read(e.offset, e.stored)
        return if (e.method == 0) stored else Zip.inflate(stored, e.raw)
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

    /** Every element, in the order of the extract the pack was made from. */
    override fun elements(): Sequence<OsmElement> {
        val idx = index
        val all = arrayOfNulls<OsmElement>(idx.count)
        val chunks = chunkTable.size / 2
        for (c in 0 until chunks) {
            val els = decodeChunk(c)
            for ((j, el) in els.withIndex()) all[idx.ordinal[c * idx.chunkSize + j]] = el
        }
        return all.asSequence().map { it ?: throw PackFormatException("an element is missing from the chunks") }
    }

    /** The elements whose box meets the given one (edges touching count), in the extract's order. */
    fun query(south: Double, west: Double, north: Double, east: Double): List<OsmElement> {
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

    /** The elements within [metres] of [centre] (their box meets the circle's bounding box), in the extract's order. */
    fun around(centre: LatLon, metres: Double): List<OsmElement> = query(BBox.around(centre, metres))

    /** The box of the element stored at extract position [ordinal], or null; for tests of the index. */
    internal fun boxes(): List<DoubleArray> {
        val idx = index
        return List(idx.boxed.size) { doubleArrayOf(idx.minLat[it], idx.minLon[it], idx.maxLat[it], idx.maxLon[it], idx.ordinal[idx.boxed[it]].toDouble()) }
    }

    /** Checks every section's CRC-32; returns the names of the damaged ones. */
    fun verify(): List<String> {
        val bad = ArrayList<String>()
        for (e in entries.values) {
            val crc = CRC32().also { it.update(input.read(e.offset, e.stored)) }
            if (crc.value != e.crc) bad += e.name
        }
        return bad
    }

    /** Chunks decoded and kept for the next query. */
    val cachedChunks: Int get() = synchronized(cache) { cache.size }

    val chunkCount: Int get() = chunkTable.size / 2

    override fun close() = input.close()

    companion object {
        private const val CACHE_CHUNKS = 128

        fun open(file: File): PackReader = PackReader(FilePackInput(file))

        fun open(bytes: ByteArray): PackReader = PackReader(BytesPackInput(bytes))

        fun open(input: PackInput): PackReader = PackReader(input)
    }
}

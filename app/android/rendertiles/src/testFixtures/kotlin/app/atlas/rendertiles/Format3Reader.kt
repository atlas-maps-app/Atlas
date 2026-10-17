package app.atlas.rendertiles

import app.atlas.render.data.ByteSource
import app.atlas.render.data.PackManifest
import app.atlas.rendertiles.codec.Columns
import app.atlas.rendertiles.codec.Format3Writer
import app.atlas.rendertiles.codec.LeSource
import app.atlas.rendertiles.codec.StringTable
import app.atlas.rendertiles.codec.TileFormatException
import app.atlas.rendertiles.model.Cell
import app.atlas.rendertiles.model.Codec
import app.atlas.rendertiles.model.Feature
import app.atlas.rendertiles.model.Format
import app.atlas.rendertiles.model.Geom
import app.atlas.rendertiles.model.Grid
import app.atlas.rendertiles.model.Layer
import app.atlas.rendertiles.model.Reach
import app.atlas.rendertiles.model.RenderHead
import app.atlas.rendertiles.model.RenderTile
import app.atlas.rendertiles.model.Schema
import app.atlas.rendertiles.model.StyleTables
import com.github.luben.zstd.Zstd
import java.io.File
import java.security.MessageDigest
import java.util.zip.CRC32

/**
 * Reads a format-3 render pack back: the container, the sections, every tile decoded to [RenderTile]s with every
 * check [Format] asks of a reader (lengths, offsets and bounds against the bytes, the payload version, the chunks'
 * SHA-256, the sections' CRC-32) and its one rule for what a reader does not know (a layer, a column, a section: skipped;
 * a known column in another codec: an error). The Kotlin twin of `CP/tiles/`, for tests and the core's checks.
 *
 * One check goes beyond what the core can make, to hold the writer to the format: the CRC-32 of the whole `chunks`
 * section ([checkChunksCrc]; a reader by ranges relies on each chunk's SHA-256). Each tile's bounds must be
 * [Grid.tileBox]'s, here as in the core.
 */
class Format3Reader(private val bytes: ByteArray, private val checkChunksCrc: Boolean = true) {
    /** One section of the directory. */
    class Section(val name: String, val offset: Long, val stored: Long, val raw: Long, val crc: Long, val method: Int)

    /** One entry of the chunk table, with its place in the file. */
    class Chunk(val z: Int, val x: Int, val y: Int, val offset: Long, val stored: Int, val raw: Int, val count: Int, val sha256: ByteArray)

    val manifest: PackManifest
    val version: Int
    val sections: Map<String, Section>
    val chunks: List<Chunk>

    /** The `reach` section: one entry per chunk, in [chunks]' order. */
    val reach: List<Reach>
    val head: RenderHead
    val style: StyleTables

    constructor(file: File, checkChunksCrc: Boolean = true) : this(file.readBytes(), checkChunksCrc)

    init {
        val h = ByteSource(bytes, 0, minOf(bytes.size, Format3Writer.HEADER))
        if (bytes.size < Format3Writer.HEADER || String(h.bytes(8), Charsets.US_ASCII) != "ATLASPCK") throw TileFormatException("not an Atlas pack")
        h.byte()
        version = h.byte()
        if (version != Format.VERSION) throw TileFormatException("format $version, not ${Format.VERSION}")
        h.byte(); h.byte()
        val ml = h.u32()
        val dl = h.u32()
        if (Format3Writer.HEADER + ml + dl > bytes.size) throw TileFormatException("the manifest and directory run past the pack")
        manifest = PackManifest.parse(String(bytes, Format3Writer.HEADER, ml.toInt(), Charsets.UTF_8))
        if (manifest.version != version) throw TileFormatException("the manifest says format ${manifest.version}")
        val dir = ByteSource(bytes, (Format3Writer.HEADER + ml).toInt(), (Format3Writer.HEADER + ml + dl).toInt())
        val m = LinkedHashMap<String, Section>()
        while (dir.remaining > 0) {
            val s = Section(dir.string(), dir.u64(), dir.u64(), dir.u64(), dir.u32(), dir.byte())
            if (s.offset < 0 || s.stored < 0 || s.offset + s.stored > bytes.size) throw TileFormatException("section ${s.name} runs past the pack")
            val crcd = s.name != Format.SECTION_CHUNKS || checkChunksCrc
            if (crcd && CRC32().also { it.update(bytes, s.offset.toInt(), s.stored.toInt()) }.value != s.crc) throw TileFormatException("section ${s.name} is damaged")
            m[s.name] = s
        }
        sections = m
        val tiles = section(Format.SECTION_TILES)
        if (tiles.method != Format.METHOD_STORED) throw TileFormatException("the chunk table is not stored")
        val chunkSection = section(Format.SECTION_CHUNKS)
        if (chunkSection.method != Format.METHOD_ZSTD) throw TileFormatException("the chunks are not zstd")
        val t = ByteSource(bytes, tiles.offset.toInt(), (tiles.offset + tiles.stored).toInt())
        val n = t.varInt()
        // an entry takes at least 38 bytes: zoom, five one-byte varints and the hash
        if (n < 0 || n > t.remaining / CHUNK_ENTRY_MIN) throw TileFormatException("$n chunks in a table of ${t.remaining} bytes")
        var at = chunkSection.offset
        val list = ArrayList<Chunk>(n)
        repeat(n) {
            val c = Chunk(t.byte(), t.varInt(), t.varInt(), at, t.varInt(), t.varInt(), t.varInt(), t.bytes(32))
            checkTile(c.z, c.x, c.y)
            if (c.stored < 0 || c.raw < 0 || c.raw > Format.MAX_TILE_BYTES) throw TileFormatException("chunk ${c.z}/${c.x}/${c.y}: ${c.stored} bytes stored, ${c.raw} raw")
            at += c.stored
            list += c
        }
        if (t.remaining != 0) throw TileFormatException("the chunk table has bytes left over")
        if (at != chunkSection.offset + chunkSection.stored) throw TileFormatException("the chunks do not fill their section")
        chunks = list
        reach = readReach(sectionBytes(Format.SECTION_REACH), list.size)
        head = RenderHead.fromBytes(sectionBytes(Format.SECTION_HEAD))
        style = StyleTables.fromBytes(sectionBytes(Format.SECTION_STYLE))
    }

    private fun section(name: String): Section = sections[name] ?: throw TileFormatException("no section $name")

    /** The reach of [c]. */
    fun reachOf(c: Chunk): Reach = reach[chunks.indexOf(c)]

    private fun sectionBytes(name: String): ByteArray {
        val s = section(name)
        val stored = bytes.copyOfRange(s.offset.toInt(), (s.offset + s.stored).toInt())
        return when (s.method) {
            Format.METHOD_STORED -> stored
            Format.METHOD_ZSTD -> {
                if (s.raw > Int.MAX_VALUE) throw TileFormatException("section $name is too big")
                Zstd.decompress(stored, s.raw.toInt()).also { if (it.size.toLong() != s.raw) throw TileFormatException("section $name inflates to ${it.size} bytes, not ${s.raw}") }
            }
            else -> throw TileFormatException("section $name has method ${s.method}")
        }
    }

    /** The decompressed bytes of [c], checked against its SHA-256. */
    fun raw(c: Chunk): ByteArray {
        val stored = bytes.copyOfRange(c.offset.toInt(), c.offset.toInt() + c.stored)
        val raw = Zstd.decompress(stored, c.raw)
        if (raw.size != c.raw) throw TileFormatException("chunk ${c.z}/${c.x}/${c.y} inflates to ${raw.size} bytes, not ${c.raw}")
        if (!MessageDigest.getInstance("SHA-256").digest(raw).contentEquals(c.sha256)) throw TileFormatException("chunk ${c.z}/${c.x}/${c.y} is damaged")
        return raw
    }

    /** Every tile, in the file's order. */
    fun tiles(): List<RenderTile> = chunks.map { decode(it) }

    /** The tile at [z]/[x]/[y], or null. */
    fun tile(z: Int, x: Int, y: Int): RenderTile? = chunks.firstOrNull { it.z == z && it.x == x && it.y == y }?.let { decode(it) }

    /** [c]'s tile, checked against its entry in the chunk table and in `reach`. */
    fun decode(c: Chunk): RenderTile {
        val t = decodeTile(raw(c))
        if (t.level != c.z || t.x != c.x || t.y != c.y) throw TileFormatException("chunk ${c.z}/${c.x}/${c.y} holds tile ${t.level}/${t.x}/${t.y}")
        if (Reach.of(t) != reachOf(c)) throw TileFormatException("chunk ${c.z}/${c.x}/${c.y} reaches ${Reach.of(t)}, not ${reachOf(c)}")
        return t
    }

    companion object {
        private const val CHUNK_ENTRY_MIN = 38

        /** A level, column and row within [Format]'s bounds. */
        fun checkTile(z: Int, x: Long, y: Long) {
            if (z < 0 || z > Format.MAX_LEVEL) throw TileFormatException("a tile at level $z")
            if (x < 0 || y < 0 || x >= 1L shl z || y >= 1L shl z) throw TileFormatException("tile $z/$x/$y is off its level")
        }

        private fun checkTile(z: Int, x: Int, y: Int) = checkTile(z, x.toLong(), y.toLong())

        /** The `reach` section ([Reach]'s layout) of a pack of [count] chunks. */
        fun readReach(b: ByteArray, count: Int): List<Reach> {
            val src = LeSource(b, 0, b.size)
            if (b.size < Reach.HEADER_BYTES || src.u32() != Format.REACH_MAGIC.toLong()) throw TileFormatException("not a reach section")
            val payload = src.u16()
            if (payload != Format.PAYLOAD) throw TileFormatException("a reach section of payload $payload; this reader knows ${Format.PAYLOAD}")
            val e = src.u16()
            val n = src.u32()
            if (e < Reach.ENTRY_BYTES) throw TileFormatException("reach entries of $e bytes")
            if (n != count.toLong()) throw TileFormatException("$n reach entries for $count chunks")
            if (src.remaining.toLong() != n * e) throw TileFormatException("a reach section of ${src.remaining} bytes for $n entries of $e")
            return List(count) {
                val r = Reach(src.i32(), src.i32(), src.i32(), src.i32(), src.i32())
                src.bytes(e - Reach.ENTRY_BYTES)
                if (r.south > r.north || r.west > r.east) throw TileFormatException("reach $it is an empty box")
                r
            }
        }

        /** Decodes one tile's bytes ([Format]'s layout), checking everything against them. */
        fun decodeTile(b: ByteArray): RenderTile {
            val h = LeSource(b, 0, b.size)
            if (b.size < Format.HEADER_BYTES || h.u32() != Format.TILE_MAGIC.toLong()) throw TileFormatException("not a render tile")
            if (b.size > Format.MAX_TILE_BYTES) throw TileFormatException("a tile of ${b.size} bytes")
            val payload = h.u16()
            if (payload != Format.PAYLOAD) throw TileFormatException("a tile of payload $payload; this reader knows ${Format.PAYLOAD}")
            val level = h.u8()
            val n = h.u8()
            val x = h.u32()
            val y = h.u32()
            checkTile(level, x, y)
            if (n != Format.cellsPerSide(level)) throw TileFormatException("a tile at level $level with $n cells a side")
            val box = IntArray(4) { h.i32() }
            if (!box.contentEquals(Grid.tileBox(level, x.toInt(), y.toInt()))) throw TileFormatException("tile $level/$x/$y has the box ${box.joinToString()}")
            val sAt = h.u32()
            val sLen = h.u32()
            val cAt = h.u32()
            val cLen = h.u32()
            val layerCount = h.u16()
            h.u16()
            fun block(at: Long, len: Long): LeSource {
                if (at < 0 || len < 0 || at + len > b.size) throw TileFormatException("a block at $at of $len bytes runs past the tile")
                return LeSource(b, at.toInt(), (at + len).toInt())
            }
            val strings = block(sAt, sLen).let { s -> StringTable.read(s).also { if (s.remaining != 0) throw TileFormatException("the string table has bytes left over") } }
            class Entry(val layer: Layer?, val id: Int, val columns: Int, val count: Int, val src: LeSource)
            val entries = ArrayList<Entry>()
            repeat(layerCount) {
                val id = h.u8()
                h.u8()
                val cols = h.u16()
                val count = h.u32()
                val at = h.u32()
                val len = h.u32()
                if (count > Format.MAX_FEATURES) throw TileFormatException("a layer of $count features")
                // a layer this payload does not define is skipped, its features counted in the cells all the same
                val layer = Layer.of(id)?.takeIf { l -> runCatching { Schema.columns(l) }.isSuccess }
                entries += Entry(layer, id, cols, count.toInt(), block(at, len))
            }
            if (entries.map { it.id }.toSet().size != entries.size) throw TileFormatException("a layer twice")
            val cells = ArrayList<Cell>(n * n)
            val cs = block(cAt, cLen)
            repeat(n * n) {
                val total = cs.varCount()
                if (total == 0) {
                    cells += Cell(null, 0, emptyMap())
                    return@repeat
                }
                val s = cs.varZ() + box[0]
                val w = cs.varZ() + box[1]
                val no = cs.varZ() + box[0]
                val e = cs.varZ() + box[1]
                val top = cs.varZ()
                val counts = LinkedHashMap<Layer, Int>()
                var sum = 0L
                for (en in entries) {
                    val k = cs.varCount()
                    sum += k
                    if (en.layer != null) counts[en.layer] = k
                }
                if (sum != total.toLong()) throw TileFormatException("a cell of $total features counts $sum")
                cells += Cell(intArrayOf(s.toInt(), w.toInt(), no.toInt(), e.toInt()), top.toInt(), counts)
            }
            if (cs.remaining != 0) throw TileFormatException("the cell table has bytes left over")
            val layers = LinkedHashMap<Layer, List<Feature>>()
            for (en0 in entries) {
                val en = en0.layer?.let { en0 } ?: continue
                val layer = en.layer!!
                if (cells.sumOf { it.counts[layer] ?: 0 } != en.count) throw TileFormatException("${layer.label}: its cells count other than its ${en.count} features")
                // the one rule: a column this schema does not name is skipped, whatever its codec; a named one must
                // have the schema's codec
                val schema = Schema.columns(layer)
                val known = schema.associateBy { it.name }
                val present = LinkedHashMap<String, Pair<Codec, LeSource>>()
                repeat(en.columns) {
                    val name = strings.getOrNull(en.src.varCount()) ?: throw TileFormatException("a column name outside the strings")
                    val codecId = en.src.u8()
                    val block = en.src.block()
                    val col = known[name] ?: return@repeat
                    val codec = Codec.of(codecId) ?: throw TileFormatException("${layer.label}.$name has the unknown codec $codecId, not ${col.codec}")
                    if (name in present) throw TileFormatException("${layer.label}.$name twice")
                    present[name] = codec to block
                }
                if (en.src.remaining != 0) throw TileFormatException("${layer.label} has bytes after its columns")
                val values = LinkedHashMap<String, List<Any?>>()
                for (col in schema) {
                    val p = present[col.name]
                    values[col.name] = if (p == null) {
                        if (col.codec == Codec.POINT || col.codec == Codec.F64) throw TileFormatException("${layer.label} lacks its ${col.name} column")
                        if (col.codec == Codec.KEEP) values[col.name.removeSuffix(".keep")]!! else List(en.count) { Format3Writer.default(col.codec) }
                    } else {
                        if (p.first != col.codec) throw TileFormatException("${layer.label}.${col.name} is ${p.first}, not ${col.codec}")
                        @Suppress("UNCHECKED_CAST")
                        val geoms = if (col.codec == Codec.KEEP) values[col.name.removeSuffix(".keep")] as List<Geom> else null
                        Columns.decode(col.codec, p.second, en.count, strings, geoms)
                    }
                }
                val features = ArrayList<Feature>(en.count)
                var k = 0
                for ((c, cell) in cells.withIndex()) {
                    val m = cell.counts[layer] ?: 0
                    for (j in 0 until m) {
                        val fields = LinkedHashMap<String, Any?>()
                        for (col in schema) {
                            if (col.codec == Codec.KEEP) continue
                            fields[col.name] = values[col.name]!![k]
                        }
                        // a ring column carries its keep codes once the keep column is read
                        for (col in schema) if (col.codec == Codec.KEEP) fields[col.name.removeSuffix(".keep")] = values[col.name]!![k]
                        features += Feature(layer, Schema.canonical(layer, fields), c, null, 0, j.toLong())
                        k++
                    }
                }
                layers[layer] = features
            }
            return RenderTile(level, x.toInt(), y.toInt(), n, layers, cells)
        }
    }
}

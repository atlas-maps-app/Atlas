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
 * check the core's reader makes (lengths and offsets against the bytes, the payload version, the chunks' SHA-256 and the
 * sections' CRC-32). The Kotlin twin of `CP/tiles/`, for tests and the core's checks.
 */
class Format3Reader(private val bytes: ByteArray) {
    /** One section of the directory. */
    class Section(val name: String, val offset: Long, val stored: Long, val raw: Long, val crc: Long, val method: Int)

    /** One entry of the chunk table, with its place in the file. */
    class Chunk(val z: Int, val x: Int, val y: Int, val offset: Long, val stored: Int, val raw: Int, val count: Int, val sha256: ByteArray)

    val manifest: PackManifest
    val version: Int
    val sections: Map<String, Section>
    val chunks: List<Chunk>
    val head: RenderHead
    val style: StyleTables

    constructor(file: File) : this(file.readBytes())

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
            if (CRC32().also { it.update(bytes, s.offset.toInt(), s.stored.toInt()) }.value != s.crc) throw TileFormatException("section ${s.name} is damaged")
            m[s.name] = s
        }
        sections = m
        val tiles = section(Format.SECTION_TILES)
        if (tiles.method != Format.METHOD_STORED) throw TileFormatException("the chunk table is not stored")
        val chunkSection = section(Format.SECTION_CHUNKS)
        if (chunkSection.method != Format.METHOD_ZSTD) throw TileFormatException("the chunks are not zstd")
        val t = ByteSource(bytes, tiles.offset.toInt(), (tiles.offset + tiles.stored).toInt())
        val n = t.varInt()
        var at = chunkSection.offset
        val list = ArrayList<Chunk>(n)
        repeat(n) {
            val c = Chunk(t.byte(), t.varInt(), t.varInt(), at, t.varInt(), t.varInt(), t.varInt(), t.bytes(32))
            at += c.stored
            list += c
        }
        if (t.remaining != 0) throw TileFormatException("the chunk table has bytes left over")
        if (at != chunkSection.offset + chunkSection.stored) throw TileFormatException("the chunks do not fill their section")
        chunks = list
        head = RenderHead.fromBytes(sectionBytes(Format.SECTION_HEAD))
        style = StyleTables.fromBytes(sectionBytes(Format.SECTION_STYLE))
    }

    private fun section(name: String): Section = sections[name] ?: throw TileFormatException("no section $name")

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
    fun tiles(): List<RenderTile> = chunks.map { c -> decodeTile(raw(c)).also { if (it.level != c.z || it.x != c.x || it.y != c.y) throw TileFormatException("chunk ${c.z}/${c.x}/${c.y} holds tile ${it.level}/${it.x}/${it.y}") } }

    /** The tile at [z]/[x]/[y], or null. */
    fun tile(z: Int, x: Int, y: Int): RenderTile? = chunks.firstOrNull { it.z == z && it.x == x && it.y == y }?.let { decodeTile(raw(it)) }

    companion object {
        /** Decodes one tile's bytes ([Format]'s layout), checking everything against them. */
        fun decodeTile(b: ByteArray): RenderTile {
            val h = LeSource(b, 0, b.size)
            if (b.size < Format.HEADER_BYTES || h.u32() != Format.TILE_MAGIC.toLong()) throw TileFormatException("not a render tile")
            val payload = h.u16()
            if (payload != Format.PAYLOAD) throw TileFormatException("a tile of payload $payload; this reader knows ${Format.PAYLOAD}")
            val level = h.u8()
            val n = h.u8()
            val x = h.u32().toInt()
            val y = h.u32().toInt()
            val box = IntArray(4) { h.i32() }
            if (!box.contentEquals(Grid.tileBox(level, x, y))) throw TileFormatException("tile $level/$x/$y has the box ${box.joinToString()}")
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
                val present = LinkedHashMap<String, Pair<Codec, LeSource>>()
                repeat(en.columns) {
                    val name = strings.getOrNull(en.src.varCount()) ?: throw TileFormatException("a column name outside the strings")
                    val codec = Codec.of(en.src.u8()) ?: throw TileFormatException("column $name: unknown codec")
                    present[name] = codec to en.src.block()
                }
                if (en.src.remaining != 0) throw TileFormatException("${layer.label} has bytes after its columns")
                val schema = Schema.columns(layer)
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
                val unknown = present.keys - schema.map { it.name }.toSet()
                if (unknown.isNotEmpty()) throw TileFormatException("${layer.label} has columns this reader does not know: $unknown")
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
            return RenderTile(level, x, y, n, layers, cells)
        }
    }
}

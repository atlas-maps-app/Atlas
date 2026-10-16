package app.atlas.rendertiles.codec

import app.atlas.geo.BBox
import app.atlas.geo.LatLon
import app.atlas.render.data.ByteSink
import app.atlas.render.data.PackManifest
import app.atlas.render.data.PackTiles
import app.atlas.render.data.PackWriter
import app.atlas.rendertiles.model.Codec
import app.atlas.rendertiles.model.Feature
import app.atlas.rendertiles.model.Format
import app.atlas.rendertiles.model.Geom
import app.atlas.rendertiles.model.Layer
import app.atlas.rendertiles.model.Reach
import app.atlas.rendertiles.model.RenderHead
import app.atlas.rendertiles.model.RenderTile
import app.atlas.rendertiles.model.Schema
import app.atlas.rendertiles.model.StyleTables
import com.github.luben.zstd.Zstd
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.zip.CRC32

/**
 * Writes a format-3 render pack ([Format]): the tiles, each encoded ([encodeTile]) and compressed with zstd, in D1's
 * container with the `reach`, `rhead` and `style` sections. Deterministic: the same tiles, head, style and [Info] give
 * the same bytes (no clock, no host in them), and a tile's hash depends on its features alone.
 */
object Format3Writer {
    /** zstd's level for the chunks: the strongest of the ordinary levels (the packer writes once, phones read often). */
    const val ZSTD_LEVEL = 19

    /** What the manifest says of the pack: the element pack's identity, from its own manifest. */
    class Info(
        val id: String,
        val name: String,
        val bbox: BBox,
        val origin: LatLon,
        val tz: String?,
        val tradition: String?,
        val dataTime: String?,
        val config: JsonObject,
        val header: JsonObject,
        val sources: List<String>,
    )

    /** One written chunk: its tile, stored and raw sizes, feature count and the SHA-256 of its raw bytes. */
    class Chunk(val z: Int, val x: Int, val y: Int, val stored: Int, val raw: Int, val count: Int, val sha256: ByteArray) {
        val sha256Hex: String get() = sha256.joinToString("") { "%02x".format(it.toInt() and 0xFF) }
    }

    /** What [write] made: the chunks and their reach in the file's order. */
    class Written(val file: File, val bytes: Long, val chunks: List<Chunk>, val reach: List<Reach>, val sections: Map<String, Pair<Long, Long>>, val contentSha256: String)

    /** The `reach` section's bytes for [reach] ([Reach]'s layout). */
    fun reachBytes(reach: List<Reach>): ByteArray {
        val out = LeSink(Reach.HEADER_BYTES + reach.size * Reach.ENTRY_BYTES)
        out.u32(Format.REACH_MAGIC.toLong())
        out.u16(Format.PAYLOAD)
        out.u16(Reach.ENTRY_BYTES)
        out.u32(reach.size.toLong())
        for (r in reach) {
            out.i32(r.south); out.i32(r.west); out.i32(r.north); out.i32(r.east); out.i32(r.topCm)
        }
        return out.toByteArray()
    }

    /** Writes [tiles] with [head] and [style] into [file] (through a temporary file beside it). */
    fun write(tiles: List<RenderTile>, head: RenderHead, style: StyleTables, info: Info, file: File): Written {
        val levels = head.levels
        val ordered = tiles.sortedWith(compareBy<RenderTile>({ levels.indexOf(it.level).also { i -> require(i >= 0) { "tile level ${it.level} is not one of ${levels.joinToString()}" } } }, { PackTiles.hilbert(it.level, it.x, it.y) }))
        for (i in 1 until ordered.size) {
            val a = ordered[i - 1]
            val b = ordered[i]
            require(!(a.level == b.level && a.x == b.x && a.y == b.y)) { "tile ${a.level}/${a.x}/${a.y} twice" }
        }
        val chunks = ArrayList<Chunk>()
        val reach = ArrayList<Reach>()
        val stored = ArrayList<ByteArray>()
        val sha = MessageDigest.getInstance("SHA-256")
        for (t in ordered) {
            val raw = encodeTile(t)
            require(raw.size <= Format.MAX_TILE_BYTES) { "tile ${t.level}/${t.x}/${t.y} is ${raw.size} bytes, more than ${Format.MAX_TILE_BYTES}" }
            sha.reset()
            val h = sha.digest(raw)
            val z = Zstd.compress(raw, ZSTD_LEVEL)
            chunks += Chunk(t.level, t.x, t.y, z.size, raw.size, t.featureCount, h)
            reach += Reach.of(t)
            stored += z
        }
        val table = ByteSink(chunks.size * 48 + 16)
        table.varInt(chunks.size)
        val content = MessageDigest.getInstance("SHA-256")
        val c0 = ByteSink(64)
        c0.varInt(Format.PAYLOAD)
        c0.varInt(levels.size)
        for (z in levels) c0.varInt(z)
        content.update(c0.toByteArray())
        for (ch in chunks) {
            table.byte(ch.z); table.varInt(ch.x); table.varInt(ch.y); table.varInt(ch.stored); table.varInt(ch.raw); table.varInt(ch.count)
            table.bytes(ch.sha256)
            c0.reset()
            c0.byte(ch.z); c0.varInt(ch.x); c0.varInt(ch.y); c0.varInt(ch.count); c0.bytes(ch.sha256)
            content.update(c0.toByteArray())
        }
        content.update(reachBytes(reach))
        val contentHex = content.digest().joinToString("") { "%02x".format(it.toInt() and 0xFF) }
        val reachBytes = reachBytes(reach)
        val headBytes = head.toBytes()
        val styleRaw = style.toBytes()
        val styleBytes = Zstd.compress(styleRaw, ZSTD_LEVEL)
        val chunkBytes = stored.sumOf { it.size.toLong() }
        val chunkRaw = chunks.sumOf { it.raw.toLong() }
        val chunkCrc = CRC32().also { c -> for (s in stored) c.update(s) }.value

        class Dir(val name: String, val stored: Long, val raw: Long, val crc: Long, val method: Int)
        val tableBytes = table.toByteArray()
        val order = listOf(
            Dir(Format.SECTION_TILES, tableBytes.size.toLong(), tableBytes.size.toLong(), crc(tableBytes), Format.METHOD_STORED),
            Dir(Format.SECTION_REACH, reachBytes.size.toLong(), reachBytes.size.toLong(), crc(reachBytes), Format.METHOD_STORED),
            Dir(Format.SECTION_CHUNKS, chunkBytes, chunkRaw, chunkCrc, Format.METHOD_ZSTD),
            Dir(Format.SECTION_HEAD, headBytes.size.toLong(), headBytes.size.toLong(), crc(headBytes), Format.METHOD_STORED),
            Dir(Format.SECTION_STYLE, styleBytes.size.toLong(), styleRaw.size.toLong(), crc(styleBytes), Format.METHOD_ZSTD),
        )
        val counts = LinkedHashMap<String, Int>()
        for (t in ordered) for ((layer, list) in t.layers) counts.merge(layer.label, list.size, Int::plus)
        val manifest = PackManifest.build(
            info.id, info.name, info.bbox, info.origin, info.tz, info.tradition, info.dataTime, info.config, info.header, info.sources,
            version = Format.VERSION,
            extra = buildJsonObject {
                put("layout", Format.LAYOUT)
                put("payload", Format.PAYLOAD)
                put("profile", head.profile.label)
                put("levels", JsonArray(levels.map { JsonPrimitive(it) }))
                put("chunks", chunks.size)
                put("content_sha256", contentHex)
                put("engine_version", head.engineVersion)
                put("style_version", head.styleVersion)
                put("source_sha256", head.source)
                put("counts", buildJsonObject { for ((k, v) in counts) put(k, v) })
                put("sections", buildJsonObject {
                    for (s in order) put(s.name, buildJsonObject {
                        put("stored", s.stored)
                        put("raw", s.raw)
                    })
                })
                put("builder", "atlas-packer render-tiles ${Format.PAYLOAD}")
            },
        )
        val manifestBytes = manifest.json.toString().toByteArray(Charsets.UTF_8)
        fun directory(base: Long): ByteSink {
            val d = ByteSink(256)
            var offset = base
            for (s in order) {
                d.string(s.name)
                d.u64(offset); d.u64(s.stored); d.u64(s.raw); d.u32(s.crc)
                d.byte(s.method)
                offset += s.stored
            }
            return d
        }
        val dirLen = directory(0).size
        val directory = directory((HEADER + manifestBytes.size + dirLen).toLong())
        val head0 = ByteSink(HEADER)
        head0.bytes(PackWriter.MAGIC.toByteArray(Charsets.US_ASCII))
        head0.byte(0); head0.byte(Format.VERSION); head0.byte(0); head0.byte(0)
        head0.u32(manifestBytes.size.toLong())
        head0.u32(directory.size.toLong())
        file.absoluteFile.parentFile?.mkdirs()
        val tmp = File(file.path + ".part")
        tmp.outputStream().buffered(1 shl 16).use { out ->
            out.write(head0.toByteArray())
            out.write(manifestBytes)
            out.write(directory.toByteArray())
            out.write(tableBytes)
            out.write(reachBytes)
            for (s in stored) out.write(s)
            out.write(headBytes)
            out.write(styleBytes)
        }
        if (file.exists() && !file.delete()) throw IOException("cannot replace $file")
        if (!tmp.renameTo(file)) throw IOException("cannot move $tmp to $file")
        return Written(file, file.length(), chunks, reach, order.associate { it.name to (it.stored to it.raw) }, contentHex)
    }

    /** The container's fixed header: magic, version, two lengths (D1's). */
    const val HEADER = 20

    private fun crc(b: ByteArray): Long = CRC32().also { it.update(b) }.value

    /**
     * One tile's bytes ([Format]'s tile layout): the header, the layer directory, the string table, the cell table and
     * each layer's columns. A column whose every value is its default is left out.
     */
    fun encodeTile(tile: RenderTile): ByteArray {
        val strings = StringTable()
        class Block(val layer: Layer, val columns: Int, val count: Int, val bytes: ByteArray)
        val blocks = ArrayList<Block>()
        for ((layer, features) in tile.layers) {
            if (features.isEmpty()) continue
            require(features.size <= Format.MAX_FEATURES) { "tile ${tile.level}/${tile.x}/${tile.y}: ${features.size} ${layer.label} features, more than ${Format.MAX_FEATURES}" }
            val out = LeSink(1024)
            var columns = 0
            val cols = Schema.columns(layer)
            for (col in cols) {
                val values = features.map { f -> value(col, f.fields) }
                if (omitted(col, values, features)) continue
                val bytes = Columns.encode(col.codec, values, strings)
                out.varU(strings.of(col.name).toLong())
                out.u8(col.codec.id)
                out.varU(bytes.size.toLong())
                out.bytes(bytes)
                columns++
            }
            val unknown = features.flatMap { it.fields.keys }.toSet() - cols.map { it.name }.toSet()
            require(unknown.isEmpty()) { "${layer.label} features with fields the schema lacks: $unknown" }
            blocks += Block(layer, columns, features.size, out.toByteArray())
        }
        val stringBlock = LeSink(4096).also { strings.write(it) }
        val cellBlock = LeSink(256)
        val tb = tile.box
        for (c in tile.cells) {
            val total = blocks.sumOf { c.counts[it.layer] ?: 0 }
            cellBlock.varU(total.toLong())
            if (total == 0) continue
            val b = c.box ?: throw IllegalStateException("a cell of $total features has no box")
            cellBlock.varZ(b[0].toLong() - tb[0]); cellBlock.varZ(b[1].toLong() - tb[1])
            cellBlock.varZ(b[2].toLong() - tb[0]); cellBlock.varZ(b[3].toLong() - tb[1])
            cellBlock.varZ(c.maxHeightCm.toLong())
            for (bl in blocks) cellBlock.varU((c.counts[bl.layer] ?: 0).toLong())
        }
        require(tile.cells.size == tile.cellsPerSide * tile.cellsPerSide) { "${tile.cells.size} cells for ${tile.cellsPerSide} a side" }
        for (bl in blocks) require(tile.cells.sumOf { it.counts[bl.layer] ?: 0 } == bl.count) { "${bl.layer.label}: the cells count other than the layer" }
        val out = LeSink(Format.HEADER_BYTES + blocks.size * Format.LAYER_ENTRY_BYTES + stringBlock.size + cellBlock.size + blocks.sumOf { it.bytes.size })
        out.u32(Format.TILE_MAGIC.toLong())
        out.u16(Format.PAYLOAD)
        out.u8(tile.level)
        out.u8(tile.cellsPerSide)
        out.u32(tile.x.toLong()); out.u32(tile.y.toLong())
        for (v in tb) out.i32(v)
        val stringAt = Format.HEADER_BYTES + blocks.size * Format.LAYER_ENTRY_BYTES
        val cellAt = stringAt + stringBlock.size
        out.u32(stringAt.toLong()); out.u32(stringBlock.size.toLong())
        out.u32(cellAt.toLong()); out.u32(cellBlock.size.toLong())
        out.u16(blocks.size); out.u16(0)
        var at = cellAt + cellBlock.size
        for (bl in blocks) {
            out.u8(bl.layer.id); out.u8(0); out.u16(bl.columns)
            out.u32(bl.count.toLong()); out.u32(at.toLong()); out.u32(bl.bytes.size.toLong())
            at += bl.bytes.size
        }
        check(out.size == stringAt) { "the layer directory ends at ${out.size}, not $stringAt" }
        out.bytes(stringBlock.unsafeArray(), 0, stringBlock.size)
        out.bytes(cellBlock.unsafeArray(), 0, cellBlock.size)
        for (bl in blocks) out.bytes(bl.bytes)
        return out.toByteArray()
    }

    /** A feature's value for [col]: its field, or the column's default when it has none. */
    private fun value(col: Schema.Column, fields: Map<String, Any?>): Any? {
        if (col.codec == Codec.KEEP) return fields[col.name.removeSuffix(".keep")] ?: Geom.EMPTY
        return fields[col.name] ?: default(col.codec)
    }

    /** The value a missing column stands for. */
    fun default(codec: Codec): Any? = when (codec) {
        Codec.U8 -> 0
        Codec.VARU, Codec.VARZ, Codec.DELTA -> 0L
        Codec.GEOM -> Geom.EMPTY
        Codec.TAGS -> emptyMap<String, String>()
        Codec.IDS -> emptyList<Long>()
        Codec.CENTI, Codec.STR, Codec.STRN, Codec.BOOL, Codec.NVARZ, Codec.NDELTA, Codec.STRS, Codec.VALUE, Codec.KEEP, Codec.POINT, Codec.F64 -> null
    }

    private fun omitted(col: Schema.Column, values: List<Any?>, features: List<Feature>): Boolean = when (col.codec) {
        // keep codes go with their ring column: left out with it, or when no ring has any
        Codec.KEEP -> {
            val ring = col.name.removeSuffix(".keep")
            val rings = features.map { (it.fields[ring] as Geom?) ?: Geom.EMPTY }
            val keeps = rings.count { it.keep != null }
            require(keeps == 0 || keeps == rings.size) { "${col.name}: keep codes on $keeps of ${rings.size} rings" }
            keeps == 0 || rings.all { it.size == 0 }
        }
        Codec.POINT, Codec.F64 -> {
            require(values.none { it == null }) { "${col.name}: a ${col.codec.name} column needs a value for every feature" }
            false
        }
        Codec.GEOM -> values.all { (it as Geom).size == 0 }
        Codec.U8, Codec.VARU, Codec.VARZ, Codec.DELTA -> values.all { (it as Number).toLong() == 0L }
        else -> values.all { it == default(col.codec) }
    }
}

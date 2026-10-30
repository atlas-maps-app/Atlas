package app.atlas.packer.country

import app.atlas.render.data.ByteSink
import app.atlas.render.data.ByteSource
import app.atlas.render.data.PackFormatException
import app.atlas.render.data.PackWriter
import app.atlas.rendertiles.codec.Format3Writer
import app.atlas.rendertiles.model.Format
import app.atlas.rendertiles.model.Reach
import com.github.luben.zstd.Zstd
import com.github.luben.zstd.ZstdCompressCtx
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.zip.CRC32

/**
 * A window's pack as the core writes it (`load::build_window`, tiles/PackWriter.cpp's write_pack), read whole: a
 * format-3 render pack ([Format]) in D1's container, its manifest, its directory and its five sections end to end in
 * the core's order ([ORDER]), each section's CRC-32 over its stored bytes and each chunk's SHA-256 over its tile. The
 * core vendors zstd's decompression alone, so its chunks and its style are zstd frames of raw blocks, as large as what
 * they hold: [recompressed] writes the same pack with each of those frames compressed, and every other byte the core
 * wrote kept.
 */
class WindowPack private constructor(
    private val bytes: ByteArray,
    /** The manifest's bytes as the core wrote them (JSON the core never reads: the app's head of the pack). */
    val manifest: ByteArray,
    /** The directory, in the core's order. */
    val sections: List<Section>,
    /** The chunk table, in the file's order. */
    val chunks: List<Chunk>,
) {
    /** One section of the directory: where its stored bytes lie, their raw length, their CRC-32 and its method. */
    class Section(val name: String, val offset: Int, val stored: Int, val raw: Long, val crc: Long, val method: Int)

    /** One entry of the chunk table: its tile, where its frame lies and its length, its tile's length, features and SHA-256. */
    class Chunk(val z: Int, val x: Int, val y: Int, val offset: Int, val stored: Int, val raw: Int, val count: Int, val sha256: ByteArray)

    /** The pack's length in bytes. */
    val size: Int get() = bytes.size

    private val head: JsonObject by lazy {
        Json.parseToJsonElement(String(manifest, Charsets.UTF_8)) as? JsonObject ?: throw PackFormatException("the manifest is no JSON object")
    }

    private fun text(key: String): String? = (head[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

    /** The window the manifest names: `<z>/<x>/<y>` (load::place_of's name for it). */
    val window: String? get() = text("window")

    /**
     * The core's version the pack was built with, as load::window_version gives it: its manifest's `engine_version`
     * (stage P's) and `style_version`. Another version's windows are other tiles.
     */
    val version: String
        get() {
            val engine = text("engine_version")
            val style = text("style_version")
            if (engine.isNullOrEmpty() || style.isNullOrEmpty()) throw PackFormatException("the manifest names no engine_version or style_version")
            return "$engine $style"
        }

    /** Its tiles' features, as the chunk table counts them. */
    val features: Long get() = chunks.sumOf { it.count.toLong() }

    fun section(name: String): Section = sections.first { it.name == name }

    /** Section [name]'s stored bytes. */
    fun stored(name: String): ByteArray = section(name).let { bytes.copyOfRange(it.offset, it.offset + it.stored) }

    /** Chunk [c]'s tile: its frame decompressed, held to the chunk's SHA-256. */
    fun tile(c: Chunk): ByteArray {
        val raw = unframe(bytes.copyOfRange(c.offset, c.offset + c.stored), c.raw, "chunk ${c.z}/${c.x}/${c.y}")
        if (!MessageDigest.getInstance("SHA-256").digest(raw).contentEquals(c.sha256)) throw PackFormatException("chunk ${c.z}/${c.x}/${c.y} is not its SHA-256's")
        return raw
    }

    /** The style's tables: its frame decompressed, or its bytes where it is stored as they are. */
    fun style(): ByteArray = section(Format.SECTION_STYLE).let { s ->
        if (s.method == Format.METHOD_STORED) stored(s.name) else unframe(stored(s.name), s.raw.toInt(), "the style")
    }

    /**
     * This pack with each chunk's tile and the style compressed by [zstd] into a frame of its own (one frame each,
     * which says its size, as the core's reader holds a frame to: tiles/Chunks.cpp), and every other byte the core
     * wrote kept: the header, the manifest, `reach`, `rhead`, and in the chunk table each tile, raw length, feature
     * count and SHA-256; the table's stored lengths, the directory's offsets, stored lengths and CRC-32s follow the
     * new frames. Checked before it is returned: the pack written from its own sections is its own bytes, and the new
     * one reads back to the same manifest, sections, tiles and style, byte for byte once decompressed.
     */
    fun recompressed(zstd: ZstdCompressCtx): ByteArray {
        check(write(sections.map { stored(it.name) }).contentEquals(bytes)) { "the pack does not write back to its own bytes" }
        val tiles = chunks.map(::tile)
        val style = style()
        val frames = tiles.map { zstd.compress(it) }
        val table = ByteSink(16 + chunks.size * 48)
        table.varInt(chunks.size)
        for ((c, f) in chunks.zip(frames)) {
            table.byte(c.z)
            table.varInt(c.x)
            table.varInt(c.y)
            table.varInt(f.size)
            table.varInt(c.raw)
            table.varInt(c.count)
            table.bytes(c.sha256)
        }
        val joined = ByteSink(frames.sumOf { it.size })
        for (f in frames) joined.bytes(f)
        val out = write(
            listOf(
                table.toByteArray(), stored(Format.SECTION_REACH), joined.toByteArray(), stored(Format.SECTION_HEAD), zstd.compress(style),
            ),
        )
        val again = read(out)
        check(again.manifest.contentEquals(manifest)) { "the new pack's manifest is not the core's" }
        for (name in listOf(Format.SECTION_REACH, Format.SECTION_HEAD)) check(again.stored(name).contentEquals(stored(name))) { "the new pack's $name is not the core's" }
        check(again.chunks.size == chunks.size) { "the new pack holds ${again.chunks.size} chunks, the core's ${chunks.size}" }
        for ((i, c) in again.chunks.withIndex()) {
            val o = chunks[i]
            check(c.z == o.z && c.x == o.x && c.y == o.y && c.raw == o.raw && c.count == o.count && c.sha256.contentEquals(o.sha256)) {
                "the new pack's chunk ${c.z}/${c.x}/${c.y} is not the core's ${o.z}/${o.x}/${o.y}"
            }
            check(again.tile(c).contentEquals(tiles[i])) { "the new pack's tile ${c.z}/${c.x}/${c.y} is not the core's" }
        }
        check(again.style().contentEquals(style)) { "the new pack's style is not the core's" }
        return out
    }

    /** The pack of [stored], a section's stored bytes each in the directory's order, as the core lays one out. */
    private fun write(stored: List<ByteArray>): ByteArray {
        fun directory(base: Long): ByteSink {
            val d = ByteSink(256)
            var offset = base
            for ((s, b) in sections.zip(stored)) {
                d.string(s.name)
                d.u64(offset)
                d.u64(b.size.toLong())
                // a stored section is its raw bytes; a compressed one decompresses to the core's
                d.u64(if (s.method == Format.METHOD_STORED) b.size.toLong() else s.raw)
                d.u32(CRC32().also { it.update(b) }.value)
                d.byte(s.method)
                offset += b.size
            }
            return d
        }
        // the directory's length does not depend on the offsets in it (each a u64)
        val directory = directory((HEADER + manifest.size + directory(0).size).toLong()).toByteArray()
        val out = ByteSink(HEADER + manifest.size + directory.size + stored.sumOf { it.size })
        // the magic, a zero byte, the format and two zero bytes, as the core wrote them
        out.bytes(bytes, 0, 12)
        out.u32(manifest.size.toLong())
        out.u32(directory.size.toLong())
        out.bytes(manifest)
        out.bytes(directory)
        for (b in stored) out.bytes(b)
        return out.toByteArray()
    }

    companion object {
        /** The container's header: the magic, a zero byte, the format, two zero bytes, the manifest's and the directory's lengths (big-endian u32). */
        const val HEADER = Format3Writer.HEADER

        /** The sections, in the order the core writes them. */
        val ORDER = listOf(Format.SECTION_TILES, Format.SECTION_REACH, Format.SECTION_CHUNKS, Format.SECTION_HEAD, Format.SECTION_STYLE)

        /** The fewest bytes of a chunk table's entry: zoom, five one-byte varints and the SHA-256. */
        private const val CHUNK_ENTRY_MIN = 38

        /**
         * The pack in [bytes], checked: the core's layout, each section's CRC-32, the chunk table against its frames;
         * its style by [styleMethod] (the core's zstd, or stored as the phone writes a country's back: CountryStyle).
         */
        fun read(bytes: ByteArray, styleMethod: Int = Format.METHOD_ZSTD): WindowPack {
            if (bytes.size < HEADER || String(bytes, 0, 8, Charsets.US_ASCII) != PackWriter.MAGIC) throw PackFormatException("not an Atlas pack")
            if (bytes[8].toInt() != 0 || bytes[9].toInt() != Format.VERSION || bytes[10].toInt() != 0 || bytes[11].toInt() != 0) {
                throw PackFormatException("not a render pack of format ${Format.VERSION}")
            }
            val h = ByteSource(bytes, 12, HEADER)
            val manifestLength = h.u32()
            val directoryLength = h.u32()
            if (HEADER + manifestLength + directoryLength > bytes.size) throw PackFormatException("the manifest and the directory run past the pack")
            val manifest = bytes.copyOfRange(HEADER, HEADER + manifestLength.toInt())
            val d = ByteSource(bytes, HEADER + manifestLength.toInt(), (HEADER + manifestLength + directoryLength).toInt())
            val sections = ArrayList<Section>()
            var at = HEADER + manifestLength + directoryLength
            while (d.remaining > 0) {
                val name = d.string()
                val offset = d.u64()
                val stored = d.u64()
                val raw = d.u64()
                val crc = d.u32()
                val method = d.byte()
                // end to end after the directory, as the core writes them
                if (offset != at || stored < 0 || offset + stored > bytes.size) throw PackFormatException("section $name lies at $offset, not at $at where the core writes it")
                if (CRC32().also { it.update(bytes, offset.toInt(), stored.toInt()) }.value != crc) throw PackFormatException("section $name is damaged")
                val want = when (name) {
                    Format.SECTION_CHUNKS -> Format.METHOD_ZSTD
                    Format.SECTION_STYLE -> styleMethod
                    else -> Format.METHOD_STORED
                }
                if (method != want) throw PackFormatException("section $name has method $method, not the core's $want")
                if (method == Format.METHOD_STORED && raw != stored) throw PackFormatException("section $name is stored in $stored bytes of $raw")
                sections += Section(name, offset.toInt(), stored.toInt(), raw, crc, method)
                at = offset + stored
            }
            if (sections.map { it.name } != ORDER) throw PackFormatException("sections ${sections.joinToString { it.name }}, not the core's ${ORDER.joinToString()}")
            if (at != bytes.size.toLong()) throw PackFormatException("${bytes.size - at} bytes after the sections")
            val chunks = chunkTable(bytes, sections[0], sections[2])
            reachOf(bytes, sections[1], chunks.size)
            if (sections[4].raw !in 1..Int.MAX_VALUE.toLong()) throw PackFormatException("a style of ${sections[4].raw} bytes")
            return WindowPack(bytes, manifest, sections, chunks)
        }

        /** The chunk table in [tiles], its frames filling [section] in order. */
        private fun chunkTable(bytes: ByteArray, tiles: Section, section: Section): List<Chunk> {
            val t = ByteSource(bytes, tiles.offset, tiles.offset + tiles.stored)
            val n = t.varLong()
            if (n < 0 || n > t.remaining / CHUNK_ENTRY_MIN) throw PackFormatException("$n chunks in a table of ${t.remaining} bytes")
            var frameAt = section.offset.toLong()
            var raw = 0L
            val chunks = ArrayList<Chunk>(n.toInt())
            repeat(n.toInt()) {
                val z = t.byte()
                val x = t.varLong()
                val y = t.varLong()
                val stored = t.varLong()
                val length = t.varLong()
                val count = t.varLong()
                val sha = t.bytes(32)
                if (z > Format.MAX_LEVEL || x >= 1L shl z || y >= 1L shl z) throw PackFormatException("a chunk of tile $z/$x/$y")
                if (stored <= 0 || frameAt + stored > section.offset.toLong() + section.stored) throw PackFormatException("chunk $z/$x/$y's frame runs past its section")
                if (length !in 1..Format.MAX_TILE_BYTES.toLong() || count > Int.MAX_VALUE) throw PackFormatException("chunk $z/$x/$y: a tile of $length bytes, $count features")
                chunks += Chunk(z, x.toInt(), y.toInt(), frameAt.toInt(), stored.toInt(), length.toInt(), count.toInt(), sha)
                frameAt += stored
                raw += length
            }
            if (t.remaining != 0) throw PackFormatException("the chunk table has bytes left over")
            if (frameAt != section.offset.toLong() + section.stored) throw PackFormatException("the chunks do not fill their section")
            if (raw != section.raw) throw PackFormatException("the chunks' tiles are $raw bytes, their section says ${section.raw}")
            return chunks
        }

        /** Checks that [section] is a `reach` of [count] entries ([Reach]'s layout); the bytes are kept as they are. */
        private fun reachOf(bytes: ByteArray, section: Section, count: Int) {
            val b = ByteBuffer.wrap(bytes, section.offset, section.stored).order(ByteOrder.LITTLE_ENDIAN)
            if (section.stored < Reach.HEADER_BYTES || b.int != Format.REACH_MAGIC) throw PackFormatException("not a reach section")
            b.short
            val entry = b.short.toInt() and 0xFFFF
            val n = b.int.toLong() and 0xFFFFFFFFL
            if (entry < Reach.ENTRY_BYTES || n != count.toLong() || section.stored.toLong() != Reach.HEADER_BYTES + n * entry) {
                throw PackFormatException("a reach of $n entries of $entry bytes in ${section.stored} for $count chunks")
            }
        }

        /** [frame] decompressed to its [raw] bytes; one frame that says its size, as the core's reader holds a chunk to. */
        private fun unframe(frame: ByteArray, raw: Int, what: String): ByteArray {
            if (Zstd.findFrameCompressedSize(frame) != frame.size.toLong()) throw PackFormatException("$what is not one zstd frame")
            if (Zstd.getFrameContentSize(frame) != raw.toLong()) throw PackFormatException("$what's frame does not say its $raw bytes")
            val out = Zstd.decompress(frame, raw)
            if (out.size != raw) throw PackFormatException("$what decompresses to ${out.size} bytes, not $raw")
            return out
        }
    }
}

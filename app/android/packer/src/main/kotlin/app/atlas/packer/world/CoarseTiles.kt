package app.atlas.packer.world

import app.atlas.rendertiles.codec.Format3Writer
import app.atlas.rendertiles.model.Reach
import java.io.DataInputStream
import java.io.EOFException
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * A region's coarse tiles (ENGINE.md step 5's world.yml: `packer world --coarse-tiles`), which `packer world-assemble`
 * makes each L3 block's coarse pack of, from every region's tiles there, as one run of all the regions' blocks makes it:
 * the facts every pack of the run holds, the L6 blocks the region built, and each L6 to L8 tile as it was encoded.
 *
 * Little-endian: "ATWC", the format's version (u16), 0 (u16); the data's date (u16 length and its ISO 8601 text, 0 for
 * none), the inputs' hash and the sources (u16 count, each a u16 length and its text), each text UTF-8; the blocks (u32
 * count, each its column and row as u16); the tiles (u32 count, each its level (u8), column and row (u32 each), features
 * (u32), reach (south, west, north, east and top, i32 each), its layers' feature counts (u16 count, each the layer's name
 * as a u16 length and its text, and the count as u32), and its raw bytes (a u32 length and the bytes)).
 */
object CoarseTiles {
    const val MAGIC = "ATWC"
    const val VERSION = 1

    /** What a region hands on: the run's [facts], the L6 [blocks] it built and its coarse [tiles]. */
    class Region(val facts: WorldTiles.Facts, val blocks: List<Pair<Int, Int>>, val tiles: List<Format3Writer.Encoded>)

    fun write(file: File, region: Region) {
        file.parentFile?.mkdirs()
        file.outputStream().buffered(1 shl 20).use { s ->
            val b = Sink(s)
            b.bytes(MAGIC.toByteArray(Charsets.US_ASCII)); b.u16(VERSION); b.u16(0)
            b.text(region.facts.dataTime ?: "")
            b.text(region.facts.source)
            b.u16(region.facts.sources.size)
            for (t in region.facts.sources) b.text(t)
            b.u32(region.blocks.size.toLong())
            for ((x, y) in region.blocks) {
                b.u16(x); b.u16(y)
            }
            b.u32(region.tiles.size.toLong())
            for (t in region.tiles) {
                b.u8(t.level); b.u32(t.x.toLong()); b.u32(t.y.toLong()); b.u32(t.featureCount.toLong())
                b.i32(t.reach.south); b.i32(t.reach.west); b.i32(t.reach.north); b.i32(t.reach.east); b.i32(t.reach.topCm)
                b.u16(t.layerCounts.size)
                for ((name, n) in t.layerCounts) {
                    b.text(name); b.u32(n.toLong())
                }
                b.u32(t.raw.size.toLong())
                b.bytes(t.raw)
            }
        }
    }

    fun read(file: File): Region = DataInputStream(file.inputStream().buffered(1 shl 20)).use { s ->
        val r = Source(s, file)
        val magic = String(r.bytes(4), Charsets.US_ASCII)
        if (magic != MAGIC) throw IOException("$file is not a region's coarse tiles")
        val version = r.u16()
        if (version != VERSION) throw IOException("$file: coarse tiles of version $version, which this reader does not know")
        r.u16()
        val date = r.text().ifEmpty { null }
        val source = r.text()
        val sources = List(r.u16()) { r.text() }
        val blocks = List(r.count()) { r.u16() to r.u16() }
        val tiles = List(r.count()) {
            val level = r.u8()
            val x = r.u32().toInt()
            val y = r.u32().toInt()
            val features = r.u32().toInt()
            val reach = Reach(r.i32(), r.i32(), r.i32(), r.i32(), r.i32())
            val layers = LinkedHashMap<String, Int>()
            repeat(r.u16()) { layers[r.text()] = r.u32().toInt() }
            val raw = r.bytes(r.count())
            Format3Writer.Encoded(level, x, y, raw, features, reach, layers)
        }
        if (s.read() != -1) throw IOException("$file runs on past its tiles")
        Region(WorldTiles.Facts(date, sources, source), blocks, tiles)
    }

    private class Sink(private val out: java.io.OutputStream) {
        private val b = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN)

        fun bytes(a: ByteArray) = out.write(a)
        fun u8(v: Int) = out.write(v.also { require(it in 0..0xFF) })
        fun u16(v: Int) {
            require(v in 0..0xFFFF) { "$v does not fit 16 bits" }
            b.clear(); b.putShort(v.toShort()); out.write(b.array(), 0, 2)
        }
        fun u32(v: Long) {
            require(v in 0..0xFFFF_FFFFL) { "$v does not fit 32 bits" }
            b.clear(); b.putInt(v.toInt()); out.write(b.array(), 0, 4)
        }
        fun i32(v: Int) {
            b.clear(); b.putInt(v); out.write(b.array(), 0, 4)
        }
        fun text(t: String) {
            val a = t.toByteArray(Charsets.UTF_8)
            u16(a.size); out.write(a)
        }
    }

    private class Source(private val s: DataInputStream, private val file: File) {
        private val b = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN)

        fun bytes(n: Int): ByteArray = ByteArray(n).also {
            try {
                s.readFully(it)
            } catch (e: EOFException) {
                throw IOException("$file is cut short", e)
            }
        }
        private fun le(n: Int): ByteBuffer {
            b.clear(); b.put(bytes(n)); b.flip(); return b
        }
        fun u8(): Int = le(1).get().toInt() and 0xFF
        fun u16(): Int = le(2).short.toInt() and 0xFFFF
        fun u32(): Long = le(4).int.toLong() and 0xFFFF_FFFFL
        fun i32(): Int = le(4).int
        fun count(): Int = u32().also { if (it > Int.MAX_VALUE) throw IOException("$file: a count of $it") }.toInt()
        fun text(): String = String(bytes(u16()), Charsets.UTF_8)
    }
}

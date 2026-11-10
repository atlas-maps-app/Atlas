package app.atlas.providers

import app.atlas.geo.BBox
import java.io.ByteArrayInputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.GZIPInputStream

/** Bytes that are not a PMTiles archive Atlas can read: another magic number or version, a broken directory. */
class PmTilesException(message: String) : IOException(message)

/**
 * A PMTiles archive (version 3, github.com/protomaps/PMTiles) read by its bytes: [read] hands back the bytes asked for,
 * an HTTP Range request or a file's. Opening reads the header and the root directory; a lookup reads the leaf directory
 * it needs, kept once read. Tiles are located by [span] and read by the caller, together where they lie close.
 */
class PmTiles private constructor(
    val header: Header,
    private val root: List<Entry>,
    private val read: suspend (offset: Long, length: Int) -> ByteArray,
) {
    /** What the header says: where each part lies, how its tiles are packed, their zooms and the box they cover. */
    class Header(
        val rootOffset: Long,
        val rootLength: Long,
        val leafOffset: Long,
        val dataOffset: Long,
        val internalCompression: Int,
        val tileCompression: Int,
        val tileType: Int,
        val minZoom: Int,
        val maxZoom: Int,
        val bounds: BBox,
    )

    /** A directory entry: [runLength] tiles from [tileId] at [offset], [length] bytes; a run of 0 is a leaf directory. */
    class Entry(val tileId: Long, val offset: Long, val length: Int, val runLength: Int)

    /** Where a tile's bytes lie: [offset] from the archive's start, [length] bytes. */
    data class Span(val offset: Long, val length: Int)

    private val leaves = HashMap<Long, List<Entry>>()

    /** Where tile [z]/[x]/[y] lies, or null when the archive has none there. */
    suspend fun span(z: Int, x: Int, y: Int): Span? {
        val id = tileId(z, x, y)
        var dir = root
        repeat(MAX_DEPTH) {
            val e = find(dir, id) ?: return null
            if (e.runLength > 0) return Span(header.dataOffset + e.offset, e.length)
            dir = leaves[e.offset] ?: directory(decompress(read(header.leafOffset + e.offset, e.length), header.internalCompression)).also { leaves[e.offset] = it }
        }
        return null
    }

    /** A tile's bytes as [span] located them, unpacked. */
    fun unpack(bytes: ByteArray): ByteArray = decompress(bytes, header.tileCompression)

    companion object {
        const val HEADER_BYTES = 127

        /** Tile types. */
        const val MVT = 1

        /** Compressions. */
        const val NONE = 1
        const val GZIP = 2

        /** Leaf directories nest at most this deep (the writers make one level). */
        private const val MAX_DEPTH = 4

        /** Opens the archive [read] reads: its header and root directory. */
        suspend fun open(read: suspend (offset: Long, length: Int) -> ByteArray): PmTiles {
            val h = header(read(0, HEADER_BYTES))
            if (h.rootLength <= 0 || h.rootLength > Int.MAX_VALUE) throw PmTilesException("a root directory of ${h.rootLength} bytes")
            return PmTiles(h, directory(decompress(read(h.rootOffset, h.rootLength.toInt()), h.internalCompression)), read)
        }

        fun header(b: ByteArray): Header {
            if (b.size < HEADER_BYTES || String(b, 0, 7, Charsets.US_ASCII) != "PMTiles") throw PmTilesException("not a PMTiles archive")
            if (b[7].toInt() != 3) throw PmTilesException("PMTiles version ${b[7]}, not 3")
            val buf = ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN)
            fun u8(at: Int) = b[at].toInt() and 0xff
            fun e7(at: Int) = buf.getInt(at) / 1e7
            return Header(
                rootOffset = buf.getLong(8), rootLength = buf.getLong(16), leafOffset = buf.getLong(40), dataOffset = buf.getLong(56),
                internalCompression = u8(97), tileCompression = u8(98), tileType = u8(99), minZoom = u8(100), maxZoom = u8(101),
                bounds = BBox(e7(102), e7(106), e7(110), e7(114)),
            )
        }

        /** A directory's entries, from its unpacked bytes: counts, then tile id deltas, runs, lengths and offsets as varints. */
        fun directory(b: ByteArray): List<Entry> {
            var i = 0
            fun varint(): Long {
                var shift = 0
                var v = 0L
                while (true) {
                    if (i >= b.size || shift > 63) throw PmTilesException("a directory cut short")
                    val x = b[i++].toInt() and 0xff
                    v = v or ((x and 0x7f).toLong() shl shift)
                    if (x < 0x80) return v
                    shift += 7
                }
            }
            val n = varint()
            if (n < 0 || n > b.size) throw PmTilesException("a directory of $n entries in ${b.size} bytes")
            val count = n.toInt()
            val ids = LongArray(count)
            var last = 0L
            for (k in 0 until count) { last += varint(); ids[k] = last }
            val runs = IntArray(count) { varint().toInt() }
            val lengths = IntArray(count) { varint().toInt() }
            val offsets = LongArray(count)
            for (k in 0 until count) {
                val o = varint()
                // 0 after the first entry: the bytes right after the previous entry's
                offsets[k] = if (o == 0L && k > 0) offsets[k - 1] + lengths[k - 1] else o - 1
            }
            return List(count) { Entry(ids[it], offsets[it], lengths[it], runs[it]) }
        }

        /** The entry that holds tile [id]: the one with that id, else the run or leaf before it that reaches it. */
        internal fun find(dir: List<Entry>, id: Long): Entry? {
            var lo = 0
            var hi = dir.size - 1
            while (lo <= hi) {
                val m = (lo + hi) ushr 1
                val c = dir[m].tileId
                when {
                    c < id -> lo = m + 1
                    c > id -> hi = m - 1
                    else -> return dir[m]
                }
            }
            if (hi < 0) return null
            val e = dir[hi]
            return if (e.runLength == 0 || id - e.tileId < e.runLength) e else null
        }

        /** A tile's id: the tiles of every coarser zoom before it, then its place along the zoom's Hilbert curve. */
        fun tileId(z: Int, x: Int, y: Int): Long {
            require(z in 0..26 && x in 0 until (1 shl z) && y in 0 until (1 shl z)) { "no tile $z/$x/$y" }
            var acc = 0L
            for (t in 0 until z) acc += 1L shl (2 * t)
            var tx = x.toLong()
            var ty = y.toLong()
            var d = 0L
            var s = (1L shl z) shr 1
            while (s > 0) {
                val rx = if (tx and s != 0L) 1L else 0L
                val ry = if (ty and s != 0L) 1L else 0L
                d += s * s * ((3 * rx) xor ry)
                if (ry == 0L) {
                    if (rx == 1L) {
                        tx = s - 1 - tx
                        ty = s - 1 - ty
                    }
                    val t = tx
                    tx = ty
                    ty = t
                }
                s = s shr 1
            }
            return acc + d
        }

        fun decompress(b: ByteArray, compression: Int): ByteArray = when (compression) {
            NONE, 0 -> b
            GZIP -> try {
                GZIPInputStream(ByteArrayInputStream(b)).use { it.readBytes() }
            } catch (e: IOException) {
                throw PmTilesException("a gzipped part that does not unpack: ${e.message}")
            }
            else -> throw PmTilesException("compression $compression, which Atlas does not unpack")
        }
    }
}

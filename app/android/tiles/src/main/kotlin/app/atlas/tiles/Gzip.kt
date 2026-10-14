package app.atlas.tiles

import java.io.ByteArrayOutputStream
import java.util.zip.CRC32
import java.util.zip.DataFormatException
import java.util.zip.GZIPOutputStream
import java.util.zip.Inflater

/** Gzip as tiles use it: they travel and are stored gzipped, and are handed out raw. */
object Gzip {
    private const val FHCRC = 2
    private const val FEXTRA = 4
    private const val FNAME = 8
    private const val FCOMMENT = 16

    fun isGzip(b: ByteArray): Boolean = b.size >= 2 && b[0] == 0x1f.toByte() && b[1] == 0x8b.toByte()

    /** [b] decompressed when it is gzip, otherwise [b] itself. */
    fun unwrap(b: ByteArray): ByteArray = if (isGzip(b)) decompress(b) else b

    /** [b] gzipped unless it already is. */
    fun wrap(b: ByteArray, level: Int = 6): ByteArray = if (isGzip(b)) b else compress(b, level)

    fun compress(b: ByteArray, level: Int = 6): ByteArray {
        val out = ByteArrayOutputStream(b.size / 2 + 64)
        object : GZIPOutputStream(out, 8192) {
            init { def.setLevel(level) }
        }.use { it.write(b) }
        return out.toByteArray()
    }

    /** The most [decompress] inflates by default: far more than any tile, directory or metadata, far less than memory. */
    const val MAX_SIZE = 64 shl 20

    /** The largest array the JVM reliably allocates. */
    private const val MAX_ARRAY = Int.MAX_VALUE - 8

    /**
     * Decompresses one or more concatenated gzip members, checking each one's CRC and length.
     * The output is sized from the trailer, so a typical tile inflates with a single allocation.
     * @throws TileFormatException on corrupt data, or when it would inflate past [maxSize] bytes (a gzip bomb).
     */
    fun decompress(b: ByteArray, maxSize: Int = MAX_SIZE): ByteArray {
        require(maxSize >= 0) { "maxSize $maxSize" }
        // One byte past the cap must still fit in an array.
        val cap = minOf(maxSize, MAX_ARRAY - 1)
        // The trailer's size is a hint only (it may lie); deflate cannot expand data more than about 1032 times.
        // Room for one byte past the cap tells "exactly cap" from "more".
        val ceiling = maxOf(64L, minOf(cap + 1L, b.size * 1032L + 64))
        var out = ByteArray(trailerSize(b).coerceIn(64L, ceiling).toInt())
        var outLen = 0
        var p = 0
        val inflater = Inflater(true)
        val crc = CRC32()
        try {
            while (p < b.size) {
                if (b.size - p < 18 || b[p] != 0x1f.toByte() || b[p + 1] != 0x8b.toByte()) {
                    if (p == 0) throw TileFormatException("not gzip data")
                    break // Trailing garbage after a complete member is ignored, as gzip(1) does.
                }
                if (b[p + 2].toInt() != 8) throw TileFormatException("gzip method ${b[p + 2]} is not deflate")
                p = skipHeader(b, p)
                inflater.reset()
                inflater.setInput(b, p, b.size - p)
                val memberStart = outLen
                while (!inflater.finished()) {
                    if (outLen == out.size) {
                        if (outLen > cap) throw TileFormatException("gzip data inflates past $cap bytes")
                        out = out.copyOf(minOf(cap + 1L, out.size * 2L).toInt())
                    }
                    val n = try {
                        inflater.inflate(out, outLen, out.size - outLen)
                    } catch (e: DataFormatException) {
                        throw TileFormatException("corrupt gzip data: ${e.message}")
                    }
                    outLen += n
                    if (n == 0 && (inflater.needsInput() || inflater.needsDictionary())) throw TileFormatException("truncated gzip data")
                }
                if (outLen > cap) throw TileFormatException("gzip data inflates past $cap bytes")
                p = b.size - inflater.remaining
                if (b.size - p < 8) throw TileFormatException("gzip trailer missing")
                crc.reset()
                crc.update(out, memberStart, outLen - memberStart)
                if (readIntLE(b, p) != crc.value.toInt()) throw TileFormatException("gzip CRC mismatch")
                if (readIntLE(b, p + 4) != outLen - memberStart) throw TileFormatException("gzip length mismatch")
                p += 8
            }
        } finally {
            inflater.end()
        }
        return if (outLen == out.size) out else out.copyOf(outLen)
    }

    private fun skipHeader(b: ByteArray, start: Int): Int {
        val flags = b[start + 3].toInt()
        var p = start + 10
        if (flags and FEXTRA != 0) p += 2 + ((b[p].toInt() and 0xff) or ((b[p + 1].toInt() and 0xff) shl 8))
        if (flags and FNAME != 0) { while (p < b.size && b[p] != 0.toByte()) p++; p++ }
        if (flags and FCOMMENT != 0) { while (p < b.size && b[p] != 0.toByte()) p++; p++ }
        if (flags and FHCRC != 0) p += 2
        if (p > b.size) throw TileFormatException("truncated gzip header")
        return p
    }

    /** The last member's stated size (modulo 2³², as gzip keeps it), or a guess when that says nothing. */
    private fun trailerSize(b: ByteArray): Long =
        if (b.size >= 18) (readIntLE(b, b.size - 4).toLong() and 0xffffffffL).let { if (it == 0L) b.size * 4L else it } else 64L

    private fun readIntLE(b: ByteArray, p: Int): Int =
        (b[p].toInt() and 0xff) or ((b[p + 1].toInt() and 0xff) shl 8) or
            ((b[p + 2].toInt() and 0xff) shl 16) or ((b[p + 3].toInt() and 0xff) shl 24)
}

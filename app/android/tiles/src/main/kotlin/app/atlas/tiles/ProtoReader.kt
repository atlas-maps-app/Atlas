package app.atlas.tiles

import java.io.IOException

/** Malformed protobuf, vector tile or archive data. */
open class TileFormatException(message: String) : IOException(message)

/**
 * A minimal, allocation-free protobuf wire reader over a byte array.
 *
 * Read a message with [next] in a loop, then the field's value with the method matching its wire type, or [skip] it.
 * Embedded messages are read in place: `val outer = pushLimit(length())`, read fields, then `popLimit(outer)`.
 */
class ProtoReader(val buf: ByteArray, start: Int = 0, end: Int = buf.size) {
    /** The next byte to read. */
    var pos: Int = start

    /** The end of the current message (exclusive). */
    var limit: Int = end
        private set

    /** The current field's key: field number and wire type. */
    var tag: Int = 0
        private set

    val field: Int get() = tag ushr 3
    val wireType: Int get() = tag and 7
    val hasMore: Boolean get() = pos < limit

    init {
        require(start in 0..end && end <= buf.size) { "range $start..$end outside 0..${buf.size}" }
    }

    /** Moves to the next field of the current message; false at its end. */
    fun next(): Boolean {
        if (pos >= limit) return false
        tag = varint32()
        if (tag ushr 3 == 0) throw TileFormatException("field number 0 at ${pos - 1}")
        return true
    }

    /** A varint truncated to 32 bits (as protobuf does for int32 and uint32). */
    fun varint32(): Int {
        val b = buf
        var p = pos
        if (p >= limit) throw truncated()
        var x = b[p++].toInt()
        if (x >= 0) {
            pos = p
            return x
        }
        x = x and 0x7f
        var shift = 7
        while (true) {
            if (p >= limit) throw truncated()
            val c = b[p++].toInt()
            if (shift < 32) x = x or ((c and 0x7f) shl shift)
            if (c >= 0) break
            shift += 7
            if (shift > 63) throw TileFormatException("varint too long at $pos")
        }
        pos = p
        return x
    }

    /** A full 64-bit varint. */
    fun varint(): Long {
        val b = buf
        var p = pos
        var x = 0L
        var shift = 0
        while (true) {
            if (p >= limit) throw truncated()
            val c = b[p++].toInt()
            x = x or ((c and 0x7f).toLong() shl shift)
            if (c >= 0) break
            shift += 7
            if (shift > 63) throw TileFormatException("varint too long at $pos")
        }
        pos = p
        return x
    }

    fun int32(): Int = varint32()
    fun int64(): Long = varint()
    fun uint32(): Int = varint32()
    fun uint64(): Long = varint()
    fun bool(): Boolean = varint() != 0L
    fun sint32(): Int = zigzag(varint32())
    fun sint64(): Long = zigzag(varint())

    fun fixed32(): Int {
        if (pos + 4 > limit) throw truncated()
        val b = buf
        val p = pos
        pos = p + 4
        return (b[p].toInt() and 0xff) or ((b[p + 1].toInt() and 0xff) shl 8) or
            ((b[p + 2].toInt() and 0xff) shl 16) or ((b[p + 3].toInt() and 0xff) shl 24)
    }

    fun fixed64(): Long {
        val lo = fixed32().toLong() and 0xffffffffL
        val hi = fixed32().toLong()
        return lo or (hi shl 32)
    }

    fun float(): Float = java.lang.Float.intBitsToFloat(fixed32())
    fun double(): Double = java.lang.Double.longBitsToDouble(fixed64())

    /** Reads a length prefix and checks the bytes it announces are there. */
    fun length(): Int {
        val n = varint()
        if (n < 0 || n > limit - pos) throw TileFormatException("length $n past the end at $pos")
        return n.toInt()
    }

    fun string(): String {
        val n = length()
        val s = String(buf, pos, n, Charsets.UTF_8)
        pos += n
        return s
    }

    fun bytes(): ByteArray {
        val n = length()
        val out = buf.copyOfRange(pos, pos + n)
        pos += n
        return out
    }

    /** Points the reader at another message `start until end` of the same buffer. */
    fun reset(start: Int, end: Int) {
        require(start in 0..end && end <= buf.size) { "range $start..$end outside 0..${buf.size}" }
        pos = start
        limit = end
        tag = 0
    }

    /** Narrows reading to the next [length] bytes and returns the limit to restore with [popLimit]. */
    fun pushLimit(length: Int): Int {
        val old = limit
        val end = pos + length
        if (length < 0 || end > old) throw TileFormatException("message of $length bytes past the end at $pos")
        limit = end
        return old
    }

    /** Ends an embedded message: jumps past it and restores the outer limit. */
    fun popLimit(oldLimit: Int) {
        pos = limit
        limit = oldLimit
    }

    /** Skips the value of the current field. */
    fun skip() {
        when (tag and 7) {
            WIRE_VARINT -> varint()
            WIRE_FIXED64 -> skipBytes(8)
            WIRE_LENGTH -> skipBytes(length())
            WIRE_FIXED32 -> skipBytes(4)
            else -> throw TileFormatException("unsupported wire type ${tag and 7} at $pos")
        }
    }

    private fun skipBytes(n: Int) {
        if (n > limit - pos) throw truncated()
        pos += n
    }

    /** Calls [block] with each varint of a packed repeated field (the length prefix is read here). */
    inline fun packedVarints(block: (Long) -> Unit) {
        val old = pushLimit(length())
        while (pos < limit) block(varint())
        popLimit(old)
    }

    /** A packed repeated uint32 field as an exact-size array. */
    fun packedUInt32(): IntArray {
        val n = length()
        val end = pos + n
        val old = pushLimit(n)
        var count = 0
        for (i in pos until end) if (buf[i] >= 0) count++
        val out = IntArray(count)
        for (i in 0 until count) out[i] = varint32()
        if (pos != end) throw TileFormatException("packed field ends inside a varint at $pos")
        popLimit(old)
        return out
    }

    private fun truncated() = TileFormatException("truncated protobuf at $pos (limit $limit)")

    companion object {
        const val WIRE_VARINT = 0
        const val WIRE_FIXED64 = 1
        const val WIRE_LENGTH = 2
        const val WIRE_FIXED32 = 5

        fun zigzag(n: Int): Int = (n ushr 1) xor -(n and 1)
        fun zigzag(n: Long): Long = (n ushr 1) xor -(n and 1L)
    }
}

package app.atlas.search

/** A growable byte buffer for the store's compact blobs: unsigned varints and UTF-8 strings. Reused between rows. */
internal class ByteSink(capacity: Int = 128) {
    private var buf = ByteArray(capacity)

    var size = 0
        private set

    fun reset() {
        size = 0
    }

    /** An unsigned LEB128 varint: seven bits a byte, low bits first. [v] must not be negative. */
    fun varint(v: Long) {
        require(v >= 0) { "varints are unsigned: $v" }
        raw(v)
    }

    fun varint(v: Int) = varint(v.toLong())

    /** A signed value, zigzag-coded so small negatives stay short. */
    fun zigzag(v: Long) = raw((v shl 1) xor (v shr 63))

    /** [v]'s 64 bits as an unsigned varint. */
    private fun raw(v: Long) {
        ensure(10)
        var x = v
        while (x and 0x7fL.inv() != 0L) {
            buf[size++] = ((x and 0x7f) or 0x80).toByte()
            x = x ushr 7
        }
        buf[size++] = x.toByte()
    }

    fun bytes(b: ByteArray) {
        ensure(b.size)
        System.arraycopy(b, 0, buf, size, b.size)
        size += b.size
    }

    fun toByteArray(): ByteArray = buf.copyOf(size)

    private fun ensure(n: Int) {
        if (size + n > buf.size) buf = buf.copyOf(maxOf(buf.size * 2, size + n))
    }
}

/** Reads what a [ByteSink] wrote. */
internal class ByteSource(private val buf: ByteArray, private var pos: Int = 0, private val end: Int = buf.size) {
    val hasMore: Boolean get() = pos < end

    fun varint(): Long {
        var shift = 0
        var out = 0L
        while (true) {
            if (pos >= end) throw IllegalStateException("a varint runs past the end of its blob")
            val b = buf[pos++].toInt()
            out = out or ((b and 0x7f).toLong() shl shift)
            if (b and 0x80 == 0) return out
            shift += 7
            if (shift > 63) throw IllegalStateException("a varint longer than 64 bits")
        }
    }

    fun int(): Int = varint().toInt()

    fun zigzag(): Long {
        val v = varint()
        return (v ushr 1) xor -(v and 1)
    }

    /** Steps over [length] bytes. */
    fun skip(length: Int) {
        if (length < 0 || pos + length > end) throw IllegalStateException("a string runs past the end of its blob")
        pos += length
    }

    /** [length] bytes of UTF-8 as a string. */
    fun string(length: Int): String {
        if (length < 0 || pos + length > end) throw IllegalStateException("a string runs past the end of its blob")
        val s = String(buf, pos, length, Charsets.UTF_8)
        pos += length
        return s
    }
}

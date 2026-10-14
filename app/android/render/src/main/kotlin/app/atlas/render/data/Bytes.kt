package app.atlas.render.data

/** A growable byte buffer with LEB128 varints, as the pack format writes them. */
class ByteSink(initial: Int = 1024) {
    private var buf = ByteArray(initial.coerceAtLeast(16))
    var size = 0
        private set

    private fun room(n: Int) {
        if (size + n > buf.size) buf = buf.copyOf(maxOf(buf.size * 2, size + n))
    }

    fun byte(v: Int) {
        room(1)
        buf[size++] = v.toByte()
    }

    fun bytes(src: ByteArray, from: Int = 0, len: Int = src.size - from) {
        room(len)
        System.arraycopy(src, from, buf, size, len)
        size += len
    }

    /** An unsigned LEB128 varint (negative values take ten bytes). */
    fun varLong(value: Long) {
        room(10)
        var v = value
        while (v and 0x7FL.inv() != 0L) {
            buf[size++] = ((v and 0x7F) or 0x80).toByte()
            v = v ushr 7
        }
        buf[size++] = v.toByte()
    }

    fun varInt(value: Int) = varLong(value.toLong() and 0xFFFFFFFFL)

    /** A signed varint, zig-zag coded so small magnitudes stay small. */
    fun zigzag(value: Long) = varLong((value shl 1) xor (value shr 63))

    fun u32(v: Long) {
        room(4)
        buf[size++] = (v ushr 24).toByte()
        buf[size++] = (v ushr 16).toByte()
        buf[size++] = (v ushr 8).toByte()
        buf[size++] = v.toByte()
    }

    fun u64(v: Long) {
        u32(v ushr 32)
        u32(v and 0xFFFFFFFFL)
    }

    fun f64(v: Double) = u64(java.lang.Double.doubleToRawLongBits(v))

    /** A length-prefixed UTF-8 string. */
    fun string(s: String) {
        val b = s.toByteArray(Charsets.UTF_8)
        varInt(b.size)
        bytes(b)
    }

    fun toByteArray(): ByteArray = buf.copyOf(size)

    fun reset() {
        size = 0
    }

    /** The buffer itself (valid up to [size]); for handing to a compressor without a copy. */
    fun unsafeArray(): ByteArray = buf
}

/** Reads what [ByteSink] wrote. */
class ByteSource(private val buf: ByteArray, var pos: Int = 0, private val end: Int = buf.size) {
    val remaining: Int get() = end - pos

    fun byte(): Int {
        if (pos >= end) throw PackFormatException("a section ends early")
        return buf[pos++].toInt() and 0xFF
    }

    fun varLong(): Long {
        var result = 0L
        var shift = 0
        while (true) {
            val b = byte()
            result = result or ((b and 0x7F).toLong() shl shift)
            if (b and 0x80 == 0) return result
            shift += 7
            if (shift > 63) throw PackFormatException("a varint is too long")
        }
    }

    fun varInt(): Int = varLong().toInt()

    fun zigzag(): Long {
        val v = varLong()
        return (v ushr 1) xor -(v and 1)
    }

    fun u32(): Long {
        if (pos + 4 > end) throw PackFormatException("a section ends early")
        val v = ((buf[pos].toLong() and 0xFF) shl 24) or ((buf[pos + 1].toLong() and 0xFF) shl 16) or
            ((buf[pos + 2].toLong() and 0xFF) shl 8) or (buf[pos + 3].toLong() and 0xFF)
        pos += 4
        return v
    }

    fun u64(): Long = (u32() shl 32) or u32()

    fun f64(): Double = java.lang.Double.longBitsToDouble(u64())

    fun bytes(n: Int): ByteArray {
        if (n < 0 || pos + n > end) throw PackFormatException("a section ends early")
        val r = buf.copyOfRange(pos, pos + n)
        pos += n
        return r
    }

    fun string(): String {
        val n = varInt()
        if (n < 0 || pos + n > end) throw PackFormatException("a string runs past its section")
        val s = String(buf, pos, n, Charsets.UTF_8)
        pos += n
        return s
    }
}

/** A pack that is not one, or is damaged, or is newer than this reader. */
class PackFormatException(message: String) : RuntimeException(message)

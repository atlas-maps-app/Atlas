package app.atlas.render.py

import java.math.BigInteger

/**
 * CPython's `hash()` on 64-bit builds, with PYTHONHASHSEED=0: ints, floats, str, bytes and tuples.
 *
 * Kotlin values stand for Python ones: Int, Long and BigInteger are int; Double is float; Boolean is bool;
 * String is str; ByteArray is bytes; a List or an Array is a tuple.
 */
object PyHash {
    private const val BITS = 61
    private const val MODULUS = (1L shl BITS) - 1
    private const val INF = 314159L
    private const val XXPRIME_1 = -7046029288634856825L // 11400714785074694791
    private const val XXPRIME_2 = -4417276706812531889L // 14029467366897019727
    private const val XXPRIME_5 = 2870177450012600261L
    private val BIG_MODULUS = BigInteger.valueOf(MODULUS)

    /** `hash(v)`. */
    fun hash(v: Any?): Long = when (v) {
        is Boolean -> if (v) 1L else 0L
        is Int -> hashInt(v.toLong())
        is Long -> hashInt(v)
        is BigInteger -> hashBig(v)
        is Double -> hashDouble(v)
        is Float -> hashDouble(v.toDouble())
        is String -> hashStr(v)
        is ByteArray -> hashBytes(v, v.size)
        is List<*> -> hashTuple(v)
        is Array<*> -> hashTuple(v.asList())
        null -> throw IllegalArgumentException("hash(None) depends on the address of None; not reproducible")
        else -> throw IllegalArgumentException("no Python hash for ${v::class}")
    }

    /** `hash(n)` for an int. */
    fun hashInt(n: Long): Long {
        if (n == Long.MIN_VALUE) return hashBig(BigInteger.valueOf(n))
        val a = if (n < 0) -n else n
        var x = a % MODULUS
        if (n < 0) x = -x
        return if (x == -1L) -2L else x
    }

    private fun hashBig(n: BigInteger): Long {
        var x = n.abs().mod(BIG_MODULUS).toLong()
        if (n.signum() < 0) x = -x
        return if (x == -1L) -2L else x
    }

    /** `hash(x)` for a float: the value reduced modulo 2**61 - 1, so integral floats hash like ints. */
    fun hashDouble(v: Double): Long {
        if (!v.isFinite()) {
            if (v.isInfinite()) return if (v > 0) INF else -INF
            throw IllegalArgumentException("hash(nan) depends on the object's address since 3.10")
        }
        var e = PyMath.frexpExp(v)
        var m = if (v == 0.0) 0.0 else Math.scalb(v, -e)
        var sign = 1L
        if (m < 0) {
            sign = -1L; m = -m
        }
        var x = 0L
        while (m != 0.0) {
            x = ((x shl 28) and MODULUS) or (x ushr (BITS - 28))
            m *= 268435456.0
            e -= 28
            val y = m.toLong()
            m -= y.toDouble()
            x += y
            if (java.lang.Long.compareUnsigned(x, MODULUS) >= 0) x -= MODULUS
        }
        e = if (e >= 0) e % BITS else BITS - 1 - ((-1 - e) % BITS)
        x = ((x shl e) and MODULUS) or (x ushr (BITS - e))
        x *= sign
        return if (x == -1L) -2L else x
    }

    /** `hash(s)` for a str: SipHash over CPython's internal representation (1, 2 or 4 bytes per code point). */
    fun hashStr(s: String): Long {
        if (s.isEmpty()) return 0L
        var maxCp = 0
        var n = 0
        var i = 0
        while (i < s.length) {
            val cp = s.codePointAt(i)
            if (cp > maxCp) maxCp = cp
            i += Character.charCount(cp)
            n++
        }
        val kind = if (maxCp < 256) 1 else if (maxCp < 65536) 2 else 4
        val buf = ByteArray(n * kind)
        var k = 0
        i = 0
        while (i < s.length) {
            val cp = s.codePointAt(i)
            i += Character.charCount(cp)
            for (b in 0 until kind) buf[k++] = (cp ushr (8 * b)).toByte()
        }
        return hashBytes(buf, buf.size)
    }

    /** `hash(b)` for bytes. */
    fun hashBytes(src: ByteArray, len: Int): Long {
        if (len == 0) return 0L
        val x = sipHash(0L, 0L, src, len, PyCompat.sipRounds)
        return if (x == -1L) -2L else x
    }

    /** `hash(t)` for a tuple: CPython 3.8+'s xxHash-style combination of the items' hashes. */
    fun hashTuple(items: List<*>): Long {
        var acc = XXPRIME_5
        for (it in items) {
            val lane = hash(it)
            acc += lane * XXPRIME_2
            acc = (acc shl 31) or (acc ushr 33)
            acc *= XXPRIME_1
        }
        acc += items.size.toLong() xor (XXPRIME_5 xor 3527539L)
        return if (acc == -1L) 1546275796L else acc
    }

    /** SipHash-c-4 (c = 2) or SipHash-1-3 (c = 1), as in CPython's pyhash.c. */
    internal fun sipHash(k0: Long, k1: Long, src: ByteArray, len: Int, c: Int): Long {
        var b = len.toLong() shl 56
        var v0 = k0 xor 0x736f6d6570736575L
        var v1 = k1 xor 0x646f72616e646f6dL
        var v2 = k0 xor 0x6c7967656e657261L
        var v3 = k1 xor 0x7465646279746573L
        val finals = if (c == 2) 4 else 3
        var off = 0
        var left = len
        while (left >= 8) {
            var mi = 0L
            for (j in 0 until 8) mi = mi or ((src[off + j].toLong() and 0xff) shl (8 * j))
            off += 8; left -= 8
            v3 = v3 xor mi
            repeat(c) {
                v0 += v1; v2 += v3; v1 = rotl(v1, 13) xor v0; v3 = rotl(v3, 16) xor v2; v0 = rotl(v0, 32)
                v2 += v1; v0 += v3; v1 = rotl(v1, 17) xor v2; v3 = rotl(v3, 21) xor v0; v2 = rotl(v2, 32)
            }
            v0 = v0 xor mi
        }
        var t = 0L
        for (j in 0 until left) t = t or ((src[off + j].toLong() and 0xff) shl (8 * j))
        b = b or t
        v3 = v3 xor b
        repeat(c) {
            v0 += v1; v2 += v3; v1 = rotl(v1, 13) xor v0; v3 = rotl(v3, 16) xor v2; v0 = rotl(v0, 32)
            v2 += v1; v0 += v3; v1 = rotl(v1, 17) xor v2; v3 = rotl(v3, 21) xor v0; v2 = rotl(v2, 32)
        }
        v0 = v0 xor b
        v2 = v2 xor 0xffL
        repeat(finals) {
            v0 += v1; v2 += v3; v1 = rotl(v1, 13) xor v0; v3 = rotl(v3, 16) xor v2; v0 = rotl(v0, 32)
            v2 += v1; v0 += v3; v1 = rotl(v1, 17) xor v2; v3 = rotl(v3, 21) xor v0; v2 = rotl(v2, 32)
        }
        return (v0 xor v1) xor (v2 xor v3)
    }

    private fun rotl(x: Long, b: Int): Long = (x shl b) or (x ushr (64 - b))
}

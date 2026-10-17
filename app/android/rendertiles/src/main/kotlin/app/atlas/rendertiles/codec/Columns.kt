package app.atlas.rendertiles.codec

import app.atlas.rendertiles.model.Codec
import app.atlas.rendertiles.model.Geom
import app.atlas.rendertiles.model.GridPoint
import app.atlas.rendertiles.model.Keep

/** A render tile, `rhead` or `style` that is not one, is damaged, or is newer than this reader. */
class TileFormatException(message: String) : RuntimeException(message)

/** A growable little-endian byte buffer with LEB128 varints: every block of the payload is written with it. */
class LeSink(initial: Int = 1024) {
    private var buf = ByteArray(initial.coerceAtLeast(16))
    var size = 0
        private set

    private fun room(n: Int) {
        if (size + n > buf.size) buf = buf.copyOf(maxOf(buf.size * 2, size + n))
    }

    fun u8(v: Int) {
        room(1)
        buf[size++] = v.toByte()
    }

    fun u16(v: Int) {
        room(2)
        buf[size++] = v.toByte()
        buf[size++] = (v ushr 8).toByte()
    }

    fun u32(v: Long) {
        room(4)
        for (k in 0 until 4) buf[size++] = (v ushr (8 * k)).toByte()
    }

    fun i32(v: Int) = u32(v.toLong() and 0xFFFFFFFFL)

    fun u64(v: Long) {
        room(8)
        for (k in 0 until 8) buf[size++] = (v ushr (8 * k)).toByte()
    }

    fun f64(v: Double) = u64(java.lang.Double.doubleToRawLongBits(v))

    /** An unsigned LEB128 varint (a negative value takes ten bytes). */
    fun varU(value: Long) {
        room(10)
        var v = value
        while (v and 0x7FL.inv() != 0L) {
            buf[size++] = ((v and 0x7F) or 0x80).toByte()
            v = v ushr 7
        }
        buf[size++] = v.toByte()
    }

    /** A signed varint, zig-zag coded. */
    fun varZ(value: Long) = varU((value shl 1) xor (value shr 63))

    fun bytes(src: ByteArray, from: Int = 0, len: Int = src.size - from) {
        room(len)
        System.arraycopy(src, from, buf, size, len)
        size += len
    }

    /** A varint byte length and the UTF-8 bytes of [s]. */
    fun utf8(s: String) {
        val b = s.toByteArray(Charsets.UTF_8)
        varU(b.size.toLong())
        bytes(b)
    }

    /** [block]'s bytes, after their varint length. */
    fun block(block: LeSink) {
        varU(block.size.toLong())
        bytes(block.buf, 0, block.size)
    }

    /** Overwrites the four bytes at [at] with [v] (a header field filled in once its value is known). */
    fun putU32(at: Int, v: Long) {
        require(at + 4 <= size) { "no u32 at $at" }
        for (k in 0 until 4) buf[at + k] = (v ushr (8 * k)).toByte()
    }

    fun putU16(at: Int, v: Int) {
        require(at + 2 <= size) { "no u16 at $at" }
        buf[at] = v.toByte()
        buf[at + 1] = (v ushr 8).toByte()
    }

    fun toByteArray(): ByteArray = buf.copyOf(size)

    /** The buffer itself, valid up to [size]. */
    fun unsafeArray(): ByteArray = buf
}

/** Reads what [LeSink] wrote, checking every read against [end]. */
class LeSource(private val buf: ByteArray, var pos: Int = 0, val end: Int = buf.size) {
    init {
        if (pos < 0 || end > buf.size || pos > end) throw TileFormatException("a block lies outside its bytes")
    }

    val remaining: Int get() = end - pos

    private fun need(n: Int) {
        if (n < 0 || pos + n > end) throw TileFormatException("a block ends early")
    }

    fun u8(): Int {
        need(1)
        return buf[pos++].toInt() and 0xFF
    }

    fun u16(): Int {
        need(2)
        val v = (buf[pos].toInt() and 0xFF) or ((buf[pos + 1].toInt() and 0xFF) shl 8)
        pos += 2
        return v
    }

    fun u32(): Long {
        need(4)
        var v = 0L
        for (k in 0 until 4) v = v or ((buf[pos + k].toLong() and 0xFF) shl (8 * k))
        pos += 4
        return v
    }

    fun i32(): Int = u32().toInt()

    fun u64(): Long {
        need(8)
        var v = 0L
        for (k in 0 until 8) v = v or ((buf[pos + k].toLong() and 0xFF) shl (8 * k))
        pos += 8
        return v
    }

    fun f64(): Double = java.lang.Double.longBitsToDouble(u64())

    /** An unsigned LEB128 varint in its shortest form: one byte string per value, so a longer one is refused. */
    fun varU(): Long {
        var result = 0L
        var shift = 0
        while (true) {
            val b = u8()
            if (shift == 63 && b > 1) throw TileFormatException("a varint is too long")
            result = result or ((b and 0x7F).toLong() shl shift)
            if (b and 0x80 == 0) {
                if (b == 0 && shift > 0) throw TileFormatException("an overlong varint")
                return result
            }
            shift += 7
            if (shift > 63) throw TileFormatException("a varint is too long")
        }
    }

    /** A varint that must fit an Int count or index (0 to [Int.MAX_VALUE]). */
    fun varCount(): Int {
        val v = varU()
        if (v < 0 || v > Int.MAX_VALUE) throw TileFormatException("a count of $v is out of range")
        return v.toInt()
    }

    fun varZ(): Long {
        val v = varU()
        return (v ushr 1) xor -(v and 1)
    }

    fun bytes(n: Int): ByteArray {
        need(n)
        val r = buf.copyOfRange(pos, pos + n)
        pos += n
        return r
    }

    /** A varint byte length and that many bytes of UTF-8; a string that is not UTF-8 is refused, never read as U+FFFD. */
    fun utf8(): String {
        val n = varCount()
        need(n)
        val s = try {
            Charsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(buf, pos, n)).toString()
        } catch (e: java.nio.charset.CharacterCodingException) {
            throw TileFormatException("a string that is not UTF-8")
        }
        pos += n
        return s
    }

    /** The next varint-length block, as a source over it. */
    fun block(): LeSource {
        val n = varCount()
        need(n)
        val b = LeSource(buf, pos, pos + n)
        pos += n
        return b
    }
}

/**
 * The strings of one tile, in the order they are first used, so a tile's bytes depend only on its features. Indexes
 * are what columns store.
 */
class StringTable {
    private val index = HashMap<String, Int>()
    private val list = ArrayList<String>()

    fun of(s: String): Int = index.getOrPut(s) { list.add(s); list.size - 1 }

    val strings: List<String> get() = list

    fun write(out: LeSink) {
        out.varU(list.size.toLong())
        for (s in list) out.utf8(s)
    }

    companion object {
        fun read(src: LeSource): List<String> {
            val n = src.varCount()
            if (n > src.remaining) throw TileFormatException("a string table of $n strings in ${src.remaining} bytes")
            return List(n) { src.utf8() }
        }
    }
}

/**
 * Typed values (the [Codec.VALUE] column, `rhead`'s views and `style`'s tables): what the port's dicts hold, with
 * Kotlin's types kept, so a value decodes equal to the one encoded.
 *
 * A type byte, then: 0 null; 1 false; 2 true; 3 an Int (zig-zag varint); 4 a Long (zig-zag varint); 5 a Double (8 bytes,
 * its bits); 6 a string; 7 a list (varint count, the values); 8 a map with string keys (varint count, then key and
 * value). Inside a tile a string is a varint index into its table; in `rhead` and `style` it is written out (varint
 * length, UTF-8).
 */
object Values {
    const val NULL = 0
    const val FALSE = 1
    const val TRUE = 2
    const val INT = 3
    const val LONG = 4
    const val DOUBLE = 5
    const val STRING = 6
    const val LIST = 7
    const val MAP = 8

    /** Writes [v]; strings go through [strings] when given (a tile), else inline. */
    fun write(out: LeSink, v: Any?, strings: StringTable?) {
        when (v) {
            null -> out.u8(NULL)
            is Boolean -> out.u8(if (v) TRUE else FALSE)
            is Int, is Short, is Byte -> { out.u8(INT); out.varZ((v as Number).toLong()) }
            is Long -> { out.u8(LONG); out.varZ(v) }
            is Double -> { out.u8(DOUBLE); out.f64(v) }
            is Float -> { out.u8(DOUBLE); out.f64(v.toDouble()) }
            is String -> { out.u8(STRING); string(out, v, strings) }
            is List<*> -> {
                out.u8(LIST)
                out.varU(v.size.toLong())
                for (e in v) write(out, e, strings)
            }
            is Map<*, *> -> {
                out.u8(MAP)
                out.varU(v.size.toLong())
                for ((k, e) in v) {
                    if (k !is String) throw IllegalArgumentException("a map key must be a string, not ${k?.let { it::class.simpleName }}")
                    string(out, k, strings)
                    write(out, e, strings)
                }
            }
            is DoubleArray -> write(out, v.toList(), strings)
            is IntArray -> write(out, v.toList(), strings)
            else -> throw IllegalArgumentException("a ${v::class.qualifiedName} has no typed-value coding")
        }
    }

    /** Reads a value; strings from [strings] when given (a tile), else inline. Maps keep their order. */
    fun read(src: LeSource, strings: List<String>?, depth: Int = 0): Any? {
        if (depth > 32) throw TileFormatException("a value nests too deep")
        return when (val t = src.u8()) {
            NULL -> null
            FALSE -> false
            TRUE -> true
            INT -> {
                val v = src.varZ()
                if (v < Int.MIN_VALUE || v > Int.MAX_VALUE) throw TileFormatException("an Int of $v")
                v.toInt()
            }
            LONG -> src.varZ()
            DOUBLE -> src.f64()
            STRING -> string(src, strings)
            LIST -> {
                val n = src.varCount()
                if (n > src.remaining) throw TileFormatException("a list of $n values in ${src.remaining} bytes")
                ArrayList<Any?>(n).apply { repeat(n) { add(read(src, strings, depth + 1)) } }
            }
            MAP -> {
                val n = src.varCount()
                if (n > src.remaining) throw TileFormatException("a map of $n entries in ${src.remaining} bytes")
                LinkedHashMap<String, Any?>(n * 2).apply { repeat(n) { put(string(src, strings), read(src, strings, depth + 1)) } }
            }
            else -> throw TileFormatException("unknown value type $t")
        }
    }

    private fun string(out: LeSink, s: String, strings: StringTable?) {
        if (strings != null) out.varU(strings.of(s).toLong()) else out.utf8(s)
    }

    private fun string(src: LeSource, strings: List<String>?): String {
        if (strings == null) return src.utf8()
        val i = src.varCount()
        if (i >= strings.size) throw TileFormatException("string $i of ${strings.size}")
        return strings[i]
    }
}

/**
 * The column codecs ([Codec]): one encoder and one decoder each. A column holds a value per feature; [encode] takes the
 * values in feature order, [decode] gives them back equal (`==` for scalars and strings, content for geometry).
 */
object Columns {
    /** The column's bytes for [values] (one per feature). Geometry columns take [Geom]s, keep columns take [Geom]s too. */
    fun encode(codec: Codec, values: List<Any?>, strings: StringTable): ByteArray {
        val out = LeSink(64 + values.size * 2)
        when (codec) {
            Codec.U8 -> for (v in values) out.u8(int(v).also { require(it in 0..255) { "a U8 of $it" } })
            Codec.VARU -> for (v in values) out.varU(long(v).also { require(it >= 0) { "a VARU of $it" } })
            Codec.VARZ -> for (v in values) out.varZ(long(v))
            Codec.DELTA -> {
                var prev = 0L
                for (v in values) {
                    val x = long(v)
                    out.varZ(x - prev)
                    prev = x
                }
            }
            Codec.CENTI -> for (v in values) centi(out, v as Double?)
            Codec.STR -> for (v in values) out.varU(if (v == null) 0L else strings.of(v as String) + 1L)
            Codec.STRN -> {
                var prev = 0L
                for (v in values) {
                    val str = v as String?
                    val n = str?.let { canonicalInt(it) }
                    out.varU(when {
                        str == null -> 0L
                        n != null -> {
                            val d = n - prev
                            prev = n
                            2 * ((d shl 1) xor (d shr 63)) + 2
                        }
                        else -> 2L * strings.of(str) + 1
                    })
                }
            }
            Codec.BOOL -> for (v in values) out.u8(when (v) { null -> 0; false -> 1; true -> 2; else -> error("a BOOL of $v") })
            Codec.GEOM -> Geometry.encode(values.map { it as Geom }, out)
            Codec.KEEP -> for (v in values) {
                val g = v as Geom
                val k = g.keep ?: throw IllegalArgumentException("a keep column needs keep codes on every ring")
                if (g.size < Keep.MIN_POINTS) {
                    require(k.all { it.toInt() == Keep.ALL }) { "a ring of ${g.size} vertices is never simplified: its codes must all be ${Keep.ALL}" }
                    continue
                }
                out.bytes(k)
            }
            Codec.POINT -> {
                val lat = LeSink(values.size * 3)
                val lon = LeSink(values.size * 3)
                var pLat = 0L
                var pLon = 0L
                for (v in values) {
                    val p = v as GridPoint
                    lat.varZ(p.lat - pLat); pLat = p.lat.toLong()
                    lon.varZ(p.lon - pLon); pLon = p.lon.toLong()
                }
                out.block(lat); out.block(lon)
            }
            Codec.NDELTA -> {
                var prev = 0L
                for (v in values) {
                    if (v == null) out.varU(0) else {
                        val x = long(v)
                        val d = x - prev
                        require(d != Long.MIN_VALUE) { "a nullable delta column cannot step by Long.MIN_VALUE" }
                        prev = x
                        out.varU(((d shl 1) xor (d shr 63)) + 1)
                    }
                }
            }
            Codec.TAGS -> for (v in values) {
                @Suppress("UNCHECKED_CAST")
                val m = (v as Map<String, String>?) ?: emptyMap()
                out.varU(m.size.toLong())
                for ((k, t) in m) {
                    out.varU(strings.of(k).toLong())
                    out.varU(strings.of(t).toLong())
                }
            }
            Codec.NVARZ -> for (v in values) {
                if (v == null) out.varU(0) else {
                    val z = long(v)
                    require(z != Long.MIN_VALUE) { "a nullable integer column holds no Long.MIN_VALUE" }
                    out.varU(((z shl 1) xor (z shr 63)) + 1)
                }
            }
            Codec.IDS -> for (v in values) {
                @Suppress("UNCHECKED_CAST")
                val ids = (v as List<Long>?) ?: emptyList()
                out.varU(ids.size.toLong())
                var prev = 0L
                for (x in ids) {
                    out.varZ(x - prev)
                    prev = x
                }
            }
            Codec.STRS -> for (v in values) {
                if (v == null) out.varU(0) else {
                    @Suppress("UNCHECKED_CAST")
                    val l = v as List<String>
                    out.varU(l.size + 1L)
                    for (s in l) out.varU(strings.of(s).toLong())
                }
            }
            Codec.VALUE -> for (v in values) Values.write(out, v, strings)
            Codec.F64 -> for (v in values) out.f64(v as Double)
        }
        return out.toByteArray()
    }

    /**
     * [count] values of [codec] from [src]. A [Codec.KEEP] column needs the decoded [geoms] of its ring column (the codes
     * are attached to them and the geoms returned).
     */
    fun decode(codec: Codec, src: LeSource, count: Int, strings: List<String>, geoms: List<Geom>? = null): List<Any?> {
        if (count < 0) throw TileFormatException("a column of $count values")
        fun str(i: Int): String = if (i in strings.indices) strings[i] else throw TileFormatException("string $i of ${strings.size}")
        val out = ArrayList<Any?>(count)
        when (codec) {
            Codec.U8 -> repeat(count) { out.add(src.u8()) }
            Codec.VARU -> repeat(count) { out.add(src.varU()) }
            Codec.VARZ -> repeat(count) { out.add(src.varZ()) }
            Codec.DELTA -> {
                var prev = 0L
                repeat(count) { prev += src.varZ(); out.add(prev) }
            }
            Codec.CENTI -> repeat(count) { out.add(centi(src)) }
            Codec.STR -> repeat(count) {
                val i = src.varU()
                out.add(if (i == 0L) null else str(checkIndex(i - 1)))
            }
            Codec.STRN -> {
                var prev = 0L
                repeat(count) {
                    val v = src.varU()
                    out.add(when {
                        v == 0L -> null
                        v and 1L == 1L -> str(checkIndex((v - 1) ushr 1))
                        else -> {
                            val z = (v - 2) ushr 1
                            prev += (z ushr 1) xor -(z and 1)
                            if (prev < 0) throw TileFormatException("a number of $prev")
                            prev.toString()
                        }
                    })
                }
            }
            Codec.BOOL -> repeat(count) {
                out.add(when (val b = src.u8()) { 0 -> null; 1 -> false; 2 -> true; else -> throw TileFormatException("a BOOL of $b") })
            }
            Codec.GEOM -> out.addAll(Geometry.decode(src, count))
            Codec.KEEP -> {
                val gs = geoms ?: throw TileFormatException("a keep column without its rings")
                if (gs.size != count) throw TileFormatException("a keep column of $count rings for ${gs.size}")
                for (g in gs) {
                    val k = if (g.size < Keep.MIN_POINTS) ByteArray(g.size) { Keep.ALL.toByte() } else src.bytes(g.size)
                    for (b in k) if (b < 0 || b > 15) throw TileFormatException("a keep code of $b")
                    out.add(Geom(g.lat, g.lon, k))
                }
            }
            Codec.POINT -> {
                val lat = src.block()
                val lon = src.block()
                var pLat = 0L
                var pLon = 0L
                repeat(count) {
                    pLat += lat.varZ(); pLon += lon.varZ()
                    out.add(GridPoint(grid(pLat), grid(pLon)))
                }
                if (lat.remaining != 0 || lon.remaining != 0) throw TileFormatException("a point column has bytes left over")
            }
            Codec.NDELTA -> {
                var prev = 0L
                repeat(count) {
                    val v = src.varU()
                    if (v == 0L) out.add(null) else {
                        val z = v - 1
                        prev += (z ushr 1) xor -(z and 1)
                        out.add(prev)
                    }
                }
            }
            Codec.TAGS -> repeat(count) {
                val n = src.varCount()
                if (n > src.remaining) throw TileFormatException("$n tags in ${src.remaining} bytes")
                val m = LinkedHashMap<String, String>(n * 2)
                repeat(n) { m[str(src.varCount())] = str(src.varCount()) }
                out.add(m)
            }
            Codec.NVARZ -> repeat(count) {
                val v = src.varU()
                out.add(if (v == 0L) null else ((v - 1) ushr 1) xor -((v - 1) and 1))
            }
            Codec.IDS -> repeat(count) {
                val n = src.varCount()
                if (n > src.remaining) throw TileFormatException("$n ids in ${src.remaining} bytes")
                var prev = 0L
                out.add(List(n) { prev += src.varZ(); prev })
            }
            Codec.STRS -> repeat(count) {
                val n = src.varU()
                if (n == 0L) out.add(null) else {
                    val k = checkIndex(n - 1)
                    if (k > src.remaining) throw TileFormatException("$k strings in ${src.remaining} bytes")
                    out.add(List(k) { str(src.varCount()) })
                }
            }
            Codec.VALUE -> repeat(count) { out.add(Values.read(src, strings)) }
            Codec.F64 -> repeat(count) { out.add(src.f64()) }
        }
        if (src.remaining != 0) throw TileFormatException("a ${codec.name} column has ${src.remaining} bytes left over")
        return out
    }

    /** The value of [s] when it is a non-negative decimal integer written canonically (no sign, no leading zero, below 2^53), else null. */
    fun canonicalInt(s: String): Long? {
        if (s.isEmpty() || s.length > 15 || (s.length > 1 && s[0] == '0')) return null
        var n = 0L
        for (c in s) {
            if (c !in '0'..'9') return null
            n = n * 10 + (c - '0')
        }
        return n
    }

    /** True when [v] is exactly its hundredths (and not −0): [Codec.CENTI] codes it as an integer. */
    fun isCenti(v: Double): Boolean {
        if (v.isNaN() || v.isInfinite() || Math.abs(v) > 9e15 / 100) return false
        val n = Math.round(v * 100)
        return n / 100.0 == v && !(v == 0.0 && 1.0 / v < 0)
    }

    private fun centi(out: LeSink, v: Double?) {
        when {
            v == null -> out.varU(1)
            isCenti(v) -> {
                val n = Math.round(v * 100)
                out.varU(((n shl 1) xor (n shr 63)) shl 2)
            }
            else -> { out.varU(2); out.f64(v) }
        }
    }

    private fun centi(src: LeSource): Double? {
        val t = src.varU()
        return when ((t and 3).toInt()) {
            0 -> {
                val z = t ushr 2
                val n = (z ushr 1) xor -(z and 1)
                n / 100.0
            }
            1 -> { if (t != 1L) throw TileFormatException("a CENTI null of $t"); null }
            2 -> { if (t != 2L) throw TileFormatException("a CENTI double of $t"); src.f64() }
            else -> throw TileFormatException("a CENTI tag of $t")
        }
    }

    private fun checkIndex(v: Long): Int = if (v < 0 || v > Int.MAX_VALUE) throw TileFormatException("an index of $v") else v.toInt()

    private fun grid(v: Long): Int = if (v < Int.MIN_VALUE || v > Int.MAX_VALUE) throw TileFormatException("a coordinate of $v off the grid") else v.toInt()

    private fun long(v: Any?): Long = when (v) {
        is Long -> v
        is Int -> v.toLong()
        is Short -> v.toLong()
        is Byte -> v.toLong()
        else -> throw IllegalArgumentException("an integer column holds ${v?.let { it::class.simpleName }}")
    }

    private fun int(v: Any?): Int = long(v).toInt()
}

/**
 * The [Codec.GEOM] column: every feature's vertices, with the vertices neighbours share (party walls, junctions, quay
 * edges) written once. Ten sub-blocks, each a varint byte length and its bytes:
 * 1. counts: a varint vertex count per feature;
 * 2. kinds: a byte per vertex, 0 for a new vertex, 1 for a repeat of an earlier vertex of the column (the latest one at
 *    the same point);
 * 3. jumps: for a new vertex that is its feature's first, zig-zag varint deltas of latitude and longitude from the
 *    column's previous vertex (the previous feature's last; (0, 0) at the start);
 * 4–7. for every other new vertex, the zig-zag of its latitude delta and of its longitude delta from the feature's previous
 *    vertex, as 16-bit values split into byte planes: latitude low bytes, latitude high bytes, longitude low bytes,
 *    longitude high bytes; a value of 0xFFFF or more is written as 0xFFFF and given in full in
 * 8. escapes: varints, in vertex order, latitude before longitude;
 * 9. first references: for a repeat whose previous vertex in its feature is not a repeat (or that starts its feature), a
 *    varint: how many vertices back, in the column, the vertex it repeats lies;
 * 10. next references: for a repeat following a repeat in its feature, a zig-zag varint: the vertex it repeats minus
 *    (the previous repeat's vertex − 1): a wall shared with a neighbour runs backwards along the neighbour's ring.
 */
object Geometry {
    private const val ESCAPE = 0xFFFF

    fun encode(geoms: List<Geom>, out: LeSink) {
        val counts = LeSink(geoms.size + 8)
        val kinds = LeSink(1024)
        val jumps = LeSink(256)
        val planes = Array(4) { LeSink(1024) }
        val escapes = LeSink(64)
        val refFirst = LeSink(256)
        val refNext = LeSink(256)
        val seen = HashMap<Long, Int>()
        var k = 0
        var lastLat = 0L
        var lastLon = 0L
        fun plane(v: Long, lo: LeSink, hi: LeSink) {
            val z = (v shl 1) xor (v shr 63)
            if (z in 0 until ESCAPE) {
                lo.u8((z and 0xFF).toInt()); hi.u8((z ushr 8).toInt())
            } else {
                lo.u8(0xFF); hi.u8(0xFF); escapes.varU(z)
            }
        }
        for (g in geoms) {
            counts.varU(g.size.toLong())
            var prevRef = -1
            for (i in 0 until g.size) {
                val lat = g.lat[i].toLong()
                val lon = g.lon[i].toLong()
                val key = (lat shl 32) or (lon and 0xFFFFFFFFL)
                val s = seen[key]
                if (s != null) {
                    kinds.u8(1)
                    if (prevRef >= 0) refNext.varZ((s - (prevRef - 1)).toLong()) else refFirst.varU((k - s).toLong())
                    prevRef = s
                } else {
                    kinds.u8(0)
                    if (i == 0) {
                        jumps.varZ(lat - lastLat); jumps.varZ(lon - lastLon)
                    } else {
                        plane(lat - lastLat, planes[0], planes[1]); plane(lon - lastLon, planes[2], planes[3])
                    }
                    prevRef = -1
                }
                seen[key] = k
                k++
                lastLat = lat
                lastLon = lon
            }
        }
        out.block(counts); out.block(kinds); out.block(jumps)
        for (p in planes) out.block(p)
        out.block(escapes); out.block(refFirst); out.block(refNext)
    }

    fun decode(src: LeSource, count: Int): List<Geom> {
        val counts = src.block()
        val kinds = src.block()
        val jumps = src.block()
        val planes = Array(4) { src.block() }
        val escapes = src.block()
        val refFirst = src.block()
        val refNext = src.block()
        val sizes = IntArray(count) { counts.varCount() }
        val total = sizes.fold(0L) { a, n -> a + n }
        if (total > kinds.remaining) throw TileFormatException("$total vertices for ${kinds.remaining} kinds")
        val allLat = IntArray(total.toInt())
        val allLon = IntArray(total.toInt())
        fun plane(lo: LeSource, hi: LeSource): Long {
            val z0 = (lo.u8() or (hi.u8() shl 8)).toLong()
            val z = if (z0 == ESCAPE.toLong()) escapes.varU() else z0
            return (z ushr 1) xor -(z and 1)
        }
        fun grid(v: Long): Int = if (v < Int.MIN_VALUE || v > Int.MAX_VALUE) throw TileFormatException("a coordinate of $v off the grid") else v.toInt()
        val out = ArrayList<Geom>(count)
        var k = 0
        var lastLat = 0L
        var lastLon = 0L
        for (n in sizes) {
            val a = IntArray(n)
            val o = IntArray(n)
            var prevRef = -1
            for (i in 0 until n) {
                when (val kind = kinds.u8()) {
                    1 -> {
                        val t = if (prevRef >= 0) {
                            val v = prevRef - 1 + refNext.varZ()
                            if (v < 0 || v >= k) throw TileFormatException("a repeat of vertex $v at vertex $k")
                            v.toInt()
                        } else {
                            val back = refFirst.varU()
                            if (back < 1 || back > k) throw TileFormatException("a repeat $back back at vertex $k")
                            (k - back).toInt()
                        }
                        a[i] = allLat[t]; o[i] = allLon[t]
                        prevRef = t
                    }
                    0 -> {
                        if (i == 0) {
                            a[i] = grid(lastLat + jumps.varZ()); o[i] = grid(lastLon + jumps.varZ())
                        } else {
                            a[i] = grid(lastLat + plane(planes[0], planes[1])); o[i] = grid(lastLon + plane(planes[2], planes[3]))
                        }
                        prevRef = -1
                    }
                    else -> throw TileFormatException("a vertex kind of $kind")
                }
                allLat[k] = a[i]; allLon[k] = o[i]
                k++
                lastLat = a[i].toLong()
                lastLon = o[i].toLong()
            }
            out.add(Geom(a, o, null))
        }
        for (b in listOf(counts, kinds, jumps, escapes, refFirst, refNext) + planes) if (b.remaining != 0) throw TileFormatException("a geometry column has bytes left over")
        return out
    }
}


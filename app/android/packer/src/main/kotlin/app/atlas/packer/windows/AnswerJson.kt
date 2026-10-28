package app.atlas.packer.windows

import app.atlas.packer.pbf.TagView

/**
 * Bytes of a window's answer: compact JSON with Overpass's values (output_formats/output_json.cc of `out geom`): ids
 * and references as integers, coordinates with exactly seven decimals (what `std::fixed << setprecision(7)` prints of
 * a stored coordinate: its 1e-7 degree integer as a decimal), strings as escape_cstr writes them (a quote, a backslash,
 * a line feed, a tab and a carriage return escaped, another control character printed `?`, every other character as
 * its UTF-8). Grows as it is written; reused window after window.
 */
class JsonOut(capacity: Int = 1 shl 16) {
    var bytes = ByteArray(capacity)
        private set
    var size = 0
        private set

    fun clear() {
        size = 0
    }

    private fun room(n: Int) {
        if (size + n <= bytes.size) return
        var c = bytes.size.toLong() * 2
        while (c < size.toLong() + n) c *= 2
        require(c <= MAX) { "an answer over ${MAX shr 20} MiB" }
        bytes = bytes.copyOf(c.toInt())
    }

    fun byte(b: Int): JsonOut {
        room(1)
        bytes[size++] = b.toByte()
        return this
    }

    /** [s], which is ASCII and needs no escape (the answer's own keys and words). */
    fun ascii(s: String): JsonOut {
        room(s.length)
        for (c in s) bytes[size++] = c.code.toByte()
        return this
    }

    fun bytes(b: ByteArray, from: Int = 0, n: Int = b.size): JsonOut {
        room(n)
        System.arraycopy(b, from, bytes, size, n)
        size += n
        return this
    }

    fun long(v: Long): JsonOut {
        if (v == Long.MIN_VALUE) return ascii(v.toString())
        room(20)
        var x = v
        if (x < 0) {
            bytes[size++] = '-'.code.toByte()
            x = -x
        }
        val start = size
        do {
            bytes[size++] = ('0'.code + (x % 10).toInt()).toByte()
            x /= 10
        } while (x > 0)
        reverse(start, size - 1)
        return this
    }

    /** A coordinate of [e7] 1e-7 degrees with seven decimals: -0.0000001, 0.0000000, 52.3745000. */
    fun e7(e7: Long): JsonOut {
        room(24)
        var x = e7
        if (x < 0) {
            bytes[size++] = '-'.code.toByte()
            x = -x
        }
        long(x / 10_000_000)
        bytes[size++] = '.'.code.toByte()
        var f = x % 10_000_000
        val start = size
        for (i in 0 until 7) {
            bytes[size++] = ('0'.code + (f % 10).toInt()).toByte()
            f /= 10
        }
        reverse(start, size - 1)
        return this
    }

    /** A double Overpass prints with seven decimals: a stored coordinate's (or ±180), so its nearest 1e-7 degree integer. */
    fun fixed7(v: Double): JsonOut = e7(Math.round(v * 1e7))

    /** [s] quoted, as escape_cstr writes it, in UTF-8. */
    fun string(s: String): JsonOut {
        room(s.length * 3 + 2)
        bytes[size++] = '"'.code.toByte()
        var i = 0
        while (i < s.length) {
            val c = s[i].code
            when {
                c == '"'.code -> { room(2); bytes[size++] = '\\'.code.toByte(); bytes[size++] = '"'.code.toByte() }
                c == '\\'.code -> { room(2); bytes[size++] = '\\'.code.toByte(); bytes[size++] = '\\'.code.toByte() }
                c == '\n'.code -> { room(2); bytes[size++] = '\\'.code.toByte(); bytes[size++] = 'n'.code.toByte() }
                c == '\t'.code -> { room(2); bytes[size++] = '\\'.code.toByte(); bytes[size++] = 't'.code.toByte() }
                c == '\r'.code -> { room(2); bytes[size++] = '\\'.code.toByte(); bytes[size++] = 'r'.code.toByte() }
                c < 0x20 -> bytes[size++] = '?'.code.toByte()
                c < 0x80 -> bytes[size++] = c.toByte()
                c < 0x800 -> {
                    bytes[size++] = (0xC0 or (c shr 6)).toByte()
                    bytes[size++] = (0x80 or (c and 0x3F)).toByte()
                }
                Character.isHighSurrogate(s[i]) && i + 1 < s.length && Character.isLowSurrogate(s[i + 1]) -> {
                    val cp = Character.toCodePoint(s[i], s[i + 1])
                    room(4)
                    bytes[size++] = (0xF0 or (cp shr 18)).toByte()
                    bytes[size++] = (0x80 or ((cp shr 12) and 0x3F)).toByte()
                    bytes[size++] = (0x80 or ((cp shr 6) and 0x3F)).toByte()
                    bytes[size++] = (0x80 or (cp and 0x3F)).toByte()
                    i++
                }
                // a lone surrogate (never in a string read from UTF-8): the replacement character, as a decoder gives it
                Character.isSurrogate(s[i]) -> {
                    bytes[size++] = 0xEF.toByte()
                    bytes[size++] = 0xBF.toByte()
                    bytes[size++] = 0xBD.toByte()
                }
                else -> {
                    bytes[size++] = (0xE0 or (c shr 12)).toByte()
                    bytes[size++] = (0x80 or ((c shr 6) and 0x3F)).toByte()
                    bytes[size++] = (0x80 or (c and 0x3F)).toByte()
                }
            }
            i++
        }
        room(1)
        bytes[size++] = '"'.code.toByte()
        return this
    }

    fun toByteArray(): ByteArray = bytes.copyOf(size)

    private fun reverse(from: Int, to: Int) {
        var a = from
        var b = to
        while (a < b) {
            val t = bytes[a]
            bytes[a] = bytes[b]
            bytes[b] = t
            a++
            b--
        }
    }

    companion object {
        /** The largest answer a window's JSON may be (a gzip member's length in the index is 32 bits). */
        const val MAX = 1L shl 30
    }
}

/** The pieces of Overpass's JSON that do not depend on a window: an element's tags, the head and the foot. */
object AnswerJson {
    /** The answer's generator: not Overpass's own, so what made it is plain. */
    const val GENERATOR = "Atlas packer from an OpenStreetMap PBF extract"

    /** output_json.cc's copyright line. */
    const val COPYRIGHT = "The data included in this document is from www.openstreetmap.org. The data is made available under ODbL."

    /** The answer up to its first element, its data as of [date] (`YYYY-MM-DDTHH:MM:SSZ`). */
    fun head(date: String): ByteArray = JsonOut(512).ascii("{\"version\":0.6,\"generator\":").string(GENERATOR)
        .ascii(",\"osm3s\":{\"timestamp_osm_base\":").string(date).ascii(",\"copyright\":").string(COPYRIGHT)
        .ascii("},\"elements\":[").toByteArray()

    /** The answer after its last element. */
    val FOOT: ByteArray = "]}".toByteArray(Charsets.US_ASCII)

    /**
     * The inside of an element's `"tags":{…}`: its [tags] as `"key":"value"` pairs in key order by the bytes of their
     * UTF-8 (the order Overpass keeps them in), into [out]; nothing for an element without tags (Overpass prints no key
     * then). Returns false, writing nothing, when a key comes twice, which no element of OpenStreetMap's database holds
     * and the core refuses.
     */
    fun tags(tags: TagView, out: JsonOut): Boolean {
        val n = tags.size
        if (n == 0) return true
        val order = sortedKeys(tags)
        for (i in 1 until n) if (tags.key(order[i]) == tags.key(order[i - 1])) return false
        for (i in 0 until n) {
            if (i > 0) out.byte(','.code)
            out.string(tags.key(order[i])).byte(':'.code).string(tags.value(order[i]))
        }
        return true
    }

    /** The tags' indices in key order by code point, which is the order of the keys' UTF-8 bytes. */
    private fun sortedKeys(tags: TagView): IntArray {
        val n = tags.size
        val order = IntArray(n) { it }
        // insertion sort: an element has a few tags, and the keys' own order is often close
        for (i in 1 until n) {
            val k = order[i]
            val key = tags.key(k)
            var j = i - 1
            while (j >= 0 && byCodePoint(tags.key(order[j]), key) > 0) {
                order[j + 1] = order[j]
                j--
            }
            order[j + 1] = k
        }
        return order
    }

    /** Compares two strings by their code points (UTF-8's byte order), not by UTF-16's units. */
    fun byCodePoint(a: String, b: String): Int {
        val n = minOf(a.length, b.length)
        for (i in 0 until n) {
            val x = a[i]
            val y = b[i]
            if (x != y) return fixed(x) - fixed(y)
        }
        return a.length - b.length
    }

    // UTF-16 units in code point order: a surrogate (U+D800 to U+DFFF, a code point past U+FFFF) after U+E000 to U+FFFF
    private fun fixed(c: Char): Int = c.code.let { if (it >= 0xD800) (if (it < 0xE000) it + 0x2000 else it - 0x800) else it }
}

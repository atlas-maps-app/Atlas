package app.atlas.render.py

/**
 * One canonical copy of each distinct string a load reads, so a scene keeps "building", "yes" or a street's name once however
 * many elements carry it. Strings up to [INTERN_MAX] characters are the JVM's own interned copy, the same object as a literal
 * in the code, so the engine's lookups (`t["building"]`, `hw == "residential"`) meet by identity; longer ones are pooled here only.
 *
 * One pool serves one load or one snapshot decode, then goes with it (what it handed out stays in the scene). Not thread-safe.
 */
class StringPool(expected: Int = 1024) {
    private var table: Array<String?> = arrayOfNulls(tableSize(expected))
    private var count = 0

    /** Distinct strings held. */
    val size: Int get() = count

    /** The canonical copy of [s]. */
    fun of(s: String): String {
        val h = s.hashCode()
        val t = table
        val mask = t.size - 1
        var b = spread(h) and mask
        while (true) {
            val e = t[b] ?: return add(canonical(s), b)
            if (e === s || (e.hashCode() == h && e == s)) return e
            b = (b + 1) and mask
        }
    }

    /** The canonical copy of the characters [chars] from [start] until [end], made into a String only when it is new. */
    fun of(chars: CharArray, start: Int, end: Int): String {
        var h = 0
        for (i in start until end) h = 31 * h + chars[i].code
        val n = end - start
        val t = table
        val mask = t.size - 1
        var b = spread(h) and mask
        while (true) {
            val e = t[b] ?: return add(canonical(String(chars, start, n)), b)
            if (e.length == n && e.hashCode() == h && same(e, chars, start)) return e
            b = (b + 1) and mask
        }
    }

    private fun same(e: String, chars: CharArray, start: Int): Boolean {
        for (i in e.indices) if (e[i] != chars[start + i]) return false
        return true
    }

    private fun canonical(s: String): String = if (s.length <= INTERN_MAX) s.intern() else s

    private fun add(s: String, bucket: Int): String {
        table[bucket] = s
        if (++count * 4 > table.size * 3) grow()
        return s
    }

    private fun grow() {
        val old = table
        val t = arrayOfNulls<String>(old.size * 2)
        val mask = t.size - 1
        for (e in old) {
            if (e == null) continue
            var b = spread(e.hashCode()) and mask
            while (t[b] != null) b = (b + 1) and mask
            t[b] = e
        }
        table = t
    }

    private companion object {
        /** Strings up to this length are interned with the JVM, so literals meet them by identity. */
        const val INTERN_MAX = 24

        fun tableSize(expected: Int): Int {
            var n = 16
            while (n * 3 < expected.coerceAtLeast(1) * 4) n = n shl 1
            return n
        }

        fun spread(h: Int): Int {
            val m = h * -0x61c88647
            return m xor (m ushr 16)
        }
    }
}

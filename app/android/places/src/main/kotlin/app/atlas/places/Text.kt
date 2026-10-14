package app.atlas.places

import java.text.Normalizer

/** Text helpers for search: folding for case- and diacritic-insensitive matching, and edit distance. */
object Text {
    private val marks = Regex("\\p{Mn}+")
    private val spaces = Regex("[\\s\\p{P}]+")

    /**
     * Lower case, accents removed, compatibility forms unfolded (full-width letters, ligatures), "ß" as "ss",
     * punctuation as single spaces: "Café-Bar" -> "cafe bar", "ｃａｆｅ" -> "cafe".
     */
    fun fold(s: String): String {
        if (s.isEmpty()) return s
        var ascii = true
        for (c in s) if (c.code >= 128) { ascii = false; break }
        val base = if (ascii) s.lowercase() else Normalizer.normalize(s, Normalizer.Form.NFKD).lowercase().replace(marks, "")
        val sb = StringBuilder(base.length)
        for (c in base) {
            when (c) {
                'ß' -> sb.append("ss")
                'ø' -> sb.append('o')
                'æ' -> sb.append("ae")
                'œ' -> sb.append("oe")
                'ł' -> sb.append('l')
                'đ' -> sb.append('d')
                'ı' -> sb.append('i')
                '’', '\'', 'ʼ' -> {}
                else -> sb.append(c)
            }
        }
        return sb.toString().replace(spaces, " ").trim()
    }

    /**
     * Damerau–Levenshtein distance (optimal string alignment) between [a] and the first [bLength] characters of [b],
     * or [max] + 1 as soon as it must exceed [max]. Allocates three small rows; fine for names and terms.
     */
    fun distance(a: CharSequence, b: CharSequence, max: Int = Int.MAX_VALUE, bLength: Int = b.length): Int {
        val n = a.length
        val m = bLength.coerceAtMost(b.length)
        if (kotlin.math.abs(n - m) > max) return max + 1
        if (n == 0) return m
        if (m == 0) return n
        var prev2 = IntArray(m + 1)
        var prev = IntArray(m + 1) { it }
        var cur = IntArray(m + 1)
        for (i in 1..n) {
            cur[0] = i
            var rowMin = cur[0]
            val ca = a[i - 1]
            for (j in 1..m) {
                val cb = b[j - 1]
                val cost = if (ca == cb) 0 else 1
                var v = minOf(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + cost)
                if (i > 1 && j > 1 && ca == b[j - 2] && a[i - 2] == cb) v = minOf(v, prev2[j - 2] + 1)
                cur[j] = v
                if (v < rowMin) rowMin = v
            }
            if (rowMin > max) return max + 1
            val t = prev2; prev2 = prev; prev = cur; cur = t
        }
        return prev[m]
    }

    /**
     * How far [q] is from being a prefix of [c]: the least Damerau–Levenshtein distance (optimal string alignment)
     * between [q] and any prefix of [c], so a letter left out or added while typing counts once ("restur" is 1 from
     * "restaurant"). Returns [max] + 1 as soon as it must exceed [max].
     */
    fun prefixDistance(q: CharSequence, c: CharSequence, max: Int): Int {
        val n = q.length
        // a prefix longer than n + max cannot be within max of q
        val m = minOf(c.length, n + max)
        if (n == 0) return 0
        if (m == 0) return if (n <= max) n else max + 1
        var prev2 = IntArray(m + 1)
        var prev = IntArray(m + 1) { it }
        var cur = IntArray(m + 1)
        for (i in 1..n) {
            cur[0] = i
            var rowMin = i
            val ca = q[i - 1]
            for (j in 1..m) {
                val cb = c[j - 1]
                var v = minOf(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + if (ca == cb) 0 else 1)
                if (i > 1 && j > 1 && ca == c[j - 2] && q[i - 2] == cb) v = minOf(v, prev2[j - 2] + 1)
                cur[j] = v
                if (v < rowMin) rowMin = v
            }
            if (rowMin > max) return max + 1
            val t = prev2; prev2 = prev; prev = cur; cur = t
        }
        var best = max + 1
        for (j in 0..m) if (prev[j] < best) best = prev[j]
        return best
    }

    /** How many typos a query of this length may carry: none up to 3 letters, one up to 7, then two. */
    fun typosAllowed(length: Int): Int = when {
        length <= 3 -> 0
        length <= 7 -> 1
        else -> 2
    }
}

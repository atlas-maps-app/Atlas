package app.atlas.search

import app.atlas.places.Categories
import app.atlas.places.Cuisine
import app.atlas.places.Presets
import app.atlas.places.Text

/** What the full-text index holds for a place, already folded (lower case, no accents, no punctuation). */
internal class SearchDocument(val name: String, val other: String, val terms: String, val addr: String)

/** The text side of search: which tags hold names, what goes in the index, and folding of queries. */
internal object SearchText {
    private val NAME_KEYS = setOf(
        "name", "alt_name", "old_name", "official_name", "short_name", "loc_name", "int_name", "nat_name", "reg_name",
        "brand", "bridge:name", "tunnel:name",
    )
    private val OTHER_KEYS = listOf("operator", "network", "ref", "branch", "artist_name", "subject")
    private const val MAX_TERMS_PER_LANG = 8

    /** True for keys that hold a name or a translation of one ("name:nl", "alt_name", "brand:fr"). */
    fun isNameKey(key: String): Boolean {
        if (key in NAME_KEYS) return true
        val colon = key.indexOf(':')
        if (colon < 0) return false
        if (key.endsWith(":wikidata") || key.endsWith(":wikipedia") || "etymology" in key || "pronunciation" in key) return false
        return key.substring(0, colon) in NAME_KEYS
    }

    /** Every name string of a place, unfolded: name, its translations, alternatives, brand. */
    fun names(tags: Map<String, String>): List<String> {
        val out = ArrayList<String>(4)
        tags["name"]?.let { out += it }
        for ((k, v) in tags) if (k != "name" && isNameKey(k)) out += v
        return out
    }

    fun document(tags: Map<String, String>, preset: String?, category: String?, langs: List<String>): SearchDocument {
        val name = LinkedHashSet<String>()
        for (n in names(tags)) for (part in n.split(';')) Text.fold(part).takeIf { it.isNotEmpty() }?.let { name += it }

        val other = LinkedHashSet<String>()
        for (k in OTHER_KEYS) tags[k]?.let { v -> for (part in v.split(';')) Text.fold(part).takeIf { it.isNotEmpty() }?.let { other += it } }
        for (c in Cuisine.values(tags)) {
            other += Text.fold(c.replace('_', ' '))
            for (l in langs) other += Text.fold(Cuisine.name(c, l))
        }

        val terms = LinkedHashSet<String>()
        preset?.let { Presets.byId(it) }?.let { p ->
            for (l in langs) {
                terms += Text.fold(p.name(l))
                for (t in p.terms(l).take(MAX_TERMS_PER_LANG)) terms += Text.fold(t)
            }
        }
        category?.let { Categories.byId(it) }?.let { c -> for (l in langs) terms += Text.fold(c.name(l)) }

        val addr = StringBuilder()
        for (k in ADDR_KEYS) tags[k]?.let { if (addr.isNotEmpty()) addr.append(' '); addr.append(Text.fold(it)) }
        return SearchDocument(name.joinToString(" "), other.joinToString(" "), terms.joinToString(" "), addr.toString())
    }

    private val ADDR_KEYS = listOf("addr:street", "addr:place", "addr:housenumber", "addr:unit", "addr:postcode", "addr:city")

    /** The folded words of [s]. */
    fun words(s: String): List<String> {
        val f = Text.fold(s)
        if (f.isEmpty()) return emptyList()
        return f.split(' ').filter { it.isNotEmpty() }
    }
}

/** Trigrams of words, packed in a long each, for the typo-tolerant vocabulary lookup. */
internal object Grams {
    private const val START = '\u0001'
    private const val END = '\u0002'

    /** The trigrams of `^word$`; without the end marker when [prefix] (the word is still being typed). */
    fun of(word: String, prefix: Boolean = false): LongArray {
        val s = if (prefix) "$START$word" else "$START$word$END"
        if (s.length < 3) return LongArray(0)
        val out = LongArray(s.length - 2)
        for (i in 0 until s.length - 2) out[i] = pack(s[i], s[i + 1], s[i + 2])
        return out.distinct().toLongArray()
    }

    private fun pack(a: Char, b: Char, c: Char): Long = (a.code.toLong() shl 32) or (b.code.toLong() shl 16) or c.code.toLong()
}

/**
 * How well a query's words fit a name: exact beats prefix beats a typo, and a name the query covers fully beats one
 * it only touches. Scores run 0 (no fit) to 1 (the very name).
 */
internal object NameMatch {
    /** Score of query [words] (folded; the last may be unfinished when [typing]) against one folded [name]. */
    fun score(words: List<String>, name: String, typing: Boolean = true): Double {
        if (words.isEmpty() || name.isEmpty()) return 0.0
        val q = words.joinToString(" ")
        if (name == q) return 1.0
        if (typing && name.startsWith(q) && (name.length == q.length || name[q.length] == ' ')) return 0.97
        if (typing && name.startsWith(q)) return 0.93
        val nameWords = name.split(' ')
        var sum = 0.0
        var used = 0
        for ((i, w) in words.withIndex()) {
            val last = i == words.size - 1
            var best = 0.0
            for (n in nameWords) {
                val s = word(w, n, typing && last || w.length >= 3)
                if (s > best) best = s
            }
            if (best <= 0.0) return 0.0
            sum += best
            used++
        }
        val coverage = minOf(1.0, used.toDouble() / nameWords.size)
        return (sum / words.size) * (0.82 + 0.15 * coverage)
    }

    /** One word against one word: 1 exact, ~0.9 prefix, ~0.7 one typo, less for two. */
    fun word(q: String, n: String, prefixOk: Boolean): Double {
        if (q == n) return 1.0
        if (prefixOk && q.length >= 2 && n.startsWith(q)) return 0.86 + 0.1 * q.length / n.length
        val k = Text.typosAllowed(q.length)
        if (k == 0) return 0.0
        val d = Text.distance(q, n, k)
        if (d <= k) return 0.78 - 0.14 * d
        // a word still being typed with a typo: from five letters, or "hema" would find every "hea…" and "dema…"
        if (prefixOk && n.length > q.length && q.length >= 5) {
            val p = Text.prefixDistance(q, n, k)
            if (p <= k) return 0.7 - 0.12 * p
        }
        return 0.0
    }

    /** The best score of [words] over a place's names and, a little lower, over its other text (operator, address, kind). */
    fun best(words: List<String>, names: List<String>, others: List<String>, typing: Boolean = true): Double {
        var best = 0.0
        for (n in names) best = maxOf(best, score(words, Text.fold(n), typing))
        if (best >= 0.97) return best
        // words spread over name and other fields: "albert heijn prinsengracht"
        if (others.isNotEmpty()) {
            val all = (names + others).joinToString(" ") { Text.fold(it) }
            val mixed = score(words, all, typing)
            if (mixed > 0) best = maxOf(best, mixed * 0.8)
        }
        return best
    }
}

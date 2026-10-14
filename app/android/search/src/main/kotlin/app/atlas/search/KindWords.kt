package app.atlas.search

import app.atlas.places.Categories
import app.atlas.places.Cuisine
import app.atlas.places.Presets
import java.util.TreeMap

/** One kind of place a store holds: a preset, a category or a cuisine, by its dictionary id, and how many places are it. */
internal class Kind(val type: Int, val id: Int, val name: String, val count: Int) {
    /** The token the text index holds for it on each of its places' line. */
    val token: String get() = token(type, id)

    companion object {
        const val PRESET = 0
        const val CATEGORY = 1
        const val CUISINE = 2

        private val PREFIX = arrayOf("_p", "_c", "_u")

        /** A kind's token: `_` and a letter (folded text never holds `_`), then its value id. */
        fun token(type: Int, id: Int): String = "${PREFIX[type]}$id"
    }
}

/**
 * The words that name the kinds of places a store holds, in its languages: preset names and search terms, category
 * names, cuisines. The text index holds one short token per kind on each place rather than these words, so "molen
 * cafe" finds the café called De Molen: the query's words are turned into the tokens of the kinds they name.
 */
internal class KindWords(kinds: List<Kind>, langs: List<String>) {
    private class Hit(val token: String, val count: Int)

    private val words = TreeMap<String, MutableList<Hit>>()

    /** Every word of four letters or more with how many places it names: for the typo vocabulary. */
    val vocabulary = HashMap<String, Long>()

    init {
        for (k in kinds) {
            val texts = LinkedHashSet<String>()
            when (k.type) {
                Kind.PRESET -> Presets.byId(k.name)?.let { p ->
                    for (l in langs) {
                        texts += p.name(l)
                        texts += p.terms(l).take(MAX_TERMS_PER_LANG)
                    }
                }
                Kind.CATEGORY -> Categories.byId(k.name)?.let { c -> for (l in langs) texts += c.name(l) }
                Kind.CUISINE -> {
                    texts += k.name.replace('_', ' ')
                    for (l in langs) texts += Cuisine.name(k.name, l)
                }
            }
            val seen = HashSet<String>()
            for (t in texts) for (w in SearchText.indexWords(t)) {
                if (w.length < 2 || !seen.add(w)) continue
                words.getOrPut(w) { ArrayList(2) }.add(Hit(k.token, k.count))
                if (w.length >= 4 && w.none { it.isDigit() }) vocabulary[w] = (vocabulary[w] ?: 0L) + k.count
            }
        }
    }

    /** True when [word] is a word of a kind's names (or begins one, when [prefix]). */
    fun knows(word: String, prefix: Boolean): Boolean =
        if (prefix) words.subMap(word, true, word + '\uffff', false).isNotEmpty() else words.containsKey(word)

    /**
     * The tokens of the kinds [word] names (the kinds with a word it begins, while [prefix]), the commonest first, at
     * most [max]. Words of fewer than three letters name nothing: every kind has a word beginning so.
     */
    fun tokens(word: String, prefix: Boolean, max: Int = MAX_TOKENS): List<String> {
        if (word.length < MIN_WORD) return emptyList()
        val exact = words[word].orEmpty()
        val more = if (prefix) words.subMap(word, false, word + '\uffff', false).values.flatten() else emptyList()
        val out = LinkedHashSet<String>()
        for (h in exact.sortedByDescending { it.count }) out += h.token
        for (h in more.sortedByDescending { it.count }) out += h.token
        return out.take(max)
    }

    companion object {
        /** The text index's kind tokens of a place: its preset, its category and its cuisines (interned as needed). */
        fun tokensOf(preset: Int?, category: Int?, tags: Map<String, String>, dict: Dictionaries): List<String> {
            val out = ArrayList<String>(4)
            preset?.let { out += Kind.token(Kind.PRESET, it) }
            category?.let { out += Kind.token(Kind.CATEGORY, it) }
            tags["cuisine"]?.let { c ->
                for (part in c.split(';')) {
                    val t = part.trim()
                    if (t.isNotEmpty() && t.length <= TagIndex.MAX_VALUE) out += Kind.token(Kind.CUISINE, dict.internValue(t))
                }
            }
            return out
        }

        const val MIN_WORD = 3
        const val MAX_TOKENS = 40
        private const val MAX_TERMS_PER_LANG = 8
    }
}

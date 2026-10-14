package app.atlas.search

import app.atlas.places.Text

/**
 * The typo vocabulary of a store: its distinct words of four letters or more (names, streets, the kinds it holds),
 * numbered in order of length, and for every trigram the list of words that have it as delta-coded varints. A
 * misspelt word finds its near spellings through the trigrams it shares with them, then an exact edit distance.
 */
internal class Vocabulary(private val store: PlaceStore) {
    /** The first word id of each length; index = length. Read once from the store. */
    private var firstOfLength: IntArray? = null
    private var counts = IntArray(0)
    private val touched = IntArrayList(256)

    /** Recent answers: a search asks for the same words again (its places and its streets, keystroke after keystroke). */
    private val recent = object : LinkedHashMap<String, List<String>>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, List<String>>?) = size > RECENT
    }

    /** Forgets what was read (after a rebuild). */
    fun forget() {
        firstOfLength = null
        recent.clear()
    }

    /**
     * Words within Damerau–Levenshtein distance 1 of [word] from 4 letters and 2 from 8 (or, while [prefix], whose
     * beginning is), the nearest first, at most [max]. Called under the store's lock.
     */
    fun near(word: String, prefix: Boolean, max: Int): List<String> {
        val key = "$word\u0000$prefix\u0000$max"
        recent[key]?.let { return it }
        return find(word, prefix, max).also { recent[key] = it }
    }

    private fun find(word: String, prefix: Boolean, max: Int): List<String> {
        val k = Text.typosAllowed(word.length)
        if (k == 0) return emptyList()
        val grams = Grams.of(word, prefix)
        if (grams.isEmpty()) return emptyList()
        val lengths = lengths()
        if (lengths.size <= 1) return emptyList()
        val needed = (grams.size - 3 * k).coerceAtLeast(1)
        val minLen = (word.length - k).coerceAtLeast(MIN_LENGTH)
        val maxLen = if (prefix) lengths.size - 1 else minOf(word.length + k, lengths.size - 1)
        if (minLen > maxLen) return emptyList()
        val low = lengths[minLen]
        val high = if (maxLen + 1 < lengths.size) lengths[maxLen + 1] - 1 else lengths[lengths.size - 1] - 1
        if (high < low) return emptyList()
        if (counts.size <= high) counts = IntArray(lengths[lengths.size - 1] + 1)
        touched.clear()
        for (g in grams) {
            store.stmt("SELECT words FROM gram WHERE g = ?1") { st ->
                st.bindLong(1, g)
                if (st.step()) {
                    val src = ByteSource(st.getBlob(0))
                    var id = 0
                    while (src.hasMore) {
                        id += src.int()
                        if (id < low) continue
                        if (id > high) break
                        if (counts[id]++ == 0) touched.add(id)
                    }
                }
            }
        }
        val candidates = IntArrayList(64)
        for (i in 0 until touched.size) {
            val id = touched[i]
            if (counts[id] >= needed) candidates.add(id)
        }
        // the most trigrams shared first, as the old SQL ordered them; then the words themselves
        val order = (0 until candidates.size).map { candidates[it] }.sortedByDescending { counts[it] }.take(MAX_CANDIDATES)
        for (i in 0 until touched.size) counts[touched[i]] = 0
        if (order.isEmpty()) return emptyList()
        val found = ArrayList<Triple<String, Int, Long>>()
        store.stmt("SELECT w, n FROM word WHERE id IN (SELECT value FROM json_each(?1))") { st ->
            st.bindText(1, order.joinToString(",", "[", "]"))
            while (st.step()) {
                val w = st.getText(0)
                // the word itself, and while typing every word it begins, the plain query finds already
                if (w == word || (prefix && w.startsWith(word))) continue
                val d = if (prefix) Text.prefixDistance(word, w, k) else Text.distance(word, w, k)
                if (d <= k) found += Triple(w, d, st.getLong(1))
            }
        }
        // nearest spelling first, then the same first letter (rarely the one mistyped), then the closest length (a typo
        // rarely adds a syllable), then the commonest
        return found.sortedWith(
            compareBy<Triple<String, Int, Long>> { it.second }
                .thenBy { if (it.first[0] == word[0]) 0 else 1 }
                .thenBy { kotlin.math.abs(it.first.length - word.length) }
                .thenByDescending { it.third },
        ).take(max).map { it.first }
    }

    private fun lengths(): IntArray {
        firstOfLength?.let { return it }
        // lengths[L] = the first id of a word of length L or more; the last entry is one past the last id
        var maxLen = 0
        var last = 0
        store.stmt("SELECT max(length(w)), max(id) FROM word") { st ->
            if (st.step() && !st.isNull(0)) {
                maxLen = st.getLong(0).toInt()
                last = st.getLong(1).toInt()
            }
        }
        val out = IntArray(maxLen + 2) { last + 1 }
        if (last > 0) {
            store.stmt("SELECT length(w) AS l, min(id) FROM word GROUP BY l") { st ->
                while (st.step()) out[st.getLong(0).toInt()] = st.getLong(1).toInt()
            }
            for (l in maxLen downTo 0) out[l] = minOf(out[l], out[l + 1])
        }
        firstOfLength = out
        return out
    }

    companion object {
        const val MIN_LENGTH = 4
        private const val MAX_CANDIDATES = 1000
        private const val RECENT = 256

        /** True when [term] of a text index belongs in the vocabulary: a word of four letters or more, no digits, no kind token. */
        fun keeps(term: String): Boolean = term.length >= MIN_LENGTH && term[0] != '_' && term.none { it.isDigit() }

        /** Writes [words] (word → how many places use it) as the store's vocabulary, replacing what was there. */
        fun write(store: PlaceStore, words: Map<String, Long>) {
            store.exec("DELETE FROM word")
            store.exec("DELETE FROM gram")
            val sorted = words.keys.sortedWith(compareBy<String> { it.length }.thenBy { it })
            val postings = HashMap<Long, IntArrayList>(sorted.size * 2)
            val insertWord = store.prepare("INSERT INTO word(id, w, n) VALUES (?1, ?2, ?3)")
            try {
                for ((i, w) in sorted.withIndex()) {
                    val id = i + 1
                    insertWord.bindLong(1, id.toLong())
                    insertWord.bindText(2, w)
                    insertWord.bindLong(3, words.getValue(w))
                    insertWord.step()
                    insertWord.reset()
                    for (g in Grams.of(w)) postings.getOrPut(g) { IntArrayList(4) }.add(id)
                }
            } finally {
                insertWord.close()
            }
            val insertGram = store.prepare("INSERT INTO gram(g, words) VALUES (?1, ?2)")
            val sink = ByteSink(1024)
            try {
                for ((g, ids) in postings) {
                    sink.reset()
                    var prev = 0
                    for (j in 0 until ids.size) {
                        sink.varint(ids[j] - prev)
                        prev = ids[j]
                    }
                    insertGram.bindLong(1, g)
                    insertGram.bindBlob(2, sink.toByteArray())
                    insertGram.step()
                    insertGram.reset()
                }
            } finally {
                insertGram.close()
            }
        }
    }
}

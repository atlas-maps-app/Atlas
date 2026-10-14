package app.atlas.search

import java.text.Normalizer

/** One word of a typed query: folded [text], the [raw] text it came from, and where. */
internal data class Word(val text: String, val raw: String, val start: Int, val end: Int) {
    val isNumber: Boolean get() = text.isNotEmpty() && text.all { it.isDigit() || it == '.' || it == ',' } && text.first().isDigit() && text.last().isDigit()
    val isPunctuation: Boolean get() = text == "," || text == "&" || text == "+" || text == ";"
}

/**
 * Splits natural-language queries into folded words: lower case, accents and apostrophes gone ("d'une" is "d une"),
 * hyphens as spaces ("step-free" is "step free"); commas, "&", "+" and ";" are words of their own; times ("22:00",
 * "22h30"), decimals ("2,5") and "24/7" stay whole.
 */
internal object Words {
    fun split(text: String): List<Word> {
        val out = ArrayList<Word>()
        var i = 0
        val n = text.length
        while (i < n) {
            val c = text[i]
            when {
                c.isWhitespace() -> i++
                c == ',' || c == '&' || c == '+' || c == ';' -> {
                    out += Word(c.toString(), c.toString(), i, i + 1)
                    i++
                }
                c.isLetterOrDigit() -> {
                    val start = i
                    while (i < n && continues(text, i)) i++
                    val raw = text.substring(start, i)
                    out += Word(fold(raw), raw, start, i)
                }
                else -> i++
            }
        }
        return out.filter { it.text.isNotEmpty() }
    }

    /** True while position [i] still belongs to the word: letters, digits, and separators inside numbers. */
    private fun continues(t: String, i: Int): Boolean {
        val c = t[i]
        if (c.isLetterOrDigit()) return true
        if (i == 0 || i + 1 >= t.length) return false
        val before = t[i - 1]
        val after = t[i + 1]
        // 22:00, 2.5, 2,5 (a comma between digits with no space), 24/7
        return before.isDigit() && after.isDigit() && (c == ':' || c == '.' || c == ',' || c == '/')
    }

    /** Lower case without accents; ß as ss and other letters as their plain forms. */
    fun fold(s: String): String {
        val lower = s.lowercase()
        val base = if (lower.all { it.code < 128 }) lower else Normalizer.normalize(lower, Normalizer.Form.NFD).replace(MARKS, "")
        val sb = StringBuilder(base.length)
        for (c in base) {
            when (c) {
                'ß' -> sb.append("ss")
                'ø' -> sb.append('o')
                'æ' -> sb.append("ae")
                'œ' -> sb.append("oe")
                'ł' -> sb.append('l')
                else -> sb.append(c)
            }
        }
        return sb.toString()
    }

    private val MARKS = Regex("\\p{Mn}+")
}

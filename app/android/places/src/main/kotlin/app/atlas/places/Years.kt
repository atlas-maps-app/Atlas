package app.atlas.places

import java.util.Locale

/**
 * The years an OpenStreetMap date says, in the forms the wiki lists for `start_date`: "1890", "1890-05-12", "~1890",
 * "1890s", "early 1890s", "C19", "mid C19", "before 1900", "after 1850", "1885..1890". A comparison ("built before
 * 1900") takes one year of it ([year]): the year itself, the middle of a span (a building of the 19th century was
 * built before 1900), or the bound of "before" and "after".
 */
object Years {
    /** The first and last year [value] could be (either open for "before" and "after"), or null when it says none. */
    fun of(value: String): Pair<Double, Double>? {
        val v = value.trim().lowercase(Locale.ROOT).removePrefix("~").removePrefix("ca.").removePrefix("circa").removePrefix("c.").trim()
        if (v.isEmpty()) return null
        RANGE.matchEntire(v)?.let { m -> return of(m.groupValues[1])?.first?.let { a -> of(m.groupValues[2])?.second?.let { b -> a to b } } }
        BEFORE.matchEntire(v)?.let { m -> return of(m.groupValues[1])?.let { Double.NEGATIVE_INFINITY to it.first - 1 } }
        AFTER.matchEntire(v)?.let { m -> return of(m.groupValues[1])?.let { it.second + 1 to Double.POSITIVE_INFINITY } }
        val (part, rest) = PARTS.firstNotNullOfOrNull { (word, p) -> if (v.startsWith("$word ")) p to v.removePrefix("$word ").trim() else null } ?: (null to v)
        CENTURY.matchEntire(rest)?.let { m ->
            val c = m.groupValues[1].ifEmpty { m.groupValues[2] }.toInt()
            return part(c * 100.0 - 99, c * 100.0, part)
        }
        DECADE.matchEntire(rest)?.let { m ->
            val d = m.groupValues[1].toDouble()
            return part(d, d + 9, part)
        }
        DATE.matchEntire(rest)?.let { m ->
            val y = m.groupValues[1].toInt()
            // the Dutch building register's "year unknown"
            if (y == UNKNOWN_BAG) return null
            return y.toDouble() to y.toDouble()
        }
        return null
    }

    /** The one year a comparison takes of [value]: itself, the middle of a span, the bound of "before" or "after". */
    fun year(value: String): Double? {
        val (a, b) = of(value) ?: return null
        return when {
            a.isInfinite() -> b
            b.isInfinite() -> a
            else -> (a + b) / 2
        }
    }

    /** True for keys whose values are dates, compared by their years. */
    fun isDateKey(key: String): Boolean =
        key in DATE_KEYS || key.endsWith(":start_date") || key.endsWith(":end_date") || key.startsWith("check_date")

    /** The first, middle or last third of a span ("early C19"), or all of it. */
    private fun part(from: Double, to: Double, part: Int?): Pair<Double, Double> {
        if (part == null) return from to to
        val third = (to - from + 1) / 3
        val a = from + kotlin.math.floor(third * part)
        val b = if (part == 2) to else from + kotlin.math.floor(third * (part + 1)) - 1
        return a to b
    }

    private val DATE_KEYS = setOf("start_date", "end_date", "opening_date", "construction_date", "year_of_construction", "date", "survey:date", "building:start_date")

    /** The Dutch building register (BAG) dates a building whose year it does not know 1005. */
    private const val UNKNOWN_BAG = 1005

    private val PARTS = listOf("early" to 0, "mid" to 1, "late" to 2)
    private val RANGE = Regex("""^(.+?)\s*(?:\.\.|–|—|-(?=\s*\d{3,4}\b(?!-)))\s*(.+)$""")
    private val BEFORE = Regex("""^(?:before|<)\s*(.+)$""")
    private val AFTER = Regex("""^(?:after|>)\s*(.+)$""")
    private val CENTURY = Regex("""^(?:c\s*(\d{1,2})|(\d{1,2})(?:st|nd|rd|th)\s+century)$""")
    private val DECADE = Regex("""^(\d{3}0)s$""")
    private val DATE = Regex("""^(\d{3,4})(?:-\d{2}(?:-\d{2})?)?$""")
}

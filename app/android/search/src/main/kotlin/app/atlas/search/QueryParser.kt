package app.atlas.search

import java.time.ZonedDateTime

/** Which syntax a query was read in. */
enum class QuerySyntax { POWER, NATURAL }

/** What a chip of the query builder stands for. */
enum class ChipKind { KIND, PROPERTY, PREFERENCE, OPEN, DISTANCE, AREA, SORT, LIMIT, NAME, TYPE, ID, NEWER, FILTER }

/**
 * One chip of the query builder (☕ café, wifi, ⏱ open, ↳ 200 m 🚲 dock): a symbol, a short label, and the value the
 * user can edit (a tag filter's text, metres, a time, an area's name).
 */
data class Chip(
    val kind: ChipKind,
    /** A symbol id for `Symbols.icon`. */
    val symbol: String,
    val label: String,
    val value: String,
    /** 0 for the main row; 1, 2… for each ↳ "near" row in order. */
    val row: Int = 0,
    /** Which step of a "then" plan it belongs to. */
    val step: Int = 0,
    /** For a property: "without"; for a near row: "away from". */
    val negated: Boolean = false,
    /** Where the words it stands for begin in [ParsedQuery.text], or -1 when it has no words of its own there. */
    val start: Int = -1,
    /** Where those words end (exclusive), or -1. */
    val end: Int = -1,
) {
    /** The characters of the query text this chip highlights ("A ▢quiet café▢ with an ▢outlet▢"), or null. */
    val span: IntRange? get() = if (start >= 0 && end > start) start until end else null
}

/**
 * A query as read from text: the query tree (one per step of a "then" plan), the chips that show it, the words it
 * did not understand, and how sure it is (0..1).
 */
data class ParsedQuery(
    val text: String,
    val steps: List<Query>,
    val chips: List<Chip>,
    val unknown: List<String>,
    val confidence: Double,
    val syntax: QuerySyntax,
    /** The language most of the words were in, when it could tell. */
    val language: String? = null,
    /** True when the text is surely a query, not a name: it has properties, times, nearness, an area, an order or steps. */
    val structured: Boolean = false,
) {
    /** The query, or the first step of a plan. */
    val query: Query get() = steps.firstOrNull() ?: Query.All

    val isPlan: Boolean get() = steps.size > 1

    companion object {
        fun empty(text: String) = ParsedQuery(text, emptyList(), emptyList(), emptyList(), 0.0, QuerySyntax.NATURAL)
    }
}

/**
 * Reads what people type into queries, offline, in two syntaxes:
 * - power syntax, close to Overpass Turbo's wizard: `amenity=cafe and outdoor_seating=yes`,
 *   `shop=bicycle near amenity=bicycle_rental 200m`, `"socket:type2_combo:output">=150kW within 2km`, `type:node`, `id:n123`;
 * - natural language in English, Dutch, German and French: "quiet café with an outlet, then dinner",
 *   "café 200 m from a bike dock", "apotheek nu open", "Restaurant mit Terrasse in der Nähe".
 */
object QueryParser {
    /** Parses [text]; [lang] is the app's language (it weighs kind names), [now] settles "today" and "tomorrow". */
    fun parse(text: String, lang: String = "en", now: ZonedDateTime? = null): ParsedQuery {
        val t = text.trim()
        if (t.isEmpty()) return ParsedQuery.empty(text)
        return if (PowerParser.looksLikePower(t)) PowerParser.parse(t, lang, now) else NaturalParser(lang, now).parse(t)
    }
}

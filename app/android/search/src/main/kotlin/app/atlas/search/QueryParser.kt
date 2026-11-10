package app.atlas.search

import java.time.ZonedDateTime

/** Which syntax a query was read in. */
enum class QuerySyntax { POWER, NATURAL }

/** What a chip of the query builder stands for. */
enum class ChipKind { KIND, PROPERTY, PREFERENCE, OPEN, DISTANCE, AREA, SORT, LIMIT, NAME, TYPE, ID, NEWER, FILTER, INSIDE, CONTAINS, ASK, AIM }

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
    /** What it asks for: the places, how many there are, or their opening hours side by side. */
    val ask: Ask = Ask.LIST,
    /** Something to do it names ("a lake to swim"): an [Intents] id, which parts the results by how surely each serves it. */
    val intent: String? = null,
    /** A way from one place to another it asks for ("from the station to the Westerkerk"). */
    val route: RouteAsk? = null,
    /** The tag key a [Ask.FACT] question asks of its place ("height", "start_date", "phone", "toilets"). */
    val asked: String? = null,
    /**
     * Words after a kind read as where it is ("boulangerie amsterdam"), not said to be an area: the engine reads the
     * text again without ([QueryParser.parse]'s `guessAreas`) when no place of that name is known ("bakker Jansen").
     */
    val guessedArea: String? = null,
    /** What a count is told by place ("how many bars per neighbourhood"): `neighbourhood` or `town`; null for one total. */
    val per: String? = null,
) {
    /** The query, or the first step of a plan. */
    val query: Query get() = steps.firstOrNull() ?: Query.All

    val isPlan: Boolean get() = steps.size > 1

    /**
     * True when the text is answered as the query it reads as; otherwise it is searched for as words (names,
     * addresses, brands, kinds), its query only a suggestion ("Café de Jaren" is a name, not cafés and years).
     */
    val isQuery: Boolean get() = steps.isNotEmpty() && (syntax == QuerySyntax.POWER || (structured && confidence >= QUERY_CONFIDENCE))

    companion object {
        /** How sure a natural reading must be to be answered as a query. */
        const val QUERY_CONFIDENCE = 0.6

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
    /**
     * Parses [text]; [lang] is the app's language (it weighs kind names), [now] settles "today" and "tomorrow",
     * [country] (ISO, the searcher's) picks among brands of the same name; with [guessAreas], words right after a
     * kind may be where it is ([ParsedQuery.guessedArea]).
     */
    fun parse(text: String, lang: String = "en", now: ZonedDateTime? = null, country: String? = null, guessAreas: Boolean = true): ParsedQuery {
        val t = text.trim()
        if (t.isEmpty()) return ParsedQuery.empty(text)
        if (PowerParser.looksLikePower(t) || KEY_ONLY.matches(t)) return PowerParser.parse(t, lang, now)
        // a brand of several words is a brand, not a way ("AH to go")
        if (Lexicon.brand(t, country) == null) {
            // addresses one after the other, a way calling at each
            RouteWords.stops(t)?.let { r -> route(t, r, lang, now, country)?.let { return it } }
            RouteWords.read(t)?.let { r -> route(t, r, lang, now, country)?.let { return it } }
        }
        return NaturalParser(lang, now, country, guessAreas).parse(t)
    }

    /** A tag key and its subkeys on its own ("toilets:wheelchair"): the places that have it. */
    private val KEY_ONLY = Regex("""^[a-z_]+(:[a-z_]+)+$""")

    /**
     * A way asked for, its end read as a query of its own ("route to the nearest pharmacy"). A bare "A to B" is a way
     * only when neither side names a kind of place, something to do or a time ("a lake to swim" is not); a way said
     * only by how it keeps clear only when its end is a place ("Los Angeles no highways", not "cafés without tolls"); a
     * list of addresses only when its streets are no kinds of place ("bus 26, 125").
     */
    private fun route(text: String, r: RouteWords.Found, lang: String, now: ZonedDateTime?, country: String?): ParsedQuery? {
        val to = NaturalParser(lang, now, country).parse(r.to)
        // "coffee on my way to work": coffee is what is searched for, along the way
        r.along?.let { subject ->
            val q = NaturalParser(lang, now, country).parse(subject)
            return q.copy(text = text, route = RouteAsk(r.from, r.to, r.mode, r.fromWord, along = true, prefs = r.prefs, via = r.via))
        }
        if (!r.explicit) {
            val from = NaturalParser(lang, now, country).parse(r.from ?: return null)
            // "a place to sit", "things to do": the words make a phrase of their own across the "to"
            if (asks(to) || asks(from) || asks(NaturalParser(lang, now, country).parse(text))) return null
        }
        if (r.implied && asks(to)) return null
        if (r.streets.any { s -> !RouteWords.streetLike(s) && asks(NaturalParser(lang, now, country).parse(s)) }) return null
        return to.copy(text = text, chips = emptyList(), structured = false, confidence = 1.0, route = RouteAsk(r.from, r.to, r.mode, r.fromWord, prefs = r.prefs, via = r.via))
    }

    private fun asks(p: ParsedQuery): Boolean = p.intent != null || p.ask != Ask.LIST ||
        p.chips.any { it.kind in setOf(ChipKind.KIND, ChipKind.AIM, ChipKind.OPEN, ChipKind.SORT, ChipKind.PROPERTY, ChipKind.ASK, ChipKind.DISTANCE) }
}

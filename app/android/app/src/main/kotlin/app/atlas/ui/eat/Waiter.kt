package app.atlas.ui.eat

import app.atlas.search.EvalContext
import app.atlas.search.Hit
import app.atlas.search.OfflineEvaluator
import app.atlas.search.ParsedQuery
import app.atlas.search.Query
import app.atlas.search.QueryParser
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * "Ask the waiter": what you type or say, read by `:search`'s [QueryParser] ("vegan ramen open late", "terras met
 * bier", "pizza near the Westerkerk"), kept to places to eat and drink, and answered offline over the area's places.
 */
object Waiter {
    /** The answer: what was understood, the best places (ranked by `:search`), how many matched, and how long it took. */
    data class Answer(val text: String, val parsed: ParsedQuery, val hits: List<Hit>, val total: Int, val micros: Long) {
        /** True when the parser understood nothing but a name. */
        val onlyName: Boolean get() = parsed.query is Query.Name
    }

    /** Answers [text] at the table's moment [at] in [zone]. */
    fun ask(text: String, area: EatArea, at: LocalDateTime, zone: ZoneId, lang: String, limit: Int = 8): Answer {
        val zoned = at.atZone(zone)
        val parsed = QueryParser.parse(text, lang, zoned)
        val query = Query.and(Query.Tags(EatKind.FILTER), parsed.query)
        val ctx = EvalContext(location = area.centre, now = zoned, lang = lang, limit = limit, hours = area.hours)
        val result = OfflineEvaluator(area.source).run(query, ctx)
        return Answer(text, parsed, result.hits, result.total, result.micros)
    }
}

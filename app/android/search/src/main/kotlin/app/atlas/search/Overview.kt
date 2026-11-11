package app.atlas.search

import app.atlas.places.OpenSpan
import app.atlas.places.OpenState
import app.atlas.places.OpeningHoursContext
import app.atlas.places.Presets
import app.atlas.places.Symbols
import java.time.DayOfWeek
import java.time.ZonedDateTime

/**
 * "How many bins in this town": how many places a query finds, how many of those surely (their tags say so, not
 * only maybe), and how many of each kind, the most first; told by place where asked ("bars per neighbourhood"), how
 * many in each, the most first ([groups]).
 */
data class CountAnswer(val total: Int, val sure: Int, val kinds: List<KindCount>, val groups: List<KindCount> = emptyList())

/** One kind among counted places ("Bench · 412"). */
data class KindCount(val label: String, val symbol: String, val count: Int)

/**
 * "Overview of bakery opening times": the places' hours side by side. Per day of this week, how many open and when
 * the first opens, the last closes, and the middle ones open and close ([days]); today, how many are open in each
 * hour ([byHour], 24 counts); and each place's week ([places]), its brand's hours where it keeps none of its own.
 * [unknown] places say no hours at all.
 */
data class HoursAnswer(val days: List<DaySpread>, val byHour: List<Int>, val known: Int, val unknown: Int, val places: List<PlaceWeek>)

/** One day of an [HoursAnswer], in minutes after midnight (a close after midnight is past 1440); null when none open. */
data class DaySpread(val day: DayOfWeek, val open: Int, val earliest: Int?, val latest: Int?, val opensMiddle: Int?, val closesMiddle: Int?)

/** One place's week, Monday to Sunday; [inferred] when the hours are its brand's, not its own. */
data class PlaceWeek(val hit: Hit, val week: List<List<OpenSpan>>, val inferred: Boolean)

/**
 * "How tall is the Westertoren", "does the HEMA have toilets": what a place found says of the fact asked, [value] as
 * tagged under [key]; both null where it says nothing.
 */
data class FactAnswer(val hit: Hit, val key: String?, val value: String?)

/** The answers to the questions a query asks rather than the places it lists: how many, their hours, a fact of them. */
object Overview {
    /** What [hits] say of [asked] ("height", "start_date", "toilets"), in their order, those that say it first. */
    fun facts(hits: List<Hit>, asked: String): List<FactAnswer> {
        val keys = FACT_KEYS[asked] ?: listOf(asked)
        return hits.map { h ->
            val k = keys.firstOrNull { it in h.record.tags }
            FactAnswer(h, k, k?.let { h.record.tags[it] })
        }.sortedBy { if (it.value == null) 1 else 0 }
    }

    /** Where each fact is tagged, the likeliest first. */
    private val FACT_KEYS = mapOf(
        "height" to listOf("height", "building:height", "est_height", "ele"),
        "start_date" to listOf("start_date", "construction_date", "opening_date"),
        "architect" to listOf("architect", "architect:wikidata"),
        "phone" to listOf("phone", "contact:phone", "contact:mobile", "mobile"),
        "website" to listOf("website", "contact:website", "url"),
        "capacity" to listOf("capacity", "capacity:bicycle", "capacity:car"),
        "toilets" to listOf("toilets", "toilets:access", "toilets:wheelchair"),
        "internet_access" to listOf("internet_access", "internet_access:fee"),
    )

    /** How many of [records] there are, [sure] of them surely, by kind in [lang]. */
    fun count(records: List<PlaceRecord>, sure: Int, lang: String): CountAnswer {
        val kinds = records.groupBy { r -> Presets.match(r.tags)?.let { it.id to it.name(lang) } ?: (KIND_UNKNOWN to KIND_UNKNOWN) }
            .map { (k, list) -> KindCount(k.second, Symbols.symbolFor(list.first().tags), list.size) }
            .sortedByDescending { it.count }
        return CountAnswer(records.size, sure, kinds)
    }

    /** The hours of [hits] this week and today, at [now]; a branch without hours takes its brand's from [book]. */
    fun hours(hits: List<Hit>, now: ZonedDateTime, context: OpeningHoursContext, book: HoursBook): HoursAnswer {
        val today = now.toLocalDate()
        val places = ArrayList<PlaceWeek>()
        var unknown = 0
        for (h in hits) {
            val known = book.of(h.record)
            if (known == null) { unknown++; continue }
            places += PlaceWeek(h, known.hours.weekTable(today, context), !known.confirmed)
        }
        val days = DayOfWeek.entries.map { day ->
            val spans = places.map { p -> p.week[day.ordinal].filter { it.state == OpenState.OPEN } }.filter { it.isNotEmpty() }
            val opens = spans.map { s -> s.minOf { it.start } }.sorted()
            val closes = spans.map { s -> s.maxOf { it.end } }.sorted()
            DaySpread(day, spans.size, opens.firstOrNull(), closes.lastOrNull(), opens.getOrNull(opens.size / 2), closes.getOrNull(closes.size / 2))
        }
        val todays = places.map { p -> p.week[today.dayOfWeek.ordinal].filter { it.state == OpenState.OPEN } }
        // yesterday's spans that run past midnight count this morning
        val yesterday = places.map { p -> p.week[today.dayOfWeek.minus(1).ordinal].filter { it.state == OpenState.OPEN && it.end > DAY } }
        val byHour = (0 until 24).map { hour ->
            val m = hour * 60 + 30
            todays.indices.count { i -> todays[i].any { m in it.start until it.end } || yesterday[i].any { m + DAY in it.start until it.end } }
        }
        return HoursAnswer(days, byHour, places.size, unknown, places)
    }

    private const val DAY = 1440
    private const val KIND_UNKNOWN = "?"
}

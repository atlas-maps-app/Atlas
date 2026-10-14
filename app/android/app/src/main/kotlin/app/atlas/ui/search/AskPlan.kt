package app.atlas.ui.search

import app.atlas.geo.Geo
import app.atlas.geo.LatLon
import app.atlas.model.Place
import app.atlas.places.Categories
import app.atlas.places.OpeningHours
import app.atlas.places.OpeningHoursContext
import app.atlas.search.Chip
import app.atlas.search.ChipKind
import app.atlas.search.Hit
import app.atlas.search.ParsedQuery
import app.atlas.search.Query
import app.atlas.search.QueryResult
import app.atlas.search.SortBy
import app.atlas.ui.kit.WalkTime
import java.time.Duration
import java.time.LocalDateTime

/**
 * One stop of a plan: the step it answers, the place chosen for it (null when none fits), when you get there and how
 * long you are likely to stay, the walk from the stop before, and how many other places would have fitted.
 */
data class AskStop(
    val step: Int,
    /** The words of the step's main chip ("café", "dinner"), for the node and for "nothing found for …". */
    val label: String,
    val symbol: String,
    val hit: Hit?,
    val arrive: LocalDateTime,
    val stay: Duration,
    /** Metres walked from the stop before (from where you are for the first). */
    val walkM: Double,
    /** Places that matched the step, before the time and the refinements chose one. */
    val matched: Int = 0,
    /** True when the place's hours say it is open from [arrive] for the whole [stay]; null when they are not mapped. */
    val openThen: Boolean? = null,
) {
    val place: Place? get() = hit?.place
    val leave: LocalDateTime get() = arrive.plus(stay)
}

/** A plan read from one sentence: its stops in order, and its totals. */
data class AskPlan(val stops: List<AskStop>) {
    /** Metres on foot, start to last stop. */
    val walkM: Double get() = stops.filter { it.hit != null }.sumOf { it.walkM }
    val found: Int get() = stops.count { it.hit != null }
    val end: LocalDateTime? get() = stops.lastOrNull { it.hit != null }?.leave
}

/** A way to change the plan without retyping: shorter walks, only places surely open then, step-free. */
enum class Refine { CLOSER, OPEN, STEP_FREE }

/**
 * Turns a sentence's plan ("a quiet café with an outlet, then dinner") into a timed outing: you leave at a time from a
 * point, walk to each stop (straight line × 1.3 at 4.8 km/h), stay a usual while for its kind, and walk on. Each step
 * is searched around the stop before; of its matches, the best one that is not closed when you arrive is chosen (with
 * [Refine.OPEN], only one whose hours say it is open the whole stay). The stays are estimates, said as such.
 */
object AskPlanner {
    /** How many matches of a step are weighed for the time of arrival. */
    private const val CONSIDER = 12

    /**
     * The plan of [parsed]'s steps, each run by [search] (the step's query, searched around a point) from [from] at
     * [start]; [hours] reads opening hours with the country's holidays.
     */
    fun plan(
        parsed: ParsedQuery,
        from: LatLon,
        start: LocalDateTime,
        hours: OpeningHoursContext,
        refine: Set<Refine> = emptySet(),
        search: (Query, LatLon) -> QueryResult,
    ): AskPlan {
        var at = from
        var time = start
        val stops = ArrayList<AskStop>()
        for ((i, step) in refined(parsed.steps, refine).withIndex()) {
            val main = mainChip(parsed, i)
            val result = search(step, at)
            val chosen = choose(result.hits.take(CONSIDER), at, time, hours, strict = Refine.OPEN in refine)
            val hit = chosen?.first
            val walk = hit?.let { Geo.distance(at, it.record.location) } ?: 0.0
            val arrive = time.plusSeconds(WalkTime.seconds(walk).toLong())
            val stay = hit?.let { stayAt(it.tags) } ?: Duration.ZERO
            stops += AskStop(i, main?.label ?: parsed.text, main?.symbol ?: hit?.symbol ?: "ui-map-pin", hit, arrive, stay, walk, result.total, chosen?.second)
            if (hit != null) {
                at = hit.record.location
                time = arrive.plus(stay)
            }
        }
        return AskPlan(stops)
    }

    /**
     * The first of [hits] (in the evaluator's order) that is open for its whole stay when walked to from [from] at
     * [time]; else, unless [strict], the first whose hours are not mapped. With it, whether its hours say open.
     */
    fun choose(hits: List<Hit>, from: LatLon, time: LocalDateTime, hours: OpeningHoursContext, strict: Boolean): Pair<Hit, Boolean?>? {
        var unknown: Hit? = null
        for (h in hits) {
            val arrive = time.plusSeconds(WalkTime.seconds(Geo.distance(from, h.record.location)).toLong())
            when (openFor(h.tags, arrive, stayAt(h.tags), hours)) {
                true -> return h to true
                null -> if (unknown == null) unknown = h
                false -> Unit
            }
        }
        return if (strict) null else unknown?.let { it to null }
    }

    /** True when [tags]' hours say open from [arrive] for [stay], false when not, null when they are not mapped. */
    fun openFor(tags: Map<String, String>, arrive: LocalDateTime, stay: Duration, hours: OpeningHoursContext): Boolean? {
        val oh = OpeningHours.parseOrNull(tags["opening_hours"]) ?: return null
        if (oh.isAlwaysOpen) return true
        val st = oh.status(arrive, hours)
        if (st.unknown) return null
        if (!st.isOpen) return false
        val closes = st.changesAt ?: return true
        // Closing a little before the usual stay ends still works; closing on arrival does not.
        return !closes.isBefore(arrive.plus(stay.dividedBy(2)))
    }

    /** The chip that names what step [step] looks for (its kind), else its first chip. */
    fun mainChip(parsed: ParsedQuery, step: Int): Chip? {
        val own = parsed.chips.filter { it.step == step && it.row == 0 }
        return own.firstOrNull { it.kind == ChipKind.KIND } ?: own.firstOrNull { it.kind != ChipKind.DISTANCE }
    }

    /** [steps] changed by the refinements: step-free only, nearest first. */
    fun refined(steps: List<Query>, refine: Set<Refine>): List<Query> = steps.map { q ->
        var r = q
        if (Refine.STEP_FREE in refine) r = Query.and(r, Query.tags("wheelchair~^(yes|designated)$"))
        if (Refine.CLOSER in refine) r = Query.Sort(r, SortBy.DISTANCE)
        r
    }

    /** How long people usually stay at a place of these tags: a coffee, a meal, a drink, a film, an errand. */
    fun stayAt(tags: Map<String, String>): Duration {
        val minutes = when (tags["amenity"]) {
            "cafe", "ice_cream" -> 45L
            "restaurant", "food_court" -> 90L
            "fast_food" -> 25L
            "bar", "pub", "biergarten" -> 75L
            "cinema", "theatre" -> 150L
            "library" -> 60L
            "charging_station" -> 40L
            "parking", "bicycle_parking" -> 5L
            else -> when {
                tags["tourism"] == "museum" || tags["tourism"] == "gallery" -> 90L
                tags["leisure"] == "park" -> 40L
                "shop" in tags -> 20L
                Categories.groupOf(tags)?.id == "eat" -> 60L
                else -> 30L
            }
        }
        return Duration.ofMinutes(minutes)
    }
}

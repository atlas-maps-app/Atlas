package app.atlas.ui.eat

import app.atlas.places.TagFilter
import app.atlas.providers.Freshness
import app.atlas.providers.OverpassArea
import app.atlas.providers.Trends
import app.atlas.search.EvalContext
import app.atlas.search.Hit
import app.atlas.search.OfflineEvaluator
import app.atlas.search.OpenTime
import app.atlas.search.OverpassCompiler
import app.atlas.search.Query
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId

/** A wish rather than a rule: places that have it rank higher, the others stay. Each is a tag rule. */
enum class Wish(val rule: String, val symbol: String) {
    DOGS("dog~^(yes|leashed|outside)$", "ui-paw"),
    QUIET("background_music=no or music=no or live_music=no or noise=quiet", "ui-moon"),
    KIDS("kids_area=yes or highchair=yes or changing_table=yes or kids=yes", "ui-baby"),
    TAKEAWAY("takeaway~^(yes|only)$", "ui-shopping-bag"),
    RESERVABLE("reservation~^(yes|recommended|required)$", "ui-calendar");

    val filter: TagFilter by lazy { TagFilter.parse(rule) }
}

/** What the "ranked by" stamps order the served places by, in the order they were pressed. */
enum class RankKey(val symbol: String) {
    RATING("ui-star"), WALK("ui-walk"), TRENDING("ui-flame"), LATE("ui-moon"), NEW("ui-sparkles"), PRICE("ui-euro"),
}

/** The lines of the guest check, in the order they are written. */
enum class Line { TIME, CUISINES, NOT, FEATURES, ACCESS, WISHES, PAYMENT, WALK }

/**
 * The order: when (the table's time unless [time] is set), for how many, for how long; cuisines (any of them); what
 * not to have; what the place must have; wishes; price and payment; how far to walk; and how to rank. Every hard
 * line is a `:search` [Query]; [OrderModel] runs them in turn so each line shows how many places are left.
 */
data class OrderPad(
    val time: LocalTime? = null,
    val party: Int = 2,
    val minutes: Int = 120,
    val cuisines: List<String> = emptyList(),
    /** Cuisines not wanted, and [FAST_FOOD] for fast food. */
    val not: List<String> = emptyList(),
    val outside: Boolean = false,
    val vegan: Boolean = false,
    val vegetarian: Boolean = false,
    val stepFree: Boolean = false,
    val wishes: Set<Wish> = emptySet(),
    /** 1..4 ($ to $$$$), from the rating providers; null for any. */
    val maxPrice: Int? = null,
    val card: Boolean = false,
    val maxWalk: Int? = null,
    val rank: List<RankKey> = listOf(RankKey.RATING, RankKey.WALK),
) {
    /** True when [line] says anything (the time line always does). */
    fun has(line: Line): Boolean = when (line) {
        Line.TIME -> true
        Line.CUISINES -> cuisines.isNotEmpty()
        Line.NOT -> not.isNotEmpty()
        Line.FEATURES -> outside || vegan || vegetarian
        Line.ACCESS -> stepFree
        Line.WISHES -> wishes.isNotEmpty()
        Line.PAYMENT -> maxPrice != null || card
        Line.WALK -> maxWalk != null
    }

    companion object {
        const val FAST_FOOD = "fast_food"

        val DATE_NIGHT = OrderPad(
            time = LocalTime.of(19, 30), party = 2, minutes = 120, not = listOf(FAST_FOOD),
            wishes = setOf(Wish.QUIET, Wish.RESERVABLE), maxWalk = 15, rank = listOf(RankKey.RATING, RankKey.WALK),
        )
        val QUICK_LUNCH = OrderPad(
            time = LocalTime.of(12, 30), party = 1, minutes = 45, wishes = setOf(Wish.TAKEAWAY), maxWalk = 5, rank = listOf(RankKey.WALK),
        )
        val KIDS = OrderPad(
            time = LocalTime.of(17, 30), party = 4, minutes = 90, stepFree = true, wishes = setOf(Wish.KIDS), maxWalk = 10,
            rank = listOf(RankKey.WALK, RankKey.RATING),
        )
    }
}

/** One line's result: how many places are left after it (soft lines leave the count as it was). */
data class LineCount(val line: Line, val count: Int)

/** The order evaluated: per-line counts, the ranked places, the query and what it all cost. */
data class OrderResult(
    val lines: List<LineCount>,
    val base: Int,
    val served: List<Hit>,
    val total: Int,
    val micros: Long,
    val query: Query,
    /** How many of the places left have a price level from a provider: "≤ $$" can only judge those. */
    val pricesKnown: Int,
)

/** Turns an [OrderPad] into `:search` queries, evaluates them offline over an area, ranks and exports them. */
object OrderModel {
    private val MEALS = TagFilter.parse("amenity~^(restaurant|fast_food|food_court)$")
    private val STEP_FREE = TagFilter.parse("wheelchair~^(yes|designated)$")
    private val OUTSIDE = TagFilter.parse("outdoor_seating~^(yes|terrace|garden|patio|veranda|pedestrian_zone|sidewalk|street|roof)$ or beer_garden=yes")
    private val VEGAN = TagFilter.parse("diet:vegan~^(yes|only|limited)$ or cuisine=vegan")
    private val VEGETARIAN = TagFilter.parse("diet:vegetarian~^(yes|only|limited)$ or diet:vegan~^(yes|only)$ or cuisine=vegetarian or cuisine=vegan")
    private val CARD = TagFilter.Or(EatPlace.CARD_KEYS.map { TagFilter.Equals("payment:$it", "yes") })

    /** `cuisine=` clauses for a canonical cuisine and the other spellings mappers use ("kebab": "doner", "kebap"). */
    private fun cuisineClauses(cuisine: String): List<TagFilter> = CuisineTaxonomy.spellings(cuisine).map { TagFilter.Equals("cuisine", it) }

    /** The moment the order is for: [OrderPad.time] on the table's day ([at]), or the table's own time. */
    fun momentOf(pad: OrderPad, at: LocalDateTime): LocalDateTime {
        val t = pad.time ?: return at
        val day: LocalDate = at.toLocalDate()
        val then = day.atTime(t)
        // A time well before the table's means the next day (lunch tomorrow, asked in the evening).
        return if (then.isBefore(at.minusMinutes(30))) then.plusDays(1) else then
    }

    /** The query part of each hard line, in order; null for lines that say nothing or are soft. */
    fun parts(pad: OrderPad, at: LocalDateTime, area: EatArea): List<Pair<Line, Query?>> {
        val moment = momentOf(pad, at)
        val time: Query = if (pad.minutes > 1) {
            Query.and(Query.OpenAt(OpenTime.At(moment)), Query.OpenAt(OpenTime.At(moment.plusMinutes(pad.minutes - 1L))))
        } else Query.OpenAt(OpenTime.At(moment))
        return listOf(
            Line.TIME to Query.and(Query.Tags(MEALS), time),
            Line.CUISINES to pad.cuisines.takeIf { it.isNotEmpty() }?.let { cs -> Query.Tags(TagFilter.Or(cs.flatMap(::cuisineClauses))) },
            Line.NOT to pad.not.takeIf { it.isNotEmpty() }?.let { ns ->
                Query.Not(Query.Tags(TagFilter.Or(ns.flatMap { if (it == OrderPad.FAST_FOOD) listOf(TagFilter.Equals("amenity", "fast_food")) else cuisineClauses(it) })))
            },
            Line.FEATURES to listOfNotNull(
                OUTSIDE.takeIf { pad.outside }, VEGAN.takeIf { pad.vegan }, VEGETARIAN.takeIf { pad.vegetarian && !pad.vegan },
            ).takeIf { it.isNotEmpty() }?.let { Query.Tags(if (it.size == 1) it[0] else TagFilter.And(it)) },
            Line.ACCESS to if (pad.stepFree) Query.Tags(STEP_FREE) else null,
            Line.WISHES to pad.wishes.takeIf { it.isNotEmpty() }?.let { ws -> Query.and(ws.map { Query.Prefer(Query.Tags(it.filter)) }) },
            Line.PAYMENT to if (pad.card) Query.Tags(CARD) else null,
            Line.WALK to pad.maxWalk?.let { Query.Within(area.centre, walkMetres(it)) },
        )
    }

    /** The straight-line radius a walk of [minutes] covers in town (the kit's walking speed and detour). */
    fun walkMetres(minutes: Int): Double = minutes * 60.0 * (4.8 / 3.6) / 1.3

    /** The whole order as one query (hard lines and wishes), for the evaluator and the `{} QL` export. */
    fun query(pad: OrderPad, at: LocalDateTime, area: EatArea): Query = Query.and(parts(pad, at, area).mapNotNull { it.second })

    /**
     * Runs the order over [area]: each line in turn (so the guest check shows 214 → 31 → 12 …), then the whole,
     * ranked by the pad's stamps. Price is judged from the providers' price levels; places without one stay.
     */
    fun evaluate(pad: OrderPad, at: LocalDateTime, zone: ZoneId, area: EatArea, ratings: AreaRatings, lang: String, serve: Int = 12): OrderResult {
        val started = System.nanoTime()
        val moment = momentOf(pad, at)
        val ctx = EvalContext(
            location = area.centre, now = moment.atZone(zone), lang = lang, limit = Int.MAX_VALUE, hours = area.hours,
            rating = { p -> ratings.stars(p.key) },
        )
        val evaluator = OfflineEvaluator(area.source)
        val parts = parts(pad, at, area)
        val counts = ArrayList<LineCount>()
        val acc = ArrayList<Query>()
        var base = 0
        var last = 0
        // The price belongs to the payment line: from there on, places dearer than asked are left out.
        val priceOk = { key: String -> pad.maxPrice == null || (ratings[key]?.priceLevel?.let { it <= pad.maxPrice } ?: true) }
        for ((line, q) in parts) {
            if (line != Line.TIME && !pad.has(line)) continue
            if (q != null) acc += q
            val n = when {
                line == Line.WISHES -> last
                line.ordinal >= Line.PAYMENT.ordinal && pad.maxPrice != null -> evaluator.matches(Query.and(acc), ctx).count { priceOk(it.place.key) }
                else -> evaluator.matches(Query.and(acc), ctx).size
            }
            if (line == Line.TIME) base = n
            last = n
            counts += LineCount(line, n)
        }
        val result = evaluator.run(Query.and(acc), ctx)
        val priced = result.hits.filter { priceOk(it.place.key) }
        val ranked = rank(priced, pad, moment, area, ratings)
        return OrderResult(
            lines = counts,
            base = base,
            served = ranked.take(serve),
            total = ranked.size,
            micros = (System.nanoTime() - started) / 1000,
            query = Query.and(acc),
            pricesKnown = result.hits.count { priceOf(it, ratings) != null },
        )
    }

    private fun priceOf(h: Hit, ratings: AreaRatings): Int? = ratings[h.place.key]?.priceLevel

    /** When [h] opened (or opens, or was first mapped), if OSM says it is new; null otherwise. */
    private fun newSince(h: Hit, today: LocalDate): LocalDate? =
        Trends.freshness(h.place, today)?.takeIf { it.kind in NEW_KINDS }?.date

    private val NEW_KINDS = setOf(Freshness.Kind.NEW, Freshness.Kind.OPENING_SOON, Freshness.Kind.NEWLY_MAPPED)

    /** Orders [hits]: wishes met first, then each stamp in the order pressed, then `:search`'s own score. */
    fun rank(hits: List<Hit>, pad: OrderPad, moment: LocalDateTime, area: EatArea, ratings: AreaRatings): List<Hit> {
        var cmp: Comparator<Hit> = compareByDescending { h -> h.why.count { it.startsWith("prefer:") } }
        for (k in pad.rank) {
            cmp = when (k) {
                RankKey.RATING -> cmp.thenByDescending { ratings.stars(it.place.key) ?: -1.0 }
                RankKey.WALK -> cmp.thenBy { it.distance ?: Double.MAX_VALUE }
                RankKey.TRENDING -> cmp.thenByDescending { ratings[it.place.key]?.trend?.velocity ?: -1.0 }
                RankKey.LATE -> cmp.thenByDescending { h -> area[h.place.key]?.let { closesOrMax(it, moment, area) } ?: LocalDateTime.MIN }
                RankKey.NEW -> cmp.thenByDescending { h -> newSince(h, moment.toLocalDate()) ?: LocalDate.MIN }
                RankKey.PRICE -> cmp.thenBy { ratings[it.place.key]?.priceLevel ?: Int.MAX_VALUE }
            }
        }
        return hits.sortedWith(cmp.thenByDescending { it.score })
    }

    /** When [p] closes after [at]; 24/7 places never close, so they rank as latest. */
    private fun closesOrMax(p: EatPlace, at: LocalDateTime, area: EatArea): LocalDateTime =
        if (p.alwaysOpen) LocalDateTime.MAX else p.closesAt(at, area.hours) ?: at

    /** Overpass QL for the order around [area]'s centre (as far as the walk line allows, else the area's radius). */
    fun ql(pad: OrderPad, at: LocalDateTime, area: EatArea): String {
        val radius = pad.maxWalk?.let { walkMetres(it) } ?: area.radiusM
        return OverpassCompiler.compile(query(pad, at, area), OverpassArea.Around(area.centre, radius), me = area.centre, timeoutSeconds = 30)
    }

    /** The cuisines most places around serve, for the cuisine line's choices. */
    fun commonCuisines(area: EatArea, n: Int = 16): List<String> =
        EatSets.carte(area.places).flatMap { it.cuisines }.groupingBy { it }.eachCount()
            .entries.sortedByDescending { it.value }.take(n).map { it.key }
}

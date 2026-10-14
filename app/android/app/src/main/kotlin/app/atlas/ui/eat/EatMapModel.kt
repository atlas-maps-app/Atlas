package app.atlas.ui.eat

import app.atlas.providers.Freshness
import app.atlas.providers.Trend
import app.atlas.providers.Trends
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime

/** The sheet's tabs on the eat map. */
enum class EatTab { TRENDING, TOP, NEW, LOVED }

/** The round filters over the eat map. */
enum class EatFilter(val symbol: String) {
    OPEN("ui-clock"), RATED("ui-star"), VEGAN("ui-leaf"), TERRACE("ui-umbrella"), WHEELCHAIR("ui-wheelchair"),
    DOGS("ui-paw"), RESERVE("ui-calendar"), PRICE("ui-euro");

    /** True when [p] passes: open now, rated four stars or more, … ; price means a level of two or less. */
    fun has(p: EatPlace, at: LocalDateTime, area: EatArea, ratings: AreaRatings): Boolean = when (this) {
        OPEN -> p.openness(at, area.hours).isOpen
        RATED -> (ratings.stars(p.key) ?: 0.0) >= 4.0
        VEGAN -> p.diet("vegan") || p.diet("vegetarian")
        TERRACE -> p.outside
        WHEELCHAIR -> p.stepFree
        DOGS -> p.tags["dog"]?.let { it == "yes" || it == "leashed" } == true
        RESERVE -> p.tags["reservation"]?.let { it == "yes" || it == "recommended" || it == "required" } == true
        PRICE -> ratings[p.key]?.priceLevel?.let { it <= 2 } == true
    }
}

/**
 * What the eat map shows for a tab: the places in the tab's order, and [ranked] false when the tab had nothing to go
 * on (no reviews known, nothing new, nothing jotted), so the list is simply the nearest open places and says so.
 */
data class EatMapView(
    val tab: EatTab,
    val places: List<EatPlace>,
    val ranked: Boolean,
    val filterCounts: Map<EatFilter, Int>,
    /** Per place, what OSM says about how new it is (for the ✦ badge). */
    val fresh: Map<String, Freshness>,
)

/** The eat map's logic: filters, the four tabs from real ratings, trends and OSM dates, rings and pins. */
object EatMapModel {
    /** Places to eat (restaurants, fast food, food courts). */
    fun eateries(area: EatArea): List<EatPlace> = EatSets.carte(area.places)

    fun view(area: EatArea, at: LocalDateTime, tab: EatTab, filters: Set<EatFilter>, ratings: AreaRatings, loved: Set<String>, today: LocalDate = at.toLocalDate()): EatMapView {
        val all = eateries(area)
        val chosen = all.filter { p -> filters.all { it.has(p, at, area, ratings) } }
        val fresh = HashMap<String, Freshness>()
        for (p in all) Trends.freshness(p.place, today)?.takeIf { it.kind in NEW_KINDS }?.let { fresh[p.key] = it }
        val ranked: List<EatPlace> = when (tab) {
            EatTab.TRENDING -> chosen.filter { trendOf(it, ratings)?.rising == true }
                .sortedByDescending { trendOf(it, ratings)?.velocity ?: 0.0 }
            EatTab.TOP -> chosen.filter { ratings.stars(it.key) != null }
                .sortedWith(compareByDescending<EatPlace> { ratings.stars(it.key) }.thenByDescending { ratings[it.key]?.count ?: 0 })
            EatTab.NEW -> chosen.filter { it.key in fresh }.sortedByDescending { fresh[it.key]?.date ?: LocalDate.MIN }
            EatTab.LOVED -> chosen.filter { it.key in loved }
        }
        val places = ranked.ifEmpty {
            chosen.sortedWith(compareBy<EatPlace> { if (it.openness(at, area.hours).isOpen) 0 else 1 }.thenBy { it.distanceM })
        }
        return EatMapView(
            tab = tab,
            places = places.take(60),
            ranked = ranked.isNotEmpty(),
            filterCounts = EatFilter.entries.associateWith { f -> all.count { f.has(it, at, area, ratings) } },
            fresh = fresh,
        )
    }

    private val NEW_KINDS = setOf(Freshness.Kind.NEW, Freshness.Kind.OPENING_SOON, Freshness.Kind.NEWLY_MAPPED)

    fun trendOf(p: EatPlace, ratings: AreaRatings): Trend? = ratings[p.key]?.trend

    /**
     * The ring round a pin: how much of the evening is left, 1 for four hours or more until closing, down to 0 at
     * closing; null when it is not open or its hours are not known (then no ring is drawn).
     */
    fun ring(p: EatPlace, at: LocalDateTime, area: EatArea): Float? {
        if (p.alwaysOpen) return 1f
        if (!p.openness(at, area.hours).isOpen) return null
        val closes = p.closesAt(at, area.hours) ?: return 1f
        val left = Duration.between(at, closes).toMinutes().coerceAtLeast(0)
        return (left / 240f).coerceIn(0.04f, 1f)
    }

    /**
     * Four points of a review sparkline from a [Trend]: reviews a month before the last year, over the last year
     * before the last 90 days, over the 90 days before the last 30, and over the last 30. Null with too few reviews.
     */
    fun sparkline(t: Trend?): FloatArray? {
        if (t == null || t.total < 3) return null
        val older = (t.total - t.last365).coerceAtLeast(0) / 12f
        val year = (t.last365 - t.last90).coerceAtLeast(0) / 9f
        val quarter = (t.last90 - t.last30).coerceAtLeast(0) / 2f
        val month = t.last30.toFloat()
        return floatArrayOf(older, year, quarter, month)
    }
}

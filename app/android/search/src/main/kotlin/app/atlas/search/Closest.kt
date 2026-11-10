package app.atlas.search

import app.atlas.places.ChangeKind
import app.atlas.places.OpenState
import app.atlas.places.OpeningHours
import app.atlas.places.OpeningHoursContext
import app.atlas.places.TagFilter
import java.time.Duration
import java.time.LocalDateTime
import kotlin.math.abs

/**
 * What keeps a place from being just as asked, and how far off it is (the owner, 4 November: "should always show
 * closest match if you drop or lower a requirement. Search should rarely not present any results"). When the
 * requirements of a search leave no place, the places that come closest are shown, each with what it misses: it opens
 * in 20 minutes for open now, lies 300 m beyond the circle, is rated 4.1 for 4.5 and up, costs a price step more.
 * [off] runs from only just missed (towards 0) to wholly missed (1).
 */
sealed interface Miss {
    val off: Double

    /** Not open at the time asked: it opens [minutes] after it, at [at]. */
    data class Opens(val at: LocalDateTime, val minutes: Long) : Miss {
        override val off: Double get() = part(minutes / HOUR)
    }

    /** Not open at the time asked: it closed [minutes] before it, at [at] (asked open late, it closes at 22:00). */
    data class Closed(val at: LocalDateTime, val minutes: Long) : Miss {
        override val off: Double get() = part(minutes / HOUR)
    }

    /** Its hours are not known, so whether it is open at the time asked cannot be said. */
    data object HoursUnknown : Miss {
        override val off: Double get() = UNSAID
    }

    /** [metres] beyond the circle of [radius] asked for. */
    data class Beyond(val metres: Double, val radius: Double) : Miss {
        override val off: Double get() = part(metres / radius.coerceAtLeast(1.0))
    }

    /** Farther than [maxDistance] from what it was to be near ([label], drawn with [symbol]): the nearest is [metres] away, null when none is at hand. */
    data class Farther(val label: String, val symbol: String?, val metres: Double?, val maxDistance: Double) : Miss {
        override val off: Double get() = metres?.let { part((it - maxDistance) / maxDistance.coerceAtLeast(1.0)) } ?: 1.0
    }

    /** Rated [rating] where [wanted] or more was asked; [rating] null when it has none. */
    data class Rated(val rating: Double?, val wanted: Double) : Miss {
        override val off: Double get() = rating?.let { part(wanted - it) } ?: UNSAID
    }

    /** [steps] price bands dearer than the dearest asked (cheaper than the cheapest, below zero). */
    data class Price(val steps: Int) : Miss {
        override val off: Double get() = part(PRICE_STEP * abs(steps))
    }

    /** Not what was asked of [facet] ([label]: the value asked, "Vegan", "Step-free"); [unsaid] when its tags do not say. */
    data class Lacks(val facet: String, val label: String, val symbol: String?, val unsaid: Boolean) : Miss {
        override val off: Double get() = if (unsaid) UNSAID else 1.0
    }

    /** Not in the area asked ([area], its name): [metres] outside it, null where it cannot be told. */
    data class Outside(val area: String, val metres: Double?) : Miss {
        override val off: Double get() = 1.0
    }

    private companion object {
        const val HOUR = 60.0
        const val UNSAID = 0.5
        const val PRICE_STEP = 0.5

        /** A share of the way to wholly missed, never quite nothing. */
        fun part(x: Double): Double = x.coerceIn(0.05, 1.0)
    }
}

/**
 * The closest matches, when nothing is just as asked: the places meeting all but the fewest requirements first, of
 * those the least far off, the usual order between equals. A query is parted into what its places are (their kind,
 * name, brand, area) and the requirements they are to meet, each let go of in turn.
 */
object Closest {
    /** [items] closest first by [misses]: the fewest, then the least far off to a tenth; their own order between equals. */
    fun <T> rank(items: List<T>, misses: (T) -> List<Miss>): List<T> =
        items.sortedWith(compareBy<T> { misses(it).size }.thenBy { Math.round(misses(it).sumOf { m -> m.off } * 10) })

    /**
     * How far hours [h] are from being open at [at]: when they next open after it or, where going earlier will do
     * ([back], a time ahead), when they last closed before it that day, whichever is nearer. Null when they neither
     * open after it nor closed before it within the week (hours that are all off).
     */
    fun openMiss(h: OpeningHours, at: LocalDateTime, back: Boolean, context: OpeningHoursContext): Miss? {
        val s = h.status(at, context)
        if (s.unknown) return Miss.HoursUnknown
        val opens = s.changesAt?.takeIf { s.nextChangeKind == ChangeKind.OPENS }?.let { Miss.Opens(it, Duration.between(at, it).toMinutes()) }
        val minute = at.hour * 60 + at.minute
        val closed = if (!back) null else h.spans(at.toLocalDate(), context)
            .filter { it.state == OpenState.OPEN && it.end <= minute }.maxOfOrNull { it.end }
            ?.let { end -> at.toLocalDate().atStartOfDay().plusMinutes(end.toLong()) }
            ?.let { Miss.Closed(it, Duration.between(it, at).toMinutes()) }
        return listOfNotNull(opens, closed).minByOrNull { it.off }
    }

    /**
     * [q] parted into what its places are and the requirements they are to meet: a time, a circle, a nearness, a
     * rating, a wish, a tag beyond the kind. The subject of a "near" or an "inside" is its places' too, its far side a
     * requirement. Null when there is no requirement to let go of, or nothing says what the places are.
     */
    fun split(q: Query): Pair<Query, List<Query>>? {
        var inner = q
        val wraps = ArrayList<(Query) -> Query>()
        while (true) {
            inner = when (val w = inner) {
                is Query.Sort -> { wraps += { Query.Sort(it, w.by) }; w.query }
                is Query.Limit -> { wraps += { Query.Limit(it, w.count) }; w.query }
                else -> break
            }
        }
        val base = ArrayList<Query>()
        val needs = ArrayList<Query>()
        part(inner, base, needs)
        if (needs.isEmpty() || base.isEmpty()) return null
        return wraps.foldRight(Query.and(base)) { wrap, acc -> wrap(acc) } to needs
    }

    /** [q] without its areas ("in Zandvoort"), and those areas; [q] itself and none when it has none. */
    fun withoutAreas(q: Query): Pair<Query, List<Query.InArea>> {
        val areas = ArrayList<Query.InArea>()
        fun strip(x: Query): Query = when (x) {
            is Query.InArea -> { areas += x; Query.All }
            is Query.And -> Query.and(x.parts.map { strip(it) }.filter { it !== Query.All })
            is Query.Sort -> strip(x.query).let { if (it === Query.All) it else Query.Sort(it, x.by) }
            is Query.Limit -> strip(x.query).let { if (it === Query.All) it else Query.Limit(it, x.count) }
            else -> x
        }
        val out = strip(q)
        return if (areas.isEmpty()) q to emptyList() else out to areas
    }

    private fun part(q: Query, base: MutableList<Query>, needs: MutableList<Query>) {
        when {
            q is Query.And -> q.parts.forEach { part(it, base, needs) }
            // "shop=bakery and wheelchair=yes" as one filter: its kind and each other tag apart
            q is Query.Tags && q.filter is TagFilter.And -> (q.filter as TagFilter.And).parts.forEach { part(Query.Tags(it), base, needs) }
            q is Query.Near -> { part(q.subject, base, needs); needs += q.copy(subject = Query.All) }
            q is Query.NotNear -> { part(q.subject, base, needs); needs += q.copy(subject = Query.All) }
            q is Query.Inside -> { part(q.subject, base, needs); needs += Query.Inside(Query.All, q.container) }
            q is Query.Contains -> { part(q.subject, base, needs); needs += q.copy(subject = Query.All) }
            q === Query.All -> {}
            isKind(q) -> base += q
            else -> needs += q
        }
    }

    /** True for what says which places are meant: a kind, a name, a brand, an element, an area or a route. */
    private fun isKind(q: Query): Boolean = when (q) {
        is Query.Category, is Query.Brand, is Query.Name, is Query.Id, is Query.Type, is Query.InArea, is Query.AlongRoute, is Query.Prefer -> true
        is Query.Tags -> q.filter.keys().let { keys -> keys.isNotEmpty() && keys.all { it in Labels.KIND_KEYS || it == "cuisine" } }
        is Query.Or -> q.parts.all { isKind(it) }
        else -> false
    }
}

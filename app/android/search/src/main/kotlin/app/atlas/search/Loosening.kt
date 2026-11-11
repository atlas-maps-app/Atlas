package app.atlas.search

/** What a loosening let go of, for the words that say so: a time, a circle, a nearness, a part, a wish, a rating, an area. */
enum class LooseAspect { TIME, FAR, NEAR, INSIDE, WISH, RATING, AREA }

/**
 * How a query that found nothing as asked was loosened to find something: [from] let go of, or [to] in its place, and
 * the order the places take instead ([by]: a bakery "open late" none is, then the bakeries that close latest).
 */
data class Loosened(val from: Query, val to: Query?, val by: SortBy? = null) {
    /** What kind of requirement [from] is. */
    val aspect: LooseAspect
        get() = when (from) {
            is Query.OpenAt, is Query.ChangesAt, is Query.ClosedOn -> LooseAspect.TIME
            is Query.Within -> LooseAspect.FAR
            is Query.Near, is Query.NotNear -> LooseAspect.NEAR
            is Query.Inside, is Query.Contains -> LooseAspect.INSIDE
            is Query.Rated -> LooseAspect.RATING
            is Query.Want -> if (from.part is Query.Rated) LooseAspect.RATING else LooseAspect.WISH
            is Query.InArea -> LooseAspect.AREA
            else -> LooseAspect.WISH
        }
}

/**
 * The hard parts of a query that may give way when it finds nothing: a time (the places then ordered by when they
 * close or open), a radius or a "near" three times as wide, a closing time asked about within an hour and a half, a
 * part to contain, a place to be inside. Only the subject's own parts give way, never what it is near or inside.
 */
object Loosening {
    /** The parts of [q] that may give way, outer first. */
    fun of(q: Query): List<Loosened> {
        val out = ArrayList<Loosened>()
        spine(q) { p ->
            when (p) {
                is Query.OpenAt -> out += Loosened(p, null, orderInstead(p.time))
                is Query.ChangesAt -> if (p.withinMinutes < WIDE_CHANGE_MINUTES) out += Loosened(p, p.copy(withinMinutes = WIDE_CHANGE_MINUTES))
                is Query.Within -> out += Loosened(p, p.copy(radius = p.radius * WIDER))
                is Query.Near -> out += Loosened(p, p.copy(maxDistance = p.maxDistance * WIDER))
                is Query.Contains -> out += Loosened(p, p.subject)
                is Query.Inside -> out += Loosened(p, p.subject)
                else -> {}
            }
        }
        return out
    }

    /** Which gives way first: a time, then the distances, then what it contains, then what it is inside. */
    fun rank(l: Loosened): Int = when (l.from) {
        is Query.OpenAt, is Query.ChangesAt -> 0
        is Query.Within -> 1
        is Query.Near -> 2
        is Query.Contains -> 3
        else -> 4
    }

    /** [q] with [ways] applied, outer first; ordered as the first of them that orders says, unless [q] has an order. */
    fun apply(q: Query, ways: List<Loosened>): Query {
        var out = ways.fold(q) { acc, w -> swap(acc, w.from, w.to) ?: Query.All }
        val by = ways.firstNotNullOfOrNull { it.by }
        val ordered = out is Query.Sort || (out is Query.Limit && out.query is Query.Sort)
        if (by != null && !ordered) out = Query.Sort(out, by)
        return out
    }

    /** The order that stands in for a time let go of: for an evening, how late they close; a morning, how early they open; now, how soon. */
    private fun orderInstead(t: OpenTime): SortBy? = when (t) {
        OpenTime.Now -> SortBy.OPENS_NEXT
        OpenTime.Tonight, OpenTime.Late -> SortBy.CLOSES_LATEST
        is OpenTime.Today -> if (t.time.hour < NOON) SortBy.OPENS_EARLIEST else SortBy.CLOSES_LATEST
        is OpenTime.At -> if (t.time.hour < NOON) SortBy.OPENS_EARLIEST else SortBy.CLOSES_LATEST
        is OpenTime.OnDay -> null
    }

    /** The subject's own parts, outer first: through conjunctions, orders and limits, and the subject of a near or inside. */
    private fun spine(q: Query, visit: (Query) -> Unit) {
        visit(q)
        when (q) {
            is Query.And -> q.parts.forEach { spine(it, visit) }
            is Query.Sort -> spine(q.query, visit)
            is Query.Limit -> spine(q.query, visit)
            is Query.Near -> spine(q.subject, visit)
            is Query.NotNear -> spine(q.subject, visit)
            is Query.Inside -> { spine(q.subject, visit); spine(q.container, visit) }
            is Query.Contains -> spine(q.subject, visit)
            else -> {}
        }
    }

    /** [q] with the node [old] (by identity) as [new], or without it when [new] is null; unchanged nodes stay the same. */
    private fun swap(q: Query, old: Query, new: Query?): Query? {
        if (q === old) return new
        return when (q) {
            is Query.And -> {
                val parts = q.parts.mapNotNull { swap(it, old, new) }
                if (parts.size == q.parts.size && parts.indices.all { parts[it] === q.parts[it] }) q else Query.and(parts)
            }
            is Query.Sort -> swap(q.query, old, new)?.let { if (it === q.query) q else Query.Sort(it, q.by) }
            is Query.Limit -> swap(q.query, old, new)?.let { if (it === q.query) q else Query.Limit(it, q.count) }
            is Query.Near -> swap(q.subject, old, new)?.let { if (it === q.subject) q else q.copy(subject = it) }
            is Query.NotNear -> swap(q.subject, old, new)?.let { if (it === q.subject) q else q.copy(subject = it) }
            is Query.Inside -> {
                val s = swap(q.subject, old, new) ?: Query.All
                // a container that gives way to nothing leaves the subject alone
                val c = swap(q.container, old, new) ?: return s
                if (s === q.subject && c === q.container) q else Query.Inside(s, c)
            }
            is Query.Contains -> swap(q.subject, old, new)?.let { if (it === q.subject) q else q.copy(subject = it) }
            else -> q
        }
    }

    private const val WIDER = 3.0
    private const val WIDE_CHANGE_MINUTES = 90
    private const val NOON = 12
}

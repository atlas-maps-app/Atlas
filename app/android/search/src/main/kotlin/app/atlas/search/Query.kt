package app.atlas.search

import app.atlas.geo.BBox
import app.atlas.geo.LatLon
import app.atlas.model.OsmId
import app.atlas.model.OsmType
import app.atlas.places.TagFilter
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId

/** How results are ordered. */
enum class SortBy { RELEVANCE, DISTANCE, RATING, OPEN_LONGEST }

/** When a place should be open: now, at a time, tonight, late, on a day. Resolved against the search's clock. */
sealed interface OpenTime {
    /** Open at the moment of the search. */
    data object Now : OpenTime

    /** Open at a fixed local date and time. */
    data class At(val time: LocalDateTime) : OpenTime

    /** Open at [time] today, or tomorrow when that time has passed. */
    data class Today(val time: LocalTime) : OpenTime

    /** Open this evening: at 20:30 today. */
    data object Tonight : OpenTime

    /** Open late: at 23:00 today. */
    data object Late : OpenTime

    /** Open on the next [day] (today counts), at [time] when given, else at any time that day. */
    data class OnDay(val day: DayOfWeek, val time: LocalTime? = null) : OpenTime

    companion object {
        /** Open at an [instant], read on the clocks of [zone] (opening hours are local times). */
        fun at(instant: Instant, zone: ZoneId): OpenTime = At(LocalDateTime.ofInstant(instant, zone))
    }
}

/**
 * A search as a tree: what to find (tags, a name, a category, a brand), where (a circle, a box, along a route, near
 * other things) and how to order it. [QueryParser] builds it from text; [OfflineEvaluator] runs it over the stores and
 * [OverpassCompiler] turns it into Overpass QL.
 */
sealed interface Query {
    /** Places whose tags pass [filter]. */
    data class Tags(val filter: TagFilter) : Query

    /** Places named like [text], typos forgiven. */
    data class Name(val text: String) : Query

    /** Places of an Atlas category (a leaf, or a group and all its leaves). */
    data class Category(val id: String) : Query

    /** Branches of a brand, by its name-suggestion-index id. */
    data class Brand(val id: String) : Query

    /** Places open at [time]. */
    data class OpenAt(val time: OpenTime) : Query

    /** Within [radius] metres of [center]; a null centre means the searcher's own location. */
    data class Within(val center: LatLon?, val radius: Double) : Query

    /** Inside [bbox]; [name] says what the box is ("Amsterdam"), and stands alone until it is resolved. */
    data class InArea(val bbox: BBox?, val name: String? = null) : Query {
        init {
            require(bbox != null || name != null) { "An area needs a box or a name" }
        }
    }

    /** Within [maxDetourM] metres of the route through [line]. */
    data class AlongRoute(val line: List<LatLon>, val maxDetourM: Double) : Query

    /** [subject] within [maxDistance] metres of something that [other] finds: "A within d of B". */
    data class Near(val subject: Query, val other: Query, val maxDistance: Double) : Query

    /** [subject] with nothing that [other] finds within [minDistance] metres. */
    data class NotNear(val subject: Query, val other: Query, val minDistance: Double) : Query

    /** [query] ordered by [by]. */
    data class Sort(val query: Query, val by: SortBy) : Query

    /** At most [count] results of [query]. */
    data class Limit(val query: Query, val count: Int) : Query

    data class And(val parts: List<Query>) : Query

    data class Or(val parts: List<Query>) : Query

    data class Not(val part: Query) : Query

    /** Only OSM elements of [type]. */
    data class Type(val type: OsmType) : Query

    /** The one element [id]. */
    data class Id(val id: OsmId) : Query

    /** Elements edited since [since] (online only: the offline stores keep no edit times unless imported with meta). */
    data class Newer(val since: Instant) : Query

    /** A wish rather than a rule ("quiet", "cheap"): places that pass [part] rank higher, the others stay. */
    data class Prefer(val part: Query, val weight: Double = 0.25) : Query

    /** Everything. */
    data object All : Query

    companion object {
        fun tags(text: String): Query = Tags(TagFilter.parse(text))

        /** A conjunction, flattened; a single part stands alone. */
        fun and(vararg parts: Query): Query = and(parts.toList())

        fun and(parts: List<Query>): Query {
            val flat = parts.flatMap { if (it is And) it.parts else listOf(it) }.filter { it != All }
            return when (flat.size) {
                0 -> All
                1 -> flat[0]
                else -> And(flat)
            }
        }

        fun or(parts: List<Query>): Query {
            val flat = parts.flatMap { if (it is Or) it.parts else listOf(it) }
            return if (flat.size == 1) flat[0] else Or(flat)
        }
    }
}

/** Walks a query tree depth first, parents before children. */
fun Query.walk(visit: (Query) -> Unit) {
    visit(this)
    when (this) {
        is Query.Near -> { subject.walk(visit); other.walk(visit) }
        is Query.NotNear -> { subject.walk(visit); other.walk(visit) }
        is Query.Sort -> query.walk(visit)
        is Query.Limit -> query.walk(visit)
        is Query.And -> parts.forEach { it.walk(visit) }
        is Query.Or -> parts.forEach { it.walk(visit) }
        is Query.Not -> part.walk(visit)
        is Query.Prefer -> part.walk(visit)
        else -> {}
    }
}

package app.atlas.search

import app.atlas.geo.BBox
import app.atlas.geo.LatLon
import app.atlas.model.OsmId
import app.atlas.model.OsmType
import app.atlas.places.ChangeKind
import app.atlas.places.CompareOp
import app.atlas.places.TagFilter
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId

/**
 * How results are ordered: by relevance, distance, rating, open the longest tonight; by the year they were built
 * (oldest, newest), how tall or how high they stand, what they cost, how big or small they are, how long (a bridge),
 * a charger's power, a hotel's stars, how many hours they open today, and how early or late they open.
 */
enum class SortBy {
    RELEVANCE, DISTANCE, RATING, OPEN_LONGEST, OLDEST, NEWEST, TALLEST, HIGHEST, CHEAPEST, LARGEST, SMALLEST, LONGEST, POWER, STARS,
    MOST_HOURS, OPENS_EARLIEST, OPENS_LATEST, CLOSES_LATEST, OPENS_NEXT,
}

/**
 * What a query asks for: the places, how many there are, how their opening hours compare, or one fact of a place
 * ("how tall is the Westertoren": [ParsedQuery.asked] says which).
 */
enum class Ask { LIST, COUNT, HOURS, FACT }

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

    /**
     * Inside [bbox]; [name] says what the box is ("Amsterdam"), and stands alone until it is resolved. [TOWN] names
     * the town the searcher is in ("how many bins in this town"), [NEIGHBOURHOOD] the quarter ("cafés in this
     * neighbourhood"), [CENTRE] the town's centre ("a hotel not in the city centre").
     */
    data class InArea(val bbox: BBox?, val name: String? = null) : Query {
        init {
            require(bbox != null || name != null) { "An area needs a box or a name" }
        }

        companion object {
            const val TOWN = "@town"
            const val NEIGHBOURHOOD = "@neighbourhood"
            const val CENTRE = "@centre"
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

    /** [subject] lying in or at a place that [container] finds: a café in a mall, a cash machine in a station. */
    data class Inside(val subject: Query, val container: Query) : Query

    /** [subject] with a place that [part] finds lying in or at it: woods with a lake, a park with a playground. */
    data class Contains(val subject: Query, val part: Query) : Query

    /**
     * A rule where saying nothing is not saying no ("a Starbucks with toilets"): places that pass [part] come first;
     * those whose tags do not say come after them, likely when most of their brand's branches pass or a place that
     * [nearby] finds lies within [metres] (public toilets next door), else unsure; those that say otherwise go. A
     * place in a building takes what the building says of its age, height, floors and colour.
     */
    data class Want(val part: Query, val nearby: Query? = null, val metres: Double = 0.0) : Query

    /** Rated [op] [value] (0..5) by the ratings providers give ("rated above 4"). */
    data class Rated(val op: CompareOp, val value: Double) : Query

    /**
     * Places that open or close ([kind]) within [withinMinutes] of [at], on [day] (today when null): "what shops
     * close around 19:30".
     */
    data class ChangesAt(val kind: ChangeKind, val at: LocalTime, val withinMinutes: Int = 30, val day: DayOfWeek? = null) : Query

    /** Places whose own colour, their building's or their roof's, is of [family] ("red", "white"): a red church. */
    data class Coloured(val family: String) : Query

    /** Closed all of [day] by their opening hours (their brand's where they keep none); those that say none are not. */
    data class ClosedOn(val day: DayOfWeek) : Query

    /**
     * Places whose [key] ("height", "ele", "start_date") stands [op] that of the place [other] finds: "taller than
     * the Westertoren", "older than the Westerkerk".
     */
    data class Than(val key: String, val op: CompareOp, val other: Query) : Query

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
        is Query.Inside -> { subject.walk(visit); container.walk(visit) }
        is Query.Contains -> { subject.walk(visit); part.walk(visit) }
        is Query.Want -> { part.walk(visit); nearby?.walk(visit) }
        is Query.Than -> other.walk(visit)
        else -> {}
    }
}

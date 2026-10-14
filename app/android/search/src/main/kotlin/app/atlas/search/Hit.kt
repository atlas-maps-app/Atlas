package app.atlas.search

import app.atlas.geo.BBox
import app.atlas.geo.LatLon
import app.atlas.model.Place
import app.atlas.places.OpeningHoursContext
import app.atlas.places.OpenStatus
import java.time.ZonedDateTime

/** Resolves a named area ("Amsterdam", "de Jordaan") to a box, or null when it does not know it. */
fun interface AreaResolver {
    fun resolve(name: String): BBox?
}

/** What a search runs against: who is searching, where and when, and in which language. */
data class EvalContext(
    /** Where the searcher is: distances, "around me" and the location bias are measured from here. */
    val location: LatLon? = null,
    /** What the map shows: the default area of a search that names none. */
    val viewport: BBox? = null,
    val now: ZonedDateTime = ZonedDateTime.now(),
    val lang: String = "en",
    /** How many results to return at most (a [Query.Limit] can ask for fewer). */
    val limit: Int = 50,
    /** Holidays and the sun, for opening hours. */
    val hours: OpeningHoursContext = OpeningHoursContext.Default,
    /** A place's rating 0..5 when a provider gave one: from `extra["rating"]` unless told otherwise. */
    val rating: (Place) -> Double? = { it.extra["rating"]?.toDoubleOrNull() },
    /** Resolves [Query.InArea] names that came without a box. */
    val areas: AreaResolver? = null,
)

/** The thing found near a result for one "near" clause: the nearest one, and how far. */
data class NearMatch(
    /** The clause's position in the chain, 0 for the first "near". */
    val clause: Int,
    val record: PlaceRecord,
    val distance: Double,
    val maxDistance: Double,
)

/** One result: the place, how well and why it matched, how far it is, whether it is open, what is near it. */
data class Hit(
    val record: PlaceRecord,
    val score: Double,
    /** How well the text fits (1 when the query had no text part). */
    val relevance: Double,
    /** Metres from the searcher (or from the centre of the area searched), or null when neither is known. */
    val distance: Double?,
    /** The opening state at the time asked for (now when none), or null when the place has no hours. */
    val open: OpenStatus?,
    /** Per "near" clause, the nearest match ("🚲 60 m · 🌲 30 m"). */
    val near: List<NearMatch> = emptyList(),
    /** Metres along the route, for [Query.AlongRoute]. */
    val along: Double? = null,
    /** Why it matched, in short machine-friendly words: "name", "amenity=cafe", "open", "category:health"… */
    val why: List<String> = emptyList(),
    /** The rating used for ranking, when there was one. */
    val rating: Double? = null,
) {
    val place: Place get() = record.place
    val tags: Map<String, String> get() = record.tags
    val symbol: String get() = record.symbol
}

/** The answer to one query: the ranked hits, how many matched before the limit, and what it cost. */
data class QueryResult(
    val hits: List<Hit>,
    /** Matches before the limit was applied. */
    val total: Int,
    /** Places looked at. */
    val examined: Int,
    val micros: Long,
)

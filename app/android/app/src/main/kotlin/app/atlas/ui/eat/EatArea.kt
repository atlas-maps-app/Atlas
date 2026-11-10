package app.atlas.ui.eat

import app.atlas.geo.LatLon
import app.atlas.places.OpeningHoursContext
import app.atlas.search.HoursBook
import app.atlas.search.MemoryPlaces
import app.atlas.search.PlaceRecord
import app.atlas.services.PlaceOrigin
import app.atlas.ui.kit.openTotal
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * Every place to eat and drink within [radiusM] of [centre], nearest first, as one source gave them: the offline
 * place store (every tag), the map tiles (names and kinds only: no hours, no cuisines) or Overpass online.
 * [loading] is true while a better answer may still come; [error] says why the network failed, when it did.
 */
class EatArea(
    val centre: LatLon,
    val radiusM: Double,
    val places: List<EatPlace>,
    val origin: PlaceOrigin?,
    val loading: Boolean,
    val error: String? = null,
    /** The country most places here give in `addr:country`: its holidays apply to opening hours. */
    val country: String? = null,
    zone: ZoneId = ZoneId.systemDefault(),
) {
    /** Holidays of [country] and the real sun here, for every opening-hours question about this area. */
    val hours: OpeningHoursContext = OpeningHoursContext.forPlace(country, zone, centre.lat, centre.lon)

    init {
        guessHours(places)
    }

    /** The places as a search source, so `:search` queries run over exactly what the screens show. */
    val source: MemoryPlaces by lazy { MemoryPlaces(places.map { it.place }) }

    private val byKey: Map<String, EatPlace> by lazy { places.associateBy { it.key } }

    /** The place with [key], if it is in this area. */
    operator fun get(key: String): EatPlace? = byKey[key]

    /** True when nothing is known yet and something may still come. */
    val waiting: Boolean get() = places.isEmpty() && loading

    /** The share of [among] with hours of their own Atlas can read, 0..1 (0 when empty). */
    fun hoursShare(among: List<EatPlace> = places): Double =
        if (among.isEmpty()) 0.0 else among.count { it.hours != null } / among.size.toDouble()

    /** How many of [among] are open at [at] by their hours, known or guessed. */
    fun openCount(among: List<EatPlace>, at: LocalDateTime): Int = among.count { it.openness(at, hours).isOpen }

    /**
     * What an object on the table counts: the places open at [at], or, when none of them has hours (an area nobody
     * mapped hours in), every place; [Count.open] says which.
     */
    fun count(among: List<EatPlace>, at: LocalDateTime): Count {
        if (among.isNotEmpty() && among.none { it.hasHours }) return Count(among.size, open = false)
        val open = among.filter { it.openness(at, hours).isOpen }
        return Count(open.size, open = true, guessed = open.count { it.guessed })
    }

    /** A number on the table: of open places, [guessed] of them open by guessed hours, or of places whose hours nobody mapped. */
    data class Count(val value: Int, val open: Boolean, val guessed: Int = 0) {
        /** As written: the places surely open, "≈7" where only guessed hours keep any open ([openTotal]). */
        val shown: String get() = if (open) openTotal(value, guessed) else value.toString()
    }

    companion object {
        /** How far the eat screens look: about a quarter of an hour on foot. */
        const val RADIUS_M = 1_200.0

        fun empty(centre: LatLon, loading: Boolean = true) = EatArea(centre, RADIUS_M, emptyList(), null, loading)

        /**
         * Gives each of [places] that maps no hours those its brand's other branches among them keep, else those the
         * places of its kind around it keep ([HoursBook], as the one search guesses them); once for a set of places.
         */
        internal fun guessHours(places: List<EatPlace>) {
            if (places.all { it.hours != null || it.weighed }) return
            val records = places.map { PlaceRecord.of(it.place) }
            val book = HoursBook(records)
            places.forEachIndexed { i, p ->
                if (p.hours == null) p.guess = book.of(records[i])
                p.weighed = true
            }
        }

        /** The country most of [places] give in `addr:country`, or null when none does. */
        fun countryOf(places: List<EatPlace>): String? =
            places.mapNotNull { it.tags["addr:country"]?.trim()?.uppercase()?.takeIf { c -> c.length == 2 } }
                .groupingBy { it }.eachCount().maxByOrNull { it.value }?.key
    }
}

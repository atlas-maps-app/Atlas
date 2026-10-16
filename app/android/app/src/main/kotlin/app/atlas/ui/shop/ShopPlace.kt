package app.atlas.ui.shop

import app.atlas.geo.Geo
import app.atlas.geo.LatLon
import app.atlas.model.Place
import app.atlas.places.Brand
import app.atlas.places.Brands
import app.atlas.places.ChangeKind
import app.atlas.places.Localise
import app.atlas.places.OpenStatus
import app.atlas.places.OpeningHours
import app.atlas.places.OpeningHoursContext
import app.atlas.places.Presets
import app.atlas.services.PlaceOrigin
import app.atlas.ui.kit.WalkTime
import java.time.DayOfWeek
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters
import kotlin.math.cos
import kotlin.math.roundToInt

/** Whether a place is open at a moment, as the shop screens show it. */
internal enum class Open {
    OPEN,

    /** Open, but closing within [ShopPlace.SOON_MINUTES]. */
    CLOSING_SOON,
    CLOSED,

    /** No hours mapped, or hours that do not say. */
    UNKNOWN;

    val isOpen: Boolean get() = this == OPEN || this == CLOSING_SOON
}

/**
 * A shop, errand or amenity as the shop screens read it: its name, how far it is from the centre of the area, its
 * brand, and its opening hours parsed once on first use. Everything else is read from [tags] when asked.
 */
internal class ShopPlace(val place: Place, val name: String, val distanceM: Double) {
    val tags: Map<String, String> get() = place.tags
    val key: String get() = place.key
    val location: LatLon get() = place.location

    /** The chain it belongs to, from `brand:wikidata`, the brand tag or the name; null for an independent. */
    val brand: Brand? by lazy { Brands.forTags(place.tags) }

    /** True when no chain claims it (no `brand` tag and no brand matched). */
    val independent: Boolean get() = tags["brand"] == null && tags["brand:wikidata"] == null && brand == null

    val walkSeconds: Double get() = WalkTime.seconds(distanceM)

    /** Whole minutes on foot, at least one. */
    val walkMinutes: Int get() = maxOf(1, (walkSeconds / 60.0).roundToInt())

    /** The parsed `opening_hours`, or null when none is mapped or it cannot be read. */
    val hours: OpeningHours? by lazy { OpeningHours.parseOrNull(tags["opening_hours"]) }

    val hasHours: Boolean get() = hours != null

    /** True for `24/7`. */
    val alwaysOpen: Boolean get() = hours?.isAlwaysOpen == true

    fun status(at: LocalDateTime, ctx: OpeningHoursContext): OpenStatus? = hours?.status(at, ctx)?.takeUnless { it.unknown }

    fun openness(at: LocalDateTime, ctx: OpeningHoursContext): Open {
        val h = hours ?: return Open.UNKNOWN
        if (h.isAlwaysOpen) return Open.OPEN
        val s = h.status(at, ctx)
        if (s.unknown) return Open.UNKNOWN
        if (!s.isOpen) return Open.CLOSED
        val closes = closing(s) ?: return Open.OPEN
        return if (Duration.between(at, closes).toMinutes() in 0..SOON_MINUTES) Open.CLOSING_SOON else Open.OPEN
    }

    /** When it closes after [at] when open then and the hours say so; null otherwise (closed, 24/7, open end). */
    fun closesAt(at: LocalDateTime, ctx: OpeningHoursContext): LocalDateTime? {
        val h = hours ?: return null
        if (h.isAlwaysOpen) return null
        return closing(h.status(at, ctx))
    }

    /** When it next opens after [at] when closed then; null when open or the hours do not say. */
    fun opensAt(at: LocalDateTime, ctx: OpeningHoursContext): LocalDateTime? {
        val s = hours?.status(at, ctx) ?: return null
        if (s.isOpen || s.unknown) return null
        return s.changesAt?.takeIf { s.nextChangeKind == ChangeKind.OPENS }
    }

    private fun closing(s: OpenStatus): LocalDateTime? =
        if (s.isOpen && !s.openEnd && s.nextChangeKind == ChangeKind.CLOSES) s.changesAt else null

    /** True when it is open at some moment of [date] after [hour] (late opening: 22 for "open past ten"). */
    fun openAfter(date: LocalDate, hour: Int, ctx: OpeningHoursContext): Boolean {
        val h = hours ?: return false
        if (h.isAlwaysOpen) return true
        return h.spans(date, ctx).any { it.state == app.atlas.places.OpenState.OPEN && it.end > hour * 60 }
    }

    /** True when it is open on the next Sunday at midday (or the one today); null when no hours are mapped. */
    fun openSunday(from: LocalDate, ctx: OpeningHoursContext): Boolean? {
        val h = hours ?: return null
        if (h.isAlwaysOpen) return true
        val sunday = from.with(TemporalAdjusters.nextOrSame(DayOfWeek.SUNDAY))
        return h.status(LocalDateTime.of(sunday, LocalTime.NOON), ctx).takeUnless { it.unknown }?.isOpen
    }

    fun yes(key: String): Boolean = tags[key]?.let { it == "yes" || it == "only" || it == "designated" } ?: false

    val stepFree: Boolean get() = tags["wheelchair"] == "yes" || tags["wheelchair"] == "designated"
    val selfCheckout: Boolean get() = yes("self_checkout")
    val delivery: Boolean get() = yes("delivery") || tags.keys.any { it.startsWith("delivery:") && yes(it) }
    val parking: Boolean get() = tags["parking"]?.let { it != "no" } ?: false || yes("capacity:parking") || tags["parking:spaces"] != null

    /** The footprint in m²: the bounding box of its building outline when it is a way; null for a point. */
    val footprintM2: Int? by lazy {
        val b = place.bbox ?: return@lazy null
        val lat = (b.north + b.south) / 2
        val h = (b.north - b.south) * METRES_PER_DEGREE
        val w = (b.east - b.west) * METRES_PER_DEGREE * cos(Math.toRadians(lat))
        (h * w).takeIf { it >= 20.0 }?.let { ((it / 10).roundToInt() * 10) }
    }

    /** The name of its kind in [lang] ("Bakery"), from its preset. */
    fun kindName(lang: String?): String? = Presets.match(tags)?.name(lang)

    /** The tag the kind is read from: `shop=bakery` as "bakery". */
    val kindValue: String? get() = tags["shop"] ?: tags["amenity"] ?: tags["craft"]

    override fun toString() = "$name (${place.id ?: place.key})"

    companion object {
        const val SOON_MINUTES = 45L

        /** The `payment:*` keys that mean a card is taken. */
        val CARD_KEYS = listOf("cards", "credit_cards", "debit_cards", "contactless", "maestro", "visa", "mastercard", "american_express", "v_pay", "girocard")
        private const val METRES_PER_DEGREE = 111_320.0

        /** Wraps [place] measured from [centre]; null when it has no name to show. */
        fun of(place: Place, centre: LatLon, lang: String?, nameless: Boolean = false): ShopPlace? {
            val name = Localise.name(place.tags, lang) ?: Presets.match(place.tags)?.name(lang)?.takeIf { nameless } ?: return null
            return ShopPlace(place, name, Geo.distance(centre, place.location))
        }
    }
}

/**
 * The places a shop screen shows, around [centre] within [radiusM]: where they came from, whether a better answer may
 * still come ([loading]) and why the network failed when it did ([error]).
 */
internal class ShopArea(
    val centre: LatLon,
    val radiusM: Double,
    val places: List<ShopPlace>,
    val origin: PlaceOrigin?,
    val loading: Boolean,
    val error: String? = null,
    zone: ZoneId = ZoneId.systemDefault(),
) {
    /** The country most places give in `addr:country`: its holidays apply to opening hours. */
    val country: String? = places.mapNotNull { it.tags["addr:country"]?.trim()?.uppercase()?.takeIf { c -> c.length == 2 } }
        .groupingBy { it }.eachCount().maxByOrNull { it.value }?.key

    /** Holidays of [country] and the real sun here, for every opening-hours question about this area. */
    val hours: OpeningHoursContext = OpeningHoursContext.forPlace(country, zone, centre.lat, centre.lon)

    /** True when the tiles answered: kinds and names only, so counts are of places, not of open ones. */
    val sketchOnly: Boolean get() = origin == PlaceOrigin.TILES

    val waiting: Boolean get() = places.isEmpty() && loading

    /** How many of [among] have hours Atlas can read. */
    fun withHours(among: List<ShopPlace> = places): Int = among.count { it.hasHours }

    /**
     * What a shelf counts: the places open at [at]; when the source has no hours at all (the tiles, or an area nobody
     * mapped hours in) every place, and [Count.open] says which.
     */
    fun count(among: List<ShopPlace>, at: LocalDateTime): Count =
        if (sketchOnly || (among.isNotEmpty() && among.none { it.hasHours })) Count(among.size, open = false)
        else Count(among.count { it.openness(at, hours).isOpen }, open = true)

    data class Count(val value: Int, val open: Boolean)

    companion object {
        fun empty(centre: LatLon, radiusM: Double = DEFAULT_RADIUS_M) = ShopArea(centre, radiusM, emptyList(), null, loading = true)

        /** How far the shop screens look: about a quarter of an hour on foot. */
        const val DEFAULT_RADIUS_M = 1_200.0
    }
}

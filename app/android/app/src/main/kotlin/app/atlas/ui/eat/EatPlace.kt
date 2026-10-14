package app.atlas.ui.eat

import app.atlas.geo.Geo
import app.atlas.geo.LatLon
import app.atlas.model.Place
import app.atlas.places.ChangeKind
import app.atlas.places.Cuisine
import app.atlas.places.Localise
import app.atlas.places.OpenStatus
import app.atlas.places.OpeningHours
import app.atlas.places.OpeningHoursContext
import app.atlas.places.Presets
import app.atlas.ui.kit.WalkTime
import java.time.Duration
import java.time.LocalDateTime

/** Whether a place is open at a moment, as the eat screens show it. */
enum class Openness {
    OPEN,

    /** Open, but closing within [EatPlace.SOON_MINUTES]. */
    CLOSING_SOON,
    CLOSED,

    /** No hours mapped, or hours that do not say. */
    UNKNOWN;

    val isOpen: Boolean get() = this == OPEN || this == CLOSING_SOON
}

/**
 * A place to eat or drink with what the screens read from its tags worked out once: its [kind], its cuisines (in
 * mapped order, canonical spellings), its name, how far it is and its opening hours (parsed on first use).
 */
class EatPlace(
    val place: Place,
    val kind: EatKind,
    val name: String,
    /** Metres from the centre of the area, in a straight line. */
    val distanceM: Double,
) {
    val tags: Map<String, String> get() = place.tags
    val key: String get() = place.key
    val location: LatLon get() = place.location

    /** The cuisine values, canonical ("pancakes" -> "pancake"), without duplicates. */
    val cuisines: List<String> = Cuisine.values(place.tags).map { CuisineTaxonomy.canonical(it) }.distinct()

    /** Seconds on foot, from the straight line with the city detour. */
    val walkSeconds: Double get() = WalkTime.seconds(distanceM)

    /** Whole minutes on foot, at least one. */
    val walkMinutes: Int get() = maxOf(1, Math.round(walkSeconds / 60.0).toInt())

    /** The parsed `opening_hours`, or null when none is mapped or it cannot be read. */
    val hours: OpeningHours? by lazy { OpeningHours.parseOrNull(place.tags["opening_hours"]) }

    /** True when the place has hours Atlas can read. */
    val hasHours: Boolean get() = hours != null

    /** The status at [at]; null when the hours are not mapped or not readable. */
    fun status(at: LocalDateTime, context: OpeningHoursContext): OpenStatus? = hours?.status(at, context)

    /** Open, closing soon, closed or unknown at [at]. */
    fun openness(at: LocalDateTime, context: OpeningHoursContext): Openness {
        val h = hours ?: return Openness.UNKNOWN
        if (h.isAlwaysOpen) return Openness.OPEN
        val s = h.status(at, context)
        if (s.unknown) return Openness.UNKNOWN
        if (!s.isOpen) return Openness.CLOSED
        val closes = closesAt(s) ?: return Openness.OPEN
        val left = Duration.between(at, closes).toMinutes()
        return if (left in 0..SOON_MINUTES) Openness.CLOSING_SOON else Openness.OPEN
    }

    /** When it closes after [at] if open then and the hours say so; null otherwise (closed, 24/7, an open end). */
    fun closesAt(at: LocalDateTime, context: OpeningHoursContext): LocalDateTime? {
        val h = hours ?: return null
        if (h.isAlwaysOpen) return null
        return closesAt(h.status(at, context))
    }

    /** When it next opens after [at] if closed then; null when it is open or the hours do not say. */
    fun opensAt(at: LocalDateTime, context: OpeningHoursContext): LocalDateTime? {
        val s = hours?.status(at, context) ?: return null
        if (s.isOpen || s.unknown) return null
        return s.changesAt?.takeIf { s.nextChangeKind == ChangeKind.OPENS }
    }

    /** True for `24/7` hours. */
    val alwaysOpen: Boolean get() = hours?.isAlwaysOpen == true

    private fun closesAt(s: OpenStatus): LocalDateTime? =
        if (s.isOpen && !s.openEnd && s.nextChangeKind == ChangeKind.CLOSES) s.changesAt else null

    /** The tag [key] says yes (or only, or designated). */
    fun yes(key: String): Boolean = tags[key]?.let { yesValue(it) } ?: false

    /** Offers food for [diet] ("vegan", "vegetarian", "gluten_free", "halal", "kosher"): yes, only or some options. */
    fun diet(diet: String): Boolean {
        val v = tags["diet:$diet"]
        if (v != null) return v.split(';').any { it.trim() in DIET_YES }
        // A vegan place is vegetarian too; a kitchen named after the diet serves it only.
        return when (diet) {
            "vegan" -> "vegan" in cuisines
            "vegetarian" -> "vegetarian" in cuisines || "vegan" in cuisines || tags["diet:vegan"]?.trim() == "only"
            else -> false
        }
    }

    /** Has seats outside. */
    val outside: Boolean get() = tags["outdoor_seating"]?.let { it != "no" } ?: false || tags["beer_garden"] == "yes"

    /** Step-free entrance. */
    val stepFree: Boolean get() = tags["wheelchair"] == "yes" || tags["wheelchair"] == "designated"

    /** Takes cards (any card, or contactless). */
    val takesCards: Boolean
        get() = CARD_KEYS.any { yes("payment:$it") }

    /** Cash only: cards refused, or cash marked as the only way to pay. */
    val cashOnly: Boolean
        get() = tags["payment:cash"] == "only" || (CARD_KEYS.all { k -> tags["payment:$k"]?.let { it == "no" } ?: true } &&
            CARD_KEYS.any { tags["payment:$it"] == "no" })

    /** Takes cash. */
    val takesCash: Boolean get() = yes("payment:cash") || yes("payment:coins") || tags["payment:cash"] == "only"

    /** Free Wi-Fi or Wi-Fi at all. */
    val wifi: Boolean get() = tags["internet_access"]?.let { it == "wlan" || it == "yes" || it == "wifi" || it == "public" } ?: false

    /** Takeaway offered. */
    val takeaway: Boolean get() = tags["takeaway"]?.let { it == "yes" || it == "only" } ?: false

    /** The name of the place's kind in [lang] ("Restaurant", "Café"), from its preset. */
    fun kindName(lang: String?): String? = Presets.match(tags)?.name(lang)

    override fun toString() = "$name (${place.id ?: place.key})"

    companion object {
        /** Closing within this many minutes counts as closing soon. */
        const val SOON_MINUTES = 45L

        private val DIET_YES = setOf("yes", "only", "limited", "designated")
        internal val CARD_KEYS = listOf(
            "cards", "credit_cards", "debit_cards", "contactless", "maestro", "visa", "mastercard", "american_express", "v_pay", "girocard",
        )

        private fun yesValue(v: String) = v == "yes" || v == "only" || v == "designated"

        /** Wraps [place] for the eat screens, measured from [centre]; null when it is not a place to eat or drink. */
        fun of(place: Place, centre: LatLon, lang: String?): EatPlace? {
            val kind = EatKind.of(place.tags)
            if (kind == EatKind.UNKNOWN) return null
            val name = Localise.name(place.tags, lang) ?: Presets.match(place.tags)?.name(lang) ?: return null
            return EatPlace(place, kind, name, Geo.distance(centre, place.location))
        }
    }
}

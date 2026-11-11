package app.atlas.ui.kit

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import app.atlas.R
import app.atlas.geo.Geo
import app.atlas.geo.LatLon
import app.atlas.model.Place
import app.atlas.places.Brand
import app.atlas.places.Brands
import app.atlas.places.ChangeKind
import app.atlas.places.KindColours
import app.atlas.places.Localise
import app.atlas.places.OpenStatus
import app.atlas.places.OpeningHours
import app.atlas.places.Presets
import app.atlas.places.Symbols
import java.time.Duration
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.ConcurrentHashMap
import java.util.Locale

/**
 * What a list row says about a place: its name, its kind in the reader's language, its mark and colour ([markOf]),
 * whether it is open at [now] (from `opening_hours`, null when not mapped) and how far it is from where you are.
 */
@Immutable
data class PlaceSummary(
    val place: Place,
    val name: String,
    val kind: String?,
    val symbol: String,
    val colour: Int,
    val open: OpenStatus?,
    val alwaysOpen: Boolean,
    val distanceM: Double?,
) {
    /** Walking time as the boards show it ("4′"), from the straight-line distance with a detour factor. */
    val walk: String? get() = distanceM?.let { walkMinutes(WalkTime.seconds(it)) }

    companion object {
        fun of(place: Place, from: LatLon?, lang: String?, now: LocalDateTime = LocalDateTime.now()): PlaceSummary {
            val tags = place.tags
            val preset = Presets.match(tags)
            val kind = preset?.name(lang)
            val own = Localise.name(tags, lang)
            // A place with no name of its own is titled by what it is ("Gift Shop"), and no kind line repeats it ("Gifts")
            val name = own ?: preset?.fullName(lang) ?: place.id?.toString() ?: ""
            val hours = OpeningHours.parseOrNull(tags["opening_hours"])
            val (symbol, colour) = markOf(place)
            return PlaceSummary(
                place = place,
                name = name,
                kind = kind?.takeIf { own != null && it != name },
                symbol = symbol,
                colour = colour,
                open = hours?.status(now),
                alwaysOpen = hours?.isAlwaysOpen == true,
                distanceM = from?.let { Geo.distance(it, place.location) },
            )
        }
    }
}

/**
 * How [place] is marked wherever Atlas draws it, as a pin, a row's disc, a card's badge or its page's tile: its brand's
 * logo where the brand has one, else its kind's symbol (kinds.tsv), on its family's colour ([Symbols.markFor],
 * [Symbols.colourFor]) or its kind's own, its thing's ([KindColours]: a bakery's bread brown); the same mark on every
 * screen, so a place seen once is known again, but on the search's pins and rows, where a chain with a colour of its
 * own is on its sign ([signOf]). Kept by the place's key, for the maps and lists that ask on every frame.
 */
fun markOf(place: Place): Pair<String, Int> {
    marks[place.key]?.let { (tags, mark) -> if (tags == place.tags) return mark }
    val mark = Symbols.markFor(place.tags) to (KindColours.of(place.tags) ?: Symbols.colourFor(place.tags))
    if (marks.size >= MARKS) marks.clear()
    marks[place.key] = place.tags to mark
    return mark
}

private val marks = ConcurrentHashMap<String, Pair<Map<String, String>, Pair<String, Int>>>()

/**
 * The brand whose own sign [place] wears on the search's pins and rows, as on the brand's page: one with a logo and a
 * colour of its own, the Albert Heijn blue everyone knows from the street (the owner, 6 November, of a green Albert
 * Heijn among the groceries: "Why doesnt albert heijn get its color on this image?"). Null for the rest, a brand whose
 * colour is not known among them, which wear [markOf].
 */
fun signOf(place: Place): Brand? =
    Brands.forTags(place.tags)?.takeIf { b -> b.colour != null && b.logoSlug?.let { Symbols.has("logo-$it") } == true }

/** The most marks kept: a city's places in view and in the lists, a few times over. */
private const val MARKS = 4096

/** Walking times on foot in a city: 4.8 km/h along streets about 1.3 times longer than the straight line. */
object WalkTime {
    private const val SPEED = 4.8 / 3.6
    private const val DETOUR = 1.3

    fun seconds(straightMetres: Double): Double = straightMetres * DETOUR / SPEED
}

private val HH_MM: DateTimeFormatter = DateTimeFormatter.ofPattern("H:mm")

/** What the open line says about a place, before it is put into words ([openLine] does that). */
sealed interface OpenWords {
    /** "open 24/7": always open, or open with no change within a year. */
    data object Always : OpenWords

    /** "closes in 25 min": closing within [SOON_MINUTES]. */
    data class ClosesIn(val minutes: Int) : OpenWords

    /** "open until 23:00". */
    data class OpenUntil(val at: LocalDateTime) : OpenWords

    /** "open": open now, but the hours do not say until when (an open end, or what follows is unknown). */
    data object Open : OpenWords

    /** "closed · opens 9:00", naming the day when it is not today. */
    data class Opens(val at: LocalDateTime, val sameDay: Boolean) : OpenWords

    /** "closed", with no opening the hours name. */
    data object Closed : OpenWords

    companion object {
        /** Closing within this many minutes is said as "closes in". */
        const val SOON_MINUTES = 45L

        /** The words for [s] at [now]; null when its hours are not mapped or say nothing for now. */
        fun of(s: PlaceSummary, now: LocalDateTime): OpenWords? {
            if (s.alwaysOpen) return Always
            val st = s.open ?: return null
            if (st.unknown) return null
            val at = st.changesAt
            if (st.isOpen) {
                if (at == null) return Always
                // Only a stated closing time is said; an open end's is a guess, and a change to "unknown" is no closing.
                if (st.nextChangeKind != ChangeKind.CLOSES || st.openEnd) return Open
                val left = Duration.between(now, at).toMinutes()
                return if (left in 0..SOON_MINUTES) ClosesIn(left.toInt()) else OpenUntil(at)
            }
            return if (at != null && st.nextChangeKind == ChangeKind.OPENS) Opens(at, at.toLocalDate() == now.toLocalDate()) else Closed
        }
    }
}

/**
 * The open-status words and their colour, as the boards write them: "open until 20:00" in green, "closes in 25 min"
 * in amber, "closed · opens 7:00" (or "opens Mon 9:00" on another day) in grey, "open 24/7"; null when the hours are
 * not mapped or not readable.
 */
@Composable
fun openLine(s: PlaceSummary, now: LocalDateTime = LocalDateTime.now()): Pair<String, Color>? {
    return when (val w = OpenWords.of(s, now) ?: return null) {
        OpenWords.Always -> stringResource(R.string.kit_open_24_7) to OPEN_GREEN
        is OpenWords.ClosesIn -> stringResource(R.string.kit_closes_in, w.minutes) to CLOSING_AMBER
        is OpenWords.OpenUntil -> stringResource(R.string.kit_open_until, w.at.format(HH_MM)) to OPEN_GREEN
        OpenWords.Open -> stringResource(R.string.kit_open) to OPEN_GREEN
        is OpenWords.Opens -> {
            val time = if (w.sameDay) w.at.format(HH_MM) else w.at.format(DateTimeFormatter.ofPattern("EEE H:mm", Locale.getDefault()))
            stringResource(R.string.kit_closed_opens, time) to CLOSED_GREY
        }
        OpenWords.Closed -> stringResource(R.string.kit_closed) to CLOSED_GREY
    }
}

/** The boards' colours for open (#12663A), closing soon (#8A5A00) and closed (#5A5F6C). */
private val OPEN_GREEN = Color(0xFF12663A)
private val CLOSING_AMBER = Color(0xFF8A5A00)
private val CLOSED_GREY = Color(0xFF5A5F6C)

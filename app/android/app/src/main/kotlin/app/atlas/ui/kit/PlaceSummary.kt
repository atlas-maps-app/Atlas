package app.atlas.ui.kit

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import app.atlas.R
import app.atlas.geo.Geo
import app.atlas.geo.LatLon
import app.atlas.model.Place
import app.atlas.places.ChangeKind
import app.atlas.places.Localise
import app.atlas.places.OpenStatus
import app.atlas.places.OpeningHours
import app.atlas.places.Presets
import app.atlas.places.Symbols
import java.time.Duration
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * What a list row says about a place: its name, its kind in the reader's language, its symbol and colour, whether it
 * is open at [now] (from `opening_hours`, null when not mapped) and how far it is from where you are.
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
            val name = Localise.name(tags, lang) ?: kind ?: place.id?.toString() ?: ""
            val hours = OpeningHours.parseOrNull(tags["opening_hours"])
            return PlaceSummary(
                place = place,
                name = name,
                kind = kind?.takeIf { it != name },
                symbol = Symbols.symbolFor(tags),
                colour = Symbols.colourFor(tags),
                open = hours?.status(now),
                alwaysOpen = hours?.isAlwaysOpen == true,
                distanceM = from?.let { Geo.distance(it, place.location) },
            )
        }
    }
}

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
    val c = Atlas.colors
    return when (val w = OpenWords.of(s, now) ?: return null) {
        OpenWords.Always -> stringResource(R.string.kit_open_24_7) to c.open
        is OpenWords.ClosesIn -> stringResource(R.string.kit_closes_in, w.minutes) to c.caution
        is OpenWords.OpenUntil -> stringResource(R.string.kit_open_until, w.at.format(HH_MM)) to c.open
        OpenWords.Open -> stringResource(R.string.kit_open) to c.open
        is OpenWords.Opens -> {
            val time = if (w.sameDay) w.at.format(HH_MM) else w.at.format(DateTimeFormatter.ofPattern("EEE H:mm", Locale.getDefault()))
            stringResource(R.string.kit_closed_opens, time) to c.inkSoft
        }
        OpenWords.Closed -> stringResource(R.string.kit_closed) to c.inkSoft
    }
}

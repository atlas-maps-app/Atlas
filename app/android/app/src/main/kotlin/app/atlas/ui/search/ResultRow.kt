package app.atlas.ui.search

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import app.atlas.R
import app.atlas.geo.LatLon
import app.atlas.model.Place
import app.atlas.places.OpenStatus
import app.atlas.places.OpeningHours
import app.atlas.places.Presets
import app.atlas.places.Symbols
import app.atlas.places.Localise
import app.atlas.geo.Geo
import app.atlas.ui.kit.Atlas
import app.atlas.ui.kit.OpenWords
import app.atlas.ui.kit.PlaceSummary
import app.atlas.ui.kit.Symbol
import app.atlas.ui.kit.SymbolTile
import app.atlas.ui.kit.WalkTime
import app.atlas.ui.kit.contentColourFor
import app.atlas.ui.kit.shortDistance
import app.atlas.ui.kit.walkMinutes
import java.time.LocalDateTime

/** How open a place is, as a colour family: open, closing soon, closed, or not known. */
enum class Tone { OPEN, SOON, CLOSED, UNKNOWN }

/** The tone of [words]: what colours the walk minutes and the status words of a row. */
fun toneOf(words: OpenWords?): Tone = when (words) {
    null -> Tone.UNKNOWN
    OpenWords.Always, OpenWords.Open, is OpenWords.OpenUntil -> Tone.OPEN
    is OpenWords.ClosesIn -> Tone.SOON
    is OpenWords.Opens, OpenWords.Closed -> Tone.CLOSED
}

/** The colour of a [Tone] in the current theme. */
@Composable
fun toneColour(t: Tone): Color = when (t) {
    Tone.OPEN -> Atlas.colors.open
    Tone.SOON -> Atlas.colors.caution
    Tone.CLOSED -> Atlas.colors.inkSoft
    Tone.UNKNOWN -> Atlas.colors.ink
}

private const val FAR_WALK_S = 30 * 60.0

/** Walking minutes for [metres] that are worth walking, a distance for farther ("2 h 40 on foot" is not the point). */
fun walkOrDistance(metres: Double): String =
    if (WalkTime.seconds(metres) <= FAR_WALK_S) walkMinutes(WalkTime.seconds(metres)) else shortDistance(metres)

/**
 * What a row says about a place, worked out once: its name and kind, symbol and colour, its open status (holiday
 * aware when [open] comes from a search that knew the country) and how far it is.
 */
@Immutable
data class PlaceLine(val summary: PlaceSummary, val words: OpenWords?, val stepFree: Boolean) {
    val place: Place get() = summary.place
    val tone: Tone get() = toneOf(words)

    /** Walking minutes for a near place, a distance for a far one (a walk of over half an hour is not the point). */
    val trailing: String?
        get() = summary.distanceM?.let(::walkOrDistance)

    companion object {
        /** The line for [place] seen from [from] at [now]; [open] overrides the status read without holidays. */
        fun of(place: Place, from: LatLon?, lang: String?, now: LocalDateTime, open: OpenStatus? = null, name: String? = null, distanceM: Double? = null): PlaceLine {
            val base = PlaceSummary.of(place, from, lang, now)
            val s = base.copy(
                name = name ?: base.name,
                open = open ?: base.open,
                distanceM = distanceM ?: base.distanceM,
            )
            return PlaceLine(s, OpenWords.of(s, now), place.tags["wheelchair"] == "yes")
        }
    }
}

/**
 * A row of the result lists: a symbol tile (or a custom [leading] one), the bold name, a line whose first words are the
 * open status in its colour, and a big right-hand value (walk minutes, a distance, an arrow) in [trailingColour].
 */
@Composable
fun ResultRow(
    title: String,
    line: AnnotatedString?,
    modifier: Modifier = Modifier,
    symbol: String? = null,
    tile: Color = Atlas.colors.inkSoft,
    leading: (@Composable () -> Unit)? = null,
    trailing: String? = null,
    trailingSymbol: String? = null,
    trailingColour: Color = Atlas.colors.ink,
    badge: String? = null,
    titleColour: Color = Atlas.colors.ink,
    tileSize: Dp = 48.dp,
    titleLines: Int = 1,
    onClick: (() -> Unit)? = null,
) {
    Row(
        modifier
            .fillMaxWidth()
            .heightIn(min = 68.dp)
            .then(if (onClick != null) Modifier.clickable(role = Role.Button, onClick = onClick) else Modifier)
            .padding(vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        when {
            leading != null -> leading()
            symbol != null -> SymbolTile(symbol, tile, size = tileSize)
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = Atlas.type.name, color = titleColour, maxLines = titleLines, overflow = TextOverflow.Ellipsis)
            if (line != null) Text(line, style = Atlas.type.caption, color = Atlas.colors.inkSoft, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (badge != null) Symbol(badge, tint = Atlas.colors.go, size = 16.dp)
        if (trailing != null) Text(trailing, style = Atlas.type.numeralSmall.copy(fontWeight = FontWeight.ExtraBold), color = trailingColour, textAlign = TextAlign.End, maxLines = 1)
        if (trailingSymbol != null) Symbol(trailingSymbol, tint = trailingColour, size = 20.dp)
    }
}

/** The status words in their colour, then the other [parts] in grey, joined with " · ". */
@Composable
fun statusLine(line: PlaceLine, now: LocalDateTime, vararg parts: String?): AnnotatedString? {
    val open = openWords(line)?.let { text -> text to toneColour(line.tone) }
    return joinLine(open, parts.filterNotNull().filter { it.isNotBlank() })
}

/**
 * A row's open status as the boards write it: "open until 23:00" (midnight as 24:00), "closes in 25 min",
 * "closed · opens 7:00" (with the day when not today), "open 24/7"; null when the hours are not mapped.
 */
@Composable
fun openWords(line: PlaceLine): String? = when (val w = line.words) {
    null -> null
    OpenWords.Always -> stringResource(R.string.kit_open_24_7)
    is OpenWords.ClosesIn -> stringResource(R.string.kit_closes_in, w.minutes)
    is OpenWords.OpenUntil -> stringResource(R.string.kit_open_until, untilClock(w.at))
    OpenWords.Open -> stringResource(R.string.kit_open)
    is OpenWords.Opens -> stringResource(
        R.string.kit_closed_opens,
        if (w.sameDay) clock(w.at) else w.at.dayOfWeek.getDisplayName(java.time.format.TextStyle.SHORT, java.util.Locale.getDefault()) + " " + clock(w.at),
    )
    OpenWords.Closed -> stringResource(R.string.kit_closed)
}

/** "[status] · a · b" with the status in its colour; null when there is nothing to say. */
fun joinLine(status: Pair<String, Color>?, rest: List<String>): AnnotatedString? {
    if (status == null && rest.isEmpty()) return null
    return buildAnnotatedString {
        status?.let { (t, c) -> withStyle(SpanStyle(color = c, fontWeight = FontWeight.SemiBold)) { append(t) } }
        for ((i, p) in rest.withIndex()) {
            if (status != null || i > 0) append(" · ")
            append(p)
        }
    }
}

/** A numbered square, blue for the first and ink for the rest, as the Query board ranks its results. */
@Composable
fun RankTile(rank: Int, size: Dp = 48.dp) {
    val c = Atlas.colors
    val bg = if (rank == 1) c.go else c.pill
    Box(Modifier.size(size).clip(RoundedCornerShape(size * 0.28f)).background(bg), contentAlignment = Alignment.Center) {
        Text(rank.toString(), style = Atlas.type.numeralSmall, color = contentColourFor(bg))
    }
}

/** The kind of [place] in [lang] from its preset, or null. */
fun kindOf(place: Place, lang: String?): String? = Presets.match(place.tags)?.name(lang)

/** The one-line address of [place], or null. */
fun addressOf(place: Place): String? = Localise.address(place.tags)

/** The symbol and colour of a place, as the map and the lists show it. */
fun symbolOf(place: Place): Pair<String, Int> = Symbols.symbolFor(place.tags) to Symbols.colourFor(place.tags)

/** Metres from [from] to [place], or null when either is unknown. */
fun distanceTo(from: LatLon?, place: Place): Double? = from?.let { Geo.distance(it, place.location) }

/** True when the hours of [place] parse. */
fun hasHours(place: Place): Boolean = OpeningHours.parseOrNull(place.tags["opening_hours"]) != null

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
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Size
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
import app.atlas.ui.kit.WalkTime
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

/** The colour of a [Tone], as the boards colour it. */
@Composable
fun toneColour(t: Tone): Color = Color(toneArgb(t))

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

/** The Search board's colours for a row's walk minutes: open green, closing amber, closed grey, unknown ink. */
fun toneArgb(t: Tone): Long = when (t) {
    Tone.OPEN -> 0xFF12663A
    Tone.SOON -> 0xFF8A5A00
    Tone.CLOSED -> ROW_SOFT
    Tone.UNKNOWN -> ROW_INK
}

internal const val ROW_INK = 0xFF14161BL
internal const val ROW_SOFT = 0xFF5A5F6CL

/** The Search board's row text: the name in 15 / 800, the line under it in 12, the walk minutes in 13 / 900. */
internal val ROW_TITLE = boardText(15f, 800, 19.5f)
internal val ROW_LINE = boardText(12f, 400, 15.5f)
internal val ROW_VALUE = boardText(13f, 900, 17f, tnum = true)

/**
 * A row of the result lists, as the Search board draws one: 64 px under a hairline (#EEF0F4), a 40 px tile with corners
 * 12 (a symbol on the place's colour, or a custom [leading] one), the name over a grey line 2 px apart, 12 px between
 * them, and a right-hand value (walk minutes, a distance, "→") in [trailingColour].
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
    trailingColour: Color = Color(ROW_INK),
    badge: String? = null,
    titleColour: Color = Color(ROW_INK),
    tileSize: Dp = 40.dp,
    titleLines: Int = 1,
    hairline: Boolean = true,
    onClick: (() -> Unit)? = null,
) {
    Row(
        modifier
            .fillMaxWidth()
            .then(if (hairline) Modifier.drawBehind { drawRect(Color(0xFFEEF0F4), size = Size(size.width, density)) } else Modifier)
            .padding(top = 1.dp)
            .heightIn(min = 64.dp)
            .then(if (onClick != null) Modifier.clickable(role = Role.Button, onClick = onClick) else Modifier),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        when {
            leading != null -> leading()
            symbol != null -> RowTile(symbol, tile, tileSize)
        }
        Column(Modifier.weight(1f).padding(vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = ROW_TITLE, color = titleColour, maxLines = titleLines, overflow = TextOverflow.Ellipsis)
            if (line != null) Text(line, style = ROW_LINE, color = Color(ROW_SOFT), maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (badge != null) Symbol(badge, tint = Color(0xFF2E5BFF), size = 16.dp)
        if (trailing != null) Text(trailing, style = ROW_VALUE, color = trailingColour, textAlign = TextAlign.End, maxLines = 1)
        // The board's arrow is a character in the value's face, not a drawing.
        if (trailingSymbol != null) Text("→", style = ROW_VALUE, color = trailingColour, maxLines = 1)
    }
}

/** The board's tile: [symbol] in white on [colour], a [side] px square with corners 12 (the symbol 22 px of 40). */
@Composable
internal fun RowTile(symbol: String, colour: Color, side: Dp = 40.dp) {
    Box(Modifier.size(side).clip(RoundedCornerShape(side * 0.3f)).background(colour), contentAlignment = Alignment.Center) {
        Symbol(symbol, tint = Color.White, size = side * 0.55f)
    }
}

/** The status words, then the other [parts], joined with " · " in the row's grey (the board colours only the minutes). */
@Composable
fun statusLine(line: PlaceLine, now: LocalDateTime, vararg parts: String?): AnnotatedString? {
    val rest = parts.filterNotNull().filter { it.isNotBlank() }
    val all = listOfNotNull(openWords(line)) + rest
    return if (all.isEmpty()) null else AnnotatedString(all.joinToString(" · "))
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

/** A numbered square as the Query board ranks its results: 40 px, corners 12, blue for the first and ink for the rest. */
@Composable
fun RankTile(rank: Int, size: Dp = 40.dp) {
    Box(Modifier.size(size).clip(RoundedCornerShape(12.dp)).background(Color(if (rank == 1) 0xFF2E5BFF else ROW_INK)), contentAlignment = Alignment.Center) {
        Text(rank.toString(), style = boardText(15f, 700, 19.5f), color = Color.White)
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

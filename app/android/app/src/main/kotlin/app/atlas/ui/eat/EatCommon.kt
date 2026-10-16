package app.atlas.ui.eat

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.layout
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.offset
import app.atlas.R
import app.atlas.nav.LocalServices
import app.atlas.services.PlaceOrigin
import app.atlas.ui.kit.Atlas
import app.atlas.ui.kit.PillButton
import app.atlas.ui.kit.StateMessage
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

/** Lets the content run [x] beyond its slot on both sides, as the boards' wider rows do (padding cannot be negative). */
fun Modifier.bleed(x: Dp): Modifier = layout { measurable, constraints ->
    val px = x.roundToPx()
    val placeable = measurable.measure(constraints.offset(horizontal = 2 * px))
    layout((placeable.width - 2 * px).coerceAtLeast(0), placeable.height) { placeable.place(-px, 0) }
}

/** [base] plus the navigation bar's height: the room a list needs at its foot so nothing hides under the bar (edge to edge). */
@Composable
internal fun bottomRoom(base: Dp): Dp = base + WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()

/** What the open line says, before words: open until, closing soon, closed and when it opens, open 24/7, unknown. */
sealed interface OpenSay {
    data class Until(val at: LocalDateTime) : OpenSay
    data class ClosesSoon(val at: LocalDateTime, val minutes: Long) : OpenSay
    data object Open : OpenSay
    data object Always : OpenSay
    data class Opens(val at: LocalDateTime, val sameDay: Boolean) : OpenSay
    data object Closed : OpenSay

    companion object {
        /** The words for [p] at [at]; null when its hours are not mapped. */
        fun of(p: EatPlace, at: LocalDateTime, area: EatArea): OpenSay? {
            if (p.alwaysOpen) return Always
            return when (p.openness(at, area.hours)) {
                Openness.UNKNOWN -> null
                Openness.OPEN -> p.closesAt(at, area.hours)?.let { Until(it) } ?: Open
                Openness.CLOSING_SOON -> p.closesAt(at, area.hours)!!.let { ClosesSoon(it, java.time.Duration.between(at, it).toMinutes()) }
                Openness.CLOSED -> p.opensAt(at, area.hours)?.let { Opens(it, it.toLocalDate() == at.toLocalDate()) } ?: Closed
            }
        }
    }
}

/** "→ 23:00" (green), "closes 19:30" (amber), "opens 9:00" (grey), "24/7"; null when the hours are not mapped. */
@Composable
internal fun openWords(p: EatPlace, ctx: EatContext): Pair<String, Color>? {
    val c = Atlas.colors
    return when (val s = OpenSay.of(p, ctx.at, ctx.area) ?: return null) {
        is OpenSay.Until -> "→ ${s.at.format(HM)}" to c.open
        is OpenSay.ClosesSoon -> stringResource(R.string.eat_closes_at, s.at.format(HM)) to c.caution
        OpenSay.Open -> stringResource(R.string.kit_open) to c.open
        OpenSay.Always -> "24/7" to c.open
        is OpenSay.Opens -> stringResource(R.string.eat_opens_at, opensText(s, ctx.lang)) to c.inkSoft
        OpenSay.Closed -> stringResource(R.string.kit_closed) to c.inkSoft
    }
}

/** [t] as "9:00" on the day of [from], "Wed 9:00" on another day: a place that opens tomorrow must not look as if it opens today. */
internal fun dayTime(t: LocalDateTime, from: LocalDateTime, lang: String, pattern: DateTimeFormatter = HM): String =
    if (t.toLocalDate() == from.toLocalDate()) t.format(pattern)
    else t.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.forLanguageTag(lang)) + " " + t.format(pattern)

/** "9:00" today, "Wed 9:00" another day. */
internal fun opensText(s: OpenSay.Opens, lang: String): String =
    if (s.sameDay) s.at.format(HM) else s.at.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.forLanguageTag(lang)) + " " + s.at.format(HM)

/**
 * A printed menu line: [start] (a name) as wide as it needs up to what is left, a dotted leader filling the gap
 * (dots of [dot] every [gap], [lift] above the bottom, as a CSS dotted border moved up), and [end] (counts, minutes)
 * flush right, [pad] from the leader. The name gives way first, so the right-hand column always lines up.
 *
 * A board row lined up on its baseline gives its own geometry instead: the row's [height], and the tops of [start],
 * [end] and the dots' band ([dotsTop]) within it.
 */
@Composable
internal fun LeaderLine(
    colour: Color,
    modifier: Modifier = Modifier,
    minLeader: Dp = 18.dp,
    gap: Dp = 4.dp,
    dot: Dp = 1.2.dp,
    lift: Dp? = null,
    pad: Dp = 8.dp,
    height: Dp? = null,
    startTop: Dp = 0.dp,
    endTop: Dp = 0.dp,
    dotsTop: Dp? = null,
    start: @Composable () -> Unit,
    end: @Composable () -> Unit,
) {
    androidx.compose.ui.layout.Layout(
        contents = listOf(start, { Canvas(Modifier) { if (dotsTop != null) dotsAt(colour, gap.toPx(), dot.toPx(), dotsTop.toPx()) else dots(colour, gap.toPx(), dot.toPx(), lift?.toPx()) } }, end),
        modifier = modifier,
    ) { (s, l, e), c ->
        val padPx = pad.roundToPx()
        val endP = e.first().measure(c.copy(minWidth = 0, minHeight = 0))
        val room = (c.maxWidth - endP.width - minLeader.roundToPx() - 2 * padPx).coerceAtLeast(0)
        val startP = s.first().measure(c.copy(minWidth = 0, maxWidth = room, minHeight = 0))
        val leaderW = (c.maxWidth - endP.width - startP.width - 2 * padPx).coerceAtLeast(0)
        val h = height?.roundToPx() ?: maxOf(startP.height, endP.height)
        val leaderP = l.first().measure(androidx.compose.ui.unit.Constraints.fixed(leaderW, h))
        layout(c.maxWidth, h) {
            if (height != null) {
                startP.place(0, startTop.roundToPx())
                endP.place(c.maxWidth - endP.width, endTop.roundToPx())
            } else {
                startP.place(0, (h - startP.height) / 2)
                endP.place(c.maxWidth - endP.width, (h - endP.height) / 2)
            }
            leaderP.place(startP.width + padPx, 0)
        }
    }
}

/** Dots along the leader: [lift] above the bottom when given, else on the line of the lower-case letters' feet. */
private fun androidx.compose.ui.graphics.drawscope.DrawScope.dots(colour: Color, step: Float, dot: Float, lift: Float?) {
    val r = dot / 2f
    val y = if (lift != null) size.height * 0.78f - lift + r else size.height * 0.62f
    var x = r
    while (x < size.width) {
        drawCircle(colour, r, Offset(x, y))
        x += step
    }
}

/** Dots of [dot] every [step] in a band [top] px down, as a CSS dotted border of that width. */
private fun androidx.compose.ui.graphics.drawscope.DrawScope.dotsAt(colour: Color, step: Float, dot: Float, top: Float) {
    val r = dot / 2f
    var x = r
    while (x < size.width) {
        drawCircle(colour, r, Offset(x, top + r))
        x += step
    }
}

/** The network failed and nothing is known: said with a symbol, and why, and a way to try again. */
@Composable
internal fun EatProblem(area: EatArea, modifier: Modifier = Modifier) {
    val services = LocalServices.current
    StateMessage(
        symbol = "ui-cloud-off",
        title = stringResource(R.string.eat_problem),
        text = area.error,
        tint = Atlas.colors.alert,
        modifier = modifier,
        action = {
            PillButton(onClick = { EatRepository.of(services).refresh(area.centre) }) {
                Text(stringResource(R.string.eat_try_again), style = Atlas.type.label)
            }
        },
    )
}

/** Nothing to eat or drink around here, as far as Atlas knows offline. */
@Composable
internal fun EatEmpty(modifier: Modifier = Modifier) {
    StateMessage(symbol = "ui-utensils", title = stringResource(R.string.eat_none_here), text = stringResource(R.string.eat_none_here_why), modifier = modifier)
}

/** Where the places came from, and what that means, as a quiet line under a list. */
@Composable
internal fun sourceLine(area: EatArea): String? = when (area.origin) {
    PlaceOrigin.STORE -> stringResource(R.string.eat_source_store)
    PlaceOrigin.TILES -> stringResource(R.string.eat_source_tiles)
    PlaceOrigin.ONLINE -> stringResource(R.string.eat_source_online)
    null -> null
}

/** A loading line in the screen's own [colour] (the tests wait until it is gone). */
@Composable
internal fun Looking(colour: Color, modifier: Modifier = Modifier, vertical: Dp = 16.dp) {
    Text(
        stringResource(R.string.kit_loading),
        modifier.fillMaxWidth().padding(vertical = vertical),
        style = Atlas.type.caption,
        color = colour,
        textAlign = TextAlign.Center,
    )
}

internal val HM_PADDED: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")

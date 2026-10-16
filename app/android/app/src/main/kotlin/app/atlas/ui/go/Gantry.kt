package app.atlas.ui.go

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import app.atlas.ui.kit.Argb
import app.atlas.ui.kit.CssCorners
import app.atlas.ui.kit.CssShadow
import app.atlas.ui.kit.Symbol
import app.atlas.ui.kit.Sy
import app.atlas.ui.kit.Tx
import app.atlas.ui.kit.argb
import app.atlas.ui.kit.cssLinear

/** The Go board's colours (canvas/project/Go.dc.html): its night sky, the sign green, the service blue. */
internal object GoBoardColours {
    const val PAPER: Argb = 0xFFF3F1EC
    const val INK: Argb = 0xFF14161B
    const val SOFT: Argb = 0xFF4A505E
    const val SUB: Argb = 0xFFB9C3D0
    const val GREEN: Argb = 0xFF0E6B3F
    const val RULE: Argb = 0xFFF4F7F2
    const val BLUE: Argb = 0xFF2E5BFF
    const val READY: Argb = 0xFF7FE0A8
    const val AMBER: Argb = 0xFFFFB547
    const val FOOT: Argb = 0xFF5A5F6C
}

private const val OVERPASS = "Overpass, sans-serif"
private const val GEIST = "Geist, system-ui, sans-serif"

/** The night sky's gradient, as the board's top 420 px. */
internal val GoSky = cssLinear(180f, 0f to 0xFF16202D, 0.55f to 0xFF22303F, 1f to 0xFF2B3949)

/** The board's grid of five: a cell's left edge (cells of 77.6 px from 12 px in). */
internal fun gridX(i: Int): Float = 12f + i * 77.595f

/**
 * A green guide sign of the gantry at [x0] (its tab plate at 184 px, the sign at 204 px): [plate] on the tab, [name]
 * in large letters on up to two lines, the arrow turned to [arrowDeg] (0 up; none when null), and the [footer] row.
 */
@Composable
internal fun GuideSignAt(
    x0: Float, plate: String, name: String, arrowDeg: Float?, description: String, onClick: () -> Unit,
    kind: String? = null, footer: @Composable () -> Unit,
) {
    Box(Modifier.offset(x0.dp, 184.dp).requiredSize(184.dp, 136.dp).semantics(mergeDescendants = true) { contentDescription = description }.button(null, onClick))
    // The tab plate: green with a white rule on three sides, its foot under the sign's edge.
    Row(
        Modifier
            .offset((x0 + 14f).dp, 184.dp)
            .height(22.dp)
            .cssBox(
                bg = GoBoardColours.GREEN, r = CssCorners(6f, 6f, 0f, 0f),
                shadows = listOf(
                    CssShadow(0f, 0f, 0f, 2f, GoBoardColours.GREEN, inset = true), CssShadow(0f, 2f, 0f, 2f, GoBoardColours.RULE, inset = true),
                    CssShadow(2f, 0f, 0f, 2f, GoBoardColours.RULE, inset = true), CssShadow(-2f, 0f, 0f, 2f, GoBoardColours.RULE, inset = true),
                ),
            )
            .padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BoardText(plate, boardType(OVERPASS, 11f, 800, lineHeight = 13.5f, spacing = 0.88f), 0xFFFFFFFF)
    }
    Bx(
        x0, 204f, 184f, 116f, bg = GoBoardColours.GREEN,
        grad = listOf(cssLinear(118f, 0f to 0x21FFFFFF, 0.34f to 0x21FFFFFF, 0.34f to 0x00FFFFFF, 1f to 0x00FFFFFF)),
        r = CssCorners(10f, 10f, 10f, 10f),
        shadows = listOf(CssShadow(0f, 0f, 0f, 4f, GoBoardColours.GREEN, inset = true), CssShadow(0f, 0f, 0f, 6f, GoBoardColours.RULE, inset = true), CssShadow(0f, 12f, 26f, 0f, 0x66000000)),
    )
    SignName(name, kind, x0 + 16f, if (arrowDeg != null) 112f else 152f)
    if (arrowDeg != null) Sy("nav.straight.2", x0 + 134f, 218f, 34f, 34f, tint = 0xFFFFFFFF, rotate = arrowDeg)
    Row(
        Modifier.offset((x0 + 16f).dp, 289.5.dp).height(18.5.dp).widthIn(max = 152.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) { footer() }
}

/** The sign's footer: the mode's symbol (stroked 2.2), the time in bold and what follows it at 80 %. */
@Composable
internal fun SignFooter(mode: GoMode, time: String?, extra: String?) {
    Symbol(mode.board, Modifier.size(18.dp), tint = argb(0xFFFFFFFF), size = 18.dp, weight = 2.2f / mode.stroke)
    if (time != null) BoardText(time, boardType(OVERPASS, 15f, 700, lineHeight = 18.5f, tnum = true), 0xFFFFFFFF)
    if (extra != null) BoardText(extra, boardType(OVERPASS, 15f, if (time != null) 600 else 700, lineHeight = 18.5f, tnum = true), 0xFFFFFFFF, Modifier.alpha(if (time != null) 0.8f else 1f))
}

/** A glass chip of the night sky's row: 34 px, white at 13 % with a hairline ring; [on] lights it. */
@Composable
internal fun NightChip(text: String, on: Boolean = false, onClick: () -> Unit, lead: @Composable () -> Unit) {
    Row(
        Modifier
            .height(34.dp)
            .cssBox(bg = if (on) 0x47FFFFFF else 0x21FFFFFF, r = CssCorners(17f, 17f, 17f, 17f), shadows = listOf(CssShadow(0f, 0f, 0f, 1f, 0x24FFFFFF, inset = true)))
            .semantics { selected = on }
            .button(text, onClick)
            .padding(start = 10.dp, end = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        lead()
        BoardText(text, boardType(GEIST, 13f, 600, lineHeight = 17f, tnum = true), 0xFFFFFFFF)
    }
}

/** The step-free chip of the sheet: white, or blue with "Step-free on" when on. */
@Composable
internal fun StepFreeChip(label: String, on: Boolean, onClick: () -> Unit) {
    val fg = if (on) 0xFFFFFFFF else GoBoardColours.INK
    Row(
        Modifier
            .height(30.dp)
            .cssBox(bg = if (on) GoBoardColours.BLUE else 0xFFFFFFFF, r = CssCorners(15f, 15f, 15f, 15f), shadows = listOf(CssShadow(0f, 0f, 0f, 1f, 0x1A14161B)))
            .semantics { selected = on }
            .button(label, onClick)
            .padding(start = 8.dp, end = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Symbol("fact.step-free", Modifier.size(16.dp), tint = argb(fg), size = 16.dp)
        BoardText(label, boardType("Geist, sans-serif", 12f, 700, lineHeight = 15.5f), fg)
    }
}

/**
 * A mode's round button of "How are you going?" in the board's grid of five ([col], [row]): the 48 px disc, blue with
 * the symbol white when chosen, white otherwise; the symbol stroked 1.8 as the board draws it; the label under it.
 */
@Composable
internal fun ModeCell(col: Int, row: Int, symbol: String, stroke: Float, label: String, selected: Boolean, onClick: () -> Unit) {
    val x = gridX(col) + 14.8f
    val y = if (row == 0) 442f else 521.5f
    Box(Modifier.offset(gridX(col).dp, y.dp).requiredSize(77.6.dp, 67.5.dp).semantics { this.selected = selected }.button(label, onClick))
    Bx(
        x, y, 48f, 48f, bg = if (selected) GoBoardColours.BLUE else 0xFFFFFFFF, r = CssCorners(24f, 24f, 24f, 24f),
        shadows = if (selected) listOf(CssShadow(0f, 6f, 14f, 0f, 0x662E5BFF)) else listOf(CssShadow(0f, 2f, 6f, 0f, 0x1A181C30), CssShadow(0f, 0f, 0f, 1f, 0x0F14161B)),
    )
    Sw(symbol, x + 12.5f, y + 12.5f, 23f, 23f, tint = if (selected) 0xFFFFFFFF else GoBoardColours.INK, weight = 1.8f / stroke)
    Tl(label, gridX(col), y + 52f, 77.6f, 15.5f, GEIST, 12f, weight = if (selected) 800 else 600, lineHeight = 15.5f, align = TextAlign.Center)
}

/**
 * A blue road-service sign of "Keep it moving" in grid column [col]: the 56 px blue square with its white rule, the
 * service's [symbol] in white (or the "P" of parking when null), and its [label] and [value] under it.
 */
@Composable
internal fun ServiceSignAt(col: Int, symbol: String?, label: String, value: String, onClick: () -> Unit) {
    val x = gridX(col) + 10.8f
    Box(Modifier.offset(gridX(col).dp, 624.dp).requiredSize(77.6.dp, 90.dp).button("$label, $value", onClick))
    Bx(
        x, 624f, 56f, 56f, grad = listOf(cssLinear(160f, 0f to 0xFF2461B0, 1f to 0xFF1A4C8F)), r = CssCorners(8f, 8f, 8f, 8f),
        shadows = listOf(CssShadow(0f, 0f, 0f, 3f, 0xFF1D4F91, inset = true), CssShadow(0f, 0f, 0f, 5f, GoBoardColours.RULE, inset = true), CssShadow(0f, 4f, 10f, 0f, 0x4C1D4F91)),
    )
    if (symbol != null) Sy(symbol, x + 13f, 637f, 30f, 30f, tint = 0xFFFFFFFF)
    else Tx("P", x + 17.05f, 632f, 21.9f, 43f, OVERPASS, 34f, weight = 900, colour = 0xFFFFFFFF, lineHeight = 34f)
    Tl(label, gridX(col), 684f, 77.6f, 15.5f, GEIST, 12f, weight = 700, lineHeight = 14.4f, align = TextAlign.Center)
    Tl(value, gridX(col), 698.9f, 77.6f, 14f, GEIST, 11f, colour = GoBoardColours.SOFT, lineHeight = 13.2f, align = TextAlign.Center, tnum = true)
}

/** A white card of the board's bottom row at [x]: a 28 px [badge], the [title] and the [sub] line under it. */
@Composable
internal fun GoCardAt(x: Float, title: String, sub: String, description: String, onClick: () -> Unit, badge: @Composable () -> Unit) {
    Bx(x, 724f, 186f, 52f, bg = 0xFFFFFFFF, r = CssCorners(16f, 16f, 16f, 16f), shadows = listOf(CssShadow(0f, 2f, 8f, 0f, 0x14181C30)), modifier = Modifier.button(description, onClick))
    Box(Modifier.offset((x + 12f).dp, 736.dp).requiredSize(28.dp), contentAlignment = Alignment.Center) { badge() }
    Tl(title, x + 50f, 733.88f, 124f, 17f, GEIST, 13f, weight = 700, lineHeight = 16.25f)
    Tl(sub, x + 50f, 750.13f, 124f, 15.5f, GEIST, 12f, colour = GoBoardColours.SOFT, lineHeight = 15f, tnum = true)
}

/** A round badge of a card: [colour] with [letter] in white, as the board's line badge. */
@Composable
internal fun LetterBadge(letter: String, colour: Argb) {
    Box(Modifier.requiredSize(28.dp).cssBox(bg = colour, r = CssCorners(14f, 14f, 14f, 14f)), contentAlignment = Alignment.Center) {
        BoardText(letter, boardType(GEIST, 15f, 800, lineHeight = 19.5f), 0xFFFFFFFF)
    }
}

/** A label above content, for the sheet's two headings ("How are you going?", "Keep it moving"). */
@Composable
internal fun SheetHeading(text: String, y: Float) {
    Tl(text, 16f, y, 270f, 21.5f, OVERPASS, 17f, weight = 800, lineHeight = 21.5f, modifier = Modifier.semantics { heading() })
}


/**
 * The sign's name beside the arrow: two lines of 21 px Overpass at a line height of 1.05, as the board letters it, the
 * [kind] ("Home") over the name when the name fits one line; a name whose longest word or whole will not fit is
 * lettered smaller (down to 15 px), as a sign maker would.
 */
@Composable
private fun SignName(name: String, kind: String?, x: Float, w: Float) {
    val measurer = rememberTextMeasurer(cacheSize = 16)
    val density = LocalDensity.current
    val (lines, size) = remember(name, kind, w, density) {
        val max = with(density) { w.dp.roundToPx() }
        fun style(s: Float) = boardType(OVERPASS, s, 800, lineHeight = s * 1.05f)
        fun width(t: String, s: Float) = measurer.measure(dotted(t, OVERPASS), style(s), maxLines = 1, softWrap = false).size.width
        if (kind != null && width(name, 21f) <= max) return@remember listOf(kind, name) to 21f
        val words = name.split(' ').filter { it.isNotEmpty() }
        fun measure(s: Float) = measurer.measure(dotted(name, OVERPASS), style(s), constraints = Constraints(maxWidth = max))
        var s = 21f
        var whole = measure(s)
        while (s > 15f && !(words.all { width(it, s) <= max } && whole.lineCount <= 2)) {
            s -= 1f
            whole = measure(s)
        }
        // A third line's words join the second, which then ends in an ellipsis.
        val first = name.substring(whole.getLineStart(0), whole.getLineEnd(0, visibleEnd = true)).trim()
        val second = if (whole.lineCount > 1) name.substring(whole.getLineStart(1)).trim() else null
        listOfNotNull(first, second) to s
    }
    // Each line in its own line box, 1.05 of the size apart, as the board's two spans.
    val box = size * 26.5f / 21f
    for ((i, line) in lines.withIndex()) {
        Tl(line, x, 215.5f + i * size * 1.05f, w, box, OVERPASS, size, weight = 800, colour = 0xFFFFFFFF, lineHeight = size * 1.05f)
    }
}

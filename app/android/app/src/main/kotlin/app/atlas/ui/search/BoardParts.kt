package app.atlas.ui.search

import androidx.compose.foundation.clickable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.Constraints
import kotlin.math.roundToInt
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.ClipOp
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.addOutline
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.max
import androidx.compose.ui.unit.sp
import app.atlas.R
import app.atlas.ui.kit.Argb
import app.atlas.ui.kit.BoardFonts
import app.atlas.ui.kit.CssBorder
import app.atlas.ui.kit.CssCorners
import app.atlas.ui.kit.CssGradient
import app.atlas.ui.kit.CssShadow
import app.atlas.ui.kit.Symbol
import app.atlas.ui.kit.argb
import app.atlas.ui.kit.cssShadow
import app.atlas.ui.kit.frosted
import kotlin.math.max

/*
 * The generated boards' elements for screens laid out by Compose: El's box as a modifier (its size from the layout, so
 * rows and chips grow with live text), Tx's text as a style, and Sy's symbol, each with the boards' own numbers.
 */

/** The boards' faces by CSS name. */
internal const val GEIST = "Geist"
internal const val GEIST_MONO = "Geist Mono"
internal const val SERIF = "Instrument Serif"
internal const val CAVEAT = "Caveat"

/** Tx's text style for a layout: the face by CSS name, size, weight, line box (centred, as CSS), spacing and figures. */
internal fun boardText(
    size: Float,
    weight: Int = 400,
    lineHeight: Float = 0f,
    family: String = GEIST,
    italic: Boolean = false,
    spacing: Float = 0f,
    tnum: Boolean = false,
    align: TextAlign = TextAlign.Unspecified,
): TextStyle = TextStyle(
    fontFamily = BoardFonts.of(family),
    fontSize = size.sp,
    fontWeight = FontWeight(weight),
    fontStyle = if (italic) FontStyle.Italic else FontStyle.Normal,
    lineHeight = if (lineHeight > 0f) lineHeight.sp else TextUnit.Unspecified,
    lineHeightStyle = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.None),
    letterSpacing = if (spacing != 0f) (spacing / size).em else TextUnit.Unspecified,
    textAlign = align,
    fontFeatureSettings = if (tnum) "tnum" else null,
)

/** One line of board text in a layout, ellipsised when the live text is longer than the room. */
@Composable
internal fun BoardLine(text: String, style: TextStyle, colour: Argb, modifier: Modifier = Modifier, lines: Int = 1) {
    Text(text, modifier, style = style, color = argb(colour), maxLines = lines, overflow = TextOverflow.Ellipsis, softWrap = lines > 1)
}

/** A board symbol in a [side] square, tinted as the board tints it. */
@Composable
internal fun BoardSymbol(id: String, side: Float, tint: Argb, modifier: Modifier = Modifier, description: String? = null) {
    Symbol(id, modifier.size(side.dp), tint = argb(tint), size = side.dp, contentDescription = description)
}

/** A circle of [r] board px, as the boards round their buttons and pills. */
internal fun round(r: Float) = CssCorners(r, r, r, r)

/**
 * El's box at the size the layout gives it: shadows, glass ([blur] over the backdrop), background, gradients, border and
 * inset shadows, exactly as El draws them; the content (and a ripple) is clipped to its corners.
 */
internal fun Modifier.boardBox(
    bg: Argb = 0,
    r: CssCorners? = null,
    shadows: List<CssShadow> = emptyList(),
    border: CssBorder? = null,
    grad: List<CssGradient> = emptyList(),
    blur: Float = 0f,
    saturate: Float = 1f,
): Modifier {
    val shape: Shape = r ?: SQUARE
    var m = this
    for (s in shadows) if (!s.inset) m = m.cssShadow(shape, y = s.y.dp, blur = s.blur.dp, colour = argb(s.colour), x = s.x.dp, spread = s.spread.dp)
    if (blur > 0f) m = m.frosted(shape, argb(bg), blur.dp, saturate)
    m = m.drawBehind {
        val path = Path().apply { addOutline(shape.createOutline(size, layoutDirection, this@drawBehind)) }
        if (blur <= 0f && bg != 0L) drawPath(path, argb(bg))
        for (g in grad.asReversed()) drawPath(path, g.brush(size, density))
        border?.let { b ->
            val d = density
            val o = (r ?: SQUARE).radiiPx(size, d)
            val inner = Path().apply {
                addRoundRect(
                    RoundRect(
                        left = b.left * d, top = b.top * d, right = size.width - b.right * d, bottom = size.height - b.bottom * d,
                        topLeftCornerRadius = CornerRadius(max(0f, o[0] - b.left * d), max(0f, o[0] - b.top * d)),
                        topRightCornerRadius = CornerRadius(max(0f, o[1] - b.right * d), max(0f, o[1] - b.top * d)),
                        bottomRightCornerRadius = CornerRadius(max(0f, o[2] - b.right * d), max(0f, o[2] - b.bottom * d)),
                        bottomLeftCornerRadius = CornerRadius(max(0f, o[3] - b.left * d), max(0f, o[3] - b.bottom * d)),
                    ),
                )
            }
            val cols = b.colours
            clipPath(inner, clipOp = ClipOp.Difference) {
                if (cols.all { it == cols[0] }) {
                    drawPath(path, argb(cols[0]))
                } else clipPath(path) {
                    if (b.top > 0f) drawRect(argb(cols[0]), size = Size(size.width, b.top * d))
                    if (b.right > 0f) drawRect(argb(cols[1]), topLeft = Offset(size.width - b.right * d, 0f), size = Size(b.right * d, size.height))
                    if (b.bottom > 0f) drawRect(argb(cols[2]), topLeft = Offset(0f, size.height - b.bottom * d), size = Size(size.width, b.bottom * d))
                    if (b.left > 0f) drawRect(argb(cols[3]), size = Size(b.left * d, size.height))
                }
            }
        }
        for (s in shadows) if (s.inset) clipPath(path) {
            drawPath(path, argb(s.colour), style = Stroke(width = (s.blur + s.spread) * density))
        }
    }
    return m.clip(shape)
}

private val SQUARE = CssCorners(0f, 0f, 0f, 0f)

/** A one-colour CSS border on all four sides. */
internal fun ring(width: Float, colour: Argb) = CssBorder(width, width, width, width, longArrayOf(colour, colour, colour, colour))

/** A CSS `box-shadow: 0 0 0 [spread] [colour]` ring. */
internal fun spreadRing(spread: Float, colour: Argb) = CssShadow(0f, 0f, 0f, spread, colour)

/**
 * How far a screen moves down from its board: the boards keep their top 40 px for the status bar, a phone's may be
 * taller.
 */
@Composable
internal fun boardShift(): Dp = max(0.dp, WindowInsets.statusBars.asPaddingValues().calculateTopPadding() - 40.dp)

/**
 * A round board button of [side] px: [bg] with a [symbol] of [glyph] px in [tint], a [description] for accessibility.
 */
@Composable
internal fun BoardRoundButton(
    symbol: String,
    description: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    side: Float = 44f,
    glyph: Float = 20f,
    bg: Argb = 0xFFFFFFFF,
    tint: Argb = 0xFF14161B,
    shadows: List<CssShadow> = emptyList(),
    blur: Float = 0f,
) {
    Box(
        modifier
            .size(side.dp)
            .boardBox(bg, round(side / 2), shadows, blur = blur)
            .clickable(role = Role.Button, onClick = onClick)
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) { BoardSymbol(symbol, glyph, tint) }
}

/**
 * The boards' title row at y 42: the 44 px white back button, the serif title 10 px after it (Instrument Serif, italic,
 * line height 1) and [actions] at the right, 16 px from the sides.
 */
@Composable
internal fun BoardTitleRow(
    title: String,
    ink: Argb,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    size: Float = 34f,
    actions: @Composable RowScope.() -> Unit = {},
) {
    Row(
        modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 42.dp).height(44.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BoardRoundButton("ui.back", stringResource(R.string.kit_back), onBack, tint = ink)
        Text(
            title, Modifier.weight(1f).semantics { heading() },
            style = boardText(size, 400, size, family = SERIF, italic = true), color = argb(ink), maxLines = 1, overflow = TextOverflow.Ellipsis,
        )
        actions()
    }
}

/**
 * Board text whose line height is under the face's own (Instrument Serif or Caveat at `line-height: 1`): laid out as
 * CSS lays it, [lines] line boxes of the style's line height, the glyphs centred in them, where Compose would keep the
 * face's full ascent and descent around the first and last lines.
 */
@Composable
internal fun BoardBlock(text: String, style: TextStyle, colour: Argb, modifier: Modifier = Modifier, lines: Int = 1) {
    var count by remember { mutableIntStateOf(1) }
    Text(
        text,
        modifier.layout { m, c ->
            val p = m.measure(c.copy(minHeight = 0, maxHeight = Constraints.Infinity))
            val h = minOf(p.height, (style.lineHeight.toPx() * count).roundToInt())
            layout(p.width, h) { p.place(0, (h - p.height) / 2) }
        },
        style = style, color = argb(colour), maxLines = lines, overflow = TextOverflow.Ellipsis, softWrap = lines > 1,
        onTextLayout = { count = it.lineCount },
    )
}

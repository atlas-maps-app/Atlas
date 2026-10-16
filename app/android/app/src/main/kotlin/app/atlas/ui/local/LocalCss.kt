package app.atlas.ui.local

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.ClipOp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.asAndroidPath
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.addOutline
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.max
import androidx.compose.ui.unit.sp
import app.atlas.places.BoardSymbols
import app.atlas.ui.kit.Argb
import app.atlas.ui.kit.BoardFonts
import app.atlas.ui.kit.CssBorder
import app.atlas.ui.kit.CssCorners
import app.atlas.ui.kit.CssGradient
import app.atlas.ui.kit.CssShadow
import app.atlas.ui.kit.argb
import app.atlas.ui.kit.cssShadow
import app.atlas.ui.kit.drawSymbol
import app.atlas.ui.kit.frosted
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

/*
 * The generated boards' elements for the local screens laid out by Compose: Tx's text as a style in its CSS line box,
 * El's box as a modifier (its size from the layout, so rows grow with live data) and Sy's symbol fitted as SVG fits it,
 * each with the boards' own numbers.
 */

/** The local boards' faces by CSS name. */
internal const val GEIST = "Geist"
internal const val SERIF = "Instrument Serif"
internal const val CAVEAT = "Caveat"
internal const val CORMORANT = "Cormorant Garamond"
internal const val GEIST_MONO = "Geist Mono"

/**
 * Tx's text style: the face by CSS name, [size], [weight], [italic], [colour], the CSS line box [lineHeight], letter
 * [spacing] in px, tabular figures and a CSS text [shadow] (offset and blur in px, drawn by [BoardLine] at the density).
 */
internal fun boardStyle(
    size: Float, weight: Int = 400, colour: Argb = 0xFF14161B, lineHeight: Float = 0f, family: String = GEIST,
    italic: Boolean = false, spacing: Float = 0f, tnum: Boolean = false, align: TextAlign = TextAlign.Unspecified,
    shadow: CssShadow? = null,
): TextStyle = TextStyle(
    color = argb(colour),
    fontFamily = BoardFonts.of(family),
    fontSize = size.sp,
    fontWeight = FontWeight(weight),
    fontStyle = if (italic) FontStyle.Italic else FontStyle.Normal,
    lineHeight = if (lineHeight > 0f) lineHeight.sp else TextUnit.Unspecified,
    lineHeightStyle = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.None),
    letterSpacing = if (spacing != 0f) (spacing / size).em else TextUnit.Unspecified,
    textAlign = align,
    fontFeatureSettings = if (tnum) "tnum" else null,
    shadow = shadow?.let { Shadow(argb(it.colour), Offset(it.x, it.y), it.blur) },
)

/**
 * A CSS line box [h] px high: the text's own height centred in it, as CSS shares the leading out above and below, even
 * when the line is shorter than the font (which Compose's line height will not do).
 */
internal fun Modifier.lineBox(h: Float): Modifier = layout { m, c ->
    val p = m.measure(c.copy(minHeight = 0, maxHeight = Constraints.Infinity))
    val box = (h * density).roundToInt()
    layout(p.width, box) { p.place(0, ((box - p.height) / 2f).roundToInt()) }
}

/**
 * One line of board text in its CSS line box (the style's line height), on one line, ellipsised when the live text runs
 * out of room. The style's shadow is CSS's: offset and blur in px, the blur a Gaussian of σ = blur / 2.
 */
@Composable
internal fun BoardLine(text: String, style: TextStyle, modifier: Modifier = Modifier, colour: Color = Color.Unspecified, overflow: TextOverflow = TextOverflow.Ellipsis) {
    val h = if (style.lineHeight.isSp) style.lineHeight.value else style.fontSize.value * 1.2f
    val d = LocalDensity.current.density
    val shadow = style.shadow?.let { Shadow(it.color, it.offset * d, it.blurRadius * CSS_BLUR * d) }
    Text(
        text, modifier.lineBox(h),
        style = style.copy(lineHeight = TextUnit.Unspecified, shadow = shadow),
        color = colour, maxLines = 1, softWrap = false, overflow = overflow,
    )
}

/** Board text over several lines of the style's line height (CSS wraps at the box's width), at most [lines]. */
@Composable
internal fun BoardPara(text: String, style: TextStyle, modifier: Modifier = Modifier, lines: Int = Int.MAX_VALUE, colour: Color = Color.Unspecified) {
    Text(text, modifier, style = style, color = colour, maxLines = lines, overflow = TextOverflow.Ellipsis)
}

/**
 * El's box at the size the layout gives it, drawn as El draws it: outer shadows (CSS order, the first on top), glass
 * ([blur] over the backdrop, [saturate]d), background [bg], gradients [grad] (the first on top), inset shadows inside the
 * padding box, and the [border], within the corners [r]; the content is clipped to the corners when [clip].
 */
internal fun Modifier.boardBox(
    bg: Argb = 0, r: CssCorners? = null, shadows: List<CssShadow> = emptyList(), border: CssBorder? = null,
    grad: List<CssGradient> = emptyList(), blur: Float = 0f, saturate: Float = 1f, clip: Boolean = true,
): Modifier {
    val shape = r ?: SQUARE
    var m = this
    for (s in shadows.asReversed()) if (!s.inset) m = m.cssShadow(shape, y = s.y.dp, blur = s.blur.dp, colour = argb(s.colour), x = s.x.dp, spread = s.spread.dp)
    if (blur > 0f) m = m.frosted(shape, argb(bg), blur.dp, saturate)
    m = m.drawBehind { drawBoardBox(shape, if (blur > 0f) 0 else bg, grad, border, shadows) }
    return if (clip) m.clip(shape) else m
}

/** Draws a board box filling this scope: [bg], the gradients, the inset [shadows] and the border, within the corners [r]. */
internal fun DrawScope.drawBoardBox(r: CssCorners, bg: Argb = 0, grad: List<CssGradient> = emptyList(), border: CssBorder? = null, shadows: List<CssShadow> = emptyList()) {
    val path = Path().apply { addOutline(r.createOutline(size, layoutDirection, this@drawBoardBox)) }
    if (bg != 0L) drawPath(path, argb(bg))
    for (g in grad.asReversed()) drawPath(path, g.brush(size, density))
    if (shadows.any { it.inset }) {
        val pad = paddingBox(r, border)
        for (s in shadows.asReversed()) if (s.inset) drawInset(pad, s)
    }
    border?.let { b ->
        val d = density
        val o = r.radiiPx(size, d)
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
}

/** CSS's padding box in px: the box inside its border, its radii (x, y per corner) shrunk by the border widths. */
private class PaddingBox(val left: Float, val top: Float, val right: Float, val bottom: Float, val radii: FloatArray) {
    fun path(inset: Float = 0f, dx: Float = 0f, dy: Float = 0f): Path? {
        if (right - left - 2 * inset <= 0f || bottom - top - 2 * inset <= 0f) return null
        return Path().apply {
            addRoundRect(
                RoundRect(
                    left + inset + dx, top + inset + dy, right - inset + dx, bottom - inset + dy,
                    topLeftCornerRadius = CornerRadius(max(0f, radii[0] - inset), max(0f, radii[1] - inset)),
                    topRightCornerRadius = CornerRadius(max(0f, radii[2] - inset), max(0f, radii[3] - inset)),
                    bottomRightCornerRadius = CornerRadius(max(0f, radii[4] - inset), max(0f, radii[5] - inset)),
                    bottomLeftCornerRadius = CornerRadius(max(0f, radii[6] - inset), max(0f, radii[7] - inset)),
                ),
            )
        }
    }
}

private fun DrawScope.paddingBox(r: CssCorners, b: CssBorder?): PaddingBox {
    val d = density
    val o = r.radiiPx(size, d)
    val l = (b?.left ?: 0f) * d
    val t = (b?.top ?: 0f) * d
    val rt = (b?.right ?: 0f) * d
    val bt = (b?.bottom ?: 0f) * d
    return PaddingBox(
        l, t, size.width - rt, size.height - bt,
        floatArrayOf(max(0f, o[0] - l), max(0f, o[0] - t), max(0f, o[1] - rt), max(0f, o[1] - t), max(0f, o[2] - rt), max(0f, o[2] - bt), max(0f, o[3] - l), max(0f, o[3] - bt)),
    )
}

/**
 * One inset `box-shadow` as CSS draws it: inside the padding box, the colour everywhere outside a hole that is the
 * padding box shrunk by the spread and moved by the offset, blurred by a Gaussian of σ = blur / 2.
 */
private fun DrawScope.drawInset(pad: PaddingBox, s: CssShadow) {
    val clip = pad.path() ?: return
    val d = density
    val colour = argb(s.colour)
    val hole = pad.path(s.spread * d, s.x * d, s.y * d)
    clipPath(clip) {
        if (hole == null) {
            drawRect(colour)
        } else if (s.blur <= 0f) {
            clipPath(hole, clipOp = ClipOp.Difference) { drawRect(colour) }
        } else {
            val sigma = s.blur * d / 2f
            val m = 3f * sigma + abs(s.x * d) + abs(s.y * d) + abs(s.spread * d)
            val ring = Path().apply {
                fillType = PathFillType.EvenOdd
                addRect(Rect(-m, -m, size.width + m, size.height + m))
                addPath(hole)
            }
            val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                color = s.colour.toInt()
                // Skia's blur radius: σ = 0.57735 · radius + 0.5
                if (sigma > 0.5f) maskFilter = android.graphics.BlurMaskFilter((sigma - 0.5f) / 0.57735f, android.graphics.BlurMaskFilter.Blur.NORMAL)
            }
            drawContext.canvas.nativeCanvas.drawPath(ring.asAndroidPath(), paint)
        }
    }
}

/**
 * A board symbol as its SVG draws it in a [w] × [h] px box: the viewBox fitted inside and centred (SVG's `meet`), in
 * [tint]. A plain Symbol fits the shorter side of its box, which shrinks a symbol drawn wider than it is tall.
 */
@Composable
internal fun BoxSymbol(id: String, w: Float, h: Float, tint: Color, modifier: Modifier = Modifier) {
    val vb = remember(id) { BoardSymbols.icon(id)?.viewBox }
    Canvas(modifier.size(w.dp, h.dp)) {
        val box = vb
        if (box == null) {
            drawSymbol(id, Offset((size.width - size.minDimension) / 2f, (size.height - size.minDimension) / 2f), size.minDimension, tint)
            return@Canvas
        }
        val scale = minOf(size.width / box.width, size.height / box.height)
        val side = scale * maxOf(box.width, box.height)
        drawSymbol(id, Offset((size.width - side) / 2f, (size.height - side) / 2f), side, tint)
    }
}

/**
 * CSS `radial-gradient(ellipse at [fx] [fy], stops…)` with its centre given as fractions of the box (CSS percentages),
 * sized to the farthest corner.
 */
internal fun cssRadialAt(fx: Float, fy: Float, vararg stops: Pair<Float, Argb>): CssGradient = CssGradient { s, d ->
    app.atlas.ui.kit.cssRadial(false, s.width * fx / d, s.height * fy / d, *stops).brush(s, d)
}

/** A Compose colour as a CSS colour (ARGB). */
internal fun Color.css(): Argb = toArgb().toLong() and 0xFFFFFFFFL

/** Lets a box reach [by] past the layout's sides (the board's cabinet is 4 px wider than the page's 16 px margins). */
internal fun Modifier.outdent(by: Dp): Modifier = layout { m, c ->
    val extra = (by * 2).roundToPx()
    val p = m.measure(c.copy(minWidth = (c.minWidth + extra).coerceAtMost(c.maxWidth + extra), maxWidth = if (c.hasBoundedWidth) c.maxWidth + extra else c.maxWidth))
    layout((p.width - extra).coerceAtLeast(0), p.height) { p.place(-extra / 2, 0) }
}

/** The same radius at all four corners. */
internal fun corners(r: Float) = CssCorners(r, r, r, r)

/** A one-colour CSS border on all four sides. */
internal fun ring(width: Float, colour: Argb) = CssBorder(width, width, width, width, longArrayOf(colour, colour, colour, colour))

/** A CSS `box-shadow` list of one. */
internal fun shadow(x: Float, y: Float, blur: Float, colour: Argb, spread: Float = 0f, inset: Boolean = false) = listOf(CssShadow(x, y, blur, spread, colour, inset))

/**
 * How far a screen moves down from its board: the boards keep their top 40 px for the status bar, a phone's may be
 * taller.
 */
@Composable
internal fun boardShift(): Dp = max(0.dp, WindowInsets.statusBars.asPaddingValues().calculateTopPadding() - 40.dp)

private val SQUARE = CssCorners(0f, 0f, 0f, 0f)

/** CSS blur to Android's shadow radius: CSS blurs by σ = blur / 2, Android by σ = 0.57735 r + 0.5 px. */
private const val CSS_BLUR = 0.866f

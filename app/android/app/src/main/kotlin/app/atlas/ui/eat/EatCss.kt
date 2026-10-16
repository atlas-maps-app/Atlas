package app.atlas.ui.eat

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.innerShadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.ClipOp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.addOutline
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.shadow.Shadow as BoxShadow
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
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
import kotlin.math.max
import kotlin.math.roundToInt

/*
 * The boards' CSS for the eat screens' layouts: the same text and boxes as BoardDraw's Tx and El, but as a style and a
 * modifier, so a row whose size follows live data takes its item's exact look from the board.
 */

/**
 * A board text style as Tx sets it: the face by its CSS name, [size], [weight], [italic], [colour], the CSS line box
 * [lineHeight] (half-leading above and below), letter [spacing] in px, tabular figures, the font's own small capitals
 * (CSS `font-variant: small-caps`) and a text [shadow] in CSS px, which [CssLine] draws at the screen's density.
 */
internal fun cssText(
    family: String, size: Float, weight: Int = 400, italic: Boolean = false, colour: Argb = 0xFF14161B,
    lineHeight: Float = 0f, spacing: Float = 0f, tnum: Boolean = false, smallCaps: Boolean = false,
    shadow: CssShadow? = null, align: TextAlign = TextAlign.Unspecified, brush: Brush? = null,
): TextStyle {
    val features = listOfNotNull(if (smallCaps) "smcp" else null, if (tnum) "tnum" else null).joinToString(", ").ifEmpty { null }
    val base = TextStyle(
        fontFamily = BoardFonts.of(family),
        fontSize = size.sp,
        fontWeight = FontWeight(weight),
        fontStyle = if (italic) FontStyle.Italic else FontStyle.Normal,
        lineHeight = if (lineHeight > 0f) lineHeight.sp else TextUnit.Unspecified,
        lineHeightStyle = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.None),
        letterSpacing = if (spacing != 0f) (spacing / size).em else TextUnit.Unspecified,
        textAlign = align,
        fontFeatureSettings = features,
        shadow = shadow?.let { Shadow(argb(it.colour), Offset(it.x, it.y), it.blur) },
    )
    return if (brush != null) base.copy(brush = brush) else base.copy(color = argb(colour))
}

/**
 * A CSS line box [h] px high: the text's own height (its font's ascent to descent) centred in it, as CSS shares out
 * the leading above and below, even when the line is shorter than the font (which Compose's line height will not do).
 */
internal fun Modifier.lineBox(h: Float): Modifier = layout { m, c ->
    val p = m.measure(c.copy(minHeight = 0, maxHeight = Constraints.Infinity))
    val box = (h * density).roundToInt()
    layout(p.width, box) { p.place(0, ((box - p.height) / 2f).roundToInt()) }
}

/**
 * One line of board text in its CSS line box: [style]'s line height is the box, the text keeps its font's own height
 * centred in it, on one line (ellipsised when the room runs out). The style's shadow is CSS's: its offset and blur in
 * CSS px, the blur a Gaussian of sigma = blur / 2.
 */
@Composable
internal fun CssLine(
    text: String, style: TextStyle, modifier: Modifier = Modifier, color: Color = Color.Unspecified,
    overflow: TextOverflow = TextOverflow.Ellipsis, align: TextAlign? = null,
) {
    val h = if (style.lineHeight.isSp) style.lineHeight.value else style.fontSize.value * 1.2f
    val d = LocalDensity.current.density
    val shadow = style.shadow?.let { Shadow(it.color, it.offset * d, it.blurRadius * CSS_BLUR * d) }
    Text(
        text,
        modifier.lineBox(h),
        style = style.copy(lineHeight = TextUnit.Unspecified, shadow = shadow).let { if (align != null) it.copy(textAlign = align) else it },
        color = color,
        maxLines = 1,
        softWrap = false,
        overflow = overflow,
    )
}

/**
 * One board box as El draws it, on whatever size the layout gives: its outer [shadows], background [bg] and
 * gradients [grad] (the first on top), its [border] inside the box, and its inset shadows over them, all within the
 * corners [r]. CSS blurs a shadow by a Gaussian of sigma = blur / 2, which is a shadow radius of 0.866 × blur.
 */
internal fun Modifier.cssBox(
    bg: Argb = 0, grad: List<CssGradient> = emptyList(), r: CssCorners? = null, shadows: List<CssShadow> = emptyList(),
    border: CssBorder? = null,
): Modifier {
    val shape = r ?: SQUARE
    var m = this
    for (s in shadows) if (!s.inset) m = m.cssShadow(shape, y = s.y.dp, blur = s.blur.dp, colour = argb(s.colour), x = s.x.dp, spread = s.spread.dp)
    m = m.drawBehind { drawCssBox(shape, bg, grad, border = border) }
    for (s in shadows) if (s.inset) {
        m = m.innerShadow(shape, BoxShadow(radius = (s.blur * CSS_BLUR).dp, spread = s.spread.dp, color = argb(s.colour), offset = DpOffset(s.x.dp, s.y.dp)))
    }
    return m
}

/** Draws a board box (see [cssBox]) filling this scope; outer shadows are left to the caller, inset ones drawn as rings of blur + spread. */
internal fun DrawScope.drawCssBox(r: CssCorners, bg: Argb = 0, grad: List<CssGradient> = emptyList(), shadows: List<CssShadow> = emptyList(), border: CssBorder? = null) {
    val outline = r.createOutline(size, layoutDirection, this)
    val path = Path().apply { addOutline(outline) }
    if (bg != 0L) drawPath(path, argb(bg))
    for (g in grad.asReversed()) drawPath(path, g.brush(size, density))
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
    for (s in shadows) if (s.inset) clipPath(path) {
        drawPath(path, argb(s.colour), style = Stroke(width = (s.blur + 2 * s.spread) * density))
    }
}

/**
 * A board symbol as its SVG draws it in a [w] × [h] px box: the viewBox fitted inside and centred (SVG's `meet`), in
 * [tint]. A plain [Symbol] fits the shorter side of its box, which shrinks a symbol drawn wider than it is tall.
 */
@Composable
internal fun BoxSymbol(id: String, w: Float, h: Float, tint: Color, modifier: Modifier = Modifier) {
    val vb = remember(id) { BoardSymbols.icon(id)?.viewBox }
    Canvas(modifier.size(w.dp, h.dp)) {
        val box = vb ?: return@Canvas
        val scale = minOf(size.width / box.width, size.height / box.height)
        val side = scale * maxOf(box.width, box.height)
        drawSymbol(id, Offset((size.width - side) / 2f, (size.height - side) / 2f), side, tint)
    }
}

/** A Compose colour as a CSS colour (ARGB). */
internal fun Color.css(): Argb = toArgb().toLong() and 0xFFFFFFFFL

/** A circle's corners for a box [side] px wide (CSS `border-radius: 50%`). */
internal fun round(side: Float) = CssCorners(side / 2, side / 2, side / 2, side / 2)

/** The same radius at all four corners. */
internal fun corners(r: Float) = CssCorners(r, r, r, r)

private val SQUARE = CssCorners(0f, 0f, 0f, 0f)

/** CSS blur to Android's shadow radius: CSS blurs by sigma = blur / 2, Android by sigma = 0.57735 r + 0.5 px. */
private const val CSS_BLUR = 0.866f

/**
 * An SVG `linearGradient` in its default objectBoundingBox units from (0, 0) to (1, 1): along the diagonal of the
 * box [x], [y], [w] × [h] (px), with the bands skewed to the box as SVG skews them.
 */
internal fun svgDiagonal(x: Float, y: Float, w: Float, h: Float, vararg stops: Pair<Float, Color>): Brush {
    val a = 1f / w
    val b = 1f / h
    val n = a * a + b * b
    return Brush.linearGradient(*stops, start = Offset(x, y), end = Offset(x + 2 * a / n, y + 2 * b / n))
}

package app.atlas.ui.shop

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.statusBars
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.ClipOp
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.asAndroidPath
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.addOutline
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.layout
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.max
import androidx.compose.ui.unit.sp
import app.atlas.places.Symbols
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
import kotlin.math.min
import kotlin.math.roundToInt

/*
 * The generated boards' elements (`El`, `Tx`, `Sy`) for the shop screens: the box as a modifier at the size a layout
 * gives it, the text as a style, and a symbol fitted to its box as the browser fits an `<svg>` (viewBox, xMidYMid meet).
 * Every number a screen passes here is its board's.
 */

/** The boards' faces by CSS name. */
internal const val GEIST = "Geist"
internal const val SERIF = "Instrument Serif"
internal const val CAVEAT = "Caveat"
internal const val COURIER = "Courier Prime"
internal const val ELITE = "Special Elite"
internal const val OVERPASS = "Overpass"
internal const val MONO = "Geist Mono"

/** The ink most boards write in. */
internal const val INK: Argb = 0xFF14161B

/** Tx's text as a style: the face by CSS name, size, weight, line box (glyphs centred in it, as CSS), spacing, figures. */
internal fun bt(
    size: Float,
    weight: Int = 400,
    lineHeight: Float = 0f,
    family: String = GEIST,
    italic: Boolean = false,
    spacing: Float = 0f,
    tnum: Boolean = false,
    align: TextAlign = TextAlign.Unspecified,
    decoration: TextDecoration? = null,
    shadow: CssShadow? = null,
): TextStyle = TextStyle(
    fontFamily = BoardFonts.of(family),
    fontSize = size.sp,
    fontWeight = FontWeight(weight),
    fontStyle = if (italic) FontStyle.Italic else FontStyle.Normal,
    lineHeight = if (lineHeight > 0f) lineHeight.sp else TextUnit.Unspecified,
    lineHeightStyle = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.None),
    letterSpacing = if (spacing != 0f) (spacing / size).em else TextUnit.Unspecified,
    textAlign = align,
    textDecoration = decoration,
    fontFeatureSettings = if (tnum) "tnum" else null,
    shadow = shadow?.let { androidx.compose.ui.graphics.Shadow(argb(it.colour), Offset(it.x, it.y), it.blur) },
)

/** One line of board text in a layout, ellipsised when the live text is longer than the room. */
@Composable
internal fun Line(text: String, style: TextStyle, colour: Argb, modifier: Modifier = Modifier, lines: Int = 1) {
    Text(text, modifier, style = style, color = argb(colour), maxLines = lines, overflow = TextOverflow.Ellipsis, softWrap = lines > 1)
}

/**
 * Board text whose line height is under the face's own (Instrument Serif or Caveat at `line-height: 1`): [lines] line
 * boxes of the style's line height with the glyphs centred in them, as CSS lays them out.
 */
@Composable
internal fun Block(text: String, style: TextStyle, colour: Argb, modifier: Modifier = Modifier, lines: Int = 1) =
    Block(androidx.compose.ui.text.AnnotatedString(text), style, colour, modifier, lines)

/** [Block] for text with spans (a figure and its smaller unit). */
@Composable
internal fun Block(text: androidx.compose.ui.text.AnnotatedString, style: TextStyle, colour: Argb, modifier: Modifier = Modifier, lines: Int = 1) {
    var count by remember { mutableIntStateOf(1) }
    Text(
        text,
        modifier.layout { m, c ->
            val p = m.measure(c.copy(minHeight = 0, maxHeight = Constraints.Infinity))
            val h = minOf(p.height, (style.lineHeight.toPx() * count).roundToInt()).coerceAtLeast(c.minHeight)
            layout(p.width, h) { p.place(0, (h - p.height) / 2) }
        },
        style = style, color = argb(colour), maxLines = lines, overflow = TextOverflow.Ellipsis, softWrap = lines > 1,
        onTextLayout = { count = it.lineCount },
    )
}

/** A box at board px ([x], [y]) of [w] × [h]: a layout element placed as the board places it. */
internal fun Modifier.at(x: Float, y: Float, w: Float, h: Float): Modifier = offset(x.dp, y.dp).requiredSize(w.dp, h.dp)

/** A circle or round box of [r] board px. */
internal fun round(r: Float) = CssCorners(r, r, r, r)

/** A one-colour CSS border on all four sides. */
internal fun ring(width: Float, colour: Argb) = CssBorder(width, width, width, width, longArrayOf(colour, colour, colour, colour))

/**
 * El's box at the size the layout gives it: shadows, glass ([blur] over the backdrop), background, gradients, border and
 * inset shadows, as CSS draws them ([shadows] in CSS order, the first on top); the content (and a ripple) is clipped to
 * its corners unless [clip] is false.
 */
internal fun Modifier.boardBox(
    bg: Argb = 0,
    r: CssCorners? = null,
    shadows: List<CssShadow> = emptyList(),
    border: CssBorder? = null,
    grad: List<CssGradient> = emptyList(),
    blur: Float = 0f,
    saturate: Float = 1f,
    clip: Boolean = true,
): Modifier {
    val shape: Shape = r ?: SQUARE
    var m = this
    // CSS paints the first shadow of a list on top: draw them last to first.
    // Sharp shadows (no blur: rings, a hard foot) are drawn here as CSS draws them; soft ones by the kit's shadow.
    for (s in shadows.asReversed()) if (!s.inset && s.blur > 0f) {
        m = m.cssShadow(shape, y = s.y.dp, blur = s.blur.dp, colour = argb(s.colour), x = s.x.dp, spread = s.spread.dp)
    }
    if (blur > 0f) m = m.frosted(shape, argb(bg), blur.dp, saturate)
    m = m.drawWithCache {
        // The box's outline, the border's inner edge and the gradients' brushes, made once per size.
        val path = Path().apply { addOutline(shape.createOutline(size, layoutDirection, this@drawWithCache)) }
        val brushes = grad.asReversed().map { it.brush(size, density) }
        val radii = (r ?: SQUARE).radiiPx(size, density)
        val inner = border?.let { b ->
            val d = density
            Path().apply {
                addRoundRect(
                    RoundRect(
                        left = b.left * d, top = b.top * d, right = size.width - b.right * d, bottom = size.height - b.bottom * d,
                        topLeftCornerRadius = CornerRadius(max(0f, radii[0] - b.left * d), max(0f, radii[0] - b.top * d)),
                        topRightCornerRadius = CornerRadius(max(0f, radii[1] - b.right * d), max(0f, radii[1] - b.top * d)),
                        bottomRightCornerRadius = CornerRadius(max(0f, radii[2] - b.right * d), max(0f, radii[2] - b.bottom * d)),
                        bottomLeftCornerRadius = CornerRadius(max(0f, radii[3] - b.left * d), max(0f, radii[3] - b.bottom * d)),
                    ),
                )
            }
        }
        val sharp = shadows.asReversed().filter { !it.inset && it.blur <= 0f }.map { sh -> sharpShadow(sh, radii) to argb(sh.colour) }
        onDrawBehind {
            // CSS draws an outer shadow only outside the box: the box itself cuts it out.
            if (sharp.isNotEmpty()) clipPath(path, clipOp = ClipOp.Difference) { for ((p, c) in sharp) drawPath(p, c) }
            if (blur <= 0f && bg != 0L) drawPath(path, argb(bg))
            for (b in brushes) drawPath(path, b)
            if (border != null && inner != null) {
                val d = density
                val b = border
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
            for (s in shadows.asReversed()) if (s.inset) clipPath(path) { insetShadow(s, radii) }
        }
    }
    return if (clip) m.clip(shape) else m
}

private val SQUARE = CssCorners(0f, 0f, 0f, 0f)

/**
 * A sharp CSS outer shadow (offset [s].x, y and spread, no blur) of a box whose corners are [radii] (px): the box grown
 * by the spread, its rounded corners grown with it (radius + spread, a square corner stays square), and moved.
 */
private fun androidx.compose.ui.draw.CacheDrawScope.sharpShadow(s: CssShadow, radii: FloatArray): Path {
    val d = density
    val sp = s.spread * d
    fun r(i: Int) = CornerRadius(if (radii[i] > 0f) radii[i] + sp else 0f)
    return Path().apply {
        addRoundRect(
            RoundRect(
                left = -sp + s.x * d, top = -sp + s.y * d, right = size.width + sp + s.x * d, bottom = size.height + sp + s.y * d,
                topLeftCornerRadius = r(0), topRightCornerRadius = r(1), bottomRightCornerRadius = r(2), bottomLeftCornerRadius = r(3),
            ),
        )
    }
}

/**
 * CSS `box-shadow: inset x y blur spread colour` inside a box of corner radii [radii] (px), clipped to it by the
 * caller: the colour everywhere outside the box shrunk by the spread and moved by the offset, its edge blurred by a
 * Gaussian of σ = blur / 2.
 */
private fun androidx.compose.ui.graphics.drawscope.DrawScope.insetShadow(s: CssShadow, radii: FloatArray) {
    val d = density
    val sp = s.spread * d
    fun r(i: Int) = CornerRadius(max(0f, radii[i] - sp))
    val inner = Path().apply {
        addRoundRect(
            RoundRect(
                left = sp + s.x * d, top = sp + s.y * d, right = size.width - sp + s.x * d, bottom = size.height - sp + s.y * d,
                topLeftCornerRadius = r(0), topRightCornerRadius = r(1), bottomRightCornerRadius = r(2), bottomLeftCornerRadius = r(3),
            ),
        )
    }
    if (s.blur <= 0f) {
        clipPath(inner, clipOp = ClipOp.Difference) { drawRect(argb(s.colour)) }
        return
    }
    val reach = s.blur * d * 2 + abs(s.x * d) + abs(s.y * d) + sp
    val frame = Path().apply {
        fillType = PathFillType.EvenOdd
        addRect(androidx.compose.ui.geometry.Rect(-reach, -reach, size.width + reach, size.height + reach))
        addPath(inner)
    }
    val sigma = s.blur * d / 2f
    drawIntoCanvas { c ->
        val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            color = argb(s.colour).toArgb()
            maskFilter = android.graphics.BlurMaskFilter(((sigma - 0.5f) / 0.57735f).coerceAtLeast(0.01f), android.graphics.BlurMaskFilter.Blur.NORMAL)
        }
        c.nativeCanvas.drawPath(frame.asAndroidPath(), paint)
    }
}

/**
 * A board symbol fitted to a [w] × [h] box as the browser fits an `<svg>` with a viewBox (scaled to fit, centred), in
 * [tint] where it has no colour of its own; [rotate] in degrees about its centre.
 */
@Composable
internal fun Sym(id: String, w: Float, h: Float, tint: Argb = INK, modifier: Modifier = Modifier, alpha: Float = 1f, rotate: Float = 0f, description: String? = null) {
    val vb = remember(id) { Symbols.icon(id)?.viewBox }
    var m = modifier.requiredSize(w.dp, h.dp)
    if (alpha < 1f || rotate != 0f) m = m.graphicsLayer {
        this.alpha = alpha
        rotationZ = rotate
    }
    if (description != null) m = m.semantics {
        contentDescription = description
        role = Role.Image
    }
    // An <svg> clips what it draws outside its box.
    Canvas(m.clipToBounds()) {
        val vw = vb?.width ?: 24f
        val vh = vb?.height ?: 24f
        val k = min(size.width / vw, size.height / vh)
        // drawSymbol fits the viewBox's longer side to a square and centres it: that square, centred on the box
        val side = k * max(vw, vh)
        drawSymbol(id, Offset((size.width - side) / 2f, (size.height - side) / 2f), side, argb(tint))
    }
}

/** Sy: a board symbol at its board place. */
@Composable
internal fun SyAt(id: String, x: Float, y: Float, w: Float, h: Float, tint: Argb = INK, alpha: Float = 1f, rotate: Float = 0f) =
    Sym(id, w, h, tint, Modifier.offset(x.dp, y.dp), alpha, rotate)

/** How far a screen's top moves down from its board: the boards keep their top 40 px for the status bar. */
@Composable
internal fun boardTop(): Dp = max(0.dp, WindowInsets.statusBars.asPaddingValues().calculateTopPadding() - 40.dp)

/** How far a screen's bottom chrome rises from its board's place: a navigation bar taller than the boards' margin. */
@Composable
internal fun boardLift(): Dp = max(0.dp, WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() - 16.dp)

/**
 * A CSS flex row whose items grow (`flex-grow: 1`, basis auto): each takes its own width plus an equal share of the
 * room left, [gap] board px apart, all of the row's height; when they do not fit they shrink in proportion.
 */
@Composable
internal fun FlexRow(gap: Float, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Layout(content, modifier) { ms, c ->
        val g = gap.dp.roundToPx()
        val h = if (c.hasBoundedHeight) c.maxHeight else ms.maxOfOrNull { it.minIntrinsicHeight(c.maxWidth) } ?: 0
        val own = ms.map { it.maxIntrinsicWidth(h) }
        val room = c.maxWidth - g * (ms.size - 1).coerceAtLeast(0)
        val sum = own.sum()
        val widths = if (sum <= room) {
            val extra = if (ms.isEmpty()) 0 else (room - sum) / ms.size
            own.map { it + extra }
        } else own.map { (it.toLong() * room / sum.coerceAtLeast(1)).toInt() }
        val ps = ms.mapIndexed { i, m -> m.measure(Constraints.fixed(widths[i].coerceAtLeast(0), h)) }
        layout(c.maxWidth, h) {
            var x = 0
            for (p in ps) {
                p.place(x, 0)
                x += p.width + g
            }
        }
    }
}

/** The colour the boards give a one-colour symbol (bread brown, cheese gold) as ARGB, or null when they draw it in the text colour. */
internal fun hue(id: String): Argb? = app.atlas.places.BoardSymbols.resolve(id)?.hue

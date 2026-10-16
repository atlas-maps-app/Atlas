package app.atlas.ui.go

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.ClipOp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.addOutline
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import app.atlas.ui.kit.Argb
import app.atlas.ui.kit.BoardFonts
import app.atlas.ui.kit.CssBorder
import app.atlas.ui.kit.CssCorners
import app.atlas.ui.kit.CssGradient
import app.atlas.ui.kit.CssShadow
import app.atlas.ui.kit.SymbolPaths
import app.atlas.ui.kit.drawSymbol
import app.atlas.ui.kit.argb
import app.atlas.ui.kit.cssShadow
import app.atlas.ui.kit.frosted
import kotlin.math.max

/*
 * The go screens' share of the board runtime: a board box as a modifier, so a real layout (a row, a list item) takes a
 * board element's exact look; the board's type as a TextStyle; live text at a board line box; and the board's
 * 412 × 915 plane, kept clear of the system bars.
 */

private val Square = CssCorners(0f, 0f, 0f, 0f)

/**
 * A board box's look on any layout: its outer shadows, glass, background colour and gradients, border and inset
 * shadows, as CSS paints them (the first shadow of a list on top; an inset shadow fills the box outside the box shrunk
 * by its spread and moved by its offset).
 */
internal fun Modifier.cssBox(
    bg: Argb = 0, grad: List<CssGradient> = emptyList(), r: CssCorners? = null, shadows: List<CssShadow> = emptyList(),
    blur: Float = 0f, saturate: Float = 1f, border: CssBorder? = null, clip: Boolean = false,
): Modifier {
    val corners = r ?: Square
    var m: Modifier = this
    for (s in shadows.asReversed()) if (!s.inset) m = m.cssShadow(corners, y = s.y.dp, blur = s.blur.dp, colour = argb(s.colour), x = s.x.dp, spread = s.spread.dp)
    if (blur > 0f) m = m.frosted(corners, argb(bg), blur.dp, saturate)
    if ((blur <= 0f && bg != 0L) || grad.isNotEmpty() || border != null || shadows.any { it.inset }) m = m.drawWithCache {
        // The shapes and brushes are built once per size, not on every frame.
        val outer = Path().apply { addOutline(corners.createOutline(size, layoutDirection, this@drawWithCache)) }
        val brushes = grad.asReversed().map { it.brush(size, density) }
        val ring = border?.let { borderHole(size, density, corners, it) }
        val insets = shadows.asReversed().filter { it.inset }.map { it to insetHole(size, density, corners, it) }
        onDrawBehind {
            if (blur <= 0f && bg != 0L) drawPath(outer, argb(bg))
            for (b in brushes) drawPath(outer, b)
            if (border != null && ring != null) drawBorder(outer, ring, border)
            for ((s, hole) in insets) drawInset(outer, hole, s)
        }
    }
    return if (clip) m.clip(corners) else m
}

/** The inside of CSS's border: the box inset by the side widths, its radii shrunk by them. */
private fun borderHole(size: Size, d: Float, r: CssCorners, b: CssBorder): Path {
    val o = r.radiiPx(size, d)
    return Path().apply {
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
}

/** CSS's border: the ring between the box and its [inner] hole, one colour or one per side. */
private fun DrawScope.drawBorder(outer: Path, inner: Path, b: CssBorder) {
    val d = density
    val c = b.colours
    clipPath(inner, clipOp = ClipOp.Difference) {
        if (c.all { it == c[0] }) {
            drawPath(outer, argb(c[0]))
        } else clipPath(outer) {
            if (b.top > 0f) drawRect(argb(c[0]), size = Size(size.width, b.top * d))
            if (b.right > 0f) drawRect(argb(c[1]), topLeft = Offset(size.width - b.right * d, 0f), size = Size(b.right * d, size.height))
            if (b.bottom > 0f) drawRect(argb(c[2]), topLeft = Offset(0f, size.height - b.bottom * d), size = Size(size.width, b.bottom * d))
            if (b.left > 0f) drawRect(argb(c[3]), size = Size(b.left * d, size.height))
        }
    }
}

/**
 * The part of the box an inset `box-shadow` with no blur (all the boards use) leaves unshaded: the box shrunk by its
 * spread and moved by its offset, the radii shrunk by the spread; null when the shadow covers the whole box.
 */
private fun insetHole(size: Size, d: Float, r: CssCorners, s: CssShadow): Path? {
    val sp = s.spread * d
    val left = sp + s.x * d
    val top = sp + s.y * d
    val right = size.width - sp + s.x * d
    val bottom = size.height - sp + s.y * d
    if (right <= left || bottom <= top) return null
    val o = r.radiiPx(size, d)
    return Path().apply {
        addRoundRect(
            RoundRect(
                left, top, right, bottom,
                topLeftCornerRadius = CornerRadius(max(0f, o[0] - sp)),
                topRightCornerRadius = CornerRadius(max(0f, o[1] - sp)),
                bottomRightCornerRadius = CornerRadius(max(0f, o[2] - sp)),
                bottomLeftCornerRadius = CornerRadius(max(0f, o[3] - sp)),
            ),
        )
    }
}

/** One inset shadow: the box outside its [hole] in the shadow's colour. */
private fun DrawScope.drawInset(outer: Path, hole: Path?, s: CssShadow) {
    val colour = argb(s.colour)
    clipPath(outer) {
        if (hole == null) drawRect(colour) else clipPath(hole, clipOp = ClipOp.Difference) { drawRect(colour) }
    }
}

/**
 * A board box at ([x], [y]) sized [w] × [h] board px with the look of [cssBox]; [modifier] (a click, semantics) comes
 * after the placement, so it acts on the box where it is drawn. [content] is placed relative to the box.
 */
@Composable
internal fun Bx(
    x: Float, y: Float, w: Float, h: Float,
    bg: Argb = 0, grad: List<CssGradient> = emptyList(), r: CssCorners? = null, shadows: List<CssShadow> = emptyList(),
    blur: Float = 0f, saturate: Float = 1f, border: CssBorder? = null, alpha: Float = 1f, clip: Boolean = false,
    modifier: Modifier = Modifier, content: @Composable BoxScope.() -> Unit = {},
) {
    var m = Modifier.offset(x.dp, y.dp).requiredSize(w.dp, h.dp)
    if (alpha < 1f) m = m.graphicsLayer { this.alpha = alpha }
    Box(m.cssBox(bg, grad, r, shadows, blur, saturate, border, clip).then(modifier), content = content)
}

/** A board's type by its CSS: the face by name, size, weight and style, line height, letter spacing and figures. */
internal fun boardType(
    family: String, size: Float, weight: Int = 400, italic: Boolean = false, lineHeight: Float = 0f, spacing: Float = 0f,
    tnum: Boolean = false, shadow: CssShadow? = null, align: TextAlign = TextAlign.Start,
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
    shadow = shadow?.let { Shadow(argb(it.colour), Offset(it.x, it.y), it.blur) },
)

/**
 * Live text in a board line box ([x], [y], [w] × [h]) set as the board's [Tx][app.atlas.ui.kit.Tx] sets it, aligned in
 * the box by [align] and ending in an ellipsis when it runs past [w]; [lines] above one wraps it.
 */
@Composable
internal fun Tl(
    text: String, x: Float, y: Float, w: Float, h: Float,
    family: String, size: Float, weight: Int = 400, italic: Boolean = false, colour: Argb = 0xFF14161B,
    lineHeight: Float = 0f, spacing: Float = 0f, align: TextAlign = TextAlign.Start, tnum: Boolean = false,
    shadow: CssShadow? = null, lines: Int = 1, alpha: Float = 1f, modifier: Modifier = Modifier,
) {
    // One line keeps the board's line box; more lines take the height they need.
    var m = if (lines > 1) Modifier.offset(x.dp, y.dp).requiredWidth(w.dp) else Modifier.offset(x.dp, y.dp).requiredSize(w.dp, h.dp)
    if (alpha < 1f) m = m.graphicsLayer { this.alpha = alpha }
    Text(
        dotted(text, family), m.then(modifier),
        style = boardType(family, size, weight, italic, lineHeight, spacing, tnum, shadow, align),
        color = argb(colour), maxLines = lines, softWrap = lines > 1, overflow = TextOverflow.Ellipsis,
    )
}

/**
 * Board text in a layout: [text] in [style] and [colour], one line ending in an ellipsis unless [lines] says more. One
 * line takes CSS's line box, the style's line height, with the glyphs centred on it (Compose would take the font's full
 * height), so rows and columns of board text space as the board's flex boxes do; its baseline still aligns.
 */
@Composable
internal fun BoardText(text: String, style: TextStyle, colour: Argb, modifier: Modifier = Modifier, lines: Int = 1) {
    if (lines == 1 && style.lineHeight.isSp) {
        Box(modifier.height(style.lineHeight.value.dp), contentAlignment = Alignment.CenterStart, propagateMinConstraints = false) {
            Text(
                dotted(text, style.fontFamily), Modifier.wrapContentHeight(Alignment.CenterVertically, unbounded = true),
                style = style, color = argb(colour), maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis,
            )
        }
        return
    }
    Text(dotted(text, style.fontFamily), modifier, style = style, color = argb(colour), maxLines = lines, softWrap = lines > 1, overflow = TextOverflow.Ellipsis)
}

/**
 * A board symbol at its box, drawn as an SVG of that box draws its viewBox (scaled to fit, centred: a 36 × 20 waveform
 * fills its box), with its strokes [weight] times their own width where the board's SVG sets another.
 */
@Composable
internal fun Sw(id: String, x: Float, y: Float, w: Float, h: Float, tint: Argb = 0xFF14161B, weight: Float = 1f, rotate: Float = 0f, alpha: Float = 1f) {
    var m = Modifier.offset(x.dp, y.dp).requiredSize(w.dp, h.dp)
    if (rotate != 0f || alpha < 1f) m = m.graphicsLayer {
        rotationZ = rotate
        this.alpha = alpha
    }
    SymbolFit(id, m, argb(tint), weight)
}

/** Board symbol [id] filling [modifier]'s box as an SVG of that size draws its viewBox: scaled to fit and centred. */
@Composable
internal fun SymbolFit(id: String, modifier: Modifier, tint: Color, weight: Float = 1f, contentDescription: String? = null) {
    val described = if (contentDescription != null) Modifier.semantics {
        this.contentDescription = contentDescription
        role = Role.Image
    } else Modifier
    Canvas(modifier.then(described)) { drawSymbolFit(id, tint, weight) }
}

/** Draws board symbol [id] into the whole draw area as an SVG's `xMidYMid meet` draws its viewBox. */
internal fun DrawScope.drawSymbolFit(id: String, tint: Color, weight: Float = 1f) {
    val vb = SymbolPaths.of(id)?.viewBox
    val side = if (vb == null || vb.width <= 0f || vb.height <= 0f) size.minDimension
    else minOf(size.width / vb.width, size.height / vb.height) * maxOf(vb.width, vb.height)
    drawSymbol(id, Offset((size.width - side) / 2f, (size.height - side) / 2f), side, tint, weight)
}

/** One of the go package's board drawings ([Art]) at its board box, stretched to it with [fill]. */
@Composable
internal fun ArtAt(art: Art, x: Float, y: Float, w: Float, h: Float, tint: Argb = 0xFF14161B, fill: Boolean = false) {
    Canvas(Modifier.offset(x.dp, y.dp).requiredSize(w.dp, h.dp)) { drawArt(art, tint = argb(tint), fill = fill) }
}

/**
 * The board's 412 × 915 plane: its children placed by board px. Anchored to the top of the screen ([bottom] false) or
 * to its bottom, moved down by [dy] and drawn at [scale] about its anchor (a screen shorter than the board); a wider
 * screen centres it.
 */
@Composable
internal fun BoardPlane(modifier: Modifier = Modifier, bottom: Boolean = false, dy: Dp = 0.dp, scale: Float = 1f, content: @Composable BoxScope.() -> Unit) {
    var m = modifier
        .fillMaxWidth()
        .wrapContentSize(if (bottom) Alignment.BottomCenter else Alignment.TopCenter, unbounded = true)
        .offset(y = dy)
    if (scale != 1f) m = m.graphicsLayer {
        scaleX = scale
        scaleY = scale
        transformOrigin = TransformOrigin(0.5f, if (bottom) 1f else 0f)
    }
    Box(m.requiredSize(412.dp, 915.dp), content = content)
}

/** How a full-screen board fits the screen: the shifts that keep it clear of the system bars, and its [scale]. */
internal class BoardFit(val top: Dp, val bottom: Dp, val scale: Float)

/**
 * The fit of a board whose chrome fills the screen, its first element at [firstY] and its last ending at [lastY], on a
 * screen [height] tall: clear of the bars, and smaller (in proportion, about the top and the bottom) when the screen is
 * shorter than the board.
 */
@Composable
internal fun boardFit(height: Dp, firstY: Float, lastY: Float): BoardFit {
    val top = statusShift(firstY)
    val bottom = navShift(lastY)
    val room = height.value - top.value - bottom.value
    return BoardFit(top, bottom, if (room < 915f) (room / 915f).coerceAtLeast(0.5f) else 1f)
}

/**
 * Children side by side as CSS's `display: flex` lays out items of `flex: 1 1 auto`: each its own width, the room
 * left over shared equally, [gap] between them.
 */
@Composable
internal fun FlexRow(modifier: Modifier = Modifier, gap: Dp = 0.dp, content: @Composable () -> Unit) {
    Layout(content, modifier) { measurables, constraints ->
        val gapPx = gap.roundToPx()
        val total = constraints.maxWidth
        val n = measurables.size
        if (n == 0) return@Layout layout(total, 0) {}
        val own = measurables.map { it.maxIntrinsicWidth(constraints.maxHeight) }
        val free = (total - gapPx * (n - 1) - own.sum()).coerceAtLeast(0)
        val placeables = measurables.mapIndexed { i, m ->
            val w = own[i] + free / n + if (i < free % n) 1 else 0
            m.measure(androidx.compose.ui.unit.Constraints.fixedWidth(w.coerceAtMost(total)).copy(minHeight = 0, maxHeight = constraints.maxHeight))
        }
        val h = placeables.maxOf { it.height }
        layout(total, h) {
            var x = 0
            for (p in placeables) {
                p.place(x, 0)
                x += p.width + gapPx
            }
        }
    }
}

/**
 * How far a board's top chrome moves down so that a status bar taller than the board leaves room for keeps 8 px clear
 * of it: [firstY] is where the board's first element starts. Nothing on a status bar the board allows for.
 */
@Composable
internal fun statusShift(firstY: Float): Dp {
    val top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    return max(0f, top.value + 8f - firstY).dp
}

/**
 * How far a board's bottom chrome moves up so that it keeps 8 px clear of a navigation bar taller than the board leaves
 * room for: [lastY] is where the board's last element ends.
 */
@Composable
internal fun navShift(lastY: Float): Dp {
    val bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    return max(0f, bottom.value + 8f - (915f - lastY)).dp
}

/**
 * [text] as the boards' web Overpass sets it in the Overpass the app bundles, which lacks the middle dot (U+00B7): its
 * bullet operator (U+2219) stands in, the same small dot at the same height, so the line keeps its face and baseline.
 */
internal fun dotted(text: String, family: String): AnnotatedString = dotted(text, BoardFonts.of(family))

internal fun dotted(text: String, family: FontFamily?): AnnotatedString =
    AnnotatedString(if (family == BoardFonts.Overpass && '·' in text) text.replace('·', '\u2219') else text)

/**
 * The stroke weight that draws board symbol [id] with lines [width] wide: the boards' SVGs set their own widths, and
 * the symbol set keeps one drawing of each shape at one of them.
 */
internal fun strokeTo(id: String, width: Float): Float {
    val own = app.atlas.places.BoardSymbols.icon(id)?.paths?.firstOrNull { it.stroke }?.strokeWidth ?: return 1f
    return if (own > 0f) width / own else 1f
}

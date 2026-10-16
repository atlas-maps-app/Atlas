package app.atlas.ui.mine

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.ClipOp
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.asAndroidPath
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.addOutline
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.isSpecified
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.max
import androidx.compose.ui.unit.sp
import app.atlas.R
import app.atlas.places.Icon
import app.atlas.ui.kit.Argb
import app.atlas.ui.kit.BoardFonts
import app.atlas.ui.kit.CssBorder
import app.atlas.ui.kit.CssCorners
import app.atlas.ui.kit.CssGradient
import app.atlas.ui.kit.CssShadow
import app.atlas.ui.kit.SymbolPaths
import app.atlas.ui.kit.argb
import app.atlas.ui.kit.cssShadow
import app.atlas.ui.kit.drawSymbol
import app.atlas.ui.kit.frosted
import app.atlas.ui.mine.art.MineArt
import java.util.Collections
import java.util.IdentityHashMap
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.roundToInt
import kotlin.math.max
import kotlin.math.min

/*
 * The mine boards' elements for screens laid out by Compose: El's box as a modifier (its size from the layout, so rows
 * and cards grow with live text), Tx's text as a style, Sy's symbols and the boards' own drawings (`mine_art.json`),
 * each with the boards' numbers (`canvas/project/<Board>.dc.html`, measured in the browser).
 */

/** The boards' faces by CSS name. */
internal const val GEIST = "Geist"
internal const val SERIF = "Instrument Serif"
internal const val MONO = "Geist Mono"

/* The ink of the boards' grey pages. */
internal const val INK: Argb = 0xFF14161B
internal const val SOFT: Argb = 0xFF5A5F6C
internal const val LABEL: Argb = 0xFF676D7C
internal const val FAINT: Argb = 0xFF9AA0AC
internal const val PAGE: Argb = 0xFFF1F3F6
internal const val WHITE: Argb = 0xFFFFFFFF
internal const val HAIR: Argb = 0xFFEEF0F4

/** Tx's text style for a layout: the face by CSS name, size, weight, line box (centred, as CSS), spacing and figures. */
internal fun bt(
    size: Float,
    weight: Int = 400,
    line: Float = 0f,
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
    lineHeight = if (line > 0f) line.sp else TextUnit.Unspecified,
    lineHeightStyle = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.None),
    letterSpacing = if (spacing != 0f) (spacing / size).em else TextUnit.Unspecified,
    textAlign = align,
    fontFeatureSettings = if (tnum) "tnum" else null,
)

/**
 * Board text in a layout, laid out as CSS lays it: line boxes of the style's line height with the glyphs centred in them
 * (where Compose keeps a face's whole ascent and descent around the first and last lines when the line height is under
 * the face's own, as with Instrument Serif at `line-height: 1`). [lines] lines at most, ellipsised when the live text is
 * longer than the room. [box] is Tx's box height for one line whose box is not its line box (a serif title's 41.5 px
 * content area around its 32 px line): the line box is centred in it, as Tx centres it.
 */
@Composable
internal fun BText(text: String, style: TextStyle, colour: Argb, modifier: Modifier = Modifier, lines: Int = 1, box: Float = 0f) {
    val css = if (style.lineHeight.isSpecified) Modifier.cssLines(style.lineHeight) else Modifier
    if (box > 0f) {
        Box(modifier.height(box.dp), contentAlignment = Alignment.CenterStart) {
            Text(text, css, style = style, color = argb(colour), maxLines = lines, overflow = TextOverflow.Ellipsis, softWrap = lines > 1)
        }
    } else {
        Text(text, modifier.then(css), style = style, color = argb(colour), maxLines = lines, overflow = TextOverflow.Ellipsis, softWrap = lines > 1)
    }
}

/** CSS's block of text: as many line boxes of [lineHeight] as the text has lines, the laid-out text centred on them. */
internal fun Modifier.cssLines(lineHeight: TextUnit): Modifier = layout { m, c ->
    val p = m.measure(c.copy(minHeight = 0, maxHeight = Constraints.Infinity))
    val l = lineHeight.toPx()
    // what Compose adds beyond the line boxes is under one line, so the count of whole lines is the line count
    val n = max(1, floor((p.height + 0.5f) / l).toInt())
    val h = min(p.height, (l * n).roundToInt()).coerceIn(c.minHeight, c.maxHeight)
    layout(p.width, h) { p.place(0, (h - p.height) / 2) }
}

/** CSS `border-radius: [r]px` on every corner. */
internal fun cr(r: Float) = CssCorners(r, r, r, r)

/** A one-colour CSS border on all four sides. */
internal fun ring(width: Float, colour: Argb) = CssBorder(width, width, width, width, longArrayOf(colour, colour, colour, colour))

/** The rows' hairline: a CSS `border-top: 1px solid [colour]`. */
internal fun hairTop(colour: Argb = HAIR) = CssBorder(1f, 0f, 0f, 0f, longArrayOf(colour, INK, INK, INK))

/**
 * El's box at the size the layout gives it: shadows (the first on top), glass ([blur] over the backdrop), background,
 * gradients, inset shadows inside the padding box and the border, exactly as El draws them; the content (and a ripple)
 * is clipped to its corners unless [clip] is false. An [outline] stands for the box's corners where CSS cuts the box to
 * another shape.
 */
internal fun Modifier.bbox(
    bg: Argb = 0,
    r: CssCorners? = null,
    shadows: List<CssShadow> = emptyList(),
    border: CssBorder? = null,
    grad: List<CssGradient> = emptyList(),
    blur: Float = 0f,
    saturate: Float = 1f,
    clip: Boolean = true,
    outline: Shape? = null,
): Modifier {
    // [outline] gives a box CSS cuts to another shape (a clip-path or mask): its background, shadows and clip follow it
    val shape: Shape = outline ?: r ?: SQUARE
    var m = this
    for (s in shadows.asReversed()) if (!s.inset) m = m.cssShadow(shape, y = s.y.dp, blur = s.blur.dp, colour = argb(s.colour), x = s.x.dp, spread = s.spread.dp)
    if (blur > 0f) m = m.frosted(shape, argb(bg), blur.dp, saturate)
    m = m.drawBehind {
        val path = Path().apply { addOutline(shape.createOutline(size, layoutDirection, this@drawBehind)) }
        if (blur <= 0f && bg != 0L) drawPath(path, argb(bg))
        for (g in grad.asReversed()) drawPath(path, g.brush(size, density))
        if (shadows.any { it.inset }) {
            val pad = paddingBox(size, density, r ?: SQUARE, border)
            for (s in shadows.asReversed()) if (s.inset) drawInset(pad, s)
        }
        border?.let { b -> drawCssBorder(b, r ?: SQUARE, path) }
    }
    return if (clip) m.clip(shape) else m
}

/** CSS's border: the ring between the outer box and the inner one inset by the widths, its radii shrunk by them. */
private fun DrawScope.drawCssBorder(b: CssBorder, r: CssCorners, path: Path) {
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

private fun paddingBox(size: Size, d: Float, r: CssCorners, b: CssBorder?): PaddingBox {
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

/** One inset `box-shadow` as CSS draws it: the colour outside a hole (the padding box shrunk by the spread and moved by the offset), blurred by σ = blur / 2. */
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
                if (sigma > 0.5f) maskFilter = android.graphics.BlurMaskFilter((sigma - 0.5f) / 0.57735f, android.graphics.BlurMaskFilter.Blur.NORMAL)
            }
            drawContext.canvas.nativeCanvas.drawPath(ring.asAndroidPath(), paint)
        }
    }
}

private val SQUARE = CssCorners(0f, 0f, 0f, 0f)

/** How far a screen moves down from its board: the boards keep their top 40 px for the status bar; a phone's may be taller. */
@Composable
internal fun shift(): Dp = max(0.dp, WindowInsets.statusBars.asPaddingValues().calculateTopPadding() - 40.dp)

/** The room a page leaves under its last element: [base] board px over the system's navigation bar. */
@Composable
internal fun foot(base: Float): Dp = base.dp + WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()

/**
 * A board symbol or drawing ([id]) in its [w] × [h] box, fitted as SVG fits a viewBox (centred, whole): the boards'
 * drawings (`art.*`, `mine_art.json`) by their own colours, the symbol set's icons tinted with the CSS `color` ([tint]).
 */
@Composable
internal fun MSy(id: String, w: Float, tint: Argb = INK, modifier: Modifier = Modifier, h: Float = w, description: String? = null) {
    val context = LocalContext.current
    val art = remember(id) { MineArt.icon(context, id) }
    val m = if (description != null) modifier.semantics { contentDescription = description } else modifier
    // an inline SVG clips to its viewport, as the boards' drawings do
    Canvas(m.size(w.dp, h.dp).then(if (art != null) Modifier.clipToBounds() else Modifier)) {
        if (art != null) drawArt(art, Offset.Zero, size, argb(tint)) else {
            val side = min(size.width, size.height)
            drawSymbol(id, Offset((size.width - side) / 2f, (size.height - side) / 2f), side, argb(tint))
        }
    }
}

private val built = Collections.synchronizedMap(IdentityHashMap<Icon, SymbolPaths>())

/**
 * Draws a board drawing into a [box] at [topLeft] as SVG does (`preserveAspectRatio="xMidYMid meet"`): its paths with
 * their colours, widths, caps, dashes, opacity and gradients ([tint] for `currentColor`), then its texts.
 */
internal fun DrawScope.drawArt(icon: Icon, topLeft: Offset, box: Size, tint: androidx.compose.ui.graphics.Color) {
    val s = built.getOrPut(icon) { SymbolPaths.build(icon) }
    val vb = s.viewBox
    val scale = min(box.width / vb.width, box.height / vb.height)
    val dx = topLeft.x + (box.width - vb.width * scale) / 2f - vb.x * scale
    val dy = topLeft.y + (box.height - vb.height * scale) / 2f - vb.y * scale
    withTransform({
        translate(dx, dy)
        scale(scale, scale, Offset.Zero)
    }) {
        for (p in s.parts) {
            if (p.fill) {
                if (p.brush != null) drawPath(p.path, p.brush, alpha = p.opacity, style = Fill)
                else drawPath(p.path, p.colour ?: tint, alpha = p.opacity, style = Fill)
            }
            if (p.stroke != null) {
                if (p.brush != null) drawPath(p.path, p.brush, alpha = p.opacity, style = p.stroke)
                else drawPath(p.path, p.colour ?: tint, alpha = p.opacity, style = p.stroke)
            }
        }
    }
    if (s.texts.isEmpty()) return
    drawIntoCanvas { canvas ->
        val nc = canvas.nativeCanvas
        for (t in s.texts) {
            val paint = t.paint
            paint.textSize = t.text.size * scale
            val c = t.colour ?: tint
            paint.color = c.copy(alpha = c.alpha * t.text.opacity).toArgb()
            val x = t.text.x * scale + dx
            val y = t.text.y * scale + dy
            if (t.text.rotation != 0f) {
                nc.save()
                nc.rotate(t.text.rotation, x, y)
                nc.drawText(t.text.text, x, y, paint)
                nc.restore()
            } else nc.drawText(t.text.text, x, y, paint)
        }
    }
}

/** The boards' round back button: 44 px of white with the 20 px back arrow, [shadows] and glass as the board has them. */
@Composable
internal fun BackButton(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    bg: Argb = WHITE,
    tint: Argb = INK,
    shadows: List<CssShadow> = emptyList(),
    blur: Float = 0f,
) {
    val back = stringResource(R.string.kit_back)
    Box(
        modifier
            .size(44.dp)
            .bbox(bg, cr(22f), shadows, blur = blur)
            .clickable(role = Role.Button, onClick = onBack)
            .semantics { contentDescription = back },
        contentAlignment = Alignment.Center,
    ) { MSy("ui.back", 20f, tint) }
}

/**
 * The grey boards' title row at y 42, 44 px high: the back button, then 10 px on the italic serif title ([size] on a line
 * of its size, centred on the row) and [trailing] at the right, 16 px from the sides.
 */
@Composable
internal fun TitleRow(
    title: String,
    onBack: (() -> Unit)?,
    modifier: Modifier = Modifier,
    ink: Argb = INK,
    size: Float = 32f,
    italic: Boolean = true,
    trailing: @Composable RowScope.() -> Unit = {},
) {
    Row(
        modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 42.dp + shift()).height(44.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (onBack != null) BackButton(onBack, tint = ink)
        // the title's line box centred on the row, as the boards' `align-items: center` puts it
        BText(title, bt(size, 400, size, SERIF, italic), ink, Modifier.weight(1f).semantics { heading() }, box = 44f)
        trailing()
    }
}

/** The small caps heading of a card: "KEPT HERE", "LENSES" (11 px, 800, 1.32 px apart, on a 14 px line). */
@Composable
internal fun CardLabel(text: String, modifier: Modifier = Modifier, colour: Argb = LABEL, spacing: Float = 1.32f) {
    BText(text.uppercase(), bt(11f, 800, 14f, spacing = spacing), colour, modifier.semantics { heading() })
}

/**
 * The boards' switch (44 × 26, radius 13): [onColour] when on, [offColour] when off, and the white 20 px knob 3 px in
 * with its `0 1px 3px` shadow of [knobShadow].
 */
@Composable
internal fun MineSwitch(
    checked: Boolean,
    onChange: (Boolean) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    onColour: Argb = 0xFF1E9E5A,
    offColour: Argb = 0xFFD5D8DE,
    knobShadow: Argb = 0x4C000000,
    enabled: Boolean = true,
) {
    Box(
        modifier
            .size(44.dp, 26.dp)
            .bbox(if (checked) onColour else offColour, cr(13f), clip = false)
            .toggleable(checked, enabled = enabled, role = Role.Switch, onValueChange = onChange)
            .semantics {
                contentDescription = label
                stateDescription = if (checked) "on" else "off"
            },
    ) {
        Box(
            Modifier
                .padding(start = if (checked) 21.dp else 3.dp, top = 3.dp)
                .size(20.dp)
                .bbox(WHITE, cr(10f), listOf(CssShadow(0f, 1f, 3f, 0f, knobShadow))),
        )
    }
}

/**
 * A pill of text on the boards (a button or a chip): [bg] and [fg], [h] high, [padH] at the sides (or [padStart] and
 * [padEnd]), an optional [symbol] [gap] px before the text in [tint] (the text's colour by default), its text [style].
 */
@Composable
internal fun TextPill(
    text: String,
    style: TextStyle,
    bg: Argb,
    fg: Argb,
    modifier: Modifier = Modifier,
    h: Float = 28f,
    padH: Float = 10f,
    symbol: String? = null,
    symbolSize: Float = 14f,
    gap: Float = 5f,
    border: CssBorder? = null,
    shadows: List<CssShadow> = emptyList(),
    blur: Float = 0f,
    tint: Argb? = null,
    padStart: Float = padH,
    padEnd: Float = padH,
    onClick: (() -> Unit)? = null,
) {
    Row(
        modifier
            .height(h.dp)
            .bbox(bg, cr(h / 2f), shadows, border, blur = blur)
            .then(if (onClick != null) Modifier.clickable(role = Role.Button, onClick = onClick) else Modifier)
            .padding(start = padStart.dp, end = padEnd.dp),
        horizontalArrangement = Arrangement.spacedBy(gap.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (symbol != null) MSy(symbol, symbolSize, tint ?: fg)
        BText(text, style, fg)
    }
}

/**
 * An honest state instead of content (loading, empty, offline, an error) in the boards' type: the [symbol] (34 px) in a
 * 72 px disc of [tint] at 12 %, the [title] (16, 800 on 20.5) and an optional line (13 on 18.2) and action, centred.
 */
@Composable
internal fun MineState(
    symbol: String,
    title: String,
    modifier: Modifier = Modifier,
    text: String? = null,
    tint: androidx.compose.ui.graphics.Color = argb(SOFT),
    ink: Argb = INK,
    soft: Argb = SOFT,
    action: (@Composable () -> Unit)? = null,
) {
    androidx.compose.foundation.layout.Column(
        modifier.fillMaxWidth().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(Modifier.size(72.dp).bbox(tint.copy(alpha = 0.12f).argbLong(), cr(36f)), contentAlignment = Alignment.Center) {
            MSy(symbol, 34f, tint.argbLong())
        }
        BText(title, bt(16f, 800, 20.5f, align = TextAlign.Center), ink, lines = 3)
        if (text != null) BText(text, bt(13f, 400, 18.2f, align = TextAlign.Center), soft, lines = 8)
        if (action != null) action()
    }
}

/**
 * CSS's `justify-content: space-between` for a row: the children at their own widths with the room left spread between
 * them; when they are wider than the row, packed from its start and running past its end, as the browser lays them.
 */
@Composable
internal fun SpaceBetweenRow(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    androidx.compose.ui.layout.Layout(content, modifier) { measurables, c ->
        val loose = Constraints(maxHeight = c.maxHeight)
        val ps = measurables.map { it.measure(loose) }
        val width = if (c.hasBoundedWidth) c.maxWidth else ps.sumOf { it.width }
        val height = ps.maxOfOrNull { it.height } ?: 0
        val free = width - ps.sumOf { it.width }
        val gap = if (free > 0 && ps.size > 1) free.toFloat() / (ps.size - 1) else 0f
        layout(width, height) {
            var x = 0f
            for (p in ps) {
                p.place(x.roundToInt(), (height - p.height) / 2)
                x += p.width + gap
            }
        }
    }
}

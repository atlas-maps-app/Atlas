package app.atlas.ui.kit

import android.graphics.BlurMaskFilter
import android.graphics.Paint
import android.graphics.Typeface
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.asAndroidPath
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.atlas.places.BoardSymbols
import app.atlas.places.Icon
import app.atlas.places.IconPath
import app.atlas.places.IconShadow
import app.atlas.places.IconText
import app.atlas.places.LineCap
import app.atlas.places.LineJoin
import app.atlas.places.PathSink
import app.atlas.places.Symbols
import app.atlas.places.TextAnchor
import app.atlas.places.ViewBox
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.hypot

/**
 * Draws the symbol [id] in a [size] square: one of the boards' own symbols (`ui.search`, `cat.eat`, or an older id such
 * as `ui-search` that one stands in for), else the app's own few ([AppSymbols]), else `:places`' open sets (`maki-cafe`,
 * `logo-albertheijn`). Paths without a colour of their own take [tint]; coloured paths keep their colours, widths, caps,
 * dashes, opacity and gradients. Give a [contentDescription] when the symbol alone carries meaning (an icon-only control
 * or a status); leave it null when a label next to it already says the same.
 */
@Composable
fun Symbol(
    id: String,
    modifier: Modifier = Modifier,
    tint: Color = LocalContentColor.current,
    size: Dp = 24.dp,
    contentDescription: String? = null,
    /** Thicker or thinner strokes: 1 is the symbol's own width (Lucide's 2 units on a 24 grid for the UI set). */
    weight: Float = 1f,
) {
    val described = if (contentDescription != null) {
        Modifier.semantics {
            this.contentDescription = contentDescription
            role = Role.Image
        }
    } else Modifier
    Canvas(modifier.then(described).size(size)) {
        drawSymbol(id, Offset.Zero, this.size.minDimension, tint, weight)
    }
}

/** Draws symbol [id] into a [side] × [side] square at [topLeft], for components drawn on a Canvas. */
fun DrawScope.drawSymbol(id: String, topLeft: Offset, side: Float, tint: Color, weight: Float = 1f) {
    val s = SymbolPaths.of(id) ?: SymbolPaths.of(Symbols.MARKER) ?: return
    val vb = s.viewBox
    val scale = side / maxOf(vb.width, vb.height)
    // Centre the shorter side of a non-square viewBox.
    val dx = topLeft.x + (side - vb.width * scale) / 2f - vb.x * scale
    val dy = topLeft.y + (side - vb.height * scale) / 2f - vb.y * scale
    withTransform({
        translate(dx, dy)
        scale(scale, scale, Offset.Zero)
    }) {
        if (s.shadows.isNotEmpty()) drawShadows(s, weight)
        for (p in s.parts) {
            if (p.fill) {
                if (p.brush != null) drawPath(p.path, p.brush, alpha = p.opacity, style = Fill)
                else drawPath(p.path, p.colour ?: tint, alpha = p.opacity, style = Fill)
            }
            if (p.stroke != null) {
                val style = if (weight == 1f) p.stroke else p.stroke(weight)
                if (p.brush != null) drawPath(p.path, p.brush, alpha = p.opacity, style = style)
                else drawPath(p.path, p.colour ?: tint, alpha = p.opacity, style = style)
            }
        }
    }
    if (s.texts.isNotEmpty()) drawTexts(s, scale, dx, dy, tint)
}

/** The symbol's drop shadows (a pin's shadow, a neon glow): every part again, moved and blurred, under the symbol. */
private fun DrawScope.drawShadows(s: SymbolPaths, weight: Float) = drawIntoCanvas { canvas ->
    val nc = canvas.nativeCanvas
    for (sh in s.shadows) {
        val paint = sh.paint
        nc.save()
        nc.translate(sh.shadow.dx, sh.shadow.dy)
        for (p in s.parts) {
            val path = p.path.asAndroidPath()
            if (p.fill) {
                paint.style = Paint.Style.FILL
                nc.drawPath(path, paint)
            }
            if (p.stroke != null) {
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = p.stroke.width * weight
                paint.strokeCap = when (p.stroke.cap) { StrokeCap.Round -> Paint.Cap.ROUND; StrokeCap.Square -> Paint.Cap.SQUARE; else -> Paint.Cap.BUTT }
                paint.strokeJoin = when (p.stroke.join) { StrokeJoin.Round -> Paint.Join.ROUND; StrokeJoin.Bevel -> Paint.Join.BEVEL; else -> Paint.Join.MITER }
                nc.drawPath(path, paint)
            }
        }
        nc.restore()
    }
}

/** Texts are laid out in pixels rather than under the viewBox scale, so small type stays crisp. */
private fun DrawScope.drawTexts(s: SymbolPaths, scale: Float, dx: Float, dy: Float, tint: Color) = drawIntoCanvas { canvas ->
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
        } else {
            nc.drawText(t.text.text, x, y, paint)
        }
    }
}

/** True when Atlas can draw [id]. */
fun hasSymbol(id: String): Boolean = Symbols.has(id) || AppSymbols.has(id)

/** One path of a symbol, ready to draw: its Path in viewBox units, its fill and its stroke at weight 1. */
internal class SymbolPart(
    val path: Path,
    val fill: Boolean,
    val stroke: Stroke?,
    /** Its own colour, or null for the tint. */
    val colour: Color?,
    val brush: Brush?,
    val opacity: Float,
    private val width: Float,
    private val cap: StrokeCap,
    private val join: StrokeJoin,
    private val effect: PathEffect?,
) {
    /** The stroke at [weight] times its own width. */
    fun stroke(weight: Float): Stroke = Stroke(width = width * weight, cap = cap, join = join, miter = MITER, pathEffect = effect)

    companion object {
        /** SVG's default miter limit. */
        const val MITER = 4f
    }
}

/** One text of a symbol with its Android paint (typeface, alignment and spacing set once). */
internal class SymbolText(val text: IconText, val colour: Color?, val paint: Paint)

/** One drop shadow with its blurred paint. */
internal class SymbolShadow(val shadow: IconShadow, val paint: Paint)

/** One symbol's parts, built once in viewBox units and kept. */
internal class SymbolPaths(
    val viewBox: ViewBox,
    val parts: List<SymbolPart>,
    val texts: List<SymbolText>,
    val shadows: List<SymbolShadow> = emptyList(),
) {
    companion object {
        private val cache = ConcurrentHashMap<String, SymbolPaths>()
        private val missing = ConcurrentHashMap.newKeySet<String>()

        fun of(id: String): SymbolPaths? {
            cache[id]?.let { return it }
            if (id in missing) return null
            // The boards' own symbols win; then the app's few; then the open sets.
            val icon = BoardSymbols.icon(id) ?: AppSymbols.icon(id) ?: Symbols.original(id)
            if (icon == null) {
                missing += id
                return null
            }
            return build(icon).also { cache[id] = it }
        }

        internal fun build(icon: Icon): SymbolPaths {
            val parts = icon.paths.map { part(icon, it) }
            val texts = icon.texts.map { t ->
                val family = when (t.family) { "serif" -> "serif"; "mono" -> "monospace"; else -> "sans-serif" }
                val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG).apply {
                    typeface = Typeface.create(Typeface.create(family, Typeface.NORMAL), t.weight.coerceIn(1, 1000), t.italic)
                    textAlign = when (t.anchor) { TextAnchor.START -> Paint.Align.LEFT; TextAnchor.MIDDLE -> Paint.Align.CENTER; TextAnchor.END -> Paint.Align.RIGHT }
                    if (t.letterSpacing != 0f && t.size > 0f) letterSpacing = t.letterSpacing / t.size
                }
                SymbolText(t, t.colour?.let { Color(it.toInt()) }, paint)
            }
            val shadows = icon.shadows.map { sh ->
                // CSS blurs by a Gaussian of sigma = blur / 2; Android's radius r means sigma = 0.57735 r + 0.5.
                val radius = ((sh.blur / 2f - 0.5f) / 0.57735f).coerceAtLeast(0.1f)
                val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = sh.colour.toInt()
                    maskFilter = BlurMaskFilter(radius, BlurMaskFilter.Blur.NORMAL)
                }
                SymbolShadow(sh, paint)
            }
            return SymbolPaths(icon.viewBox, parts, texts, shadows)
        }

        private fun part(icon: Icon, p: IconPath): SymbolPart {
            val path = Path()
            p.commands.replay(ComposePathSink(path))
            if (p.evenOdd) path.fillType = PathFillType.EvenOdd
            // A path marked neither stroke nor fill follows SVG's default: filled.
            val fill = p.fill || !p.stroke
            val width = p.strokeWidth ?: icon.strokeWidth
            val cap = when (p.cap) { LineCap.BUTT -> StrokeCap.Butt; LineCap.ROUND -> StrokeCap.Round; LineCap.SQUARE -> StrokeCap.Square }
            val join = when (p.join) { LineJoin.MITER -> StrokeJoin.Miter; LineJoin.ROUND -> StrokeJoin.Round; LineJoin.BEVEL -> StrokeJoin.Bevel }
            val effect = p.dash?.let { PathEffect.dashPathEffect(it.toFloatArray()) }
            val stroke = if (p.stroke) Stroke(width = width, cap = cap, join = join, miter = SymbolPart.MITER, pathEffect = effect) else null
            return SymbolPart(path, fill, stroke, p.colour?.let { Color(it.toInt()) }, brushOf(p), p.opacity, width, cap, join, effect)
        }

        private fun brushOf(p: IconPath): Brush? {
            val stops = p.gradient?.takeIf { it.size >= 2 } ?: return null
            val colorStops = Array(stops.size) { i -> stops[i].first to Color(stops[i].second.toInt()) }
            return if (p.radial) {
                Brush.radialGradient(*colorStops, center = Offset(p.gx1, p.gy1), radius = hypot(p.gx2 - p.gx1, p.gy2 - p.gy1).coerceAtLeast(1e-3f))
            } else {
                Brush.linearGradient(*colorStops, start = Offset(p.gx1, p.gy1), end = Offset(p.gx2, p.gy2))
            }
        }
    }
}

/** Adapts a Compose [Path] to `:places`' [PathSink]. */
internal class ComposePathSink(private val path: Path) : PathSink {
    override fun moveTo(x: Float, y: Float) = path.moveTo(x, y)
    override fun lineTo(x: Float, y: Float) = path.lineTo(x, y)
    override fun quadTo(x1: Float, y1: Float, x: Float, y: Float) = path.quadraticTo(x1, y1, x, y)
    override fun cubicTo(x1: Float, y1: Float, x2: Float, y2: Float, x: Float, y: Float) = path.cubicTo(x1, y1, x2, y2, x, y)
    override fun close() = path.close()
}

/** Draws [id] centred at [centre] in a square of [side], a shorthand for markers and dials. */
fun DrawScope.drawSymbolCentred(id: String, centre: Offset, side: Float, tint: Color, weight: Float = 1f) =
    translate(0f, 0f) { drawSymbol(id, Offset(centre.x - side / 2f, centre.y - side / 2f), side, tint, weight) }

/** Builds an [Icon] from stroked 24-unit Lucide paths. */
internal fun strokedIcon(id: String, vararg d: String) =
    Icon(id, ViewBox(0f, 0f, 24f, 24f), d.map { IconPath(it, stroke = true, fill = false) })

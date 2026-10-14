package app.atlas.ui.shop

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Matrix
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import app.atlas.places.PathSink
import app.atlas.places.SvgPath
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/**
 * A drawing taken whole from a board's inline SVG (`canvas/project/<Board>.dc.html`): the markup is pasted as it is and
 * read once into paths with their fills, strokes, gradients, transforms and text, then drawn on a Canvas. This is how
 * the shop screens keep the boards' own objects and icons rather than redrawing them by eye.
 *
 * Supported: `svg`, `g`, `path`, `rect`, `circle`, `ellipse`, `line`, `polyline`, `polygon`, `text`, `defs` with
 * `linearGradient`/`radialGradient`, presentation attributes and `style`, `transform`, `opacity`, dashes,
 * `currentColor` (drawn in the caller's tint) and a text glow from `filter: drop-shadow(…)`.
 */
internal class SvgArt private constructor(
    /** The size the board gives it, in px (dp in the app). */
    val width: Float,
    val height: Float,
    private val viewBox: Rect,
    private val stretch: Boolean,
    private val root: SvgNode.Group,
) {
    /** Draws the art into a [w] × [h] box at the origin (the whole canvas by default), `currentColor` as [tint]. */
    fun draw(scope: DrawScope, tint: Color, measurer: TextMeasurer, alpha: Float = 1f, w: Float = scope.size.width, h: Float = scope.size.height) = with(scope) {
        val sx = w / viewBox.width
        val sy = h / viewBox.height
        val kx: Float
        val ky: Float
        if (stretch) {
            kx = sx; ky = sy
        } else {
            kx = minOf(sx, sy); ky = kx
        }
        val dx = (w - viewBox.width * kx) / 2f - viewBox.left * kx
        val dy = (h - viewBox.height * ky) / 2f - viewBox.top * ky
        withTransform({
            translate(dx, dy)
            scale(kx, ky, Offset.Zero)
        }) {
            drawNode(root, tint, measurer, alpha)
        }
    }

    private fun DrawScope.drawNode(node: SvgNode, tint: Color, measurer: TextMeasurer, alpha: Float) {
        val m = node.transform
        if (m != null) {
            withTransform({ transform(m) }) { drawBare(node, tint, measurer, alpha) }
        } else {
            drawBare(node, tint, measurer, alpha)
        }
    }

    private fun DrawScope.drawBare(node: SvgNode, tint: Color, measurer: TextMeasurer, alpha: Float) {
        val a = alpha * node.opacity
        when (node) {
            is SvgNode.Group -> for (c in node.children) drawNode(c, tint, measurer, a)
            is SvgNode.Shape -> {
                val s = node.style
                s.fill?.let { p -> paint(p, tint)?.let { drawPath(node.path, it, alpha = a * s.fillOpacity, style = Fill) } }
                s.stroke?.let { p ->
                    if (s.strokeWidth > 0f) paint(p, tint)?.let {
                        val effect = s.dash?.let { d -> PathEffect.dashPathEffect(d, 0f) }
                        drawPath(node.path, it, alpha = a * s.strokeOpacity, style = Stroke(s.strokeWidth, cap = s.cap, join = s.join, pathEffect = effect))
                    }
                }
            }
            is SvgNode.Label -> drawLabel(node, tint, measurer, a)
        }
    }

    private fun paint(p: SvgPaint, tint: Color): Brush? = when (p) {
        is SvgPaint.Solid -> SolidColor(p.colour)
        SvgPaint.Current -> SolidColor(tint)
        is SvgPaint.Gradient -> p.brush
    }

    private fun DrawScope.drawLabel(node: SvgNode.Label, tint: Color, measurer: TextMeasurer, alpha: Float) {
        val s = node.style
        val colour = when (val f = s.fill) {
            is SvgPaint.Solid -> f.colour
            SvgPaint.Current -> tint
            else -> Color.Black
        }.let { it.copy(alpha = it.alpha * alpha * s.fillOpacity) }
        val base = TextStyle(
            color = colour,
            fontSize = s.fontSize.toSp(),
            fontWeight = FontWeight(s.fontWeight),
            fontFamily = s.family,
            fontStyle = if (s.italic) FontStyle.Italic else FontStyle.Normal,
            letterSpacing = if (s.letterSpacing != 0f) (s.letterSpacing / s.fontSize).em else androidx.compose.ui.unit.TextUnit.Unspecified,
        )
        val layout = measurer.measure(node.text, base, softWrap = false)
        val left = when (s.anchor) {
            1 -> node.x - layout.size.width / 2f
            2 -> node.x - layout.size.width
            else -> node.x
        }
        val top = node.y - layout.firstBaseline
        for (g in s.glows) {
            val glow = measurer.measure(node.text, base.copy(shadow = Shadow(g.colour, Offset.Zero, g.radius)), softWrap = false)
            drawText(glow, topLeft = Offset(left, top))
        }
        drawText(layout, topLeft = Offset(left, top))
    }

    companion object {
        private val cache = ConcurrentHashMap<String, SvgArt>()

        /** The art of [markup] (one `<svg>…</svg>` from a board), read once and kept. */
        fun of(markup: String): SvgArt = cache.getOrPut(markup) { SvgReader(markup).read() }

        internal fun create(width: Float, height: Float, viewBox: Rect, stretch: Boolean, root: SvgNode.Group) =
            SvgArt(width, height, viewBox, stretch, root)
    }
}

/**
 * Draws a board's SVG [markup] at the size the board gives it (or [width] × [height]), `currentColor` in [tint].
 * A [contentDescription] makes it an image for accessibility; leave it null for decoration.
 */
@Composable
internal fun BoardArt(
    markup: String,
    modifier: Modifier = Modifier,
    tint: Color = LocalContentColor.current,
    width: Dp? = null,
    height: Dp? = null,
    contentDescription: String? = null,
) {
    val art = remember(markup) { SvgArt.of(markup) }
    val measurer = rememberTextMeasurer(cacheSize = 4)
    val described = if (contentDescription != null) {
        Modifier.semantics {
            this.contentDescription = contentDescription
            role = Role.Image
        }
    } else Modifier
    Canvas(modifier.then(described).size(width ?: art.width.dp, height ?: art.height.dp)) { art.draw(this, tint, measurer) }
}

/** Draws [markup] into a box of [boxSize] px at [topLeft] in a Canvas: for art placed among other drawing. */
internal fun DrawScope.drawBoardArt(markup: String, topLeft: Offset, boxSize: androidx.compose.ui.geometry.Size, tint: Color, measurer: TextMeasurer, alpha: Float = 1f) {
    val art = SvgArt.of(markup)
    withTransform({ translate(topLeft.x, topLeft.y) }) { art.draw(this, tint, measurer, alpha, boxSize.width, boxSize.height) }
}

/** One element of an [SvgArt]. */
internal sealed class SvgNode {
    abstract val transform: Matrix?
    abstract val opacity: Float

    class Group(val children: List<SvgNode>, override val transform: Matrix?, override val opacity: Float) : SvgNode()

    class Shape(val path: Path, val style: SvgStyle, override val transform: Matrix?, override val opacity: Float) : SvgNode()

    class Label(val text: String, val x: Float, val y: Float, val style: SvgStyle, override val transform: Matrix?, override val opacity: Float) : SvgNode()
}

/** How a fill or stroke is painted. */
internal sealed class SvgPaint {
    data class Solid(val colour: Color) : SvgPaint()
    data object Current : SvgPaint()
    class Gradient(val brush: Brush) : SvgPaint()
}

/** A glow behind text, from `drop-shadow(0 0 <radius> <colour>)`. */
internal class SvgGlow(val colour: Color, val radius: Float)

/** The resolved (inherited) presentation of one element. */
internal class SvgStyle(
    val fill: SvgPaint?,
    val stroke: SvgPaint?,
    val strokeWidth: Float,
    val fillOpacity: Float,
    val strokeOpacity: Float,
    val cap: StrokeCap,
    val join: StrokeJoin,
    val dash: FloatArray?,
    val fontSize: Float,
    val fontWeight: Int,
    val family: FontFamily,
    val italic: Boolean,
    /** 0 start, 1 middle, 2 end. */
    val anchor: Int,
    val letterSpacing: Float,
    val glows: List<SvgGlow>,
)

/** A gradient from `<defs>`, turned into a brush once the shape it paints is known. */
private class GradientDef(
    val radial: Boolean,
    val userSpace: Boolean,
    val coords: FloatArray,
    val stops: List<Pair<Float, Color>>,
)

/** Reads a board's `<svg>` markup into an [SvgArt]. */
internal class SvgReader(private val src: String) {
    private val gradients = HashMap<String, GradientDef>()

    private class Tag(val name: String, val attrs: Map<String, String>, val closing: Boolean, val selfClosing: Boolean, val end: Int)

    fun read(): SvgArt {
        collectGradients()
        var i = src.indexOf("<svg")
        require(i >= 0) { "no <svg> in markup" }
        val svg = tagAt(i) ?: error("bad <svg>")
        val attrs = svg.attrs
        val vb = attrs["viewBox"]?.trim()?.split(Regex("[\\s,]+"))?.mapNotNull { it.toFloatOrNull() }
        val w = number(attrs["width"]) ?: vb?.getOrNull(2) ?: 24f
        val h = number(attrs["height"]) ?: vb?.getOrNull(3) ?: 24f
        val viewBox = if (vb != null && vb.size == 4) Rect(vb[0], vb[1], vb[0] + vb[2], vb[1] + vb[3]) else Rect(0f, 0f, w, h)
        val stretch = attrs["preserveAspectRatio"]?.trim() == "none"
        val rootStyle = cascade(DEFAULT, attrs)
        i = svg.end
        val (children, _) = readChildren(i, "svg", rootStyle)
        val root = SvgNode.Group(children, null, opacityOf(attrs))
        return SvgArt.create(w, h, viewBox, stretch, root)
    }

    /** Reads elements from [start] until `</[parent]>`; returns them and the index after the closing tag. */
    private fun readChildren(start: Int, parent: String, style: Inherited): Pair<List<SvgNode>, Int> {
        val out = ArrayList<SvgNode>()
        var i = start
        while (i < src.length) {
            val lt = src.indexOf('<', i)
            if (lt < 0) break
            if (src.startsWith("<!--", lt)) {
                i = src.indexOf("-->", lt).let { if (it < 0) src.length else it + 3 }
                continue
            }
            val tag = tagAt(lt) ?: run { i = lt + 1; null } ?: continue
            if (tag.closing) {
                if (tag.name == parent) return out to tag.end
                i = tag.end
                continue
            }
            i = tag.end
            when (tag.name) {
                "defs", "linearGradient", "radialGradient", "title", "desc", "style", "filter", "clipPath", "mask" -> {
                    if (!tag.selfClosing) i = skipTo(tag.name, i)
                }
                "g", "a", "svg" -> {
                    val s = cascade(style, tag.attrs)
                    if (tag.selfClosing) continue
                    val (kids, next) = readChildren(i, tag.name, s)
                    i = next
                    out += SvgNode.Group(kids, transformOf(tag.attrs), opacityOf(tag.attrs))
                }
                "text" -> {
                    val s = cascade(style, tag.attrs)
                    val close = src.indexOf("</text>", i)
                    val raw = if (close < 0) "" else src.substring(i, close)
                    i = if (close < 0) src.length else close + 7
                    val text = unescape(raw.replace(Regex("<[^>]*>"), "")).trim()
                    if (text.isNotEmpty()) {
                        out += SvgNode.Label(
                            text, number(tag.attrs["x"]) ?: 0f, number(tag.attrs["y"]) ?: 0f,
                            resolve(s, null), transformOf(tag.attrs), opacityOf(tag.attrs),
                        )
                    }
                }
                else -> {
                    val path = shapeOf(tag.name, tag.attrs)
                    if (!tag.selfClosing) i = skipTo(tag.name, i)
                    if (path != null) {
                        val s = cascade(style, tag.attrs)
                        if (s.evenOdd) path.fillType = PathFillType.EvenOdd
                        out += SvgNode.Shape(path, resolve(s, path.getBounds()), transformOf(tag.attrs), opacityOf(tag.attrs))
                    }
                }
            }
        }
        return out to src.length
    }

    private fun skipTo(name: String, from: Int): Int {
        var depth = 1
        var i = from
        while (i < src.length) {
            val lt = src.indexOf('<', i)
            if (lt < 0) return src.length
            val t = tagAt(lt) ?: run { i = lt + 1; null } ?: continue
            i = t.end
            if (t.name == name) {
                if (t.closing) depth-- else if (!t.selfClosing) depth++
                if (depth == 0) return i
            }
        }
        return src.length
    }

    private fun tagAt(lt: Int): Tag? {
        val gt = findTagEnd(lt) ?: return null
        var body = src.substring(lt + 1, gt).trim()
        val closing = body.startsWith("/")
        if (closing) body = body.substring(1).trim()
        val self = body.endsWith("/")
        if (self) body = body.dropLast(1).trim()
        val nameEnd = body.indexOfFirst { it.isWhitespace() }.let { if (it < 0) body.length else it }
        val name = body.substring(0, nameEnd)
        if (name.isEmpty() || !name[0].isLetter()) return null
        val attrs = LinkedHashMap<String, String>()
        for (m in ATTR.findAll(body.substring(nameEnd))) attrs[m.groupValues[1]] = unescape(m.groupValues[2].ifEmpty { m.groupValues[3] })
        attrs["style"]?.let { css ->
            for (decl in css.split(';')) {
                val c = decl.indexOf(':')
                if (c > 0) attrs[decl.substring(0, c).trim()] = decl.substring(c + 1).trim()
            }
        }
        return Tag(name, attrs, closing, self, gt + 1)
    }

    /** The `>` that ends the tag at [lt], skipping quoted attribute values. */
    private fun findTagEnd(lt: Int): Int? {
        var q = 0.toChar()
        var i = lt + 1
        while (i < src.length) {
            val ch = src[i]
            if (q != 0.toChar()) {
                if (ch == q) q = 0.toChar()
            } else if (ch == '"' || ch == '\'') q = ch
            else if (ch == '>') return i
            i++
        }
        return null
    }

    private fun collectGradients() {
        var i = 0
        while (true) {
            val lt = src.indexOf('<', i)
            if (lt < 0) break
            val t = tagAt(lt) ?: run { i = lt + 1; null } ?: continue
            i = t.end
            if (t.closing || (t.name != "linearGradient" && t.name != "radialGradient")) continue
            val id = t.attrs["id"] ?: continue
            val radial = t.name == "radialGradient"
            val user = t.attrs["gradientUnits"] == "userSpaceOnUse"
            val coords = if (radial) {
                floatArrayOf(frac(t.attrs["cx"], 0.5f), frac(t.attrs["cy"], 0.5f), frac(t.attrs["r"], 0.5f))
            } else {
                floatArrayOf(frac(t.attrs["x1"], 0f), frac(t.attrs["y1"], 0f), frac(t.attrs["x2"], 1f), frac(t.attrs["y2"], 0f))
            }
            val stops = ArrayList<Pair<Float, Color>>()
            if (!t.selfClosing) {
                val end = src.indexOf("</${t.name}>", i).let { if (it < 0) src.length else it }
                var j = i
                while (j < end) {
                    val l2 = src.indexOf('<', j)
                    if (l2 < 0 || l2 >= end) break
                    val s = tagAt(l2) ?: break
                    j = s.end
                    if (s.name != "stop" || s.closing) continue
                    val off = frac(s.attrs["offset"], 0f)
                    val c = colour(s.attrs["stop-color"]) ?: Color.Black
                    val o = number(s.attrs["stop-opacity"]) ?: 1f
                    stops += off to c.copy(alpha = c.alpha * o)
                }
                i = end
            }
            gradients[id] = GradientDef(radial, user, coords, stops)
        }
    }

    // ---- Styles ------------------------------------------------------------------------------------------------

    /** What an element inherits, before its paints are turned into brushes. */
    private data class Inherited(
        val fill: String?,
        val stroke: String?,
        val strokeWidth: Float,
        val fillOpacity: Float,
        val strokeOpacity: Float,
        val cap: StrokeCap,
        val join: StrokeJoin,
        val dash: FloatArray?,
        val evenOdd: Boolean,
        val fontSize: Float,
        val fontWeight: Int,
        val family: String,
        val italic: Boolean,
        val anchor: Int,
        val letterSpacing: Float,
        val filter: String?,
    )

    private fun cascade(parent: Inherited, a: Map<String, String>): Inherited = parent.copy(
        fill = a["fill"] ?: parent.fill,
        stroke = a["stroke"] ?: parent.stroke,
        strokeWidth = number(a["stroke-width"]) ?: parent.strokeWidth,
        fillOpacity = number(a["fill-opacity"]) ?: parent.fillOpacity,
        strokeOpacity = number(a["stroke-opacity"]) ?: parent.strokeOpacity,
        cap = when (a["stroke-linecap"]) {
            "round" -> StrokeCap.Round
            "square" -> StrokeCap.Square
            "butt" -> StrokeCap.Butt
            else -> parent.cap
        },
        join = when (a["stroke-linejoin"]) {
            "round" -> StrokeJoin.Round
            "bevel" -> StrokeJoin.Bevel
            "miter" -> StrokeJoin.Miter
            else -> parent.join
        },
        dash = a["stroke-dasharray"]?.let { d ->
            if (d == "none") null else d.split(Regex("[\\s,]+")).mapNotNull { number(it) }.let { v -> if (v.size % 2 == 1) v + v else v }.toFloatArray()
        } ?: parent.dash,
        evenOdd = a["fill-rule"]?.let { it == "evenodd" } ?: parent.evenOdd,
        fontSize = number(a["font-size"]) ?: parent.fontSize,
        fontWeight = a["font-weight"]?.let { w -> w.toIntOrNull() ?: if (w == "bold") 700 else 400 } ?: parent.fontWeight,
        family = a["font-family"] ?: parent.family,
        italic = a["font-style"]?.let { it == "italic" } ?: parent.italic,
        anchor = when (a["text-anchor"]) {
            "middle" -> 1
            "end" -> 2
            "start" -> 0
            else -> parent.anchor
        },
        letterSpacing = number(a["letter-spacing"]) ?: parent.letterSpacing,
        filter = a["filter"] ?: parent.filter,
    )

    private fun resolve(s: Inherited, bounds: Rect?): SvgStyle = SvgStyle(
        fill = paintOf(s.fill, bounds),
        stroke = paintOf(s.stroke, bounds),
        strokeWidth = s.strokeWidth,
        fillOpacity = s.fillOpacity,
        strokeOpacity = s.strokeOpacity,
        cap = s.cap,
        join = s.join,
        dash = s.dash,
        fontSize = s.fontSize,
        fontWeight = s.fontWeight,
        family = ShopType.familyFor(s.family),
        italic = s.italic,
        anchor = s.anchor,
        letterSpacing = s.letterSpacing,
        glows = glowsOf(s.filter),
    )

    private fun paintOf(v: String?, bounds: Rect?): SvgPaint? {
        val value = v?.trim() ?: return null
        if (value == "none" || value == "transparent") return null
        if (value == "currentColor") return SvgPaint.Current
        if (value.startsWith("url(")) {
            val id = value.substringAfter('#').substringBefore(')').trim()
            val g = gradients[id] ?: return null
            return SvgPaint.Gradient(brushOf(g, bounds ?: Rect(0f, 0f, 1f, 1f)))
        }
        return colour(value)?.let { SvgPaint.Solid(it) }
    }

    private fun brushOf(g: GradientDef, b: Rect): Brush {
        val stops = g.stops.ifEmpty { listOf(0f to Color.Black, 1f to Color.Black) }.toTypedArray()
        fun x(f: Float) = if (g.userSpace) f else b.left + f * b.width
        fun y(f: Float) = if (g.userSpace) f else b.top + f * b.height
        return if (g.radial) {
            val r = if (g.userSpace) g.coords[2] else g.coords[2] * maxOf(b.width, b.height)
            Brush.radialGradient(*stops, center = Offset(x(g.coords[0]), y(g.coords[1])), radius = r.coerceAtLeast(0.01f), tileMode = TileMode.Clamp)
        } else {
            Brush.linearGradient(*stops, start = Offset(x(g.coords[0]), y(g.coords[1])), end = Offset(x(g.coords[2]), y(g.coords[3])), tileMode = TileMode.Clamp)
        }
    }

    private fun glowsOf(filter: String?): List<SvgGlow> {
        if (filter == null) return emptyList()
        return GLOW.findAll(filter).mapNotNull { m ->
            val parts = m.groupValues[1].trim()
            val col = Regex("#[0-9a-fA-F]{3,8}|rgba?\\([^)]*\\)").find(parts)?.value?.let(::colour) ?: return@mapNotNull null
            val lengths = parts.replace(Regex("#[0-9a-fA-F]{3,8}|rgba?\\([^)]*\\)"), "").trim().split(Regex("\\s+")).mapNotNull { number(it) }
            SvgGlow(col, (lengths.getOrNull(2) ?: 4f) * 1.6f)
        }.toList()
    }

    // ---- Shapes ------------------------------------------------------------------------------------------------

    private fun shapeOf(name: String, a: Map<String, String>): Path? = when (name) {
        "path" -> a["d"]?.let { d -> SvgPath.parseOrNull(d)?.let { cmds -> Path().also { cmds.replay(Sink(it)) } } }
        "rect" -> {
            val x = number(a["x"]) ?: 0f
            val y = number(a["y"]) ?: 0f
            val w = number(a["width"]) ?: 0f
            val h = number(a["height"]) ?: 0f
            var rx = number(a["rx"])
            var ry = number(a["ry"])
            if (rx == null) rx = ry
            if (ry == null) ry = rx
            val cx = (rx ?: 0f).coerceAtMost(w / 2f)
            val cy = (ry ?: 0f).coerceAtMost(h / 2f)
            if (w <= 0f || h <= 0f) null
            else Path().apply { addRoundRect(RoundRect(Rect(x, y, x + w, y + h), CornerRadius(cx, cy))) }
        }
        "circle" -> {
            val r = number(a["r"]) ?: 0f
            if (r <= 0f) null else {
                val cx = number(a["cx"]) ?: 0f
                val cy = number(a["cy"]) ?: 0f
                Path().apply { addOval(Rect(cx - r, cy - r, cx + r, cy + r)) }
            }
        }
        "ellipse" -> {
            val rx = number(a["rx"]) ?: 0f
            val ry = number(a["ry"]) ?: 0f
            if (rx <= 0f || ry <= 0f) null else {
                val cx = number(a["cx"]) ?: 0f
                val cy = number(a["cy"]) ?: 0f
                Path().apply { addOval(Rect(cx - rx, cy - ry, cx + rx, cy + ry)) }
            }
        }
        "line" -> Path().apply {
            moveTo(number(a["x1"]) ?: 0f, number(a["y1"]) ?: 0f)
            lineTo(number(a["x2"]) ?: 0f, number(a["y2"]) ?: 0f)
        }
        "polyline", "polygon" -> {
            val pts = a["points"]?.trim()?.split(Regex("[\\s,]+"))?.mapNotNull { it.toFloatOrNull() }.orEmpty()
            if (pts.size < 4) null else Path().apply {
                moveTo(pts[0], pts[1])
                var k = 2
                while (k + 1 < pts.size) {
                    lineTo(pts[k], pts[k + 1]); k += 2
                }
                if (name == "polygon") close()
            }
        }
        else -> null
    }

    private class Sink(private val p: Path) : PathSink {
        override fun moveTo(x: Float, y: Float) = p.moveTo(x, y)
        override fun lineTo(x: Float, y: Float) = p.lineTo(x, y)
        override fun quadTo(x1: Float, y1: Float, x: Float, y: Float) = p.quadraticTo(x1, y1, x, y)
        override fun cubicTo(x1: Float, y1: Float, x2: Float, y2: Float, x: Float, y: Float) = p.cubicTo(x1, y1, x2, y2, x, y)
        override fun close() = p.close()
    }

    private fun opacityOf(a: Map<String, String>): Float = number(a["opacity"])?.coerceIn(0f, 1f) ?: 1f

    /** An SVG `transform` list as one matrix, or null when there is none. */
    private fun transformOf(a: Map<String, String>): Matrix? {
        val t = a["transform"]?.takeIf { it.isNotBlank() } ?: return null
        // Affine [a b c d e f], composed left to right as SVG applies them.
        var m = floatArrayOf(1f, 0f, 0f, 1f, 0f, 0f)
        for (f in TRANSFORM.findAll(t)) {
            val v = f.groupValues[2].trim().split(Regex("[\\s,]+")).mapNotNull { it.toFloatOrNull() }
            val next = when (f.groupValues[1]) {
                "translate" -> floatArrayOf(1f, 0f, 0f, 1f, v.getOrElse(0) { 0f }, v.getOrElse(1) { 0f })
                "scale" -> v.getOrElse(0) { 1f }.let { sx -> floatArrayOf(sx, 0f, 0f, v.getOrElse(1) { sx }, 0f, 0f) }
                "rotate" -> {
                    val rad = Math.toRadians(v.getOrElse(0) { 0f }.toDouble())
                    val c = kotlin.math.cos(rad).toFloat()
                    val s = kotlin.math.sin(rad).toFloat()
                    val cx = v.getOrElse(1) { 0f }
                    val cy = v.getOrElse(2) { 0f }
                    // translate(cx cy) rotate(a) translate(-cx -cy)
                    floatArrayOf(c, s, -s, c, cx - c * cx + s * cy, cy - s * cx - c * cy)
                }
                "matrix" -> if (v.size == 6) v.toFloatArray() else continue
                "skewX" -> floatArrayOf(1f, 0f, kotlin.math.tan(Math.toRadians(v.getOrElse(0) { 0f }.toDouble())).toFloat(), 1f, 0f, 0f)
                "skewY" -> floatArrayOf(1f, kotlin.math.tan(Math.toRadians(v.getOrElse(0) { 0f }.toDouble())).toFloat(), 0f, 1f, 0f, 0f)
                else -> continue
            }
            m = multiply(m, next)
        }
        return Matrix().apply {
            values[Matrix.ScaleX] = m[0]
            values[Matrix.SkewY] = m[1]
            values[Matrix.SkewX] = m[2]
            values[Matrix.ScaleY] = m[3]
            values[Matrix.TranslateX] = m[4]
            values[Matrix.TranslateY] = m[5]
        }
    }

    private fun multiply(p: FloatArray, q: FloatArray): FloatArray = floatArrayOf(
        p[0] * q[0] + p[2] * q[1],
        p[1] * q[0] + p[3] * q[1],
        p[0] * q[2] + p[2] * q[3],
        p[1] * q[2] + p[3] * q[3],
        p[0] * q[4] + p[2] * q[5] + p[4],
        p[1] * q[4] + p[3] * q[5] + p[5],
    )

    companion object {
        private val ATTR = Regex("""([\w:-]+)\s*=\s*(?:"([^"]*)"|'([^']*)')""")
        private val TRANSFORM = Regex("""(translate|scale|rotate|matrix|skewX|skewY)\s*\(([^)]*)\)""")
        private val GLOW = Regex("""drop-shadow\(((?:[^()]|\([^()]*\))*)\)""")

        private val DEFAULT = Inherited(
            fill = "#000", stroke = null, strokeWidth = 1f, fillOpacity = 1f, strokeOpacity = 1f,
            cap = StrokeCap.Butt, join = StrokeJoin.Miter, dash = null, evenOdd = false,
            fontSize = 16f, fontWeight = 400, family = "Geist", italic = false, anchor = 0, letterSpacing = 0f, filter = null,
        )

        /** A number with an optional `px`, or null. */
        fun number(s: String?): Float? = s?.trim()?.removeSuffix("px")?.trim()?.toFloatOrNull()

        /** A fraction: "50%" is 0.5, "0.4" is 0.4. */
        fun frac(s: String?, default: Float): Float {
            val v = s?.trim() ?: return default
            return if (v.endsWith("%")) (v.dropLast(1).toFloatOrNull() ?: return default) / 100f else v.toFloatOrNull() ?: default
        }

        fun unescape(s: String): String =
            s.replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&#39;", "'")

        /** A CSS colour: `#rgb`, `#rrggbb`, `#rrggbbaa`, `rgb(…)`, `rgba(…)` or a few names; null when not one. */
        fun colour(s: String?): Color? {
            val v = s?.trim()?.lowercase(Locale.ROOT) ?: return null
            if (v.startsWith("#")) {
                val h = v.substring(1)
                return when (h.length) {
                    3 -> h.map { "$it$it" }.joinToString("").toLongOrNull(16)?.let { Color(0xFF000000 or it) }
                    6 -> h.toLongOrNull(16)?.let { Color(0xFF000000 or it) }
                    8 -> h.toLongOrNull(16)?.let { Color(((it and 0xFF) shl 24) or (it ushr 8)) }
                    else -> null
                }
            }
            if (v.startsWith("rgb")) {
                val n = v.substringAfter('(').substringBefore(')').split(',').map { it.trim() }
                if (n.size < 3) return null
                val r = n[0].toFloatOrNull() ?: return null
                val g = n[1].toFloatOrNull() ?: return null
                val b = n[2].toFloatOrNull() ?: return null
                val a = n.getOrNull(3)?.toFloatOrNull() ?: 1f
                return Color(r / 255f, g / 255f, b / 255f, a.coerceIn(0f, 1f))
            }
            return when (v) {
                "white" -> Color.White
                "black" -> Color.Black
                "red" -> Color.Red
                else -> null
            }
        }
    }
}

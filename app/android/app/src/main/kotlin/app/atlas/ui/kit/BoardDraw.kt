package app.atlas.ui.kit

import android.graphics.Matrix
import android.graphics.SweepGradient
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.LinearGradientShader
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.RadialGradientShader
import androidx.compose.ui.graphics.Shader
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.addOutline
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/*
 * What the generated board code draws with (`tools/boardgen`): the boards' CSS boxes, text and vectors as the browser
 * laid them out, at their place in board px (dp under BoardScale). Every rule here is CSS's own: corner radii clamped as
 * CSS clamps them, borders inside the box (border-box), gradients along CSS's gradient line, box-shadow lists.
 */

/** A CSS colour as ARGB. */
typealias Argb = Long

fun argb(v: Argb): Color = Color(v.toInt())

/** CSS `border-radius`: the four corners (top-left, top-right, bottom-right, bottom-left), clamped to the box as CSS does. */
class CssCorners(val tl: Float, val tr: Float, val br: Float, val bl: Float) : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline {
        val d = density.density
        var a = tl * d
        var b = tr * d
        var c = br * d
        var e = bl * d
        // CSS: if adjacent radii overflow a side, all radii scale by the smallest side / sum ratio
        val f = minOf(1f, ratio(size.width, a + b), ratio(size.width, e + c), ratio(size.height, a + e), ratio(size.height, b + c))
        a *= f; b *= f; c *= f; e *= f
        return RoundedCornerShape(a, b, c, e).createOutline(size, layoutDirection, density)
    }

    private fun ratio(side: Float, sum: Float) = if (sum <= 0f) 1f else side / sum

    /** The four radii in px for a box of [size], clamped as CSS does: top-left, top-right, bottom-right, bottom-left. */
    fun radiiPx(size: Size, density: Float): FloatArray {
        val d = density
        val f = minOf(1f, ratio(size.width, (tl + tr) * d), ratio(size.width, (bl + br) * d), ratio(size.height, (tl + bl) * d), ratio(size.height, (tr + br) * d))
        return floatArrayOf(tl * d * f, tr * d * f, br * d * f, bl * d * f)
    }

    private fun RoundedCornerShape(a: Float, b: Float, c: Float, e: Float) =
        androidx.compose.foundation.shape.RoundedCornerShape(topStart = a, topEnd = b, bottomEnd = c, bottomStart = e)
}

/** One `box-shadow`: offset, blur, spread (board px) and colour; [inset] draws inside the box. */
class CssShadow(val x: Float, val y: Float, val blur: Float, val spread: Float, val colour: Argb, val inset: Boolean = false)

/** A CSS gradient: a brush for a box of the given size (px) at a [density]. */
fun interface CssGradient {
    fun brush(size: Size, density: Float): Brush
}

/** CSS `linear-gradient([angle], stops…)`: the gradient line through the centre, as long as the box's projection on it. */
fun cssLinear(angleDeg: Float, vararg stops: Pair<Float, Argb>): CssGradient = CssGradient { s, _ ->
    val t = Math.toRadians(angleDeg.toDouble())
    val len = (abs(s.width * sin(t)) + abs(s.height * cos(t))).toFloat()
    val dx = (sin(t) * len / 2).toFloat()
    val dy = (-cos(t) * len / 2).toFloat()
    val c = Offset(s.width / 2, s.height / 2)
    Brush.linearGradient(*stops.map { it.first to argb(it.second) }.toTypedArray(), start = c - Offset(dx, dy), end = c + Offset(dx, dy))
}

/** CSS `repeating-linear-gradient([angle], stops in px…)`: one period of [period] px repeated along the gradient line. */
fun cssRepeatingLinear(angleDeg: Float, period: Float, vararg stops: Pair<Float, Argb>): CssGradient = CssGradient { s, d ->
    val t = Math.toRadians(angleDeg.toDouble())
    val len = (abs(s.width * sin(t)) + abs(s.height * cos(t))).toFloat()
    val ux = sin(t).toFloat()
    val uy = -cos(t).toFloat()
    val start = Offset(s.width / 2 - ux * len / 2, s.height / 2 - uy * len / 2)
    val p = period * d
    object : ShaderBrush() {
        override fun createShader(size: Size): Shader = LinearGradientShader(
            from = start, to = start + Offset(ux * p, uy * p),
            colors = stops.map { argb(it.second) }, colorStops = stops.map { it.first / period }, tileMode = TileMode.Repeated,
        )
    }
}

/**
 * CSS `radial-gradient([circle|ellipse] at cx cy, stops…)`, sized to the farthest corner (CSS's default); the centre is
 * given in px of the box, stops as fractions of the radius.
 */
fun cssRadial(circle: Boolean, cx: Float, cy: Float, vararg stops: Pair<Float, Argb>): CssGradient = CssGradient { s, d ->
    val c = Offset(cx * d, cy * d)
    val fx = max(c.x, s.width - c.x)
    val fy = max(c.y, s.height - c.y)
    object : ShaderBrush() {
        override fun createShader(size: Size): Shader {
            if (circle) {
                return RadialGradientShader(c, hypot(fx, fy).coerceAtLeast(0.01f), stops.map { argb(it.second) }, stops.map { it.first })
            }
            // an ellipse through the farthest corner with the box's own proportions: a circle scaled on y
            val rx = (fx * Math.sqrt(2.0)).toFloat().coerceAtLeast(0.01f)
            val ry = (fy * Math.sqrt(2.0)).toFloat().coerceAtLeast(0.01f)
            val g = android.graphics.RadialGradient(c.x, c.y, rx, stops.map { argb(it.second).toArgbInt() }.toIntArray(), stops.map { it.first }.toFloatArray(), android.graphics.Shader.TileMode.CLAMP)
            g.setLocalMatrix(Matrix().apply { setScale(1f, ry / rx, c.x, c.y) })
            return g
        }
    }
}

/** CSS `conic-gradient(from [fromDeg] at cx cy, stops…)`: stops as fractions of a turn, clockwise from straight up. */
fun cssConic(fromDeg: Float, cx: Float, cy: Float, vararg stops: Pair<Float, Argb>): CssGradient = CssGradient { _, d ->
    object : ShaderBrush() {
        override fun createShader(size: Size): Shader {
            val g = SweepGradient(cx * d, cy * d, stops.map { argb(it.second).toArgbInt() }.toIntArray(), stops.map { it.first }.toFloatArray())
            // CSS measures from straight up, Android's sweep from 3 o'clock
            g.setLocalMatrix(Matrix().apply { setRotate(fromDeg - 90f, cx * d, cy * d) })
            return g
        }
    }
}

private fun Color.toArgbInt(): Int = (alpha * 255 + 0.5f).toInt() shl 24 or ((red * 255 + 0.5f).toInt() shl 16) or ((green * 255 + 0.5f).toInt() shl 8) or (blue * 255 + 0.5f).toInt()

/** A CSS border: widths per side (top, right, bottom, left) and one colour per side. */
class CssBorder(val top: Float, val right: Float, val bottom: Float, val left: Float, val colours: LongArray)

/**
 * One board box at ([x], [y]) sized [w] × [h] board px: its shadows, glass, background colour and gradient(s), border and
 * opacity, as the browser drew it. [clip] clips [content] (its children, placed relative to this box) to its corners.
 */
@Composable
fun El(
    x: Float, y: Float, w: Float, h: Float,
    bg: Argb = 0, grad: List<CssGradient> = emptyList(), r: CssCorners? = null, shadows: List<CssShadow> = emptyList(),
    blur: Float = 0f, saturate: Float = 1f, border: CssBorder? = null, alpha: Float = 1f, clip: Boolean = false,
    matrix: FloatArray? = null, origin: Pair<Float, Float>? = null,
    modifier: Modifier = Modifier, content: @Composable BoxScope.() -> Unit = {},
) {
    val shape: Shape = r ?: RectangleShapeCss
    var m = modifier.offset(x.dp, y.dp).requiredSize(w.dp, h.dp)
    if (matrix != null) m = m.graphicsLayer {
        // CSS transform about its origin, given as a 2D matrix (a b c d e f), e and f in board px
        val o = origin ?: (w / 2 to h / 2)
        val ox = o.first * density
        val oy = o.second * density
        val a = matrix[0]
        val b = matrix[1]
        val c = matrix[2]
        val dd = matrix[3]
        rotationZ = Math.toDegrees(kotlin.math.atan2(b.toDouble(), a.toDouble())).toFloat()
        scaleX = hypot(a, b)
        scaleY = (a * dd - b * c) / hypot(a, b)
        translationX = matrix[4] * density
        translationY = matrix[5] * density
        transformOrigin = androidx.compose.ui.graphics.TransformOrigin(ox / size.width.coerceAtLeast(1f), oy / size.height.coerceAtLeast(1f))
    }
    if (alpha < 1f) m = m.graphicsLayer { this.alpha = alpha }
    for (s in shadows) if (!s.inset) m = m.cssShadow(shape, y = s.y.dp, blur = s.blur.dp, colour = argb(s.colour), x = s.x.dp, spread = s.spread.dp)
    if (blur > 0f) m = m.frosted(shape, argb(bg), blur.dp, saturate)
    m = m.drawBehind {
        val outline = shape.createOutline(size, layoutDirection, this)
        val path = Path().apply { addOutline(outline) }
        if (blur <= 0f && bg != 0L) drawPath(path, argb(bg))
        for (g in grad.asReversed()) drawPath(path, g.brush(size, density))
        border?.let { b ->
            // CSS: the border is the ring between the outer rounded box and the inner one, inset by the widths, whose
            // corner radii shrink by the adjacent widths (never below 0)
            val d = density
            val inner = Path().apply {
                val o = (r ?: RectangleShapeCss).radiiPx(size, d)
                addRoundRect(
                    androidx.compose.ui.geometry.RoundRect(
                        left = b.left * d, top = b.top * d, right = size.width - b.right * d, bottom = size.height - b.bottom * d,
                        topLeftCornerRadius = androidx.compose.ui.geometry.CornerRadius(max(0f, o[0] - b.left * d), max(0f, o[0] - b.top * d)),
                        topRightCornerRadius = androidx.compose.ui.geometry.CornerRadius(max(0f, o[1] - b.right * d), max(0f, o[1] - b.top * d)),
                        bottomRightCornerRadius = androidx.compose.ui.geometry.CornerRadius(max(0f, o[2] - b.right * d), max(0f, o[2] - b.bottom * d)),
                        bottomLeftCornerRadius = androidx.compose.ui.geometry.CornerRadius(max(0f, o[3] - b.left * d), max(0f, o[3] - b.bottom * d)),
                    ),
                )
            }
            val cols = b.colours
            val uniform = cols.all { it == cols[0] }
            clipPath(inner, clipOp = androidx.compose.ui.graphics.ClipOp.Difference) {
                if (uniform) {
                    drawPath(path, argb(cols[0]))
                } else clipPath(path) {
                    // sides of different colours: each side's strip within the ring
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
    if (clip) m = m.clip(shape)
    Box(m, content = content)
}

private val RectangleShapeCss = CssCorners(0f, 0f, 0f, 0f)

/**
 * One line (or block) of a board's text at its line box ([x], [y], [w] × [h] board px): its face by CSS name, size,
 * weight, style, line height, letter spacing, colour, alignment, decoration and shadow.
 */
@Composable
fun Tx(
    text: String, x: Float, y: Float, w: Float, h: Float,
    family: String, size: Float, weight: Int = 400, italic: Boolean = false, colour: Argb = 0xFF14161B,
    lineHeight: Float = 0f, spacing: Float = 0f, align: TextAlign = TextAlign.Start, decoration: TextDecoration? = null,
    shadow: CssShadow? = null, tnum: Boolean = false, wrap: Boolean = false,
) {
    Text(
        text,
        Modifier.offset(x.dp, y.dp).requiredSize(w.dp + if (wrap) 0.dp else 2.dp, h.dp),
        style = TextStyle(
            fontFamily = BoardFonts.of(family),
            fontSize = size.sp,
            fontWeight = FontWeight(weight),
            fontStyle = if (italic) FontStyle.Italic else FontStyle.Normal,
            lineHeight = if (lineHeight > 0f) lineHeight.sp else androidx.compose.ui.unit.TextUnit.Unspecified,
            lineHeightStyle = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.None),
            letterSpacing = if (spacing != 0f) (spacing / size).em else androidx.compose.ui.unit.TextUnit.Unspecified,
            textAlign = align,
            textDecoration = decoration,
            fontFeatureSettings = if (tnum) "tnum" else null,
            shadow = shadow?.let { androidx.compose.ui.graphics.Shadow(argb(it.colour), Offset(it.x, it.y), it.blur) },
        ),
        color = argb(colour),
        softWrap = wrap,
        maxLines = if (wrap) Int.MAX_VALUE else 1,
    )
}

/** A board symbol ([id], from the board symbol set) in its box, tinted with the CSS `color` (`currentColor`). */
@Composable
fun Sy(id: String, x: Float, y: Float, w: Float, h: Float, tint: Argb = 0xFF14161B, alpha: Float = 1f) {
    Symbol(id, Modifier.offset(x.dp, y.dp).requiredSize(w.dp, h.dp).then(if (alpha < 1f) Modifier.graphicsLayer { this.alpha = alpha } else Modifier), tint = argb(tint), size = min(w, h).dp)
}

/** The board's 412 × 915 frame, its children placed by their board px. */
@Composable
fun BoardFrame(modifier: Modifier = Modifier, bg: Argb = 0, content: @Composable BoxScope.() -> Unit) {
    Box(modifier.requiredSize(412.dp, 915.dp).then(if (bg != 0L) Modifier.drawBehind { drawRect(argb(bg)) } else Modifier), content = content)
}

/** Board px as dp. */
val Float.bp: Dp get() = this.dp

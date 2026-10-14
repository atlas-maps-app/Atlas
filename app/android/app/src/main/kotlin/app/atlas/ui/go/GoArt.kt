package app.atlas.ui.go

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.atlas.places.PathSink
import app.atlas.places.SvgPath

/** The colour a part takes from the caller (SVG's `currentColor`). */
internal const val CURRENT = 1L

/** A linear gradient in the part's own box (0..1 along each side), its stops as offset to ARGB. */
internal class Gradient(val x1: Float, val y1: Float, val x2: Float, val y2: Float, val stops: List<Pair<Float, Long>>)

/**
 * One element of a board's SVG as drawn there: path data [d] (shapes already turned into paths) with its [fill] and
 * [stroke] (ARGB, or [CURRENT]), [width], [cap], [join], [dash], [alpha] and a [gradient] fill; ([dx], [dy]) is a
 * group's translation.
 */
internal class ArtPart(
    val d: String,
    val fill: Long? = null,
    val stroke: Long? = null,
    val width: Float = 1f,
    val cap: StrokeCap = StrokeCap.Butt,
    val join: StrokeJoin = StrokeJoin.Miter,
    val dash: FloatArray? = null,
    val alpha: Float = 1f,
    val gradient: Gradient? = null,
    val dx: Float = 0f,
    val dy: Float = 0f,
) {
    /** The path in viewBox units, parsed once. */
    val path: Path by lazy {
        Path().also { p ->
            SvgPath.parse(d).replay(object : PathSink {
                override fun moveTo(x: Float, y: Float) = p.moveTo(x + dx, y + dy)
                override fun lineTo(x: Float, y: Float) = p.lineTo(x + dx, y + dy)
                override fun quadTo(x1: Float, y1: Float, x: Float, y: Float) = p.quadraticTo(x1 + dx, y1 + dy, x + dx, y + dy)
                override fun cubicTo(x1: Float, y1: Float, x2: Float, y2: Float, x: Float, y: Float) =
                    p.cubicTo(x1 + dx, y1 + dy, x2 + dx, y2 + dy, x + dx, y + dy)
                override fun close() = p.close()
            })
        }
    }

    val bounds: Rect by lazy { path.getBounds() }
}

/** A board's drawing: its viewBox ([x], [y], [w], [h]) and its parts in drawing order. */
internal class Art(val x: Float, val y: Float, val w: Float, val h: Float, val parts: List<ArtPart>) {
    /** The same drawing without the parts [drop] says to leave out (by index), for variations of one board object. */
    fun without(vararg drop: Int): Art = Art(x, y, w, h, parts.filterIndexed { i, _ -> i !in drop })

    /** The same drawing mirrored left to right (a left turn from a right one). */
    val mirrored: Art by lazy { Art(x, y, w, h, parts).also { it.flip = true } }

    internal var flip = false

    /** The same drawing with every colour it states taken from the caller instead (one-colour artwork, retinted). */
    val tinted: Art by lazy {
        Art(x, y, w, h, parts.map { p ->
            ArtPart(p.d, p.fill?.let { CURRENT }, p.stroke?.let { CURRENT }, p.width, p.cap, p.join, p.dash, p.alpha, null, p.dx, p.dy)
        }).also { it.flip = flip }
    }
}

/**
 * Draws [art] into the box at [topLeft] of [size]: scaled to fit and centred, or, with [fill], stretched to the box
 * (a truss across the width). Parts in [CURRENT] take [tint]; stroke widths scale with the drawing.
 */
internal fun DrawScope.drawArt(art: Art, topLeft: Offset = Offset.Zero, size: Size = this.size, tint: Color = Color.Black, fill: Boolean = false) {
    val sx = size.width / art.w
    val sy = size.height / art.h
    val s = minOf(sx, sy)
    val scaleX = if (fill) sx else s
    val scaleY = if (fill) sy else s
    val ox = topLeft.x + (size.width - art.w * scaleX) / 2f
    val oy = topLeft.y + (size.height - art.h * scaleY) / 2f
    withTransform({
        translate(ox, oy)
        if (art.flip) {
            translate(art.w * scaleX, 0f)
            scale(-scaleX, scaleY, Offset.Zero)
        } else {
            scale(scaleX, scaleY, Offset.Zero)
        }
        translate(-art.x, -art.y)
    }) {
        for (p in art.parts) {
            val g = p.gradient
            if (g != null) {
                val b = p.bounds
                drawPath(
                    p.path,
                    Brush.linearGradient(
                        *g.stops.map { (o, c) -> o to Color(c.toInt()) }.toTypedArray(),
                        start = Offset(b.left + g.x1 * b.width, b.top + g.y1 * b.height),
                        end = Offset(b.left + g.x2 * b.width, b.top + g.y2 * b.height),
                    ),
                    alpha = p.alpha,
                )
            } else p.fill?.let { drawPath(p.path, colourOf(it, tint), alpha = p.alpha, style = Fill) }
            p.stroke?.let {
                drawPath(
                    p.path, colourOf(it, tint), alpha = p.alpha,
                    style = Stroke(p.width, cap = p.cap, join = p.join, pathEffect = p.dash?.let { d -> PathEffect.dashPathEffect(d) }),
                )
            }
        }
    }
}

private fun colourOf(c: Long, tint: Color): Color = if (c == CURRENT) tint else Color(c.toInt())

/** A board drawing at [size] (its aspect kept), in [tint] where it takes the caller's colour. */
@Composable
internal fun ArtIcon(art: Art, modifier: Modifier = Modifier, size: Dp = 24.dp, tint: Color = LocalContentColor.current, contentDescription: String? = null) {
    val w = if (art.w >= art.h) size else size * (art.w / art.h)
    val h = if (art.h >= art.w) size else size * (art.h / art.w)
    ArtBox(art, modifier.size(w, h), tint, contentDescription)
}

/** A board drawing filling [modifier]'s box. */
@Composable
internal fun ArtBox(art: Art, modifier: Modifier, tint: Color = LocalContentColor.current, contentDescription: String? = null, fill: Boolean = false) {
    val described = if (contentDescription != null) Modifier.semantics {
        this.contentDescription = contentDescription
        role = Role.Image
    } else Modifier
    Canvas(modifier.then(described)) { drawArt(art, tint = tint, fill = fill) }
}

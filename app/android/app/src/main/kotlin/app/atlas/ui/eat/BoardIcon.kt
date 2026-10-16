package app.atlas.ui.eat

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import app.atlas.places.PathSink
import app.atlas.places.SvgPath

/**
 * An icon exactly as an eat board draws it: the board's own SVG path data on its [viewBox] (24 for most), stroked
 * [strokeWidth] units wide with the board's caps and joins, or filled. Circles and rectangles of the source are
 * written as path data.
 */
class BoardGlyph(
    val paths: List<String>,
    val strokeWidth: Float = 1.5f,
    val viewBox: Float = 24f,
    val fill: Boolean = false,
    val cap: StrokeCap = StrokeCap.Round,
    val join: StrokeJoin = StrokeJoin.Round,
    /** Paths filled even on a stroked glyph (a dot, a diamond). */
    val filled: List<String> = emptyList(),
) {
    internal val strokes: List<Path> by lazy { paths.map(::toPath) }
    internal val fills: List<Path> by lazy { filled.map(::toPath) }

    private fun toPath(d: String): Path {
        val p = Path()
        SvgPath.parse(d).replay(object : PathSink {
            override fun moveTo(x: Float, y: Float) = p.moveTo(x, y)
            override fun lineTo(x: Float, y: Float) = p.lineTo(x, y)
            override fun quadTo(x1: Float, y1: Float, x: Float, y: Float) = p.quadraticTo(x1, y1, x, y)
            override fun cubicTo(x1: Float, y1: Float, x2: Float, y2: Float, x: Float, y: Float) = p.cubicTo(x1, y1, x2, y2, x, y)
            override fun close() = p.close()
        })
        return p
    }
}

/** Draws [glyph] in [tint] into a square of [side] px at [topLeft]. */
fun DrawScope.drawBoardGlyph(glyph: BoardGlyph, tint: Color, topLeft: Offset = Offset.Zero, side: Float = size.minDimension) {
    val k = side / glyph.viewBox
    withTransform({
        translate(topLeft.x, topLeft.y)
        scale(k, k, Offset.Zero)
    }) {
        if (glyph.fill) {
            for (p in glyph.strokes) drawPath(p, tint)
        } else {
            val stroke = Stroke(glyph.strokeWidth, cap = glyph.cap, join = glyph.join)
            for (p in glyph.strokes) drawPath(p, tint, style = stroke)
        }
        for (p in glyph.fills) drawPath(p, tint)
    }
}

/** A board glyph as a composable of [size], in [tint]; [contentDescription] when it stands alone. */
@Composable
fun BoardIcon(glyph: BoardGlyph, tint: Color, size: Dp, modifier: Modifier = Modifier, contentDescription: String? = null) {
    val m = if (contentDescription != null) modifier.semantics { this.contentDescription = contentDescription } else modifier
    Canvas(m.size(size)) { drawBoardGlyph(glyph, tint) }
}

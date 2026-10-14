package app.atlas.ui.kit

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.atlas.places.Icon
import app.atlas.places.IconPath
import app.atlas.places.PathSink
import app.atlas.places.Symbols
import app.atlas.places.ViewBox
import java.util.concurrent.ConcurrentHashMap

/**
 * Draws the symbol [id] from `:places` (`maki-cafe`, `ui-wheelchair`, `logo-albertheijn`) or the app's own few
 * ([AppSymbols]), tinted, in a [size] square. Give a [contentDescription] when the symbol alone carries meaning
 * (an icon-only control or a status); leave it null when a label next to it already says the same.
 */
@Composable
fun Symbol(
    id: String,
    modifier: Modifier = Modifier,
    tint: Color = LocalContentColor.current,
    size: Dp = 24.dp,
    contentDescription: String? = null,
    /** Thicker or thinner strokes for the stroked UI set: 1 is Lucide's 2 units on a 24 grid. */
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
        val stroke = Stroke(width = s.strokeWidth * weight, cap = StrokeCap.Round, join = StrokeJoin.Round)
        for (i in s.paths.indices) {
            drawPath(s.paths[i], tint, style = if (s.stroked[i]) stroke else Fill)
        }
    }
}

/** True when Atlas can draw [id]. */
fun hasSymbol(id: String): Boolean = AppSymbols.has(id) || Symbols.has(id)

/** One symbol's paths, built once in viewBox units and kept. */
internal class SymbolPaths(val viewBox: ViewBox, val paths: List<Path>, val stroked: BooleanArray, val strokeWidth: Float) {
    companion object {
        private val cache = ConcurrentHashMap<String, SymbolPaths>()
        private val missing = ConcurrentHashMap.newKeySet<String>()

        fun of(id: String): SymbolPaths? {
            cache[id]?.let { return it }
            if (id in missing) return null
            val icon = AppSymbols.icon(id) ?: Symbols.icon(id)
            if (icon == null) {
                missing += id
                return null
            }
            return build(icon).also { cache[id] = it }
        }

        private fun build(icon: Icon): SymbolPaths {
            val paths = ArrayList<Path>(icon.paths.size)
            val stroked = BooleanArray(icon.paths.size)
            icon.paths.forEachIndexed { i, p ->
                val path = Path()
                p.commands.replay(ComposePathSink(path))
                if (p.evenOdd) path.fillType = PathFillType.EvenOdd
                paths += path
                // A path marked neither stroke nor fill follows SVG's default: filled.
                stroked[i] = p.stroke && !p.fill
            }
            return SymbolPaths(icon.viewBox, paths, stroked, icon.strokeWidth)
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

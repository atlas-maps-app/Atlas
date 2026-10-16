package app.atlas.ui.local

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.Dp

/** The small drawings the boards draw inline (an arrow, a camera, a peak) as their own SVG path data on a 24 grid. */
internal object BoardPaths {
    const val CHEVRONS = "M9 6l-6 6 6 6M15 6l6 6-6 6"
    const val STEP_BACK = "M12 5v14M5 12l7 7 7-7"
    const val STEP_FORWARD = "M12 19V5M5 12l7-7 7 7"
    const val CAMERA = "M4 8h3l2-3h6l2 3h3v11H4z"
    const val CAMERA_LENS = "M15.5 13a3.5 3.5 0 1 1-7 0 3.5 3.5 0 0 1 7 0z"
    const val PEAK = "M3 20l6-12 4 6 3-4 5 10z"
    const val HEADING = "M12 2l5 14-5-3-5 3z"
    const val SUN_BEHIND = "M3 20l6-12 4 6 3-4 5 10z"

    fun parse(d: String): Path = PathParser().parsePathString(d).toPath()
}

/** One or more of the boards' paths drawn in [tint], stroked (or [filled]) on a 24 grid scaled to [size]. */
@Composable
internal fun PathGlyph(vararg d: String, tint: Color, size: Dp, modifier: Modifier = Modifier, strokeWidth: Float = 2f, filled: Boolean = false, round: Boolean = true) {
    val paths = remember(d.toList()) { d.map { BoardPaths.parse(it) } }
    Canvas(modifier.size(size)) { drawPaths(paths, tint, strokeWidth, filled, round) }
}

internal fun DrawScope.drawPaths(paths: List<Path>, tint: Color, strokeWidth: Float, filled: Boolean, round: Boolean = true) {
    val s = size.minDimension / 24f
    scale(s, s, Offset.Zero) {
        for (p in paths) drawPath(p, tint, style = if (filled) Fill else Stroke(strokeWidth, cap = if (round) StrokeCap.Round else StrokeCap.Butt, join = if (round) StrokeJoin.Round else StrokeJoin.Miter))
    }
}

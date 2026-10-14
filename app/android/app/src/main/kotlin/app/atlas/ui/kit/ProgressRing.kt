package app.atlas.ui.kit

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * A ring that fills clockwise from the top: time left open, a charger's free share, the distance to the next turn on
 * the Ride board. [content] sits in the middle.
 */
@Composable
fun ProgressRing(
    progress: Float,
    modifier: Modifier = Modifier,
    size: Dp = 64.dp,
    stroke: Dp = 6.dp,
    colour: Color = Atlas.colors.ring,
    track: Color = Atlas.colors.line,
    content: @Composable BoxScope.() -> Unit = {},
) {
    val target = fraction(progress)
    val p by animateFloatAsState(target, label = "ring")
    Box(
        modifier.size(size).semantics { progressBarRangeInfo = ProgressBarRangeInfo(target, 0f..1f) },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val w = stroke.toPx()
            val d = this.size.minDimension - w
            val topLeft = Offset((this.size.width - d) / 2f, (this.size.height - d) / 2f)
            drawArc(track, 0f, 360f, false, topLeft, Size(d, d), style = Stroke(w))
            if (p > 0f) drawArc(colour, -90f, 360f * p, false, topLeft, Size(d, d), style = Stroke(w, cap = StrokeCap.Round))
        }
        content()
    }
}

/** [value] as a share in 0..1 for the kit's instruments; a share that cannot be known (NaN, as from 0 / 0) is none. */
fun fraction(value: Float): Float = if (value.isNaN()) 0f else value.coerceIn(0f, 1f)

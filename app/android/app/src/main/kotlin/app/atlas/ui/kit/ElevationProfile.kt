package app.atlas.ui.kit

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.max
import kotlin.math.min

/**
 * A route's height along its length, as on the Ride board: a line over a soft fill, the steep climbs (grade at or
 * above [steepGrade]) in [steep], and a dot at [progress] (0..1 of the distance) for where you are.
 * [distances] are the cumulative metres at each point; without them the points are taken as evenly spaced and, the
 * spacing being unknown, no stretch is called steep. Heights that are not known (NaN) are skipped.
 */
@Composable
fun ElevationProfile(
    elevations: DoubleArray,
    modifier: Modifier = Modifier,
    distances: DoubleArray? = null,
    progress: Float? = null,
    line: Color = Atlas.colors.open,
    fill: Color = line.copy(alpha = 0.16f),
    steep: Color = Atlas.colors.eat,
    steepGrade: Double = 0.05,
    height: Dp = 72.dp,
    contentDescription: String? = null,
) {
    val profile = remember(elevations, distances, steepGrade) { ProfileShape.of(elevations, distances, steepGrade) }
    val dot = Atlas.colors.card
    Canvas(
        modifier.fillMaxWidth().height(height)
            .then(if (contentDescription != null) Modifier.semantics { this.contentDescription = contentDescription } else Modifier),
    ) {
        if (profile.size < 2) return@Canvas
        val w = size.width
        val h = size.height
        val stroke = 2.5.dp.toPx()
        val top = stroke
        val bottom = h - stroke
        fun x(i: Int) = profile.x[i] * w
        fun y(i: Int) = bottom - profile.y[i] * (bottom - top) * 0.85f
        val area = Path().apply {
            moveTo(0f, h)
            for (i in 0 until profile.size) lineTo(x(i), y(i))
            lineTo(w, h)
            close()
        }
        drawPath(area, Brush.verticalGradient(listOf(fill, fill.copy(alpha = fill.alpha * 0.3f)), top, h))
        val style = Stroke(stroke, cap = StrokeCap.Round, join = StrokeJoin.Round)
        // The line in runs of one colour, so steep stretches stand out without breaking the curve.
        var i = 0
        while (i < profile.size - 1) {
            val isSteep = profile.steep[i]
            val run = Path().apply { moveTo(x(i), y(i)) }
            var j = i
            while (j < profile.size - 1 && profile.steep[j] == isSteep) {
                run.lineTo(x(j + 1), y(j + 1))
                j++
            }
            drawPath(run, if (isSteep) steep else line, style = style)
            i = j
        }
        if (progress != null && !progress.isNaN()) {
            val f = fraction(progress)
            val px = f * w
            val k = profile.indexAt(f)
            val t = if (k + 1 < profile.size && profile.x[k + 1] > profile.x[k]) ((f - profile.x[k]) / (profile.x[k + 1] - profile.x[k])).coerceIn(0f, 1f) else 0f
            val py = y(k) + (if (k + 1 < profile.size) (y(k + 1) - y(k)) * t else 0f)
            drawCircle(Color(0x33000000), 6.dp.toPx(), Offset(px, py + 1.dp.toPx()))
            drawCircle(dot, 5.5.dp.toPx(), Offset(px, py))
            drawCircle(line, 5.5.dp.toPx(), Offset(px, py), style = Stroke(2.dp.toPx()))
        }
    }
}

/** A profile reduced to at most [MAX_POINTS] points, normalised to 0..1 on both axes, with each segment's steepness. */
@Immutable
internal class ProfileShape(val x: FloatArray, val y: FloatArray, val steep: BooleanArray) {
    val size: Int get() = x.size

    /** The index of the last point at or before [fraction] of the length. */
    fun indexAt(fraction: Float): Int {
        var lo = 0
        var hi = size - 1
        while (lo < hi) {
            val mid = (lo + hi + 1) / 2
            if (x[mid] <= fraction) lo = mid else hi = mid - 1
        }
        return lo
    }

    companion object {
        const val MAX_POINTS = 240

        private val EMPTY = ProfileShape(FloatArray(0), FloatArray(0), BooleanArray(0))

        fun of(elevations: DoubleArray, distances: DoubleArray?, steepGrade: Double): ProfileShape {
            val n = elevations.size
            val first = elevations.firstOrNull { !it.isNaN() } ?: return EMPTY
            if (n < 2) return EMPTY
            val measured = distances?.takeIf { it.size == n && it.all(Double::isFinite) }
            val d = measured ?: DoubleArray(n) { it.toDouble() }
            val total = (d[n - 1] - d[0]).takeIf { it > 0 } ?: 1.0
            // Bucket by distance and keep each bucket's mean height.
            val m = min(n, MAX_POINTS)
            val xs = FloatArray(m)
            val hs = DoubleArray(m)
            val counts = IntArray(m)
            for (i in 0 until n) {
                if (elevations[i].isNaN()) continue
                val b = (((d[i] - d[0]) / total) * (m - 1)).toInt().coerceIn(0, m - 1)
                hs[b] += elevations[i]
                counts[b]++
            }
            var lo = Double.MAX_VALUE
            var hi = -Double.MAX_VALUE
            var last = first
            for (b in 0 until m) {
                hs[b] = if (counts[b] > 0) hs[b] / counts[b] else last
                last = hs[b]
                xs[b] = b / (m - 1f)
                lo = min(lo, hs[b])
                hi = max(hi, hs[b])
            }
            // Flat routes keep a little headroom rather than exaggerating a metre into a mountain.
            val span = max(hi - lo, 20.0)
            val ys = FloatArray(m) { ((hs[it] - lo) / span).toFloat() }
            val bucketMetres = total / (m - 1)
            val steep = BooleanArray(m) { b -> measured != null && b < m - 1 && (hs[b + 1] - hs[b]) / bucketMetres >= steepGrade }
            return ProfileShape(xs, ys, steep)
        }
    }
}

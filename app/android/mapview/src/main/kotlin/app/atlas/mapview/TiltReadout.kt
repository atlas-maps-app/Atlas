package app.atlas.mapview

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.os.Build
import android.view.HapticFeedbackConstants
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * The tilt readout while it shows: where its text stands (view pixels: [x] by [align], [y] its middle), and how
 * opaque it is.
 */
internal class TiltReadoutShown(val x: Float, val y: Float, val align: Paint.Align, val alpha: Float = 1f)

/**
 * The tilt readout: while fingers tilt the map, the pitch in whole degrees just above them (beside them when they
 * near the top), in the map's own quiet type with no card behind it: ink on a white halo by day, paper on the night's
 * dark one by night. At a stop it says so ("0° · flat", "70° · max", the max falling below zoom 8 to 30° at world
 * scale, [CameraOps.maxPitch]), so a tilt that stops never looks broken; a soft tick says it under the fingers. It
 * goes a moment after they lift. Its layout and words are here; [TiltReadoutPainter] draws it.
 */
internal object TiltReadout {
    /** How long the readout stays once the fingers lift, then how long it fades, ms. */
    const val LINGER_MS = 600L
    const val FADE_MS = 200.0

    /** Its middle above the highest finger's touch, dp: clear of the fingertip. */
    const val ABOVE_DP = 56f

    /** Its near edge from the outer finger's touch when beside them, dp. */
    const val BESIDE_DP = 36f

    /** From the view's edges, dp. */
    const val MARGIN_DP = 16f

    /** The room its longest text ("70° · max") takes, dp: where it stands does not hang on what it says. */
    const val WIDTH_DP = 96f
    const val HEIGHT_DP = 22f

    /** Where a pitch stands against its limits. */
    enum class Stop { NONE, FLAT, MAX }

    /** Whether [pitch] stands at a stop: flat (0°) or the steepest [max] its zoom allows. */
    fun stop(pitch: Double, max: Double): Stop = when {
        pitch <= STOP_EPS -> Stop.FLAT
        pitch >= max - STOP_EPS -> Stop.MAX
        else -> Stop.NONE
    }

    /** The word the readout adds to its figure at [stop], or null between the stops. */
    fun words(stop: Stop): String? = when (stop) {
        Stop.NONE -> null
        Stop.FLAT -> "flat"
        Stop.MAX -> "max"
    }

    /** Whether a tilt from [before]° to [after]° reached a stop (and did not just leave one), under a [max]°. */
    fun reached(before: Double, after: Double, max: Double): Boolean =
        before != after && stop(after, max) != Stop.NONE && stop(before, max) != stop(after, max)

    /**
     * The figure for [pitch] while [shown] shows: the nearest whole degree, held until the pitch is [HOLD]° past it, so
     * a resting finger's jitter at a half degree does not flicker it.
     */
    fun figure(shown: Int?, pitch: Double): Int = if (shown != null && abs(pitch - shown) <= HOLD) shown else pitch.roundToInt()

    /**
     * Where the readout stands for the [count] fingers in [fingers] (x and y in turn) in a view of [width] × [height] px
     * at [density], inside [insets]: centred [ABOVE_DP] over the highest, within the view; once that would leave the
     * view at the top, beside the fingers at their height, on the side with more room, and there on until the tilt
     * ends ([prefer]: the alignment it stood at, CENTER for above). Writes x and y to [out] and returns the alignment.
     */
    fun place(
        fingers: FloatArray, count: Int, width: Int, height: Int, density: Float, insets: Insets, prefer: Paint.Align, out: FloatArray,
    ): Paint.Align {
        var left = Float.MAX_VALUE
        var right = -Float.MAX_VALUE
        var top = Float.MAX_VALUE
        var sx = 0f
        var sy = 0f
        for (k in 0 until count) {
            val x = fingers[2 * k]
            val y = fingers[2 * k + 1]
            left = min(left, x); right = max(right, x); top = min(top, y)
            sx += x; sy += y
        }
        val n = max(1, count)
        val m = MARGIN_DP * density
        val halfW = WIDTH_DP / 2 * density
        val halfH = HEIGHT_DP / 2 * density
        val minY = insets.top + m + halfH
        val maxY = height - insets.bottom - m - halfH
        val above = top - ABOVE_DP * density
        if (prefer == Paint.Align.CENTER && above >= minY) {
            out[0] = (sx / n).coerceIn(insets.left + m + halfW, max(insets.left + m + halfW, width - insets.right - m - halfW))
            out[1] = above
            return Paint.Align.CENTER
        }
        // beside: to the left of the fingers the text ends there (RIGHT), to the right it begins there (LEFT)
        val gap = BESIDE_DP * density
        val roomLeft = left - gap - insets.left - m
        val roomRight = width - insets.right - m - (right + gap)
        val side = when (prefer) {
            Paint.Align.RIGHT -> if (roomLeft >= 2 * halfW || roomLeft >= roomRight) Paint.Align.RIGHT else Paint.Align.LEFT
            Paint.Align.LEFT -> if (roomRight >= 2 * halfW || roomRight >= roomLeft) Paint.Align.LEFT else Paint.Align.RIGHT
            else -> if (roomLeft >= roomRight) Paint.Align.RIGHT else Paint.Align.LEFT
        }
        out[0] = if (side == Paint.Align.RIGHT) left - gap else right + gap
        out[1] = (sy / n).coerceIn(minY, max(minY, maxY))
        return side
    }

    /** The soft tick at a stop: Android 14's segment tick, a clock tick before. */
    val STOP_FEEL = if (Build.VERSION.SDK_INT >= 34) HapticFeedbackConstants.SEGMENT_TICK else HapticFeedbackConstants.CLOCK_TICK

    /** The figure holds until the pitch is this far past it, degrees. */
    const val HOLD = 0.6

    /** How close to a stop counts as at it, degrees. */
    private const val STOP_EPS = 1e-6
}

/**
 * Draws the [TiltReadout] where it stands, by day or by night, in the boards' Geist ([face] at a weight): the figure in
 * the kit's ink, a stop's word in its softer ink, each on a halo of the map's paper, as the scale bar's text sits on
 * the map. Its paints are made once; one painter per view.
 */
internal class TiltReadoutPainter(val density: Float, private val face: (Int) -> Typeface) {
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG)
    private val halo = Paint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG).apply {
        style = Paint.Style.STROKE
        strokeJoin = Paint.Join.ROUND
    }
    private val fm = Paint.FontMetrics()
    private var shown: Int? = null

    /** The figure last drawn (whole degrees), for tests. */
    val figure: Int? get() = shown

    /** Draws [r] for a camera at [pitch]° whose zoom allows [max]°; [night] for the night's map. */
    fun draw(canvas: Canvas, r: TiltReadoutShown, pitch: Double, max: Double, night: Boolean) {
        val alpha = r.alpha.coerceIn(0f, 1f)
        if (alpha <= 0f) return
        val k = density
        val p = pitch.coerceIn(0.0, 90.0)
        val figure = TiltReadout.figure(shown, p)
        shown = figure
        val number = "$figure°"
        val word = TiltReadout.words(TiltReadout.stop(p, max))?.let { " · $it" }
        style(fill, 600, 16f * k)
        val numberWidth = fill.measureText(number)
        style(fill, 500, 13f * k)
        val wordWidth = word?.let { fill.measureText(it) } ?: 0f
        val total = numberWidth + wordWidth
        val left = when (r.align) {
            Paint.Align.LEFT -> r.x
            Paint.Align.RIGHT -> r.x - total
            Paint.Align.CENTER -> r.x - total / 2
        }
        style(fill, 600, 16f * k)
        fill.getFontMetrics(fm)
        val base = r.y - (fm.ascent + fm.descent) / 2
        run(canvas, number, left, base, 600, 16f * k, if (night) NIGHT_INK else INK, night, alpha)
        if (word != null) run(canvas, word, left + numberWidth, base, 500, 13f * k, if (night) NIGHT_INK_SOFT else INK_SOFT, night, alpha)
    }

    /** One run of text at ([x], [baseline]): its halo, then its ink. */
    private fun run(canvas: Canvas, text: String, x: Float, baseline: Float, weight: Int, size: Float, colour: Int, night: Boolean, alpha: Float) {
        style(halo, weight, size)
        halo.strokeWidth = 3.5f * density
        halo.color = Colours.withAlpha(if (night) NIGHT_HALO else HALO, (if (night) 0.8f else 0.9f) * alpha)
        canvas.drawText(text, x, baseline, halo)
        style(fill, weight, size)
        fill.color = Colours.withAlpha(colour, alpha)
        canvas.drawText(text, x, baseline, fill)
    }

    private fun style(paint: Paint, weight: Int, size: Float) {
        paint.typeface = face(weight)
        paint.textSize = size
        paint.textAlign = Paint.Align.LEFT
    }

    /** Forgets the figure shown: the next tilt's readout shows its own at once. */
    fun reset() {
        shown = null
    }

    private companion object {
        // The kit's AtlasColors: LightColors' ink and inkSoft on its card white; DarkColors' on the night's earth.
        val INK = 0xFF14161B.toInt()
        val INK_SOFT = 0xFF5D636E.toInt()
        val HALO = 0xFFFFFFFF.toInt()
        val NIGHT_INK = 0xFFF2EEE6.toInt()
        val NIGHT_INK_SOFT = 0xFFAEB2BA.toInt()
        val NIGHT_HALO = MapController.NIGHT_EARTH
    }
}

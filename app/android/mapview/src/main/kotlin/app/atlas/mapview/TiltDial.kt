package app.atlas.mapview

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.os.Build
import android.view.HapticFeedbackConstants
import androidx.compose.ui.geometry.Rect
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.roundToInt
import kotlin.math.sin

/** The tilt dial while it shows: its card's top left in view pixels, and how opaque it is. */
internal class TiltDialShown(val left: Float, val top: Float, val alpha: Float = 1f)

/**
 * The tilt dial: while fingers tilt the map (or the 3D button does), a card away from the fingers shows the camera's
 * line of sight from the side, a protractor on the horizon: the eye at its corner, 0° straight down, the arc graduated
 * every degree (longer every 5° and 10°, figured every 30°) to the zoom's limit ([CameraOps.maxPitch]) and on,
 * hatched, to the horizon; the sweep and the needle at the pitch, and the pitch in figures over it with the limit. Its
 * layout, marks and detents are here; [TiltDialPainter] draws it.
 */
internal object TiltDial {
    const val WIDTH_DP = 120f
    const val HEIGHT_DP = 152f

    /** Its distance from the view's edge and from the screen's chrome, dp. */
    const val MARGIN_DP = 16f

    /** How long the dial stays once the fingers lift, then how long it fades, ms. */
    const val LINGER_MS = 900L
    const val FADE_MS = 240.0

    /** The least time between two ticks a tilt plays, ms (a limit's click always plays). */
    const val TICK_GAP_MS = 30L

    /** A graduation's kind: every degree, every 5°, every 10°. */
    enum class Mark { DEGREE, FIVE, TEN }

    /** The graduation at the whole degree [d]. */
    fun mark(d: Int): Mark = when {
        d % 10 == 0 -> Mark.TEN
        d % 5 == 0 -> Mark.FIVE
        else -> Mark.DEGREE
    }

    /**
     * The figures on the arc: every 30° up to the limit [max] (the hatched part past it has none), as few as orient the
     * eye on so small a dial; the readout says the degree.
     */
    fun figures(max: Double): List<Int> = (0..90 step 30).filter { it <= max + 0.5 }

    /** What a tilt passes: nothing, a whole degree, a tens, or one of its limits reached. */
    enum class Detent { NONE, DEGREE, TEN, LIMIT }

    /**
     * The strongest of what a tilt from [before]° to [after]° passes with the limit at [max]°: 0° or the limit
     * reached (not left), a tens, a whole degree, or nothing.
     */
    fun detent(before: Double, after: Double, max: Double): Detent {
        if (before == after) return Detent.NONE
        fun atLimit(p: Double) = p <= LIMIT_EPS || p >= max - LIMIT_EPS
        if (atLimit(after) && !atLimit(before)) return Detent.LIMIT
        // the whole degrees passed: before < k <= after going up, after <= k < before going down
        val up = after > before
        val first = if (up) floor(before + EPS).toInt() + 1 else ceil(after - EPS).toInt()
        val last = if (up) floor(after + EPS).toInt() else ceil(before - EPS).toInt() - 1
        if (first > last) return Detent.NONE
        for (k in first..last) if (k % 10 == 0) return Detent.TEN
        return Detent.DEGREE
    }

    /**
     * The figure the dial shows for [pitch] while it shows [shown]: the nearest whole degree, held until the pitch is
     * [HOLD]° past it, so a resting finger's jitter at a half degree does not flicker it.
     */
    fun readout(shown: Int?, pitch: Double): Int =
        if (shown != null && abs(pitch - shown) <= HOLD) shown else pitch.roundToInt()

    /**
     * Where the dial stands in a view of [width] × [height] px at [density], inside [insets]: on the side away from the
     * fingers at [fingersX] (a right hand's are right of the middle), from the middle of the free height outwards to
     * the first place clear of the chrome ([keepOut], dp boxes from the view's top left, as MapOverlays.labelKeepOut);
     * the other side when this one has none. Writes the card's left and top (px) to [out].
     */
    fun place(width: Int, height: Int, density: Float, insets: Insets, fingersX: Float, keepOut: List<Rect>, out: FloatArray) {
        val w = WIDTH_DP * density
        val h = HEIGHT_DP * density
        val m = MARGIN_DP * density
        val left = insets.left + m
        val right = width - insets.right - m - w
        val sides = if (fingersX >= width / 2f) floatArrayOf(left, right) else floatArrayOf(right, left)
        val top = insets.top + m
        val bottom = height - insets.bottom - m - h
        val middle = (top + bottom) / 2
        val step = STEP_DP * density
        for (x in sides) {
            var k = 0
            while (true) {
                // the middle, then a step below, a step above, two below, ...
                val off = ((k + 1) / 2) * step * (if (k % 2 == 1) 1 else -1)
                val y = middle + off
                if (abs(off) > (bottom - top) / 2 + step) break
                k++
                if (y < top - 0.5f || y > bottom + 0.5f) continue
                if (keepOut.none { it.overlaps(x, y, w, h, density, m / 2) }) {
                    out[0] = x
                    out[1] = y
                    return
                }
            }
        }
        out[0] = sides[0]
        out[1] = if (bottom >= top) middle else (height - h) / 2
    }

    /** Whether this dp box, [pad] px larger all round, meets the px box at ([x], [y]) of [w] × [h]. */
    private fun Rect.overlaps(x: Float, y: Float, w: Float, h: Float, density: Float, pad: Float): Boolean =
        left * density - pad < x + w && right * density + pad > x && top * density - pad < y + h && bottom * density + pad > y

    /** The haptic feedback at each degree, at each tens and at a limit: Android 14's segment ticks (a clock tick before), and a click. */
    val DEGREE_FEEL = if (Build.VERSION.SDK_INT >= 34) HapticFeedbackConstants.SEGMENT_FREQUENT_TICK else HapticFeedbackConstants.CLOCK_TICK
    val TEN_FEEL = if (Build.VERSION.SDK_INT >= 34) HapticFeedbackConstants.SEGMENT_TICK else HapticFeedbackConstants.CLOCK_TICK
    const val LIMIT_FEEL = HapticFeedbackConstants.VIRTUAL_KEY

    /** The readout holds its figure until the pitch is this far past it, degrees. */
    const val HOLD = 0.6

    /** How close to a limit counts as at it, degrees. */
    private const val LIMIT_EPS = 1e-6
    private const val EPS = 1e-9

    /** The steps the dial's place is looked for in, dp. */
    private const val STEP_DP = 8f
}

/**
 * Draws the [TiltDial] at its place, by day or by night: a white card (the night's ink-dark one), its ink and the go
 * blue the app's own (the kit's AtlasColors), the figures in the boards' Geist ([face] at a weight). Its paints and
 * paths are made once; one painter per view.
 */
internal class TiltDialPainter(val density: Float, private val face: (Int) -> Typeface) {
    private val card = Paint(Paint.ANTI_ALIAS_FLAG)
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG)
    private val rect = RectF()
    private val oval = RectF()
    private val path = Path()
    private var shown: Int? = null

    /** The figure last drawn (whole degrees), for tests. */
    val figure: Int? get() = shown

    /** Draws [d] for a camera at [pitch]° whose zoom allows [max]°; [night] for the night's map. */
    fun draw(canvas: Canvas, d: TiltDialShown, pitch: Double, max: Double, night: Boolean) {
        val k = density
        val ink = if (night) NIGHT_INK else INK
        val soft = if (night) NIGHT_INK_SOFT else INK_SOFT
        val go = if (night) NIGHT_GO else GO
        val surface = if (night) NIGHT_CARD else CARD
        val l = d.left
        val t = d.top
        val w = TiltDial.WIDTH_DP * k
        val h = TiltDial.HEIGHT_DP * k
        val alpha = d.alpha.coerceIn(0f, 1f)
        if (alpha <= 0f) return
        val layered = alpha < 1f
        if (layered) canvas.saveLayerAlpha(l - 28 * k, t - 28 * k, l + w + 28 * k, t + h + 34 * k, (alpha * 255).roundToInt())
        // The card, lifted off the map by a soft shadow, edged by a hairline.
        rect.set(l, t, l + w, t + h)
        card.color = surface
        card.setShadowLayer(16f * k, 0f, 5f * k, if (night) 0x99000000.toInt() else 0x40261E12)
        canvas.drawRoundRect(rect, 18f * k, 18f * k, card)
        card.clearShadowLayer()
        line.strokeWidth = 1f * k
        line.color = Colours.withAlpha(ink, if (night) 0.12f else 0.08f)
        rect.inset(0.5f * k, 0.5f * k)
        canvas.drawRoundRect(rect, 17.5f * k, 17.5f * k, line)

        val p = pitch.coerceIn(0.0, 90.0)
        val limit = max.coerceIn(0.0, 90.0)
        val figure = TiltDial.readout(shown, p)
        shown = figure
        // The figure, and what it is with its limit.
        val pad = 12f * k
        text.typeface = face(800)
        text.textSize = 26f * k
        text.letterSpacing = -0.02f
        text.textAlign = Paint.Align.LEFT
        text.color = ink
        canvas.drawText("$figure°", l + pad, t + pad + 22f * k, text)
        text.typeface = face(700)
        text.textSize = 9.5f * k
        text.letterSpacing = 0.12f
        text.textAlign = Paint.Align.RIGHT
        text.color = soft
        canvas.drawText("TILT", l + w - pad, t + pad + 9f * k, text)
        text.typeface = face(600)
        text.textSize = 10f * k
        text.letterSpacing = 0f
        canvas.drawText("max ${limit.roundToInt()}°", l + w - pad, t + pad + 22f * k, text)

        // The protractor: the eye on the horizon at its corner, 0° straight down, 90° along the horizon.
        val px = l + 15f * k
        val py = t + 46f * k
        val r = w - 12f * k - 15f * k
        oval.set(px - r, py - r, px + r, py + r)
        // Past the limit, to the horizon: hatched, out of reach.
        if (limit < 90.0) {
            fill.color = Colours.withAlpha(ink, if (night) 0.07f else 0.045f)
            canvas.drawArc(oval, 0f, (90.0 - limit).toFloat(), true, fill)
            canvas.save()
            path.rewind()
            path.moveTo(px, py)
            path.arcTo(oval, 0f, (90.0 - limit).toFloat())
            path.close()
            canvas.clipPath(path)
            line.strokeWidth = 1f * k
            line.color = Colours.withAlpha(ink, if (night) 0.16f else 0.12f)
            val gap = 5f * k
            var s = -r
            while (s < r * 2) {
                canvas.drawLine(px + s, py + r, px + s + r, py, line)
                s += gap
            }
            canvas.restore()
        }
        // The tilt so far: a sweep from straight down to the needle.
        if (p > 0.0) {
            fill.color = Colours.withAlpha(go, if (night) 0.24f else 0.13f)
            canvas.drawArc(oval, (90.0 - p).toFloat(), p.toFloat(), true, fill)
        }
        // The arc, the reachable part firmer.
        line.strokeWidth = 1f * k
        line.color = Colours.withAlpha(ink, 0.42f)
        canvas.drawArc(oval, (90.0 - limit).toFloat(), limit.toFloat(), false, line)
        line.color = Colours.withAlpha(ink, 0.16f)
        if (limit < 90.0) canvas.drawArc(oval, 0f, (90.0 - limit).toFloat(), false, line)
        // The horizon, from the eye.
        line.color = Colours.withAlpha(ink, 0.22f)
        canvas.drawLine(px + 6f * k, py, px + r + 3f * k, py, line)
        // Graduations every degree, inward from the arc; past the limit only the fives and tens, faint.
        for (deg in 0..90) {
            val mark = TiltDial.mark(deg)
            val beyond = deg > limit + 1e-6
            if (beyond && mark == TiltDial.Mark.DEGREE) continue
            val len: Float
            val alphaOf: Float
            when (mark) {
                TiltDial.Mark.TEN -> { len = 8.5f; alphaOf = 0.85f; line.strokeWidth = 1.4f * k }
                TiltDial.Mark.FIVE -> { len = 6f; alphaOf = 0.6f; line.strokeWidth = 1.1f * k }
                TiltDial.Mark.DEGREE -> { len = 3.5f; alphaOf = 0.36f; line.strokeWidth = 0.9f * k }
            }
            line.color = Colours.withAlpha(ink, if (beyond) 0.2f else alphaOf)
            val a = Math.toRadians(deg.toDouble())
            val sx = sin(a).toFloat()
            val sy = cos(a).toFloat()
            canvas.drawLine(px + sx * r, py + sy * r, px + sx * (r - len * k), py + sy * (r - len * k), line)
        }
        // Figures every 30° to the limit, inside the graduations.
        text.typeface = face(600)
        text.textSize = 9.5f * k
        text.letterSpacing = 0f
        text.textAlign = Paint.Align.CENTER
        text.color = soft
        val fr = r - 20f * k
        for (f in TiltDial.figures(limit)) {
            val a = Math.toRadians(f.toDouble())
            canvas.drawText("$f", px + sin(a).toFloat() * fr, py + cos(a).toFloat() * fr + 3.2f * k, text)
        }
        // The limits: 0°, straight down, and the zoom's steepest; blue while the pitch stands at one.
        for ((at, here) in listOf(0.0 to (p <= 0.05), limit to (p >= limit - 0.05))) {
            val a = Math.toRadians(at)
            val sx = sin(a).toFloat()
            val sy = cos(a).toFloat()
            line.strokeWidth = 2f * k
            line.color = if (here) go else ink
            canvas.drawLine(px + sx * (r - 12f * k), py + sy * (r - 12f * k), px + sx * (r + 3f * k), py + sy * (r + 3f * k), line)
        }
        // The needle, from the eye to the arc, and its tip on the arc.
        val a = Math.toRadians(p)
        val nx = sin(a).toFloat()
        val ny = cos(a).toFloat()
        line.strokeWidth = 2f * k
        line.color = go
        canvas.drawLine(px + nx * 6f * k, py + ny * 6f * k, px + nx * (r - 1f * k), py + ny * (r - 1f * k), line)
        fill.color = surface
        canvas.drawCircle(px + nx * r, py + ny * r, 5f * k, fill)
        fill.color = go
        canvas.drawCircle(px + nx * r, py + ny * r, 3.5f * k, fill)
        // The eye.
        fill.color = ink
        canvas.drawCircle(px, py, 4.5f * k, fill)
        fill.color = surface
        canvas.drawCircle(px, py, 1.8f * k, fill)
        if (layered) canvas.restore()
    }

    /** Forgets the figure shown: the next tilt's dial shows its own at once. */
    fun reset() {
        shown = null
    }

    private companion object {
        // The kit's AtlasColors: LightColors' card, ink, inkSoft and go; DarkColors' for the night's map.
        val CARD = 0xFFFFFFFF.toInt()
        val INK = 0xFF14161B.toInt()
        val INK_SOFT = 0xFF5D636E.toInt()
        val GO = 0xFF2E5BFF.toInt()
        val NIGHT_CARD = 0xFF1B1D22.toInt()
        val NIGHT_INK = 0xFFF2EEE6.toInt()
        val NIGHT_INK_SOFT = 0xFFAEB2BA.toInt()
        val NIGHT_GO = 0xFF5A7DFF.toInt()
    }
}

package app.atlas.mapview

import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * The map's labels ([MapLabel]) as the Main board sets them over the engine's frame: Instrument Serif italic, a water's
 * name in water blue on a soft white glow, a quarter's in ink on white halos. CSS `text-shadow` is the glyphs blurred by
 * a Gaussian of σ = blur / 2 under the text; here a blur mask on the same glyphs. Each label stands at the first of its
 * points where it fits on screen clear of the chrome, the markers and the labels already placed: centred on the point,
 * else above it, else beside it (a quarter's node is only where its name may stand), as [LabelPlacement] places it.
 *
 * The text stands on whole pixels (its x and its baseline rounded), so a label drawn here and its sprite
 * ([LabelSprites], which the vector engine draws, plan-vector D13) are the same pixels: both are drawn by [drawAt].
 */
internal class LabelPainter(private val density: Float) {
    /** The labels' face: the boards' Instrument Serif italic, from the app; a serif italic until it is given. */
    var typeface: Typeface = Typeface.create(Typeface.SERIF, Typeface.ITALIC)

    private val text = Paint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG).apply { textAlign = Paint.Align.CENTER }
    private val halo = Paint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG).apply { textAlign = Paint.Align.CENTER }
    private val waterGlow = blur(10f)
    private val areaHaloWide = blur(6f)
    private val areaHaloNarrow = blur(2f)
    private val fm = Paint.FontMetrics()
    private val pt = FloatArray(2)
    private val at = FloatArray(2)
    private val placement = LabelPlacement()
    private var lastLabels: List<MapLabel>? = null
    private var sorted: List<MapLabel> = emptyList()

    /** The pad about a label's text that its box keeps clear, px. */
    val pad: Float get() = 4f * density

    /** Labels placed in the last [draw], for tests and the debug panel. */
    var placedCount = 0
        private set

    private fun blur(cssBlur: Float): BlurMaskFilter {
        // Skia's blur radius r gives σ = 0.57735 r + 0.5 px
        return BlurMaskFilter(max(0.01f, (sigma(cssBlur) - 0.5f) / 0.57735f), BlurMaskFilter.Blur.NORMAL)
    }

    /** The Gaussian's σ in px of a CSS `text-shadow` blur of [cssBlur] board px. */
    private fun sigma(cssBlur: Float): Float = cssBlur * density / 2f

    /** How far a label's halo can reach beyond its glyphs, whole px: three σ of its widest blur, and a few px more. */
    fun haloReach(kind: LabelKind): Int = ceil(3f * sigma(if (kind == LabelKind.WATER) 10f else 6f)).toInt() + 4

    /**
     * Draws [labels] over the view [g]. [keepOut] holds the chrome's boxes (left, top, right, bottom in pixels, [keepOutCount]
     * of them); [markerBoxes] the placed markers' ([markerCount]).
     */
    fun draw(
        canvas: Canvas, g: GroundView, labels: List<MapLabel>,
        keepOut: FloatArray, keepOutCount: Int, markerBoxes: FloatArray, markerCount: Int,
    ) {
        placement.reset()
        placedCount = 0
        if (labels.isEmpty()) return
        if (labels !== lastLabels) {
            sorted = labels.sortedByDescending { it.priority }
            lastLabels = labels
        }
        val pad = pad
        for (label in sorted) {
            styleFor(label.kind)
            val half = text.measureText(label.text) / 2 + pad
            text.getFontMetrics(fm)
            val textH = fm.descent - fm.ascent
            var placed = false
            for (p in label.points) {
                if (!g.project(p, pt)) continue
                if (placement.tryPoint(half, pad, textH, pt[0], pt[1], g.width, g.height, keepOut, keepOutCount, markerBoxes, markerCount, at)) {
                    placed = true
                    break
                }
            }
            if (placed) {
                val baseline = at[1] - (fm.ascent + fm.descent) / 2
                drawAt(canvas, label.kind, label.text, at[0].roundToInt(), baseline.roundToInt())
                placedCount++
            }
        }
    }

    /**
     * A label's measures as placement reads them, into [out], px: half its text's width plus the pad, the pad, its
     * text's height (descent − ascent), and (ascent + descent) / 2, by which its baseline lies below its box's centre,
     * negated.
     */
    fun measures(kind: LabelKind, label: String, out: FloatArray) {
        styleFor(kind)
        text.getFontMetrics(fm)
        out[0] = text.measureText(label) / 2 + pad
        out[1] = pad
        out[2] = fm.descent - fm.ascent
        out[3] = (fm.ascent + fm.descent) / 2
    }

    /** The font's top and bottom for [kind] (above and below the baseline, px), for a sprite's height. */
    fun extent(kind: LabelKind, out: FloatArray) {
        styleFor(kind)
        text.getFontMetrics(fm)
        out[0] = fm.top
        out[1] = fm.bottom
    }

    /** A label of [kind] reading [label], its text's centre at [x] and its baseline at [baseline]: its halos, then its text. */
    fun drawAt(canvas: Canvas, kind: LabelKind, label: String, x: Int, baseline: Int) {
        styleFor(kind)
        val fx = x.toFloat()
        val fy = baseline.toFloat()
        when (kind) {
            LabelKind.WATER -> {
                halo.color = WATER_GLOW
                halo.maskFilter = waterGlow
                canvas.drawText(label, fx, fy, halo)
            }
            LabelKind.AREA -> {
                halo.color = AREA_HALO
                halo.maskFilter = areaHaloNarrow
                canvas.drawText(label, fx, fy, halo)
                halo.maskFilter = areaHaloWide
                canvas.drawText(label, fx, fy, halo)
            }
        }
        canvas.drawText(label, fx, fy, text)
    }

    /** The width of [label] in [kind]'s face, px. */
    fun textWidth(kind: LabelKind, label: String): Float {
        styleFor(kind)
        return text.measureText(label)
    }

    private fun styleFor(kind: LabelKind) {
        val size = (if (kind == LabelKind.WATER) WATER_SIZE else AREA_SIZE) * density
        text.typeface = typeface
        text.textSize = size
        halo.typeface = typeface
        halo.textSize = size
        text.color = if (kind == LabelKind.WATER) WATER_INK else AREA_INK
    }

    private companion object {
        /** The Main board's labels: the IJ in 21 px #2F5A84 on `0 0 10px rgba(255,255,255,.85)`, the Jordaan in 17 px #3A3F4B on `0 0 6px #fff, 0 0 2px #fff`. */
        const val WATER_SIZE = 21f
        const val AREA_SIZE = 17f
        const val WATER_INK = 0xFF2F5A84.toInt()
        const val AREA_INK = 0xFF3A3F4B.toInt()
        const val WATER_GLOW = 0xD9FFFFFF.toInt()
        const val AREA_HALO = 0xFFFFFFFF.toInt()
    }
}

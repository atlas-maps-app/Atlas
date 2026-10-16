package app.atlas.mapview

import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import kotlin.math.max

/**
 * The map's labels ([MapLabel]) as the Main board sets them over the engine's frame: Instrument Serif italic, a water's
 * name in water blue on a soft white glow, a quarter's in ink on white halos. CSS `text-shadow` is the glyphs blurred by
 * a Gaussian of σ = blur / 2 under the text; here a blur mask on the same glyphs. Each label stands at the first of its
 * points where it fits on screen clear of the chrome, the markers and the labels already placed: centred on the point,
 * else above it, else beside it (a quarter's node is only where its name may stand).
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
    private var boxes = FloatArray(4 * 8)
    private var boxCount = 0
    private var lastLabels: List<MapLabel>? = null
    private var sorted: List<MapLabel> = emptyList()

    /** Labels placed in the last [draw], for tests and the debug panel. */
    var placedCount = 0
        private set

    private fun blur(cssBlur: Float): BlurMaskFilter {
        // Skia's blur radius r gives σ = 0.57735 r + 0.5 px
        val sigma = cssBlur * density / 2f
        return BlurMaskFilter(max(0.01f, (sigma - 0.5f) / 0.57735f), BlurMaskFilter.Blur.NORMAL)
    }

    /**
     * Draws [labels] over the view [g]. [keepOut] holds the chrome's boxes (left, top, right, bottom in pixels, [keepOutCount]
     * of them); [markerBoxes] the placed markers' ([markerCount]).
     */
    fun draw(
        canvas: Canvas, g: GroundView, labels: List<MapLabel>,
        keepOut: FloatArray, keepOutCount: Int, markerBoxes: FloatArray, markerCount: Int,
    ) {
        boxCount = 0
        placedCount = 0
        if (labels.isEmpty()) return
        if (labels !== lastLabels) {
            sorted = labels.sortedByDescending { it.priority }
            lastLabels = labels
        }
        val pad = 4f * density
        for (label in sorted) {
            styleFor(label.kind)
            val half = text.measureText(label.text) / 2 + pad
            text.getFontMetrics(fm)
            val textH = fm.descent - fm.ascent
            val halfH = textH / 2 + pad
            var placedX = Float.NaN
            var placedY = Float.NaN
            for (p in label.points) {
                if (!g.project(p, pt)) continue
                // centred on the point, above it, then beside it (its near edge on the point), level or above
                for (k in SPOTS.indices step 2) {
                    val cx = pt[0] + SPOTS[k] * (half - pad)
                    val cy = pt[1] + SPOTS[k + 1] * textH
                    val l = cx - half
                    val r = cx + half
                    val t = cy - halfH
                    val b = cy + halfH
                    if (l < 0f || t < 0f || r > g.width || b > g.height) continue
                    if (overlaps(keepOut, keepOutCount, l, t, r, b) || overlaps(markerBoxes, markerCount, l, t, r, b) || overlaps(boxes, boxCount, l, t, r, b)) continue
                    placedX = cx
                    placedY = cy
                    add(l, t, r, b)
                    break
                }
                if (!placedX.isNaN()) break
            }
            if (!placedX.isNaN()) {
                val baseline = placedY - (fm.ascent + fm.descent) / 2
                when (label.kind) {
                    LabelKind.WATER -> {
                        halo.color = WATER_GLOW
                        halo.maskFilter = waterGlow
                        canvas.drawText(label.text, placedX, baseline, halo)
                    }
                    LabelKind.AREA -> {
                        halo.color = AREA_HALO
                        halo.maskFilter = areaHaloNarrow
                        canvas.drawText(label.text, placedX, baseline, halo)
                        halo.maskFilter = areaHaloWide
                        canvas.drawText(label.text, placedX, baseline, halo)
                    }
                }
                canvas.drawText(label.text, placedX, baseline, text)
                placedCount++
            }
        }
    }

    private fun styleFor(kind: LabelKind) {
        val size = (if (kind == LabelKind.WATER) WATER_SIZE else AREA_SIZE) * density
        text.typeface = typeface
        text.textSize = size
        halo.typeface = typeface
        halo.textSize = size
        text.color = if (kind == LabelKind.WATER) WATER_INK else AREA_INK
    }

    private fun add(l: Float, t: Float, r: Float, b: Float) {
        if (4 * (boxCount + 1) > boxes.size) boxes = boxes.copyOf(boxes.size * 2)
        boxes[4 * boxCount] = l; boxes[4 * boxCount + 1] = t; boxes[4 * boxCount + 2] = r; boxes[4 * boxCount + 3] = b
        boxCount++
    }

    private fun overlaps(list: FloatArray, n: Int, l: Float, t: Float, r: Float, b: Float): Boolean {
        for (i in 0 until n) {
            if (l < list[4 * i + 2] && r > list[4 * i] && t < list[4 * i + 3] && b > list[4 * i + 1]) return true
        }
        return false
    }

    private companion object {
        /** Where a label may stand about its point: (x in half-widths, y in text heights), in the order tried. */
        val SPOTS = floatArrayOf(0f, 0f, 0f, -0.5f, -1f, 0f, 1f, 0f, -1f, -0.5f, 1f, -0.5f)

        /** The Main board's labels: the IJ in 21 px #2F5A84 on `0 0 10px rgba(255,255,255,.85)`, the Jordaan in 17 px #3A3F4B on `0 0 6px #fff, 0 0 2px #fff`. */
        const val WATER_SIZE = 21f
        const val AREA_SIZE = 17f
        const val WATER_INK = 0xFF2F5A84.toInt()
        const val AREA_INK = 0xFF3A3F4B.toInt()
        const val WATER_GLOW = 0xD9FFFFFF.toInt()
        const val AREA_HALO = 0xFFFFFFFF.toInt()
    }
}

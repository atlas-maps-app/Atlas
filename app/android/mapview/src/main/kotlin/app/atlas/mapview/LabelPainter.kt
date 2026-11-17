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
internal class LabelPainter(val density: Float) {
    /** The labels' face: the boards' Instrument Serif italic, from the app; a serif italic until it is given. */
    var typeface: Typeface = Typeface.create(Typeface.SERIF, Typeface.ITALIC)

    /** The globe's names' face: the boards' Geist, from the app (drawn at its weight 700); a bold sans until it is given. */
    var globeTypeface: Typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)

    private val text = Paint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG).apply { textAlign = Paint.Align.CENTER }
    private val halo = Paint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG).apply { textAlign = Paint.Align.CENTER }
    private val waterGlow = blur(10f)
    private val areaHaloWide = blur(6f)
    private val areaHaloNarrow = blur(2f)
    private val globeGlow = blur(4f)
    private val fm = Paint.FontMetrics()
    private val pt = FloatArray(2)
    private val at = FloatArray(2)
    private val placement = LabelPlacement()
    private var lastLabels: List<MapLabel>? = null
    private var sorted: List<MapLabel> = emptyList()

    /** The pad about a label's text that its box keeps clear, px. */
    val pad: Float get() = 4f * density

    /** The smallest label's text size, dp (a quarter's). */
    val smallestSize: Float get() = AREA_SIZE

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
    fun haloReach(kind: LabelKind): Int = ceil(
        3f * sigma(
            when (kind) {
                LabelKind.WATER -> 10f
                LabelKind.GLOBE -> 4f
                else -> 6f
            },
        ),
    ).toInt() + 4

    /**
     * Whether the device has a glyph for every character of [label] that is drawn by itself (all but spaces, the
     * marks that join the letter before them, and the format characters) in [kind]'s face or the system's fonts behind
     * it: a name it has none for is not drawn (ENGINE.md step 5, "Names").
     */
    fun hasGlyphs(kind: LabelKind, label: String): Boolean {
        styleFor(kind)
        return hasGlyphs(text, label)
    }

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
            LabelKind.AREA, LabelKind.CITY, LabelKind.TOWN, LabelKind.VILLAGE -> {
                halo.color = AREA_HALO
                halo.maskFilter = areaHaloNarrow
                canvas.drawText(label, fx, fy, halo)
                halo.maskFilter = areaHaloWide
                canvas.drawText(label, fx, fy, halo)
            }
            LabelKind.GLOBE -> {
                halo.color = GLOBE_GLOW
                halo.maskFilter = globeGlow
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
        val size = sizeOf(kind) * density
        val face = if (kind == LabelKind.GLOBE) globeTypeface else typeface
        // the Globe board's Geist is one variable font file: its weight 700 (a face without the axis takes no notice)
        val weight = if (kind == LabelKind.GLOBE) GLOBE_WEIGHT else null
        text.typeface = face
        text.textSize = size
        text.fontVariationSettings = weight
        halo.typeface = face
        halo.textSize = size
        halo.fontVariationSettings = weight
        text.color = when (kind) {
            LabelKind.WATER -> WATER_INK
            LabelKind.GLOBE -> GLOBE_INK
            else -> AREA_INK
        }
    }

    companion object {
        /** Whether [paint]'s face, or the system's fonts behind it, has a glyph for every character of [label] drawn by itself. */
        fun hasGlyphs(paint: Paint, label: String): Boolean {
            var i = 0
            while (i < label.length) {
                val cp = label.codePointAt(i)
                val n = Character.charCount(cp)
                if (drawnAlone(cp) && !paint.hasGlyph(label.substring(i, i + n))) return false
                i += n
            }
            return true
        }

        /** A character with a glyph of its own: not a space, a mark on the letter before it, a format character or a control. */
        private fun drawnAlone(cp: Int): Boolean {
            if (Character.isWhitespace(cp) || Character.isSpaceChar(cp)) return false
            return when (Character.getType(cp).toByte()) {
                Character.NON_SPACING_MARK, Character.COMBINING_SPACING_MARK, Character.ENCLOSING_MARK, Character.FORMAT, Character.CONTROL -> false
                else -> true
            }
        }

        /** A kind's text size, board px (the core's `scene::name_size_dp` for the names' looks). */
        fun sizeOf(kind: LabelKind): Float = when (kind) {
            LabelKind.WATER -> WATER_SIZE
            LabelKind.AREA, LabelKind.CITY -> AREA_SIZE
            LabelKind.TOWN -> TOWN_SIZE
            LabelKind.VILLAGE -> VILLAGE_SIZE
            LabelKind.GLOBE -> GLOBE_SIZE
        }

        /** The Main board's labels: the IJ in 21 px #2F5A84 on `0 0 10px rgba(255,255,255,.85)`, the Jordaan in 17 px #3A3F4B on `0 0 6px #fff, 0 0 2px #fff`. */
        private const val WATER_SIZE = 21f
        private const val AREA_SIZE = 17f
        private const val WATER_INK = 0xFF2F5A84.toInt()
        private const val AREA_INK = 0xFF3A3F4B.toInt()
        private const val WATER_GLOW = 0xD9FFFFFF.toInt()
        private const val AREA_HALO = 0xFFFFFFFF.toInt()

        /**
         * The world's names (ENGINE.md step 5, "Names"): a town and a village in the place style at 15 and 13 px; on the
         * globe the Globe board's Geist 700 at 12 px in white, on a glow of the board's night (#05070D at 80 %, 4 px)
         * so it reads over the land's light greens as over the sea.
         */
        private const val TOWN_SIZE = 15f
        private const val VILLAGE_SIZE = 13f
        private const val GLOBE_SIZE = 12f
        private const val GLOBE_WEIGHT = "'wght' 700"
        private const val GLOBE_INK = 0xFFFFFFFF.toInt()
        private const val GLOBE_GLOW = 0xCC05070D.toInt()
    }
}

package app.atlas.mapview

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import kotlin.math.min

/**
 * Draws the markers as the Main board does (`canvas/project/Main.dc.html`), in Geist:
 * - a pin is a 30 px disc of its colour in a 2 px white ring with a 14 px white symbol, centred on its place
 *   (`box-shadow: 0 4px 10px rgba(20,22,27,0.25)`);
 * - the chosen pin is the board's reservation pin: a 44 px disc of its colour round a 36 px #14161B one with an 18 px
 *   symbol, on a 2 × 10 px stem over a 10 × 4 px shadow (`0 6px 16px rgba(20,22,27,0.3)`);
 * - a brand is a 34 px tile of its sign's colour, radius 10, in a 2 px white ring, its logo 20 px
 *   (`0 4px 12px rgba(20,22,27,0.25)`);
 * - a chip is the charging point's white 32 px pill: a 24 px disc of its colour with a 13 px symbol, its value 13 px
 *   bold (`0 4px 12px rgba(20,22,27,0.2)`);
 * - a value under a pin is the board's "4′" chip: white at 92 %, radius 10, 12 px semibold.
 */
class MarkerPainter(private val density: Float) {
    val metrics = MarkerMetrics(density)
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val border = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    private val icon = Paint(Paint.ANTI_ALIAS_FLAG)
    val text = Paint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG).apply {
        textSize = 13f * density
        fontFeatureSettings = "tnum"
    }
    private val labelText = Paint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG).apply {
        textSize = 13f * density
        color = INK
    }
    private val labelHalo = Paint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG).apply {
        textSize = 13f * density
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = 3f * density
        strokeJoin = Paint.Join.ROUND
    }
    private val rect = RectF()
    private val path = Path()
    private val metricsBuf = Paint.FontMetrics()

    /** The boards' sans, Geist, at a weight; the system's sans until the map's fonts are known. */
    var sans: (Int) -> Typeface = { w -> Typeface.create(Typeface.SANS_SERIF, w, false) }
        set(value) {
            field = value
            text.typeface = value(700)
            labelText.typeface = value(600)
            labelHalo.typeface = value(600)
        }

    init {
        sans = sans
    }

    /** A CSS `box-shadow` blur of [blur] px as Android's shadow radius (σ = blur / 2; σ = 0.57735 r + 0.5). */
    private fun shadow(blur: Float): Float = ((blur / 2f * density - 0.5f) / 0.57735f).coerceAtLeast(0.1f)

    /** The text a chip or a pin's value chip shows. */
    fun chipText(m: Marker): String = m.value ?: if (m.style == MarkerStyle.CHIP) (m.label ?: m.count?.toString() ?: "") else ""

    /** The width of [chipText] in pixels. */
    fun chipTextWidth(m: Marker): Float {
        val s = chipText(m)
        return if (s.isEmpty()) 0f else text.measureText(s)
    }

    /** [chipTextWidth] as a function, made once. */
    val chipWidth: (Marker) -> Float = ::chipTextWidth

    /** The view's width in pixels: a pin's name goes to the left of the pin where the right would run off it. */
    var viewWidth = Float.MAX_VALUE

    /** The centre of pin [m]'s disc when its place is at [y]. */
    private fun pinCentre(m: Marker, y: Float): Float {
        val r = metrics.pinDiameter(m.selected) / 2
        return if (m.selected) y - metrics.pinFoot(true) - metrics.pinStem(true) - r else y
    }

    /** Where the name [w] pixels wide of pin [m] at [x] starts: right of the disc, or left of it near the right edge. */
    private fun labelLeft(m: Marker, x: Float, w: Float): Float {
        val gap = metrics.pinDiameter(m.selected) / 2 + 6f * density
        val right = x + gap
        val left = x - gap - w
        return if (right + w + 2f * density <= viewWidth || left < 0f) right else left
    }

    /** The box of a pin's name beside it, into [out] at [o] (left, top, right, bottom). */
    fun labelBox(m: Marker, x: Float, y: Float, out: FloatArray, o: Int) {
        val cy = pinCentre(m, y)
        val w = labelText.measureText(m.label ?: "")
        val left = labelLeft(m, x, w)
        out[o] = left - 2f * density
        out[o + 1] = cy - labelText.textSize * 0.7f
        out[o + 2] = left + w + 2f * density
        out[o + 3] = cy + labelText.textSize * 0.7f
    }

    /** Draws marker [m] anchored at ([x], [y]); [cluster] > 0 draws it as a cluster of that many. */
    fun draw(canvas: Canvas, m: Marker, x: Float, y: Float, cluster: Int) {
        val colour = m.colour.toInt() or (0xFF shl 24)
        if (cluster > 0) {
            cluster(canvas, x, y, cluster, colour)
            return
        }
        when (m.style) {
            MarkerStyle.PIN -> pin(canvas, m, x, y, colour)
            MarkerStyle.DOT -> dot(canvas, m, x, y, colour)
            MarkerStyle.BRAND -> brand(canvas, m, x, y, colour)
            MarkerStyle.CLUSTER -> cluster(canvas, x, y, m.count ?: 1, colour)
            MarkerStyle.CHIP -> chip(canvas, m, x, y, colour)
        }
    }

    private fun pin(canvas: Canvas, m: Marker, x: Float, y: Float, colour: Int) {
        val mm = metrics
        val d = mm.pinDiameter(m.selected)
        val r = d / 2
        val cy = pinCentre(m, y)
        if (m.selected) {
            // the shadow under the point, the stem, then the 44 px disc of the place's colour round the dark one
            fill.color = Colours.withAlpha(INK, 0.35f)
            rect.set(x - 5f * density, y - 2f * density, x + 5f * density, y + 2f * density)
            canvas.drawOval(rect, fill)
            fill.color = INK
            canvas.drawRect(x - 1f * density, cy + r, x + 1f * density, cy + r + mm.pinStem(true), fill)
            fill.color = colour
            fill.setShadowLayer(shadow(16f), 0f, 6f * density, Colours.withAlpha(INK, 0.3f))
            canvas.drawCircle(x, cy, r, fill)
            fill.clearShadowLayer()
            m.ring?.let { ring(canvas, x, cy, r - 1.5f * density, it) }
            fill.color = INK
            canvas.drawCircle(x, cy, 18f * density, fill)
            symbol(canvas, m.symbol, x, cy, 18f * density, Color.WHITE)
        } else {
            border.setShadowLayer(shadow(10f), 0f, 4f * density, Colours.withAlpha(INK, 0.25f))
            canvas.drawCircle(x, cy, r, border)
            border.clearShadowLayer()
            fill.color = colour
            canvas.drawCircle(x, cy, r - mm.border, fill)
            m.ring?.let { ring(canvas, x, cy, r + mm.ring * 0.5f, it) }
            symbol(canvas, m.symbol, x, cy, 14f * density, onColour(colour))
        }
        // the board's check badge on the chosen pin: 20 px, 2 px white ring, at its top right
        m.badge?.let { badge(canvas, it, x + r * 0.8f, cy - r * 0.6f) }
        m.value?.let { valueChip(canvas, it, x, (if (m.selected) y + mm.pinFoot(true) else cy + r) + mm.valueChipGap) }
        if (m.label != null) {
            labelText.getFontMetrics(metricsBuf)
            val base = cy - (metricsBuf.ascent + metricsBuf.descent) / 2
            val lx = labelLeft(m, x, labelText.measureText(m.label))
            canvas.drawText(m.label, lx, base, labelHalo)
            canvas.drawText(m.label, lx, base, labelText)
        }
    }

    private fun dot(canvas: Canvas, m: Marker, x: Float, y: Float, colour: Int) {
        val d = metrics.dotDiameter(m.selected)
        val r = d / 2
        border.setShadowLayer(shadow(8f), 0f, 3f * density, Colours.withAlpha(INK, 0.25f))
        canvas.drawCircle(x, y, r, border)
        border.clearShadowLayer()
        fill.color = colour
        canvas.drawCircle(x, y, r - metrics.border, fill)
        m.ring?.let { ring(canvas, x, y, r + metrics.ring * 0.5f, it) }
        symbol(canvas, m.symbol, x, y, d * 0.5f, onColour(colour))
        m.badge?.let { badge(canvas, it, x + r * 0.75f, y - r * 0.75f) }
    }

    private fun brand(canvas: Canvas, m: Marker, x: Float, y: Float, colour: Int) {
        val s = metrics.brandSide(m.selected)
        val mark = m.logo?.let { Icons.loadedBrand(it) }
        val signColour = mark?.colour?.let { it or (0xFF shl 24) }
        val square = signColour ?: Color.WHITE
        val glyphColour = when {
            signColour != null -> mark.textColour?.let { it or (0xFF shl 24) } ?: onColour(signColour)
            else -> colour
        }
        // the 34 px tile, radius 10, its 2 px white ring inside it (border-box), centred on the shop
        rect.set(x - s / 2, y - s / 2, x + s / 2, y + s / 2)
        val corner = 10f * density
        border.setShadowLayer(shadow(12f), 0f, 4f * density, Colours.withAlpha(INK, 0.25f))
        canvas.drawRoundRect(rect, corner, corner, border)
        border.clearShadowLayer()
        rect.inset(metrics.border, metrics.border)
        fill.color = square
        canvas.drawRoundRect(rect, corner - metrics.border, corner - metrics.border, fill)
        val shape = mark?.shape
        if (shape != null) {
            icon.color = glyphColour
            shape.draw(canvas, x, y, 20f * density, icon)
        } else {
            // No glyph: the brand's initial, as a shop sign would.
            val name = mark?.name ?: m.label ?: m.logo ?: "?"
            val initial = name.firstOrNull { it.isLetterOrDigit() }?.uppercaseChar()?.toString() ?: "?"
            text.color = glyphColour
            val old = text.textSize
            text.textSize = 16f * density
            text.textAlign = Paint.Align.CENTER
            text.getFontMetrics(metricsBuf)
            canvas.drawText(initial, x, y - (metricsBuf.ascent + metricsBuf.descent) / 2, text)
            text.textAlign = Paint.Align.LEFT
            text.textSize = old
        }
        m.badge?.let { badge(canvas, it, x + s / 2 - 2f * density, y - s / 2 + 2f * density) }
    }

    private fun cluster(canvas: Canvas, x: Float, y: Float, count: Int, colour: Int) {
        val d = metrics.clusterDiameter(count)
        val r = d / 2
        border.setShadowLayer(shadow(10f), 0f, 4f * density, Colours.withAlpha(INK, 0.25f))
        canvas.drawCircle(x, y, r, border)
        border.clearShadowLayer()
        fill.color = colour
        canvas.drawCircle(x, y, r - metrics.border, fill)
        val label = countLabel(count)
        text.color = onColour(colour)
        val old = text.textSize
        text.textSize = (if (label.length <= 2) 14f else 12f) * density
        text.textAlign = Paint.Align.CENTER
        text.getFontMetrics(metricsBuf)
        canvas.drawText(label, x, y - (metricsBuf.ascent + metricsBuf.descent) / 2, text)
        text.textAlign = Paint.Align.LEFT
        text.textSize = old
    }

    private fun chip(canvas: Canvas, m: Marker, x: Float, y: Float, colour: Int) {
        val mm = metrics
        val label = chipText(m)
        val tw = if (label.isEmpty()) 0f else text.measureText(label)
        val w = mm.chipWidth(m.selected, tw)
        val h = mm.chipHeight(m.selected)
        val top = y - h / 2
        val left = x - w / 2
        val pill = if (m.selected) colour else Color.WHITE
        val ink = if (m.selected) onColour(colour) else INK
        rect.set(left, top, left + w, top + h)
        fill.color = pill
        fill.setShadowLayer(shadow(12f), 0f, 4f * density, Colours.withAlpha(INK, 0.2f))
        canvas.drawRoundRect(rect, h / 2, h / 2, fill)
        fill.clearShadowLayer()
        // the 24 px disc of its colour, 5 px in, with the white symbol
        val sd = mm.chipDisc
        val scx = left + mm.chipPad + sd / 2
        val scy = top + h / 2
        fill.color = if (m.selected) Colours.mix(colour, Color.WHITE, 0.16f) else colour
        canvas.drawCircle(scx, scy, sd / 2, fill)
        m.ring?.let { ring(canvas, scx, scy, sd / 2 - 1f * density, it, if (m.selected) Colours.withAlpha(Color.WHITE, 0.25f) else 0x22000000) }
        symbol(canvas, m.symbol, scx, scy, 13f * density, Color.WHITE)
        if (tw > 0f) {
            text.color = ink
            text.getFontMetrics(metricsBuf)
            canvas.drawText(label, scx + sd / 2 + mm.chipGap, scy - (metricsBuf.ascent + metricsBuf.descent) / 2, text)
        }
        m.badge?.let { badge(canvas, it, left + w - 3f * density, top + 3f * density) }
    }

    /** The board's chip under a pin ("4′"): white at 92 %, radius 10, 2 × 7 px padding, 12 px semibold; top centre at ([x], [top]). */
    private fun valueChip(canvas: Canvas, value: String, x: Float, top: Float) {
        val old = text.textSize
        val oldFace = text.typeface
        text.textSize = 12f * density
        text.typeface = sans(600)
        val tw = text.measureText(value)
        val h = metrics.valueChipHeight
        val w = tw + 2 * metrics.valueChipPad
        rect.set(x - w / 2, top, x + w / 2, top + h)
        fill.color = Colours.withAlpha(Color.WHITE, 0.92f)
        canvas.drawRoundRect(rect, 10f * density, 10f * density, fill)
        text.color = INK
        text.textAlign = Paint.Align.CENTER
        text.getFontMetrics(metricsBuf)
        canvas.drawText(value, x, top + h / 2 - (metricsBuf.ascent + metricsBuf.descent) / 2, text)
        text.textAlign = Paint.Align.LEFT
        text.textSize = old
        text.typeface = oldFace
    }

    /** A progress ring of radius [r] around ([x], [y]): [value] 0..1 of a full turn from the top, on a light track. */
    private fun ring(canvas: Canvas, x: Float, y: Float, r: Float, value: Float, track: Int = Colours.withAlpha(Color.WHITE, 0.85f)) {
        val v = value.coerceIn(0f, 1f)
        stroke.strokeWidth = metrics.ring
        stroke.color = track
        canvas.drawCircle(x, y, r, stroke)
        if (v <= 0f) return
        stroke.color = ringColour(v)
        rect.set(x - r, y - r, x + r, y + r)
        canvas.drawArc(rect, -90f, 360f * v, false, stroke)
    }

    /** The board's badge (the reservation's check): 20 px, a 2 px white ring, #E8622C, its 11 px symbol white. */
    private fun badge(canvas: Canvas, symbolId: String, x: Float, y: Float) {
        val r = metrics.badge / 2
        canvas.drawCircle(x, y, r, border)
        fill.color = BADGE
        canvas.drawCircle(x, y, r - metrics.border, fill)
        symbol(canvas, symbolId, x, y, 11f * density, Color.WHITE)
    }

    private fun symbol(canvas: Canvas, id: String, x: Float, y: Float, size: Float, colour: Int) {
        val shape = Icons.loadedShape(id) ?: return
        icon.color = colour
        shape.draw(canvas, x, y, size, icon)
    }

    companion object {
        /** The symbols and brands [markers] draw with, for warming them off the main thread. */
        fun symbolsOf(markers: List<Marker>, names: MutableSet<String>, logos: MutableSet<String>) {
            for (m in markers) {
                names += m.symbol
                m.badge?.let { names += it }
                m.logo?.let { logos += it }
            }
        }

        /** The dark ink of the boards. */
        const val INK = 0xFF14161B.toInt()
        const val BADGE = 0xFFE8622C.toInt()

        /** White on dark colours, ink on light ones. */
        fun onColour(c: Int): Int = if (Colours.luma(c) > 186f) INK else Color.WHITE

        /** A ring's colour by its value: green when there is plenty, amber when it is running low, red at the end. */
        fun ringColour(v: Float): Int = when {
            v >= 0.5f -> 0xFF1F9D55.toInt()
            v >= 0.2f -> 0xFFF2A516.toInt()
            else -> 0xFFE5482D.toInt()
        }

        /** "7", "42", "999", "1k", "1.2k", "12k": rounded down, so a count never shows more than there is. */
        fun countLabel(n: Int): String = when {
            n < 1000 -> n.toString()
            n < 10_000 -> {
                val tenths = n / 100
                if (tenths % 10 == 0) "${tenths / 10}k" else "${tenths / 10}.${tenths % 10}k"
            }
            else -> "${min(n / 1000, 999)}k"
        }
    }
}

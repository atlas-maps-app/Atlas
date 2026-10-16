package app.atlas.mapview

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import kotlin.math.min

/**
 * Draws the markers as the boards do: pins (a coloured disc with a white symbol on a short stem, larger when
 * selected, with an optional progress ring and corner badge, a value chip under it and its name beside it), dots,
 * brand logos in a rounded square, cluster bubbles with a count, and chips with a symbol and a value.
 */
class MarkerPainter(private val density: Float) {
    val metrics = MarkerMetrics(density)
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val border = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    private val icon = Paint(Paint.ANTI_ALIAS_FLAG)
    val text = Paint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG).apply {
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        textSize = 13f * density
    }
    private val labelText = Paint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG).apply {
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        textSize = 13f * density
        color = INK
    }
    private val labelHalo = Paint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG).apply {
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        textSize = 13f * density
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = 3f * density
        strokeJoin = Paint.Join.ROUND
    }
    private val rect = RectF()
    private val path = Path()
    private val metricsBuf = Paint.FontMetrics()
    private val shadowColour = 0x38000000

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

    /** Where the name [w] pixels wide of pin [m] at [x] starts: right of the disc, or left of it near the right edge. */
    private fun labelLeft(m: Marker, x: Float, w: Float): Float {
        val gap = metrics.pinDiameter(m.selected) / 2 + 6f * density
        val right = x + gap
        val left = x - gap - w
        return if (right + w + 2f * density <= viewWidth || left < 0f) right else left
    }

    /** The box of a pin's name beside it, into [out] at [o] (left, top, right, bottom). */
    fun labelBox(m: Marker, x: Float, y: Float, out: FloatArray, o: Int) {
        val r = metrics.pinDiameter(m.selected) / 2
        val cy = y - metrics.stem - r
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

    private fun groundShadow(canvas: Canvas, x: Float, y: Float, rx: Float) {
        fill.color = shadowColour
        rect.set(x - rx, y - rx * 0.38f, x + rx, y + rx * 0.38f)
        canvas.drawOval(rect, fill)
    }

    private fun pin(canvas: Canvas, m: Marker, x: Float, y: Float, colour: Int) {
        val mm = metrics
        val d = mm.pinDiameter(m.selected)
        val r = d / 2
        val cy = y - mm.stem - r
        groundShadow(canvas, x, y, 5f * density)
        // The stem down to the place, then a small foot.
        stroke.color = Colours.mix(colour, Color.BLACK, 0.25f)
        stroke.strokeWidth = 2.5f * density
        canvas.drawLine(x, cy + r - mm.border, x, y - 1f * density, stroke)
        fill.color = stroke.color
        canvas.drawCircle(x, y - 1f * density, 2f * density, fill)
        m.ring?.let { ring(canvas, x, cy, r + mm.ring * 0.5f + 0.5f * density, it) }
        border.setShadowLayer(3f * density, 0f, 1f * density, shadowColour)
        canvas.drawCircle(x, cy, r, border)
        border.clearShadowLayer()
        fill.color = colour
        canvas.drawCircle(x, cy, r - mm.border, fill)
        symbol(canvas, m.symbol, x, cy, d * 0.52f, onColour(colour))
        m.badge?.let { badge(canvas, it, x + r * 0.72f, cy - r * 0.72f) }
        m.value?.let { valueChip(canvas, it, x, y + mm.valueChipGap) }
        if (m.label != null && m.style == MarkerStyle.PIN) {
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
        border.setShadowLayer(2f * density, 0f, 0.5f * density, shadowColour)
        canvas.drawCircle(x, y, r, border)
        border.clearShadowLayer()
        fill.color = colour
        canvas.drawCircle(x, y, r - 1.5f * density, fill)
        m.ring?.let { ring(canvas, x, y, r + metrics.ring * 0.5f, it) }
        symbol(canvas, m.symbol, x, y, d * 0.58f, onColour(colour))
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
        val top = y - metrics.brandPointer - s
        groundShadow(canvas, x, y, 4f * density)
        // The pointer under the square.
        path.rewind()
        path.moveTo(x - 5f * density, top + s - 1f)
        path.lineTo(x + 5f * density, top + s - 1f)
        path.lineTo(x, y)
        path.close()
        fill.color = if (signColour != null) signColour else Color.WHITE
        canvas.drawPath(path, fill)
        val corner = s * 0.26f
        rect.set(x - s / 2, top, x + s / 2, top + s)
        border.setShadowLayer(3f * density, 0f, 1f * density, shadowColour)
        canvas.drawRoundRect(rect, corner, corner, border)
        border.clearShadowLayer()
        if (signColour != null) {
            val inset = 1.5f * density
            rect.inset(inset, inset)
            fill.color = square
            canvas.drawRoundRect(rect, corner - inset, corner - inset, fill)
        } else {
            stroke.color = 0xFFE3E0DA.toInt()
            stroke.strokeWidth = 1f * density
            canvas.drawRoundRect(rect, corner, corner, stroke)
        }
        val shape = mark?.shape
        if (shape != null) {
            icon.color = glyphColour
            shape.draw(canvas, x, top + s / 2, s * 0.6f, icon)
        } else {
            // No glyph: the brand's initial, as a shop sign would.
            val name = mark?.name ?: m.label ?: m.logo ?: "?"
            val initial = name.firstOrNull { it.isLetterOrDigit() }?.uppercaseChar()?.toString() ?: "?"
            text.color = glyphColour
            val old = text.textSize
            text.textSize = s * 0.55f
            text.textAlign = Paint.Align.CENTER
            text.getFontMetrics(metricsBuf)
            canvas.drawText(initial, x, top + s / 2 - (metricsBuf.ascent + metricsBuf.descent) / 2, text)
            text.textAlign = Paint.Align.LEFT
            text.textSize = old
        }
        m.badge?.let { badge(canvas, it, x + s / 2 - 2f * density, top + 2f * density) }
    }

    private fun cluster(canvas: Canvas, x: Float, y: Float, count: Int, colour: Int) {
        val d = metrics.clusterDiameter(count)
        val r = d / 2
        border.setShadowLayer(3f * density, 0f, 1f * density, shadowColour)
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
        val stem = mm.chipStem(m.selected)
        val bottom = y - stem
        val top = bottom - h
        val left = x - w / 2
        val pill = if (m.selected) colour else Color.WHITE
        val ink = if (m.selected) onColour(colour) else INK
        if (m.selected) {
            groundShadow(canvas, x, y, 4f * density)
            stroke.color = colour
            stroke.strokeWidth = 2f * density
            canvas.drawLine(x, bottom, x, y - 1f * density, stroke)
            fill.color = colour
            canvas.drawCircle(x, y - 1f * density, 2f * density, fill)
        } else {
            path.rewind()
            path.moveTo(x - 4f * density, bottom - 1f)
            path.lineTo(x + 4f * density, bottom - 1f)
            path.lineTo(x, y)
            path.close()
            fill.color = Color.WHITE
            canvas.drawPath(path, fill)
        }
        rect.set(left, top, left + w, bottom)
        fill.color = pill
        fill.setShadowLayer(3f * density, 0f, 1f * density, shadowColour)
        canvas.drawRoundRect(rect, h / 2, h / 2, fill)
        fill.clearShadowLayer()
        // The symbol in its disc at the left end, ringed when the marker has a ring.
        val sd = h - 2 * mm.chipPad
        val scx = left + mm.chipPad + sd / 2
        val scy = top + h / 2
        if (m.selected) {
            fill.color = Colours.mix(colour, Color.WHITE, 0.16f)
            canvas.drawCircle(scx, scy, sd / 2, fill)
        } else {
            fill.color = Colours.withAlpha(colour, 0.13f)
            canvas.drawCircle(scx, scy, sd / 2, fill)
        }
        m.ring?.let { ring(canvas, scx, scy, sd / 2 - 1f * density, it, if (m.selected) Colours.withAlpha(Color.WHITE, 0.25f) else 0x22000000) }
        symbol(canvas, m.symbol, scx, scy, sd * 0.62f, if (m.selected) ink else colour)
        if (tw > 0f) {
            text.color = ink
            text.getFontMetrics(metricsBuf)
            canvas.drawText(label, scx + sd / 2 + mm.chipPad, scy - (metricsBuf.ascent + metricsBuf.descent) / 2, text)
        }
        m.badge?.let { badge(canvas, it, left + w - 3f * density, top + 3f * density) }
    }

    /** The small white chip under a pin with its value ("4′"), top centre at ([x], [top]). */
    private fun valueChip(canvas: Canvas, value: String, x: Float, top: Float) {
        val tw = text.measureText(value)
        val h = metrics.valueChipHeight
        val w = tw + 2 * metrics.chipTextPad * 0.7f
        rect.set(x - w / 2, top, x + w / 2, top + h)
        fill.color = Color.WHITE
        fill.setShadowLayer(2f * density, 0f, 0.5f * density, shadowColour)
        canvas.drawRoundRect(rect, h / 2, h / 2, fill)
        fill.clearShadowLayer()
        text.color = INK
        val old = text.textSize
        text.textSize = 12f * density
        text.textAlign = Paint.Align.CENTER
        text.getFontMetrics(metricsBuf)
        canvas.drawText(value, x, top + h / 2 - (metricsBuf.ascent + metricsBuf.descent) / 2, text)
        text.textAlign = Paint.Align.LEFT
        text.textSize = old
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

    private fun badge(canvas: Canvas, symbolId: String, x: Float, y: Float) {
        val r = metrics.badge / 2
        canvas.drawCircle(x, y, r, border)
        fill.color = BADGE
        canvas.drawCircle(x, y, r - 1.5f * density, fill)
        symbol(canvas, symbolId, x, y, r * 1.25f, Color.WHITE)
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
        const val BADGE = 0xFFF07A22.toInt()

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

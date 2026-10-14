package app.atlas.mapview

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import app.atlas.engine.Batch
import app.atlas.engine.Frame
import app.atlas.engine.PlacedLabel
import app.atlas.engine.PlacedPoi

/**
 * Draws an engine [Frame] on an Android canvas: each batch with one `drawVertices` call (per-vertex colours, one reused
 * paint), the shadow layer through `saveLayerAlpha` so overlapping shadows do not darken each other, then labels with
 * halos (serif italic for places and water, sans for streets; path labels glyph by glyph) and the points of interest
 * as coloured discs with white symbols.
 */
class FrameDrawer(private val typefaces: LabelTypefaces = LabelTypefaces()) {
    private val vertexPaint = Paint().apply { color = Color.WHITE; isAntiAlias = false }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG)
    private val halo = Paint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG).apply {
        style = Paint.Style.STROKE
        strokeJoin = Paint.Join.ROUND
        strokeCap = Paint.Cap.ROUND
    }
    private val disc = Paint(Paint.ANTI_ALIAS_FLAG)
    private val symbol = Paint(Paint.ANTI_ALIAS_FLAG)
    private val metrics = Paint.FontMetrics()

    /** Vertices drawn by the last [drawGeometry]. */
    var lastVertices = 0
        private set

    /** The triangles of [frame], in painter's order. */
    fun drawGeometry(canvas: Canvas, frame: Frame) {
        var layer = 0
        var vertices = 0
        val w = frame.width.toFloat()
        val h = frame.height.toFloat()
        for (b in frame.batches) {
            val t = b as Batch.Triangles
            if (t.layer != layer) {
                if (layer != 0) canvas.restore()
                if (t.layer != 0) canvas.saveLayerAlpha(0f, 0f, w, h, (t.layerAlpha * 255f + 0.5f).toInt().coerceIn(0, 255))
                layer = t.layer
            }
            if (t.indexCount < 3) continue
            canvas.drawVertices(
                Canvas.VertexMode.TRIANGLES, t.vertexCount * 2, t.positions, 0, null, 0, t.colours, 0,
                t.indices, 0, t.indexCount, vertexPaint,
            )
            vertices += t.vertexCount
        }
        if (layer != 0) canvas.restore()
        lastVertices = vertices
    }

    /** The labels of [frame]: halos first, then the text. */
    fun drawLabels(canvas: Canvas, frame: Frame) {
        for (l in frame.labels) label(canvas, l)
    }

    /**
     * The points of interest of [frame], [sizePx] across at full scale; any whose box meets one of the [blocked] boxes
     * (left, top, right, bottom quadruples, [blockedCount] of them: markers drawn on top) is left out.
     */
    fun drawPois(
        canvas: Canvas, frame: Frame, sizePx: Float, ringPx: Float, blocked: FloatArray, blockedCount: Int, ring: Int = Color.WHITE,
    ) {
        for (p in frame.pois) {
            val r = sizePx * p.scale / 2
            if (isBlocked(p, r, blocked, blockedCount)) continue
            poi(canvas, p, r, ringPx, ring)
        }
    }

    private fun isBlocked(p: PlacedPoi, r: Float, b: FloatArray, n: Int): Boolean {
        for (i in 0 until n) {
            val k = 4 * i
            if (p.x + r > b[k] && p.x - r < b[k + 2] && p.y + r > b[k + 1] && p.y - r < b[k + 3]) return true
        }
        return false
    }

    private fun poi(canvas: Canvas, p: PlacedPoi, r: Float, ringPx: Float, ring: Int) {
        val style = PoiStyles.of(p.poi)
        val a = (p.alpha.coerceIn(0f, 1f) * 255f).toInt()
        if (a <= 0) return
        disc.color = ring
        disc.alpha = (a * 0.95f).toInt()
        canvas.drawCircle(p.x, p.y, r + ringPx, disc)
        disc.color = style.colour
        disc.alpha = a
        canvas.drawCircle(p.x, p.y, r, disc)
        val shape = Icons.loadedShape(style.symbol) ?: return
        symbol.color = Color.WHITE
        symbol.alpha = a
        shape.draw(canvas, p.x, p.y, r * 1.2f, symbol)
    }

    private fun label(canvas: Canvas, l: PlacedLabel) {
        val alpha = l.alpha.coerceIn(0f, 1f)
        if (alpha <= 0f) return
        val face = typefaces.of(l.style)
        text.typeface = face
        text.textSize = l.size
        text.letterSpacing = l.letterSpacing
        text.color = l.colour or (0xFF shl 24)
        text.alpha = (alpha * 255f).toInt()
        halo.typeface = face
        halo.textSize = l.size
        halo.letterSpacing = l.letterSpacing
        halo.color = l.halo or (0xFF shl 24)
        halo.alpha = (alpha * 0.85f * 255f).toInt()
        halo.strokeWidth = l.haloWidth * 2
        text.getFontMetrics(metrics)
        // The baseline that centres the text's x-height band on the point.
        val base = -(metrics.ascent + metrics.descent) / 2
        val glyphs = l.glyphs
        if (glyphs == null) {
            text.textAlign = Paint.Align.CENTER
            halo.textAlign = Paint.Align.CENTER
            if (l.angle != 0f) {
                canvas.save()
                canvas.rotate(l.angle, l.x, l.y)
            }
            canvas.drawText(l.text, l.x, l.y + base, halo)
            canvas.drawText(l.text, l.x, l.y + base, text)
            if (l.angle != 0f) canvas.restore()
            return
        }
        // Along a path: each character centred on its point and turned to its angle; all halos, then all fills.
        text.textAlign = Paint.Align.CENTER
        halo.textAlign = Paint.Align.CENTER
        text.letterSpacing = 0f
        halo.letterSpacing = 0f
        val s = l.text
        for (pass in 0..1) {
            val paint = if (pass == 0) halo else text
            for (k in s.indices) {
                val c = s[k]
                if (c == ' ' || 3 * k + 2 >= glyphs.size) continue
                canvas.save()
                canvas.translate(glyphs[3 * k], glyphs[3 * k + 1])
                canvas.rotate(glyphs[3 * k + 2])
                canvas.drawText(s, k, k + 1, 0f, base, paint)
                canvas.restore()
            }
        }
    }
}

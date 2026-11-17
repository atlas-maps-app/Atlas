package app.atlas.mapview.svg

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import androidx.core.graphics.PathParser
import app.atlas.render.Raster

/**
 * The CPU raster of the renders' SVG on Android's Canvas, as today's map drew its frames on the phone until the core
 * drew every place (ENGINE.md step 3): kept in tests alone, as the likeness oracle (`:likeness`'s references and the
 * phone look tool's canvas side) and for the raster's own tests. Its sources lie outside mapview's main source set
 * (`src/raster`), compiled into the tests that use them, so no APK carries them.
 */
object SvgRaster {
    /** What a raster's CPU filters cost: [filterMillis] running them, [sourceMillis] drawing what they read. */
    class Stats {
        var filtered = 0
        var filterPixels = 0L
        var filterMillis = 0.0
        var sourceMillis = 0.0

        internal fun add(s: SvgCanvas.Stats) {
            filtered += s.filtered
            filterPixels += s.filterPixels
            filterMillis += s.filterNanos / 1e6
            sourceMillis += s.sourceNanos / 1e6
        }
    }

    /**
     * Rasterises a render's [layers] (ground first) into one bitmap at [scale] device pixels per CSS pixel, with the
     * relief [raster] (regions.py's `t` image) between the ground and the objects, on [background].
     *
     * The shapes are drawn in one pass; each large CPU filter runs in up to [bands] horizontal bands at once on the
     * filter workers, and its noise comes from [noise] when it was made before. Neither changes a pixel: the result
     * is byte for byte the one-band raster without a cache ([SvgCanvas]). [stats], when given, adds up the time the
     * filters took.
     *
     * [cancelled] is asked before each element, filter band, primitive and noise tile: once it says so, the raster
     * throws [java.util.concurrent.CancellationException] when the bands under way have stopped, and its bitmap is
     * let go at once.
     */
    fun rasterise(
        layers: List<SvgDocument>, raster: Raster?, fonts: SvgFonts, scale: Float, filters: SvgCanvas.FilterMode = SvgCanvas.FilterMode.CPU,
        background: Int = Color.TRANSPARENT,
        bands: Int = SvgCanvas.DEFAULT_BANDS,
        noise: TurbulenceCache? = TurbulenceCache.shared,
        stats: Stats? = null,
        cancelled: () -> Boolean = { false },
    ): Bitmap {
        val first = layers.first()
        val w = pixels(first.width, scale)
        val h = pixels(first.height, scale)
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        var done = false
        try {
            if (background != Color.TRANSPARENT) c.drawColor(background)
            for ((i, doc) in layers.withIndex()) {
                val sc = SvgCanvas(doc, fonts, filters, bands, noise, cancelled)
                sc.draw(c, w.toFloat(), h.toFloat())
                stats?.add(sc.stats)
                if (i == 0 && raster != null) drawRaster(c, raster, first.width, first.height, scale)
            }
            done = true
        } finally {
            // the canvas lets go of the pixels (closing any layer a cancel left open), so a recycle frees them at once
            c.setBitmap(null)
            // no frame: its pixels go now, not at some later collection
            if (!done) bmp.recycle()
        }
        return bmp
    }

    /** The device pixels [rasterise] gives a frame side of [css] CSS pixels at [scale]. */
    fun pixels(css: Float, scale: Float): Int = Math.round(css * scale).coerceAtLeast(1)

    /**
     * The filter scale (filter pixels per CSS pixel) of a frame of [width] × [height] CSS pixels rasterised at
     * [scale]: the view-box fit [SvgCanvas.draw] makes, as the float the noise is keyed by. The screen-wide filters'
     * noise is warmed up for it ([TurbulenceCache.warmUp]).
     */
    fun filterScale(width: Float, height: Float, scale: Float): Float =
        minOf(pixels(width, scale).toFloat() / width, pixels(height, scale).toFloat() / height)

    /** The relief image over the whole frame, inside its outline when it has one. */
    private fun drawRaster(c: Canvas, r: Raster, w: Float, h: Float, scale: Float) {
        val img = BitmapFactory.decodeByteArray(r.data, 0, r.data.size) ?: return
        c.save()
        c.scale(scale, scale)
        r.clip?.let { d -> runCatching { PathParser.createPathFromPathData(d) }.getOrNull()?.let { c.clipPath(it) } }
        c.drawBitmap(img, null, RectF(0f, 0f, w, h), Paint(Paint.FILTER_BITMAP_FLAG))
        c.restore()
        img.recycle()
    }
}

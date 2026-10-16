package app.atlas.mapview

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import androidx.core.graphics.PathParser
import app.atlas.mapview.svg.SvgCamera
import app.atlas.mapview.svg.SvgCanvas
import app.atlas.mapview.svg.SvgDocument
import app.atlas.mapview.svg.SvgFonts
import app.atlas.mapview.svg.SvgWarp
import app.atlas.mapview.svg.TurbulenceCache
import app.atlas.render.Raster
import app.atlas.render.live.RegionFrame
import kotlin.math.abs

/**
 * One exact frame of the engine: the layers of a render (ground, the relief image, objects) rasterised together into
 * one bitmap, with the engine camera it was made for. It draws as made, or under another camera ([draw] warps it by
 * the ground plane's and the sky's homographies, see [SvgWarp]), so gestures stay smooth while the next exact frame is
 * made.
 */
class EngineFrame(
    val number: Long,
    val image: Bitmap,
    /** Device pixels per CSS pixel of [image]. */
    val scale: Float,
    /** The engine's camera of the render, in CSS pixels. */
    val camera: SvgCamera,
    val frame: RegionFrame,
    val region: String,
    /** The region view the camera matched, or null between views. */
    val view: String?,
    /** The moment drawn (sunny, grey, wet, night, …). */
    val moment: String,
    val tolerance: Double,
    val timings: Timings,
) {
    /** What a raster's CPU filters cost: [filterMillis] running them, [sourceMillis] drawing what they read. */
    class RasterStats {
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

    /** How long each step took, milliseconds; [svgChars] is the size of the SVG text. */
    data class Timings(val load: Long, val render: Long, val parse: Long, val raster: Long, val svgChars: Int) {
        val total: Long get() = load + render + parse + raster
    }

    private val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    private val clip = Path()
    private val matrix = Matrix()
    private val values = FloatArray(9)

    /**
     * Draws the frame as camera [to] sees it, in CSS pixels of [to]'s screen (the caller scales the canvas by the
     * density), at [alpha].
     */
    fun draw(canvas: Canvas, to: SvgCamera, alpha: Float = 1f) {
        paint.alpha = (alpha.coerceIn(0f, 1f) * 255 + 0.5f).toInt()
        if (same(camera, to)) {
            canvas.save()
            canvas.scale(1 / scale, 1 / scale)
            canvas.drawBitmap(image, 0f, 0f, paint)
            canvas.restore()
            return
        }
        val warp = SvgWarp.between(camera, to)
        part(canvas, warp.sky, warp.skyClip)
        part(canvas, warp.ground, warp.groundClip)
    }

    private fun part(canvas: Canvas, m: DoubleArray?, poly: DoubleArray) {
        if (m == null || poly.size < 6) return
        for (i in 0 until 9) values[i] = m[i].toFloat()
        matrix.setValues(values)
        clip.rewind()
        clip.moveTo(poly[0].toFloat(), poly[1].toFloat())
        for (i in 1 until poly.size / 2) clip.lineTo(poly[2 * i].toFloat(), poly[2 * i + 1].toFloat())
        clip.close()
        canvas.save()
        canvas.concat(matrix)
        canvas.clipPath(clip)
        canvas.scale(1 / scale, 1 / scale)
        canvas.drawBitmap(image, 0f, 0f, paint)
        canvas.restore()
    }

    companion object {
        /** True when two cameras differ by less than a hundredth of a pixel anywhere on screen. */
        fun same(a: SvgCamera, b: SvgCamera): Boolean =
            a.width == b.width && a.height == b.height && abs(a.cx - b.cx) < 1e-3 && abs(a.cy - b.cy) < 1e-3 &&
                abs(a.targetX - b.targetX) < 1e-4 && abs(a.targetY - b.targetY) < 1e-4 && abs(a.targetZ - b.targetZ) < 1e-4 &&
                abs(a.yaw - b.yaw) < 1e-6 && abs(a.pitch - b.pitch) < 1e-6 && abs(a.dist / b.dist - 1) < 1e-7 && a.fov == b.fov

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
            stats: RasterStats? = null,
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
}

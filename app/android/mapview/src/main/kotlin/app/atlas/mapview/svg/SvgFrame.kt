package app.atlas.mapview.svg

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path

/**
 * One exact frame of the engine: a parsed render and the camera it was made for. It rasterises once ([render], off the
 * main thread) and then draws in a few microseconds, as made ([draw]) or under another camera ([drawWarped]), so
 * gestures stay smooth while the next exact frame is being rendered.
 */
class SvgFrame(
    val doc: SvgDocument,
    val camera: SvgCamera,
    private val fonts: SvgFonts,
) {
    /** The rasterised frame, once [render] has run. */
    @Volatile
    var image: Bitmap? = null
        private set

    /** Device pixels per CSS pixel of [image]. */
    @Volatile
    var scale = 1f
        private set

    private val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    private val clip = Path()
    private val matrix = Matrix()

    /** Rasterises the frame at [scale] device pixels per CSS pixel, on [background], and keeps the bitmap. */
    fun render(scale: Float, filters: SvgCanvas.FilterMode = SvgCanvas.FilterMode.CPU, background: Int = Color.TRANSPARENT): Bitmap {
        val w = Math.round(doc.width * scale).coerceAtLeast(1)
        val h = Math.round(doc.height * scale).coerceAtLeast(1)
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        if (background != Color.TRANSPARENT) c.drawColor(background)
        SvgCanvas(doc, fonts, filters).draw(c, w.toFloat(), h.toFloat())
        this.scale = w / doc.width
        image = bmp
        return bmp
    }

    /** Draws the frame as made, in CSS pixels at the canvas origin. */
    fun draw(canvas: Canvas) {
        val img = image ?: render(1f)
        canvas.save()
        canvas.scale(1 / scale, 1 / scale)
        canvas.drawBitmap(img, 0f, 0f, paint)
        canvas.restore()
    }

    /**
     * Draws the frame made for [from] (normally [camera]) as camera [to] would see it, in CSS pixels of [to]'s screen:
     * the ground by the ground plane's homography and the sky by the rotation's (see [SvgWarp]).
     */
    fun drawWarped(canvas: Canvas, from: SvgCamera = camera, to: SvgCamera) {
        val img = image ?: render(1f)
        val warp = SvgWarp.between(from, to)
        part(canvas, img, warp.sky, warp.skyClip)
        part(canvas, img, warp.ground, warp.groundClip)
    }

    private fun part(canvas: Canvas, img: Bitmap, m: DoubleArray?, poly: DoubleArray) {
        if (m == null || poly.size < 6) return
        matrix.setValues(FloatArray(9) { m[it].toFloat() })
        clip.rewind()
        clip.moveTo(poly[0].toFloat(), poly[1].toFloat())
        for (i in 1 until poly.size / 2) clip.lineTo(poly[2 * i].toFloat(), poly[2 * i + 1].toFloat())
        clip.close()
        canvas.save()
        canvas.concat(matrix)
        canvas.clipPath(clip)
        canvas.scale(1 / scale, 1 / scale)
        canvas.drawBitmap(img, 0f, 0f, paint)
        canvas.restore()
    }
}

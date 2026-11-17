package app.atlas.mapview.svg

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.ColorSpace
import android.graphics.HardwareRenderer
import android.graphics.PixelFormat
import android.graphics.RenderNode
import android.hardware.HardwareBuffer
import android.media.ImageReader
import androidx.annotation.RequiresApi
import kotlin.math.abs

/**
 * A device check of the GPU filters: draws a document twice, with the CPU filters ([SvgFilterRaster], proven against
 * Safari in the unit tests) into a bitmap and with the AGSL filters ([SvgShaderFilters]) through a [HardwareRenderer],
 * and compares the two. Robolectric has no GPU, so this runs on a device or emulator (API 33+), for example from an
 * instrumented test or the app's debug screen.
 */
@RequiresApi(33)
object SvgShaderCheck {
    /** The comparison: mean absolute difference (0 … 255) and the share of pixels within two levels. */
    class Result(val mean: Double, val within2: Double, val cpuMillis: Long, val gpuMillis: Long, val cpu: Bitmap, val gpu: Bitmap) {
        override fun toString() = "MAD %.2f, %.1f%% within 2 levels, CPU %d ms, GPU %d ms".format(mean, 100 * within2, cpuMillis, gpuMillis)
    }

    fun compare(doc: SvgDocument, fonts: SvgFonts, scale: Float = 2f): Result {
        val w = Math.round(doc.width * scale)
        val h = Math.round(doc.height * scale)
        val t0 = System.nanoTime()
        val cpu = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        android.graphics.Canvas(cpu).also {
            it.drawColor(Color.WHITE)
            SvgCanvas(doc, fonts, SvgCanvas.FilterMode.CPU).draw(it, w.toFloat(), h.toFloat())
        }
        val t1 = System.nanoTime()
        val gpu = drawOnGpu(doc, fonts, w, h)
        val t2 = System.nanoTime()
        val a = IntArray(w * h)
        val b = IntArray(w * h)
        cpu.getPixels(a, 0, w, 0, 0, w, h)
        gpu.getPixels(b, 0, w, 0, 0, w, h)
        var sum = 0.0
        var near = 0
        for (i in a.indices) {
            var d = 0
            var m = 0
            for (sh in intArrayOf(16, 8, 0)) {
                val e = abs(((a[i] shr sh) and 0xFF) - ((b[i] shr sh) and 0xFF))
                d += e
                m = maxOf(m, e)
            }
            sum += d / 3.0
            if (m <= 2) near++
        }
        return Result(sum / a.size, near.toDouble() / a.size, (t1 - t0) / 1_000_000, (t2 - t1) / 1_000_000, cpu, gpu)
    }

    /** Draws [doc] with the shader filters through the GPU and reads the pixels back. */
    private fun drawOnGpu(doc: SvgDocument, fonts: SvgFonts, w: Int, h: Int): Bitmap {
        val reader = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 1, HardwareBuffer.USAGE_GPU_SAMPLED_IMAGE or HardwareBuffer.USAGE_GPU_COLOR_OUTPUT)
        val renderer = HardwareRenderer()
        try {
            renderer.setSurface(reader.surface)
            val node = RenderNode("svg-check")
            node.setPosition(0, 0, w, h)
            val rc = node.beginRecording()
            rc.drawColor(Color.WHITE)
            SvgCanvas(doc, fonts, SvgCanvas.FilterMode.SHADER).draw(rc, w.toFloat(), h.toFloat())
            node.endRecording()
            renderer.setContentRoot(node)
            renderer.createRenderRequest().setWaitForPresent(true).syncAndDraw()
            val image = reader.acquireNextImage()
            try {
                val hb = image.hardwareBuffer ?: error("no hardware buffer")
                try {
                    val wrapped = Bitmap.wrapHardwareBuffer(hb, ColorSpace.get(ColorSpace.Named.SRGB)) ?: error("cannot wrap the frame")
                    return wrapped.copy(Bitmap.Config.ARGB_8888, false)
                } finally {
                    hb.close()
                }
            } finally {
                image.close()
            }
        } finally {
            renderer.destroy()
            reader.close()
        }
    }
}

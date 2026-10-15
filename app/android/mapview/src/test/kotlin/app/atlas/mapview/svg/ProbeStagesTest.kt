package app.atlas.mapview.svg

import android.graphics.Bitmap
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * Diagnostic, temporary (15 October): the canopy probe matches Safari on the Mac but not on CI's x86 Linux. Prints a hash
 * of each stage so the two machines can be compared: the turbulence alone (pure Kotlin), the whole filter chain on a
 * source made in Kotlin (pure Kotlin), the source graphic drawn by Skia, and the final picture.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class ProbeStagesTest {
    private fun hash(px: IntArray): String = "%016x".format(px.fold(1125899906842597L) { h, v -> 31 * h + v })
    private fun pixels(b: Bitmap) = IntArray(b.width * b.height).also { b.getPixels(it, 0, b.width, 0, 0, b.width, b.height) }

    @Test
    fun canopyStages() {
        val file = File(javaClass.getResource("/svg/probes/canopy.svg")!!.toURI())
        val text = file.readText()
        val lines = ArrayList<String>()
        lines += "cpus ${Runtime.getRuntime().availableProcessors()} arch ${System.getProperty("os.arch")} java ${System.getProperty("java.vm.version")}"

        val t = Turbulence(17f)
        val sums = FloatArray(4)
        val turb = IntArray(320 * 320)
        for (y in 0 until 320) for (x in 0 until 320) turb[y * 320 + x] = t.colorWebKit((x + 1) * 0.5f, (y + 1) * 0.5f, .55f, .55f, 2, true, 15, sums)
        lines += "turbulence ${hash(turb)}"
        lines += "fma ${java.lang.Float.floatToRawIntBits(Math.fma(0.1f, 0.7f, 0.3f))} ${java.lang.Double.doubleToRawLongBits(Math.pow(0.55, 2.4))}"

        // The chain on a source drawn in Kotlin: the dark bar, the light square, the green disc (hard edges), at 2×.
        val doc = SvgParser.parse(text)
        val filter = doc.filters.values.single()
        val raster = SvgFilterRaster(filter)
        raster.prepare(doubleArrayOf(20.0, 20.0, 120.0, 120.0), 2.0, 2.0, SvgFilterRaster.Rect(-1, -1, 321, 321))
        val sr = raster.sourceRect
        val src = IntArray(sr.width * sr.height)
        var i = 0
        for (y in sr.top until sr.bottom) for (x in sr.left until sr.right) {
            val ux = (x + 0.5) / 2
            val uy = (y + 0.5) / 2
            src[i++] = when {
                ux in 20.0..140.0 && uy in 20.0..70.0 -> 0xff211b1f.toInt()
                ux in 20.0..80.0 && uy in 80.0..140.0 -> 0xffc8b89a.toInt()
                (ux - 112) * (ux - 112) + (uy - 112) * (uy - 112) <= 24.0 * 24 -> 0xff3e6a36.toInt()
                else -> 0
            }
        }
        val o = raster.outputRect
        val out = IntArray(o.width * o.height)
        raster.run(src, out)
        lines += "chain ${hash(out)} source ${sr.left},${sr.top} ${sr.width}x${sr.height} out ${o.left},${o.top} ${o.width}x${o.height}"

        // SvgCanvas's own steps, one by one: the source drawn by Skia on transparent, the chain on it, the result as a
        // bitmap (read back), and drawn over the probe's background.
        val plainDoc = SvgParser.parse(text.replace(" filter=\"url(#f)\"", "").replace("<rect width=\"160\" height=\"160\" fill=\"#7f8fa0\"/>", ""))
        val srcBmp = Bitmap.createBitmap(sr.width, sr.height, Bitmap.Config.ARGB_8888)
        val sc = android.graphics.Canvas(srcBmp)
        sc.translate(-sr.left.toFloat(), -sr.top.toFloat())
        sc.scale(2f, 2f)
        SvgCanvas(plainDoc, SvgFonts.fromDirectory(SvgCompare.fonts), SvgCanvas.FilterMode.CPU).draw(sc, 160f, 160f)
        val srcPx = SvgCanvas.premultipliedPixels(srcBmp)
        lines += "skia source ${hash(srcPx)}"
        val out2 = IntArray(o.width * o.height)
        raster.run(srcPx, out2)
        lines += "chain on skia source ${hash(out2)}"
        val res = SvgCanvas.bitmapOfPremultiplied(out2, o.width, o.height)
        lines += "result read back ${hash(SvgCanvas.premultipliedPixels(res))} same ${SvgCanvas.premultipliedPixels(res).contentEquals(out2)}"
        val bg = Bitmap.createBitmap(320, 320, Bitmap.Config.ARGB_8888)
        val bc = android.graphics.Canvas(bg)
        bc.drawColor(0xff7f8fa0.toInt())
        bc.drawBitmap(res, o.left.toFloat(), o.top.toFloat(), android.graphics.Paint())
        lines += "drawn over bg ${hash(pixels(bg))} at 64,64=%08x 200,100=%08x".format(bg.getPixel(64, 64), bg.getPixel(200, 100))
        // The same blend in Kotlin: src-over, rounded.
        val k = out2[(64 - o.top) * o.width + (64 - o.left)]
        lines += "result px 64,64 premul %08x".format(k)

        // Skia: the shapes without the filter, then the whole probe.
        val (plain, _) = SvgCompare.render(File.createTempFile("canopy-plain", ".svg").apply { writeText(text.replace(" filter=\"url(#f)\"", "")) })
        lines += "skia shapes ${hash(pixels(plain))}"
        val (whole, _) = SvgCompare.render(file)
        lines += "whole ${hash(pixels(whole))}"
        // A few pixels of the whole, to see the size of a difference.
        lines += "pixels " + listOf(64 to 64, 96 to 64, 64 to 192, 192 to 224, 200 to 100).joinToString(" ") { (x, y) -> "%d,%d=%08x".format(x, y, whole.getPixel(x, y)) }
        for (l in lines) println("STAGES $l")
    }
}

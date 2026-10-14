package app.atlas.mapview.svg

import android.graphics.Bitmap
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * Each of the engine's filters alone on a small probe (`svg/probes/<name>.svg`: a dark bar, a light square and a green
 * disc drawn through the filter), against Safari's rasterisation of the probe at 2× (`<name>.png`, made with
 * `qlmanage`). Also the committed real render `svg/newyork_sunny_g.svg` against Safari's picture of it.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class SvgProbeTest {
    private fun resource(path: String) = File(javaClass.getResource(path)!!.toURI())

    /** The share of pixels whose largest channel difference is at most [tolerance]. */
    private fun within(a: Bitmap, b: Bitmap, tolerance: Int): Double {
        val w = minOf(a.width, b.width)
        val h = minOf(a.height, b.height)
        val p = IntArray(w * h)
        val q = IntArray(w * h)
        a.getPixels(p, 0, w, 0, 0, w, h)
        b.getPixels(q, 0, w, 0, 0, w, h)
        var ok = 0
        for (i in p.indices) {
            var d = 0
            for (sh in intArrayOf(16, 8, 0)) d = maxOf(d, kotlin.math.abs(((p[i] shr sh) and 0xFF) - ((q[i] shr sh) and 0xFF)))
            if (d <= tolerance) ok++
        }
        return ok.toDouble() / p.size
    }

    @Test
    fun probesMatchSafari() {
        val dir = resource("/svg/probes")
        val svgs = dir.listFiles { f -> f.name.endsWith(".svg") }!!.sortedBy { it.name }
        assertTrue(svgs.size >= 12)
        val lines = ArrayList<String>()
        for (svg in svgs) {
            val name = svg.name.removeSuffix(".svg")
            val (bmp, _) = SvgCompare.render(svg)
            val ref = SvgCompare.load(File(dir, "$name.png"))
            val r = SvgCompare.compare("probe-$name", bmp, ref, tile = 32)
            val exact = within(bmp, ref, 0)
            val near = within(bmp, ref, 1)
            SvgCompare.sideBySide(r.name, bmp, ref)
            val line = "%s  exact %.1f%%  within 1: %.1f%%".format(r, 100 * exact, 100 * near)
            println(line)
            lines.add(line)
            // Measured on the owner's Mac: every probe has a mean difference under 0.25 and over 95 % of its pixels
            // within one level. The bounds leave room for anti-aliasing differences between machines.
            assertTrue(line, r.mean < 0.5)
            assertTrue(line, near > 0.9)
        }
        File(SvgCompare.out, "probes.txt").writeText(lines.joinToString("\n") + "\n")
    }

    @Test
    fun aCommittedRenderMatchesSafari() {
        val svg = resource("/svg/newyork_sunny_g.svg")
        val (bmp, canvas) = SvgCompare.render(svg)
        val ref = SvgCompare.load(resource("/svg/newyork_sunny_g.png"))
        val r = SvgCompare.compare("newyork_sunny_g-committed", bmp, ref)
        SvgCompare.sideBySide(r.name, bmp, ref)
        println("$r  within 2: %.1f%%  filtered ${canvas.stats.filtered}".format(100 * within(bmp, ref, 2)))
        assertTrue(r.toString(), r.mean < 1.2)
        assertTrue(r.toString(), r.worst.first().mean < 6)
    }
}

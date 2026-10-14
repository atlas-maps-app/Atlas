package app.atlas.mapview.svg

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** [SvgFilterRaster] on the engine's own filters, on plain pixel arrays (no Android). */
class SvgFilterRasterTest {
    private fun filter(xml: String): SvgFilter {
        val doc = SvgParser.parse("""<svg xmlns="http://www.w3.org/2000/svg" width="100" height="100"><defs>$xml</defs></svg>""")
        return doc.filters.values.single()
    }

    /** Runs [f] at [scale] over a user-space region on a source filling [src] (filter pixels) with [argb]. */
    private fun run(f: SvgFilter, region: DoubleArray, scale: Double, src: SvgFilterRaster.Rect, argb: Int): Pair<SvgFilterRaster, IntArray> {
        val r = SvgFilterRaster(f)
        assertTrue(r.prepare(region, scale, scale, SvgFilterRaster.Rect(-10000, -10000, 10000, 10000)))
        val sr = r.sourceRect
        val pixels = IntArray(maxOf(0, sr.width * sr.height))
        for (y in sr.top until sr.bottom) for (x in sr.left until sr.right) {
            if (src.contains(x, y)) pixels[(y - sr.top) * sr.width + (x - sr.left)] = argb
        }
        val out = IntArray(r.outputRect.width * r.outputRect.height)
        r.run(pixels, out)
        return r to out
    }

    private fun at(r: SvgFilterRaster, out: IntArray, x: Int, y: Int): Int {
        val o = r.outputRect
        return out[(y - o.top) * o.width + (x - o.left)]
    }

    @Test
    fun matKeepsTheSourceAndAddsGrain() {
        val f = filter(
            """<filter id="mat" filterUnits="userSpaceOnUse" x="0" y="0" width="412" height="915"><feTurbulence type="fractalNoise" baseFrequency=".16 .75" numOctaves="2" seed="13"/><feColorMatrix type="matrix" values=".33 .33 .33 0 0  .33 .33 .33 0 0  .33 .33 .33 0 0  0 0 0 0 1" result="g"/><feComposite in="SourceGraphic" in2="g" operator="arithmetic" k1=".2" k2=".9" k3="0" k4="0" result="t"/><feComposite in="t" in2="SourceAlpha" operator="in"/></filter>""",
        )
        val (r, out) = run(f, doubleArrayOf(0.0, 0.0, 412.0, 915.0), 2.0, SvgFilterRaster.Rect(100, 100, 300, 300), 0xFF8890A0.toInt())
        // Inside the source: opaque, a little darker or lighter than the source; outside: transparent.
        val inside = at(r, out, 150, 150)
        assertEquals(255, inside ushr 24)
        val red = (inside shr 16) and 0xFF
        assertTrue("red $red", red in 0x60..0xA0)
        assertEquals(0, at(r, out, 50, 50))
        assertEquals(0, at(r, out, 400, 400))
    }

    @Test
    fun turbulenceAloneIsTheNoise() {
        val f = filter(
            """<filter id="g" filterUnits="userSpaceOnUse" x="0" y="0" width="50" height="50" color-interpolation-filters="sRGB"><feTurbulence type="fractalNoise" baseFrequency=".05 .08" numOctaves="1" seed="4"/></filter>""",
        )
        val (r, out) = run(f, doubleArrayOf(0.0, 0.0, 50.0, 50.0), 2.0, SvgFilterRaster.Rect(0, 0, 0, 0), 0)
        val t = Turbulence(4f)
        for ((x, y) in listOf(3 to 7, 40 to 20, 99 to 99)) {
            val p = at(r, out, x, y)
            val ux = (x + 1).toFloat() * (1f / 2f)
            val uy = (y + 1).toFloat() * (1f / 2f)
            val a = t.channelWebKit(3, ux, uy, 0.05f, 0.08f, 1, true)
            val red = t.channelWebKit(0, ux, uy, 0.05f, 0.08f, 1, true)
            // The output is premultiplied the way vImage does it.
            assertEquals(a, p ushr 24)
            assertEquals((red * a + 127) / 255, (p shr 16) and 0xFF)
        }
    }
}

package app.atlas.mapview.svg

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import javax.imageio.ImageIO
import kotlin.math.abs

/**
 * [Turbulence] against the Filter Effects spec's own C code (its output for 13 seeds, 5 frequencies, 3 octave counts,
 * both types, in `svg/turbulence_reference.txt`, made by `svg/turbulence_reference.c`), and its single-precision
 * WebKit variant against Safari's rasterisation of a filter (`svg/probes/glint.png`).
 */
class TurbulenceTest {
    @Test
    fun parkMillerMatchesTheSpecsCheckValue() {
        // The spec's comment: seed 1 gives 1043618065 as the 10,000th number.
        var s = 1L
        repeat(10_000) { s = Turbulence.random(s) }
        assertEquals(1043618065L, s)
    }

    @Test
    fun doublePrecisionMatchesTheSpecsCCode() {
        val lines = javaClass.getResourceAsStream("/svg/turbulence_reference.txt")!!.bufferedReader().readLines().filter { !it.startsWith("#") }
        assertTrue(lines.size > 500)
        val cache = HashMap<Long, Turbulence>()
        var worst = 0.0
        for (line in lines) {
            val v = line.trim().split(' ')
            val seed = v[0].toLong()
            val t = cache.getOrPut(seed) { Turbulence(seed.toFloat()) }
            val fx = v[1].toDouble()
            val fy = v[2].toDouble()
            val oct = v[3].toInt()
            val fractal = v[4] == "1"
            val x = v[5].toDouble()
            val y = v[6].toDouble()
            for (c in 0 until 4) {
                val expected = v[7 + c].toDouble()
                val got = t.turbulence(c, x, y, fx, fy, oct, fractal)
                worst = maxOf(worst, abs(got - expected))
            }
        }
        // The same operations in the same order: equal to the last bit or very nearly.
        assertTrue("worst difference $worst", worst < 1e-12)
    }

    @Test
    fun singlePrecisionStaysCloseToTheSpec() {
        val t = Turbulence(13f)
        var off = 0
        var n = 0
        for (y in 0 until 60) for (x in 0 until 60) {
            val ux = (x + 1) * 0.5f
            val uy = (y + 1) * 0.5f
            for (c in 0 until 4) {
                val a = t.channel(c, ux.toDouble(), uy.toDouble(), 0.16f.toDouble(), 0.75f.toDouble(), 2, true)
                val b = t.channelWebKit(c, ux, uy, 0.16f, 0.75f, 2, true)
                assertTrue(abs(a - b) <= 1)
                if (a != b) off++
                n++
            }
        }
        assertTrue("$off of $n bytes differ", off < n / 50)
    }

    @Test
    fun colourAtOnceEqualsChannelByChannel() {
        val t = Turbulence(4f)
        for (y in 0 until 40) for (x in 0 until 40) {
            val ux = (x + 1) * 0.5f
            val uy = (y + 1) * 0.5f
            val p = t.colorWebKit(ux, uy, 0.55f, 0.55f, 2, true)
            assertEquals(t.channelWebKit(0, ux, uy, 0.55f, 0.55f, 2, true), (p shr 16) and 0xFF)
            assertEquals(t.channelWebKit(1, ux, uy, 0.55f, 0.55f, 2, true), (p shr 8) and 0xFF)
            assertEquals(t.channelWebKit(2, ux, uy, 0.55f, 0.55f, 2, true), p and 0xFF)
            assertEquals(t.channelWebKit(3, ux, uy, 0.55f, 0.55f, 2, true), p ushr 24)
        }
    }

    /**
     * Safari drew `glint.svg` (turbulence 0.02 × 0.3, two octaves, seed 5, then alpha = 11 R − 6.9) at 2×. Where the
     * glint shows, its alpha read back from the picture must be what the single-precision port computes, exactly.
     */
    @Test
    fun singlePrecisionMatchesSafariPixelForPixel() {
        val img = ImageIO.read(javaClass.getResourceAsStream("/svg/probes/glint.png")!!)
        val t = Turbulence(5f)
        val bg = 0x7f
        var shown = 0
        for (y in 0 until 320) for (x in 0 until 320) {
            val red = (img.getRGB(x, y) shr 16) and 0xFF
            val seen = (red - bg) * 255.0 / (255 - bg)
            val ux = (x + 1).toFloat() * 0.5f
            val uy = (y + 1).toFloat() * 0.5f
            val noise = t.colorWebKit(ux, uy, 0.02f, 0.3f, 2, true)
            // The colour matrix reads the noise after a premultiply / unpremultiply round trip, as WebKit stores it.
            val r = (SvgFilterRaster.Bytes.unpremultiply(SvgFilterRaster.Bytes.premultiply(noise)) shr 16) and 0xFF
            val alpha = SvgFilterRaster.Bytes.clamped(Math.fma(11f, r.toFloat(), -6.9f * 255f).toDouble())
            if (alpha == 0 && seen < 2) continue
            shown++
            assertTrue("($x, $y): alpha $alpha, Safari ${"%.1f".format(seen)}", abs(alpha - seen) < 1.5)
        }
        assertTrue(shown > 10_000)
    }
}

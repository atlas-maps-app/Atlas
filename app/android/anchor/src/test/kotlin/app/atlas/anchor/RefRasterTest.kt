package app.atlas.anchor

import app.atlas.mapview.svg.SvgParser
import app.atlas.render.live.Moment
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.util.Locale
import java.util.Random

/**
 * RefRaster, the GPU's rules emulated in Java (plan-gpu G1): against Java2D's raster of the same SVG (filters off) at
 * ×2.625 it stays within the planned primitives' measured likeness (Westerkerk MAD ≤ 0.25, ≥ 98.0 % within 2; the canal
 * ring MAD ≤ 0.45, ≥ 95.0 %); its two passes with depth equal strict painter's order (the inset interiors equal whole
 * coverage); and each primitive's coverage agrees with Java2D's on drawn shapes.
 */
class RefRasterTest {
    private val k = 2.625

    private fun compare(name: String, made: AnchorCases.Made, mad: Double, within2: Double): RefRaster {
        val a = AnchorCases.anchor(made)
        val w = (412 * k).toInt(); val h = (a.head.camera.height * k).toInt()
        val t0 = System.nanoTime()
        val r = RefRaster(a, k, w, h, Java2dSvg.Tiles())
        val gpu = r.render()
        val t1 = System.nanoTime()
        val ref = Java2dSvg.render(made.layers.map { SvgParser.parse(it) }, k, w, h)
        val t2 = System.nanoTime()
        val m = RefMetrics.of(gpu, ref, w, h)
        println("$name at ×$k ($w × $h): RefRaster against Java2D: $m (RefRaster ${(t1 - t0) / 1_000_000} ms, Java2D ${(t2 - t1) / 1_000_000} ms)")
        assertTrue("$name: MAD ≤ $mad ($m)", m.mad <= mad)
        assertTrue("$name: ≥ ${within2 * 100} % within 2 ($m)", m.within2 >= within2)
        return r
    }

    @Test
    fun `the Westerkerk home is within the planned likeness of Java2D's raster, and its two passes equal painter's order`() {
        val region = AnchorCases.westerkerk()
        for (m in listOf("sunny", "night")) {
            val made = AnchorCases.render(region, AnchorCases.homeView(region), Moment.Named(m))
            compare("Westerkerk $m", made, 0.25, 0.98)
            if (m != "sunny") continue
            // the inset interiors at the 4x sample positions, with the fringes depth-tested, equal whole-face coverage in
            // strict painter's order
            val a = AnchorCases.anchor(made)
            val w = (412 * k).toInt(); val h = (a.head.camera.height * k).toInt()
            val two = RefRaster(a, k, w, h, Java2dSvg.Tiles()).render()
            val strict = RefRaster(a, k, w, h, Java2dSvg.Tiles(), twoPass = false).render()
            val d = RefMetrics.of(two, strict, w, h)
            println("Westerkerk $m: two passes against strict painter's order: $d")
            assertTrue("inset interiors equal whole coverage (MAD ≤ 0.001): $d", d.mad <= 0.001)
        }
    }

    @Test
    fun `the Main board grachten_home is within the planned likeness of Java2D's raster`() {
        val region = AnchorCases.grachten()
        assumeTrue("the grachten pack is local only", region != null)
        compare("grachten_home sunny", AnchorCases.render(region!!, AnchorCases.boardView(region, 915), Moment.Named("sunny")), 0.45, 0.95)
    }

    private fun col(r: Random, alpha: Boolean): String {
        val c = "#%02X%02X%02X".format(Locale.ROOT, r.nextInt(256), r.nextInt(256), r.nextInt(256))
        return if (alpha && r.nextBoolean()) "$c\" fill-opacity=\"%.2f".format(Locale.ROOT, 0.2 + 0.7 * r.nextDouble()) else c
    }

    @Test
    fun `depth with two passes equals strict painter's order on 2,000 overlapping shapes`() {
        val r = Random(2000)
        val sb = StringBuilder("<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"300\" height=\"300\" viewBox=\"0 0 300 300\">")
        repeat(2000) {
            val x = r.nextDouble() * 280 + 10; val y = r.nextDouble() * 280 + 10; val s = 3 + r.nextDouble() * 40
            when (r.nextInt(6)) {
                0 -> sb.append("<path fill=\"${col(r, true)}\" d=\"M%.1f %.1fL%.1f %.1fL%.1f %.1fL%.1f %.1fZ\"/>".format(Locale.ROOT, x, y, x + s, y + s * 0.2, x + s * 0.9, y + s, x - s * 0.1, y + s * 0.8))
                1 -> sb.append("<path fill=\"${col(r, true)}\" d=\"M%.1f %.1fL%.1f %.1fL%.1f %.1fL%.1f %.1fL%.1f %.1fL%.1f %.1fZ\"/>".format(Locale.ROOT, x, y, x + s, y, x + s * 0.4, y + s * 0.4, x + s, y + s, x, y + s, x + s * 0.3, y + s * 0.5))
                2 -> sb.append("<circle cx=\"%.1f\" cy=\"%.1f\" r=\"%.1f\" fill=\"${col(r, true)}\"/>".format(Locale.ROOT, x, y, s / 3))
                3 -> sb.append("<path fill=\"none\" stroke=\"${col(r, false)}\" stroke-width=\"%.2f\" stroke-linejoin=\"%s\" d=\"M%.1f %.1fL%.1f %.1fL%.1f %.1f\"/>".format(Locale.ROOT, 0.3 + r.nextDouble() * 3, listOf("round", "miter", "bevel")[r.nextInt(3)], x, y, x + s, y + s * 0.3, x + s * 0.2, y + s))
                4 -> sb.append("<g opacity=\".6\"><path fill=\"${col(r, false)}\" d=\"M%.1f %.1fh%.1fv%.1fh-%.1fZ\"/><circle cx=\"%.1f\" cy=\"%.1f\" r=\"%.1f\" fill=\"${col(r, false)}\"/></g>".format(Locale.ROOT, x, y, s, s, s, x + s, y + s, s / 2))
                else -> sb.append("<path fill=\"${col(r, true)}\" fill-rule=\"evenodd\" d=\"M%.1f %.1fh%.1fv%.1fh-%.1fZ M%.1f %.1fh%.1fv%.1fh-%.1fZ\"/>".format(Locale.ROOT, x, y, s, s, s, x + s * 0.25, y + s * 0.25, s * 0.5, s * 0.5, s * 0.5))
            }
        }
        sb.append("</svg>")
        val a = AnchorCases.synthetic(sb.toString())
        val n = (300 * k).toInt()
        val two = RefRaster(a, k, n, n).render()
        val strict = RefRaster(a, k, n, n, twoPass = false).render()
        val kinds = a.head.primCounts.withIndex().filter { it.value > 0 }.joinToString { PrimKind.NAMES[it.index] + " " + it.value }
        println("2,000 shapes ($kinds): two passes against strict: ${RefMetrics.of(two, strict, n, n)}")
        assertArrayEquals("depth with two passes equals strict painter's order", strict, two)
    }

    /** RefRaster against Java2D on [label]'s shapes drawn by [body]: the metrics and the ink (darkness) ratio. */
    private fun shapes(label: String, body: (Random, StringBuilder) -> Unit): Pair<RefMetrics, Double> {
        val r = Random(5)
        val sb = StringBuilder("<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"400\" height=\"400\" viewBox=\"0 0 400 400\">")
        body(r, sb)
        sb.append("</svg>")
        val a = AnchorCases.synthetic(sb.toString())
        val n = (400 * k).toInt()
        val gpu = RefRaster(a, k, n, n).render()
        val ref = Java2dSvg.render(listOf(SvgParser.parse(sb.toString())), k, n, n)
        var ink1 = 0L; var ink2 = 0L
        for (i in gpu.indices) { ink1 += 255 - (gpu[i] and 0xFF); ink2 += 255 - (ref[i] and 0xFF) }
        val m = RefMetrics.of(gpu, ref, n, n)
        val ink = ink1.toDouble() / ink2
        println("$label: $m, ink ratio " + String.format(Locale.ROOT, "%.4f", ink))
        return m to ink
    }

    @Test
    fun `each primitive's coverage agrees with Java2D's on drawn shapes`() {
        fun gate(label: String, mad: Double, body: (Random, StringBuilder) -> Unit) {
            val (m, ink) = shapes(label, body)
            assertTrue("$label: MAD ≤ $mad ($m)", m.mad <= mad)
            assertTrue("$label: ink within 5 % (${ink})", Math.abs(ink - 1) <= 0.05)
        }
        for (size in listOf(3.0, 12.0, 40.0)) {
            gate("convex faces $size", 0.06) { r, sb ->
                repeat(300) {
                    val x = r.nextDouble() * 380 + 10; val y = r.nextDouble() * 380 + 10
                    sb.append("<path fill=\"${col(r, false)}\" d=\"M%.1f %.1fL%.1f %.1fL%.1f %.1fL%.1f %.1fZ\"/>".format(Locale.ROOT, x, y, x + size, y + size * 0.2, x + size, y + size, x, y + size * 0.8))
                }
            }
            gate("concave fills cut into faces $size", 0.2) { r, sb ->
                repeat(300) {
                    val x = r.nextDouble() * 380 + 10; val y = r.nextDouble() * 380 + 10
                    sb.append("<path fill=\"${col(r, false)}\" d=\"M%.1f %.1fL%.1f %.1fL%.1f %.1fL%.1f %.1fL%.1f %.1fZ\"/>".format(Locale.ROOT, x, y, x + size, y, x + size * 0.5, y + size * 0.4, x + size, y + size, x, y + size))
                }
            }
            gate("ellipses $size", 0.2) { r, sb ->
                repeat(300) { sb.append("<circle cx=\"%.1f\" cy=\"%.1f\" r=\"%.1f\" fill=\"${col(r, false)}\"/>".format(Locale.ROOT, r.nextDouble() * 400, r.nextDouble() * 400, size * (0.2 + r.nextDouble()) / 2)) }
            }
        }
        gate("dashed strokes", 0.15) { r, sb ->
            repeat(60) {
                var x = 20 + r.nextDouble() * 360; var y = 20 + r.nextDouble() * 360
                sb.append("<path fill=\"none\" stroke=\"#000000\" stroke-width=\"1.2\" stroke-dasharray=\"6 3 2\" stroke-dashoffset=\"1.5\" stroke-linecap=\"butt\" d=\"M%.1f %.1f".format(Locale.ROOT, x, y))
                repeat(5) {
                    x = (x + (r.nextDouble() - 0.5) * 60).coerceIn(5.0, 395.0); y = (y + (r.nextDouble() - 0.5) * 60).coerceIn(5.0, 395.0)
                    sb.append("L%.1f %.1f".format(Locale.ROOT, x, y))
                }
                sb.append("\"/>")
            }
        }
        for ((join, cap) in listOf("round" to "round", "miter" to "butt", "bevel" to "square")) for (w in listOf(0.3, 0.6, 1.5)) {
            gate("strokes $join/$cap $w", 0.15) { r, sb ->
                repeat(60) {
                    var x = 20 + r.nextDouble() * 360; var y = 20 + r.nextDouble() * 360
                    sb.append("<path fill=\"none\" stroke=\"#000000\" stroke-width=\"$w\" stroke-linejoin=\"$join\" stroke-linecap=\"$cap\" d=\"M%.1f %.1f".format(Locale.ROOT, x, y))
                    repeat(6) {
                        x = (x + (r.nextDouble() - 0.5) * 40).coerceIn(5.0, 395.0); y = (y + (r.nextDouble() - 0.5) * 40).coerceIn(5.0, 395.0)
                        sb.append("L%.1f %.1f".format(Locale.ROOT, x, y))
                    }
                    sb.append("\"/>")
                }
            }
        }
    }
}

package app.atlas.mapview.svg

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/** [SvgParser] on hand-written documents covering what the engine writes, and on the engine's real renders. */
class SvgParserTest {
    private fun parse(body: String, defs: String = "") =
        SvgParser.parse("""<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 412 915" width="412" height="915"><defs>$defs</defs>$body</svg>""")

    @Test
    fun readsTheRootSize() {
        val d = parse("")
        assertEquals(412f, d.width)
        assertEquals(915f, d.height)
        assertArrayEquals(floatArrayOf(0f, 0f, 412f, 915f), d.viewBox, 0f)
    }

    @Test
    fun pathsPackIntoArraysWithRelativeCommands() {
        val d = parse("""<path d="M10 20L30 40h5v-5l1-1zM1.5-2.5.5.5Z" fill="#123456"/>""")
        val s = d.root.children.single() as SvgShape
        assertEquals(SvgShape.Kind.PATH, s.kind)
        val verbs = d.path.verbs.copyOfRange(s.verbStart, s.verbEnd).toList()
        assertEquals(listOf(0, 1, 1, 1, 1, 2, 0, 1, 2).map { it.toByte() }, verbs)
        val xy = d.path.coords.copyOfRange(s.coordStart, s.coordStart + 14)
        assertArrayEquals(floatArrayOf(10f, 20f, 30f, 40f, 35f, 40f, 35f, 35f, 36f, 34f, 1.5f, -2.5f, 0.5f, 0.5f), xy, 1e-6f)
        assertEquals(SvgPaint.Color(0xFF123456.toInt()), s.style.fill)
    }

    @Test
    fun curvesFailLoudly() {
        try {
            parse("""<path d="M0 0C1 1 2 2 3 3"/>""")
            throw AssertionError("a curve was accepted")
        } catch (e: SvgFormatException) {
            assertTrue(e.message!!.contains("C"))
        }
    }

    @Test
    fun presentationInheritsAndStyleWins() {
        val d = parse(
            """<g fill="#ff0000" stroke="#00f" stroke-width="2" stroke-linecap="round" opacity=".5">""" +
                """<path d="M0 0L1 1" style="mix-blend-mode:screen" fill-opacity="0.25"/><circle cx="1" cy="2" r="3" fill="none"/></g>""",
        )
        val g = d.root.children.single() as SvgGroup
        assertEquals(0.5f, g.opacity)
        val p = g.children[0] as SvgShape
        assertEquals(SvgPaint.Color(0xFFFF0000.toInt()), p.style.fill)
        assertEquals(SvgPaint.Color(0xFF0000FF.toInt()), p.style.stroke)
        assertEquals(0.25f, p.style.fillOpacity)
        assertEquals(SvgStyle.Cap.ROUND, p.style.cap)
        assertEquals(SvgBlend.SCREEN, p.blend)
        assertEquals(1f, p.opacity)
        val c = g.children[1] as SvgShape
        assertEquals(SvgPaint.None, c.style.fill)
        assertArrayEquals(floatArrayOf(1f, 2f, 3f), c.geometry, 0f)
        // Shapes that look alike share one style.
        val d2 = parse("""<path d="M0 0L1 1" fill="#abc"/><path d="M2 2L3 3" fill="#abc"/>""")
        assertSame((d2.root.children[0] as SvgShape).style, (d2.root.children[1] as SvgShape).style)
    }

    @Test
    fun gradientsFollowTheirHrefChain() {
        val d = parse(
            "",
            """<linearGradient id="a" x1="0" y1="0" x2="0" y2="1"><stop offset="0" stop-color="#000" stop-opacity="0.5"/><stop offset=".6" stop-color="#fff"/><stop offset=".4" stop-color="#f00"/></linearGradient>""" +
                """<linearGradient id="b" href="#a" gradientUnits="userSpaceOnUse" x2="100"/>""" +
                """<radialGradient id="r"><stop offset="0" stop-color="#fff"/><stop offset="1" stop-color="#fff" stop-opacity="0"/></radialGradient>""",
        )
        val a = d.gradients["a"]!!
        assertEquals(SvgUnits.BOUNDING_BOX, a.units)
        // Offsets never go backwards.
        assertArrayEquals(floatArrayOf(0f, 0.6f, 0.6f), a.offsets, 0f)
        assertEquals(0x80000000.toInt(), a.colors[0])
        val b = d.gradients["b"]!!
        assertEquals(SvgUnits.USER_SPACE, b.units)
        assertArrayEquals(floatArrayOf(0f, 0f, 100f, 1f), b.coords, 0f)
        assertArrayEquals(a.offsets, b.offsets, 0f)
        val r = d.gradients["r"]!!
        assertTrue(r.radial)
        assertArrayEquals(floatArrayOf(0.5f, 0.5f, 0.5f, 0.5f, 0.5f), r.coords, 0f)
    }

    @Test
    fun patternsInheritContentAndTransform() {
        val d = parse(
            """<g><pattern id="p" width="9" height="9" patternUnits="userSpaceOnUse"><path d="M0 0h9" stroke="#8A8F96"/></pattern>""" +
                """<pattern id="q" href="#p" patternTransform="rotate(90)"/><rect width="10" height="10" fill="url(#q)"/></g>""",
        )
        val q = d.patterns["q"]!!
        assertEquals(9f, q.width)
        assertEquals(SvgUnits.USER_SPACE, q.units)
        assertSame(d.patterns["p"]!!.content, q.content)
        assertArrayEquals(floatArrayOf(0f, 1f, -1f, 0f, 0f, 0f), q.transform, 1e-6f)
        val rect = (d.root.children.single() as SvgGroup).children.single() as SvgShape
        assertEquals(SvgPaint.Ref("q", SvgPaint.None), rect.style.fill)
    }

    @Test
    fun clipsAndFiltersResolveEvenWhenDefinedLater() {
        val d = parse(
            """<g clip-path="url(#w)" filter="url(#f)"><path d="M0 0L1 1Z"/></g>""" +
                """<defs><clipPath id="w0"><path d="M0 0H5V5Z"/></clipPath><clipPath id="w" clip-path="url(#w0)"><rect width="3" height="3"/></clipPath>""" +
                """<filter id="f" x="-12%" y="-12%" width="124%" height="124%"><feTurbulence type="fractalNoise" baseFrequency=".15" numOctaves="2" seed="5" result="n"/>""" +
                """<feDisplacementMap in="SourceGraphic" in2="n" scale="10" xChannelSelector="R" yChannelSelector="G"/></filter></defs>""",
        )
        val g = d.root.children.single() as SvgGroup
        assertEquals("w", g.clip!!.id)
        assertEquals("w0", g.clip!!.clip!!.id)
        val f = g.filter!!
        assertEquals(SvgUnits.BOUNDING_BOX, f.units)
        assertArrayEquals(floatArrayOf(-0.12f, -0.12f, 1.24f, 1.24f), f.region, 1e-6f)
        val t = f.primitives[0] as SvgFe.Turbulence
        assertEquals(0.15f, t.baseX)
        assertEquals(0.15f, t.baseY)
        assertTrue(t.fractal)
        assertEquals(5f, t.seed)
        val m = f.primitives[1] as SvgFe.DisplacementMap
        assertEquals(0, m.xChannel)
        assertEquals(1, m.yChannel)
        assertEquals("n", m.input2)
        assertTrue(m.linearRgb)
    }

    @Test
    fun colourMatricesExpand() {
        val d = parse(
            "",
            """<filter id="f"><feColorMatrix type="saturate" values="0"/><feColorMatrix type="matrix" values="0 0 0 0 1  0 0 0 0 1  0 0 0 0 1  11 0 0 0 -6.9"/><feGaussianBlur stdDeviation="0.1 0.45"/><feOffset dy="5"/></filter>""",
        )
        val f = d.filters["f"]!!
        val sat = (f.primitives[0] as SvgFe.ColorMatrix).matrix
        assertEquals(0.213f, sat[0], 1e-6f)
        assertEquals(0.715f, sat[1], 1e-6f)
        assertEquals(1f, sat[18], 0f)
        assertEquals(-6.9f, (f.primitives[1] as SvgFe.ColorMatrix).matrix[19], 0f)
        val blur = f.primitives[2] as SvgFe.GaussianBlur
        assertEquals(0.1f, blur.deviationX)
        assertEquals(0.45f, blur.deviationY)
        assertEquals(5f, (f.primitives[3] as SvgFe.Offset).dy)
    }

    @Test
    fun textKeepsItsFontAndCollapsesSpaces() {
        val d = parse(
            """<text x="10" y="20" transform="matrix(0.141 0.302 -0.519 0.120 -17.9 268.7)" font-family="Geist, Helvetica, sans-serif" font-size="3.20" font-weight="700" letter-spacing="0.30" text-anchor="middle" fill="#EAF2FA" opacity="0.85">  PRINSEN &amp;
  GRACHT </text>""",
        )
        val t = d.root.children.single() as SvgText
        assertEquals("PRINSEN & GRACHT", t.text)
        assertEquals(SvgFont("Geist, Helvetica, sans-serif", 3.2f, 700, false, 0.3f, SvgFont.Anchor.MIDDLE), t.font)
        assertEquals(0.85f, t.opacity)
        assertArrayEquals(floatArrayOf(0.141f, 0.302f, -0.519f, 0.12f, -17.9f, 268.7f), t.transform, 1e-6f)
    }

    @Test
    fun transformsCompose() {
        val m = SvgValues.parseTransform("translate(10 5) rotate(90) scale(2)")!!
        // A point (1, 0) scales to (2, 0), rotates to (0, 2), moves to (10, 7).
        assertEquals(10f, m[0] * 1 + m[2] * 0 + m[4], 1e-5f)
        assertEquals(7f, m[1] * 1 + m[3] * 0 + m[5], 1e-5f)
        assertNull(SvgValues.parseTransform("  "))
    }

    @Test
    fun numbersParseLikeBrowsers() {
        assertArrayEquals(floatArrayOf(0.5f, -0.2f, 1500f, 0.7f, 3f), SvgValues.parseNumbers(".5-.2 1.5e3,.7 3"), 1e-6f)
        assertEquals(0xFFAABBCC.toInt(), SvgValues.parseColor("#abc"))
        assertEquals(0xFFAABBCC.toInt(), SvgValues.parseColor("#AABBCC"))
        assertEquals(0x80FF0000.toInt(), SvgValues.parseColor("rgba(255, 0, 0, 0.5)"))
        assertNull(SvgValues.parseColor("none"))
    }

    @Test
    fun theCommittedRenderParses() {
        val file = File(javaClass.getResource("/svg/newyork_sunny_g.svg")!!.toURI())
        val d = SvgParser.parse(file)
        assertTrue(d.drawables > 1000)
        assertTrue(d.filters.isNotEmpty())
        assertNotNull(d.clips["vb"])
    }

    /** Every render in the cache parses; a 4 MB one in well under the brief's 300 ms on the JVM. */
    @Test
    fun everyRenderInTheCacheParses() {
        val dirs = listOf(File(SvgCompare.cache, "render"), File(SvgCompare.cache, "blob"))
        val files = dirs.flatMap { d -> d.listFiles { f -> f.name.endsWith(".svg") }?.toList().orEmpty() }
        assumeTrue("no renders in the cache", files.isNotEmpty())
        // Warm the JIT on one file, as a running app would be.
        val biggest = files.maxBy { it.length() }
        repeat(3) { SvgParser.parse(biggest) }
        var slowest = 0L
        var bytes = 0L
        var drawables = 0L
        val t0 = System.nanoTime()
        for (f in files) {
            val s = System.nanoTime()
            val d = try {
                SvgParser.parse(f)
            } catch (e: SvgFormatException) {
                throw AssertionError("${f.name}: ${e.message}", e)
            }
            val ms = (System.nanoTime() - s) / 1_000_000
            if (f == biggest) slowest = ms
            bytes += f.length()
            drawables += d.drawables
        }
        val total = (System.nanoTime() - t0) / 1_000_000
        println("parsed ${files.size} renders, ${bytes / 1_000_000} MB, $drawables drawables in $total ms; ${biggest.name} (${biggest.length() / 1024} KB) in $slowest ms")
        assertTrue("${biggest.name} took $slowest ms", slowest < 300)
    }
}

package app.atlas.mapview.svg

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.math.abs

/** [SvgCanvas] on small documents, one feature at a time, through Robolectric's native graphics. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class SvgCanvasTest {
    private fun draw(body: String, w: Int = 100, h: Int = 100, scale: Float = 2f, defs: String = ""): Bitmap {
        val doc = SvgParser.parse("""<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 $w $h" width="$w" height="$h"><defs>$defs</defs>$body</svg>""")
        val bmp = Bitmap.createBitmap((w * scale).toInt(), (h * scale).toInt(), Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawColor(Color.WHITE)
        SvgCanvas(doc, SvgFonts.fromDirectory(SvgCompare.fonts), SvgCanvas.FilterMode.CPU).draw(c, bmp.width.toFloat(), bmp.height.toFloat())
        return bmp
    }

    private fun near(a: Int, b: Int, tolerance: Int = 3) =
        abs(Color.red(a) - Color.red(b)) <= tolerance && abs(Color.green(a) - Color.green(b)) <= tolerance && abs(Color.blue(a) - Color.blue(b)) <= tolerance

    private fun assertNear(expected: Int, actual: Int, tolerance: Int = 3) =
        assertTrue("expected #%06X, got #%06X".format(expected and 0xFFFFFF, actual and 0xFFFFFF), near(expected, actual, tolerance))

    @Test
    fun fillsInheritAndOpacityFolds() {
        val b = draw("""<g fill="#ff0000"><path d="M10 10H50V50H10Z"/><path d="M60 10h30v30h-30z" opacity=".5"/></g>""")
        assertNear(Color.RED, b.getPixel(60, 60))
        assertNear(Color.rgb(255, 128, 128), b.getPixel(150, 50))
        assertNear(Color.WHITE, b.getPixel(5, 5))
    }

    @Test
    fun filteredGroupKeepsItsColour() {
        val mat = """<filter id="mat" filterUnits="userSpaceOnUse" x="0" y="0" width="100" height="100"><feTurbulence type="fractalNoise" baseFrequency=".16 .75" numOctaves="2" seed="13"/><feColorMatrix type="matrix" values=".33 .33 .33 0 0  .33 .33 .33 0 0  .33 .33 .33 0 0  0 0 0 0 1" result="g"/><feComposite in="SourceGraphic" in2="g" operator="arithmetic" k1=".2" k2=".9" k3="0" k4="0" result="t"/><feComposite in="t" in2="SourceAlpha" operator="in"/></filter>"""
        val b = draw("""<g filter="url(#mat)"><path d="M10 10H90V90H10Z" fill="#8890a0"/></g>""", defs = mat)
        val p = b.getPixel(100, 100)
        assertTrue("got #%06X".format(p and 0xFFFFFF), Color.red(p) in 0x60..0xA0 && Color.blue(p) in 0x70..0xB8)
        assertNear(Color.WHITE, b.getPixel(10, 10))
    }

    @Test
    fun boxGradientsStretchOverTheShape() {
        val b = draw(
            """<rect x="10" y="10" width="80" height="20" fill="url(#g)"/>""",
            defs = """<linearGradient id="g"><stop offset="0" stop-color="#000"/><stop offset="1" stop-color="#fff"/></linearGradient>""",
        )
        // Left end black, right end white, the middle grey (interpolated in sRGB, like browsers).
        assertTrue(Color.red(b.getPixel(22, 40)) < 20)
        assertTrue(Color.red(b.getPixel(176, 40)) > 235)
        assertTrue(Color.red(b.getPixel(100, 40)) in 115..140)
    }

    @Test
    fun patternsTile() {
        val b = draw(
            """<rect width="100" height="100" fill="url(#p)"/>""",
            defs = """<pattern id="p" width="10" height="10" patternUnits="userSpaceOnUse"><rect width="5" height="10" fill="#000"/></pattern>""",
        )
        // Black stripes 5 user units wide every 10: at 2×, columns 0–9 black, 10–19 white, and so on.
        for (x in listOf(2, 22, 182)) assertNear(Color.BLACK, b.getPixel(x, 50))
        for (x in listOf(12, 32, 192)) assertNear(Color.WHITE, b.getPixel(x, 50))
    }

    @Test
    fun clipPathsIntersect() {
        val b = draw(
            """<g clip-path="url(#c2)"><rect width="100" height="100" fill="#00f"/></g>""",
            defs = """<clipPath id="c1"><rect width="50" height="100"/></clipPath><clipPath id="c2" clip-path="url(#c1)"><rect width="100" height="50"/></clipPath>""",
        )
        assertNear(Color.BLUE, b.getPixel(40, 40))
        assertNear(Color.WHITE, b.getPixel(140, 40))
        assertNear(Color.WHITE, b.getPixel(40, 140))
    }

    @Test
    fun strokesDashAndJoin() {
        val b = draw("""<path d="M0 50H100" stroke="#000" stroke-width="4" stroke-dasharray="10 10" fill="none"/>""")
        assertNear(Color.BLACK, b.getPixel(10, 100))
        assertNear(Color.WHITE, b.getPixel(30, 100))
        assertNear(Color.BLACK, b.getPixel(50, 100))
    }

    @Test
    fun evenOddLeavesAHole() {
        val b = draw("""<path d="M10 10H90V90H10ZM30 30H70V70H30Z" fill="#000" fill-rule="evenodd"/>""")
        assertNear(Color.BLACK, b.getPixel(40, 40))
        assertNear(Color.WHITE, b.getPixel(100, 100))
    }

    @Test
    fun blendModesBlend() {
        val b = draw("""<rect width="100" height="100" fill="#808080"/><rect width="100" height="100" fill="#808080" style="mix-blend-mode:screen"/>""")
        // screen(0.5, 0.5) = 0.75.
        assertNear(Color.rgb(192, 192, 192), b.getPixel(100, 100), 2)
    }

    @Test
    fun groupOpacityIsOneLayer() {
        // Two overlapping opaque squares in a group at .5: the overlap is not darker than the rest.
        val b = draw("""<g opacity=".5" fill="#000"><rect x="10" y="10" width="50" height="50"/><rect x="30" y="30" width="50" height="50"/></g>""")
        assertNear(b.getPixel(30, 30), b.getPixel(90, 90), 1)
        assertNear(Color.rgb(128, 128, 128), b.getPixel(90, 90), 2)
    }

    @Test
    fun textDrawsInTheBundledFonts() {
        val b = draw("""<text x="50" y="60" font-family="Georgia, serif" font-size="30" font-weight="700" text-anchor="middle" fill="#000">Mm</text>""")
        var dark = 0
        var left = Int.MAX_VALUE
        var right = 0
        for (y in 0 until 200) for (x in 0 until 200) if (Color.red(b.getPixel(x, y)) < 100) {
            dark++
            left = minOf(left, x)
            right = maxOf(right, x)
        }
        assertTrue("dark $dark", dark > 500)
        // Centred on x = 50 (100 px at 2×).
        assertTrue("left $left right $right", kotlin.math.abs((left + right) / 2 - 100) < 6)
    }

    @Test
    fun aFrameWarpedToItsOwnCameraIsUnchanged() {
        val doc = SvgParser.parse(
            """<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 100 100" width="100" height="100"><rect width="100" height="100" fill="#9ab"/>""" +
                """<path d="M20 60H80V90H20Z" fill="#a33"/><circle cx="50" cy="20" r="10" fill="#fd0"/></svg>""",
        )
        val cam = SvgCamera(0.0, 0.0, 0.0, 10.0, 30.0, 300.0, 40.0, 100.0, 100.0)
        val frame = SvgFrame(doc, cam, SvgFonts.fromDirectory(SvgCompare.fonts))
        frame.render(2f)
        val plain = Bitmap.createBitmap(200, 200, Bitmap.Config.ARGB_8888)
        Canvas(plain).apply { scale(2f, 2f) }.also { frame.draw(it) }
        val warped = Bitmap.createBitmap(200, 200, Bitmap.Config.ARGB_8888)
        Canvas(warped).apply { scale(2f, 2f) }.also { frame.drawWarped(it, to = cam) }
        val r = SvgCompare.compare("frame-identity", warped, plain, tile = 20)
        assertTrue(r.toString(), r.mean < 1.0)
        // Turning the camera moves the picture.
        val turned = Bitmap.createBitmap(200, 200, Bitmap.Config.ARGB_8888)
        Canvas(turned).apply { scale(2f, 2f) }.also { frame.drawWarped(it, to = cam.copy(yaw = 14.0)) }
        assertTrue(SvgCompare.compare("frame-turned", turned, plain, tile = 20).mean > 5)
    }
}

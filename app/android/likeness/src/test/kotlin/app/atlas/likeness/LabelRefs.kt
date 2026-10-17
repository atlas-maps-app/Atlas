package app.atlas.likeness

import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import app.atlas.anchor.AnchorCompiler
import app.atlas.mapview.svg.SvgFont
import app.atlas.mapview.svg.SvgGroup
import app.atlas.mapview.svg.SvgNode
import app.atlas.mapview.svg.SvgParser
import app.atlas.mapview.svg.SvgText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.math.atan2
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * The texts a reference draws, as D16's label metrics read them: each text's string, where its ink's centre is (device
 * px, through Android's own text layout with the bundled typefaces, as the frame drew it) and its ink's box, the angle of
 * its baseline (degrees, clockwise from the screen's x axis, as y points down), its size (the font size times the
 * transform's scale, device px), where it lies: on the `ground` (canal and water names, laid along the water) or on an
 * `object` (shop signs, house numbers), and whether any of its ink is inside the frame (the renders also write names
 * whose ink falls outside: the frame's clip hides them).
 */
data class Label(
    val text: String, val cx: Double, val cy: Double, val angle: Double, val size: Double, val layer: String, val opacity: Double,
    val x0: Double, val y0: Double, val x1: Double, val y1: Double, val visible: Boolean,
) {
    fun json(): Map<String, Any?> = mapOf(
        "text" to text, "cx" to cx, "cy" to cy, "angle" to angle, "size" to size, "layer" to layer, "opacity" to opacity,
        "box" to listOf(x0, y0, x1, y1), "visible" to visible,
    )
}

/** Label lists of reference frames. */
object LabelRefs {
    /** The labels of [layers] at [size], the frame moved by [shiftX] device px. */
    fun of(layers: List<String>, size: FrameSize, shiftX: Double = 0.0): List<Label> = synchronized(Frames.skia) { labels(layers, size, shiftX) }

    private fun labels(layers: List<String>, size: FrameSize, shiftX: Double): List<Label> {
        val out = ArrayList<Label>()
        for (text in layers) {
            val doc = SvgParser.parse(text)
            val scan = SvgScan.scan(text)
            var leaf = 0
            // what a generator-only filter draws is left out (structural mode), its texts with it
            fun pruned(n: SvgNode) = n.filter?.let { AnchorCompiler.generatorOnly(it) } == true
            fun walk(g: SvgGroup, opacity: Float, hidden: Boolean) {
                for (c in g.children) {
                    if (c is SvgGroup) { walk(c, opacity * c.opacity, hidden || pruned(c)); continue }
                    val at = scan.leaves[leaf++]
                    if (c !is SvgText || hidden || pruned(c)) continue
                    val o = opacity * c.opacity * c.style.fillOpacity
                    if (o <= 0f) continue
                    measure(c, size, shiftX)?.let { m ->
                        val visible = m[5] > 0 && m[4] < size.wPx && m[7] > 0 && m[6] < size.hPx
                        out.add(Label(c.text, m[0], m[1], m[2], m[3], if (scan.inObjects(at)) "object" else "ground", o.toDouble(), m[4], m[6], m[5], m[7], visible))
                    }
                }
            }
            walk(doc.root, doc.root.opacity, false)
        }
        return out
    }

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG or Paint.LINEAR_TEXT_FLAG)
    private val path = Path()
    private val bounds = RectF()
    private val matrix = Matrix()

    /** The ink centre (device px), baseline angle (degrees), size (device px) and ink box (x0, x1, y0, y1) of [t] at [size]. */
    private fun measure(t: SvgText, size: FrameSize, shiftX: Double): DoubleArray? {
        val f = t.font
        paint.typeface = Likeness.fonts.typeface(f.family, f.weight, f.italic)
        paint.fontVariationSettings = Likeness.fonts.variation(f.family, f.weight)
        paint.textSize = f.size
        paint.letterSpacing = if (f.size > 0f) f.letterSpacing / f.size else 0f
        paint.textAlign = when (f.anchor) {
            SvgFont.Anchor.MIDDLE -> Paint.Align.CENTER
            SvgFont.Anchor.END -> Paint.Align.RIGHT
            SvgFont.Anchor.START -> Paint.Align.LEFT
        }
        path.rewind()
        paint.getTextPath(t.text, 0, t.text.length, t.x, t.y, path)
        val m = t.transform
        matrix.reset()
        if (m != null) matrix.setValues(floatArrayOf(m[0], m[2], m[4], m[1], m[3], m[5], 0f, 0f, 1f))
        matrix.postScale(size.density.toFloat(), size.density.toFloat())
        matrix.postTranslate(shiftX.toFloat(), 0f)
        path.transform(matrix)
        path.computeBounds(bounds, true)
        if (bounds.isEmpty) return null
        val a = (m?.get(0) ?: 1f).toDouble(); val b = (m?.get(1) ?: 0f).toDouble()
        val c = (m?.get(2) ?: 0f).toDouble(); val d = (m?.get(3) ?: 1f).toDouble()
        val angle = Math.toDegrees(atan2(b, a))
        val scale = sqrt(abs(a * d - b * c))
        return doubleArrayOf(
            bounds.centerX().toDouble(), bounds.centerY().toDouble(), angle, f.size * scale * size.density,
            bounds.left.toDouble(), bounds.right.toDouble(), bounds.top.toDouble(), bounds.bottom.toDouble(),
        )
    }
}

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class LabelRefsTest {
    @Test
    fun `a text's centre, angle and size come from its transform and Android's text layout`() {
        val svg = "<svg xmlns='http://www.w3.org/2000/svg' viewBox='0 0 412 916' width='412' height='916'><g clip-path='url(#vb)'>" +
            "<text transform=\"matrix(0.000 1.000 -1.000 0.000 200.0 300.0)\" font-family=\"Geist, Helvetica, sans-serif\" font-size=\"10.00\" font-weight=\"600\" " +
            "text-anchor=\"middle\" fill=\"#4E6B85\">PRINSENGRACHT</text></g></svg>"
        val s1 = FrameSize.NAMED.getValue("S1")
        val l = LabelRefs.of(listOf(svg), s1).single()
        assertEquals("PRINSENGRACHT", l.text)
        assertEquals("a quarter turn clockwise", 90.0, l.angle, 1e-6)
        assertEquals(10.0 * s1.density, l.size, 1e-3)
        // centred on its anchor along the baseline, which runs down the screen; the glyphs' up turns to the screen's right
        assertEquals(300.0 * s1.density, l.cy, 2.0)
        assertTrue("the ink lies right of the baseline: ${l.cx}", l.cx > 200.0 * s1.density && l.cx < 210.0 * s1.density)
        assertEquals("ground", l.layer)
        assertTrue(l.visible && l.x0 < l.cx && l.cx < l.x1 && l.y0 < l.cy && l.cy < l.y1)
        val moved = LabelRefs.of(listOf(svg), s1, shiftX = 0.25).single()
        assertEquals(l.cx + 0.25, moved.cx, 1e-3)
    }

    @Test
    fun `the Westerkerk home names its canals on the ground`() {
        val made = Oracle.render(CaseSet.load(phone = null).case("home sunny S1"))
        val labels = LabelRefs.of(made.layers, FrameSize.NAMED.getValue("S1"))
        val ground = labels.filter { it.layer == "ground" }
        println("home sunny S1: ${labels.size} texts, on the ground ${ground.map { "${it.text} (%.0f, %.0f) %.1f°%s".format(it.cx, it.cy, it.angle, if (it.visible) "" else " off-frame") }}")
        // N18: the Westerkerk home draws 8 canal-name texts; some of them fall outside the frame, under its clip
        assertTrue("canal names ${ground.size}", ground.size >= 4)
        val visible = ground.filter { it.visible }
        assertTrue("canal names on screen ${visible.size}", visible.size >= 3)
        assertTrue(visible.all { it.x1 > 0 && it.x0 < 1080 && it.y1 > 0 && it.y0 < 2400 })
    }
}

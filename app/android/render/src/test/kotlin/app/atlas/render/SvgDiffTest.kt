package app.atlas.render

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/** SvgDiff finds exactly the edits made to a real render, and forgives only numbers within the tolerance. */
class SvgDiffTest {
    private val real: String? by lazy {
        File(Oracle.repo, "cache/render/newyork_sunny_o.svg").takeIf { it.isFile }?.readText()
    }

    private fun realSvg(): String {
        assumeTrue("cache/render/newyork_sunny_o.svg is local only", real != null)
        return real!!
    }

    private fun f(spec: String, v: Double): String = String.format(java.util.Locale.ROOT, spec, v)

    /** The [k]th match of [re] in [s]. */
    private fun nth(s: String, re: Regex, k: Int): MatchResult = re.findAll(s).drop(k).first()

    private val pathEl = Regex("<path d=\"M([0-9.]+) ([0-9.]+)[^>]*/>")

    @Test
    fun identicalDocumentsAreTheSame() {
        val s = realSvg()
        val t0 = System.nanoTime()
        val r = SvgDiff.compare(s, s)
        val ms = (System.nanoTime() - t0) / 1e6
        println("identical: $r (${f("%.0f", ms)} ms for ${s.length} chars)")
        assertTrue(r.identicalText)
        assertTrue(r.same)
        assertEquals(1.0, r.similarity, 0.0)
        assertEquals(r.expectedElements, r.actualElements)
        assertTrue(r.expectedElements > 10000)
        assertEquals(r.expectedElements, r.aligned)
    }

    @Test
    fun numbersWithinToleranceAreForgiven() {
        val s = realSvg()
        val m = nth(s, pathEl, 500)
        val x = m.groupValues[1]
        val edited = s.replaceRange(m.groups[1]!!.range, f("%.3f", x.toDouble() + 0.004))
        val r = SvgDiff.compare(s, edited)
        println("jitter: $r")
        assertFalse(r.identicalText)
        assertTrue(r.same)
        assertEquals(1, r.withinTolerance)
        assertEquals(1.0, r.similarity, 0.0)
    }

    @Test
    fun aNumberBeyondToleranceIsReported() {
        val s = realSvg()
        val m = nth(s, pathEl, 700)
        val y = m.groupValues[2]
        val changed = f("%.1f", y.toDouble() + 0.1)
        val edited = s.replaceRange(m.groups[2]!!.range, changed)
        val r = SvgDiff.compare(s, edited)
        println("number: $r")
        assertEquals(1, r.divergenceCount)
        val d = r.first[0]
        assertEquals("attribute d", d.what)
        assertTrue(d.path, d.path.startsWith("svg/") && d.path.contains("path["))
        assertTrue(d.expected!!.startsWith("M${m.groupValues[1]} $y"))
        assertTrue(d.actual!!.startsWith("M${m.groupValues[1]} $changed"))
        assertEquals(1, r.byKind["path"]!!.differing)
        assertEquals(r.expectedElements - 1, r.equal)
    }

    @Test
    fun aColourChangeIsReported() {
        val s = realSvg()
        val m = nth(s, Regex("fill=\"(#[0-9A-Fa-f]{6})\""), 3000)
        val c = m.groupValues[1]
        val other = if (c.lowercase() == "#000000") "#000001" else "#000000"
        val r = SvgDiff.compare(s, s.replaceRange(m.groups[1]!!.range, other))
        println("colour: $r")
        assertEquals(1, r.divergenceCount)
        assertEquals("attribute fill", r.first[0].what)
        assertEquals(c, r.first[0].expected)
        assertEquals(other, r.first[0].actual)
    }

    @Test
    fun aDeletedElementIsMissingAndTheRestRealigns() {
        val s = realSvg()
        val m = nth(s, pathEl, 1200)
        val r = SvgDiff.compare(s, s.removeRange(m.range))
        println("deleted: $r")
        assertEquals(1, r.missing)
        assertEquals(0, r.extra)
        assertEquals(1, r.divergenceCount)
        assertTrue(r.first[0].what.startsWith("missing <path"))
        assertEquals(r.expectedElements - 1, r.equal)
    }

    @Test
    fun anInsertedElementIsExtra() {
        val s = realSvg()
        val m = nth(s, pathEl, 2500)
        val extra = "<circle cx=\"1.5\" cy=\"2.5\" r=\"3\" fill=\"#123456\"/>"
        val r = SvgDiff.compare(s, s.substring(0, m.range.last + 1) + extra + s.substring(m.range.last + 1))
        println("inserted: $r")
        assertEquals(0, r.missing)
        assertEquals(1, r.extra)
        assertEquals(1, r.divergenceCount)
        assertEquals(extra.replace("/>", ">"), r.first[0].actual)
        assertEquals(r.expectedElements, r.equal)
    }

    @Test
    fun anAttributeAddedOrRemovedIsReported() {
        val s = realSvg()
        val m = nth(s, pathEl, 3100)
        val withOp = s.substring(0, m.range.last - 1) + " opacity=\".5\"" + s.substring(m.range.last - 1)
        val r = SvgDiff.compare(s, withOp)
        assertEquals(1, r.divergenceCount)
        assertEquals("attribute opacity extra", r.first[0].what)
        val back = SvgDiff.compare(withOp, s)
        assertEquals(1, back.divergenceCount)
        assertEquals("attribute opacity missing", back.first[0].what)
    }

    @Test
    fun manyScatteredEditsAreAllCounted() {
        val s = realSvg()
        val ms = pathEl.findAll(s).toList()
        val picks = listOf(100, 900, 1700, 2600, 3500, 4400, 5300).filter { it < ms.size }
        var edited = s
        // edit from the end so earlier ranges stay valid
        for (k in picks.asReversed()) {
            val g = ms[k].groups[1]!!
            edited = edited.replaceRange(g.range, f("%.1f", g.value.toDouble() + 5))
        }
        val r = SvgDiff.compare(s, edited)
        println("scattered: $r")
        assertEquals(picks.size, r.divergenceCount)
        assertEquals(picks.size, r.byKind["path"]!!.differing)
        assertTrue(r.similarity < 1.0 && r.similarity > 0.999)
    }

    // ---------------------------------------------------------------- the reader on its own (CI has no cache/render)

    @Test
    fun theReaderForgivesAndDecodes() {
        val doc = SvgDiff.parse(
            "<?xml version=\"1.0\"?><!DOCTYPE svg><!-- note --><svg a='1' b=\"x &amp; y\" c=3>" +
                "<g id=\"layer\"><text x=\"1\">A &lt;B&gt; &#233;<![CDATA[<raw>]]></text><path d=\"M1 2\"/></g>" +
                "</stray><rect/><rect width=\"2\">",
        )
        val svg = doc.children.single()
        assertEquals("svg", svg.tag)
        assertEquals("1", svg.attrs["a"])
        assertEquals("x & y", svg.attrs["b"])
        assertEquals("3", svg.attrs["c"])
        val g = svg.children[0]
        val text = g.children[0]
        assertEquals("A <B> é<raw>", text.children.single().text)
        assertEquals("svg/g[0]/path[0]", g.children[1].path())
        assertNotNull(g.children[1].context())
        assertEquals("id=\"layer\" on g", g.children[1].context())
        assertEquals(listOf("g", "rect", "rect"), svg.children.map { it.tag })
        assertEquals("svg/rect[1]", svg.children[2].path())
    }

    @Test
    fun syntheticEditsAreFound() {
        val a = "<svg><g id=\"a\"><path d=\"M1.0 2.0L3 4\" fill=\"#fff\"/><circle cx=\"5\" cy=\"6\" r=\"1\"/>" +
            "<text x=\"1\" y=\"2\">Hi 1.5</text></g></svg>"
        assertTrue(SvgDiff.compare(a, a).same)
        assertTrue(SvgDiff.compare(a, a.replace("L3 4", "L3.006 4")).same)
        val r = SvgDiff.compare(a, a.replace("L3 4", "L3.1 4"))
        assertEquals(1, r.divergenceCount)
        assertEquals("svg/g[0]/path[0]", r.first[0].path)
        assertEquals("id=\"a\" on g", r.first[0].context)
        assertEquals(1, SvgDiff.compare(a, a.replace("Hi 1.5", "Ho 1.5")).divergenceCount)
        assertTrue(SvgDiff.compare(a, a.replace("Hi 1.5", "Hi 1.505")).same)
        assertEquals(1, SvgDiff.compare(a, a.replace("#fff", "#ffe")).divergenceCount)
        // a different number of numbers is a divergence, not a tolerance
        assertEquals(1, SvgDiff.compare(a, a.replace("L3 4", "L3 4L5 6")).divergenceCount)
        val gone = SvgDiff.compare(a, a.replace("<circle cx=\"5\" cy=\"6\" r=\"1\"/>", ""))
        assertEquals(1, gone.missing)
        assertEquals(1, gone.divergenceCount)
    }
}

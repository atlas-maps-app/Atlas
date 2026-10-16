package app.atlas.render.live

import app.atlas.render.City
import app.atlas.render.V3
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** The item log: element counts, the radius cut as the engine's `far` test makes it, runs of the objects group, the tape's own handling. */
class TapeItemsTest {
    private fun note(kind: String, owner: Any?, fx0: Double, fz0: Double, fx1: Double = fx0, fz1: Double = fz0) =
        ItemNote(kind, owner, fx0, fz0, fx1, fz1, fx0, 1.0, fz0, -0.03)

    /** The elements item fragment [frag] adds to an objects group, as [TapeItems.elementRuns] counts them. */
    private fun elements(frag: String): Int {
        val t = TapeItems()
        t.add("x", 1, 1.0, null, listOf(frag), 0)
        return t.elementRuns(listOf(layer(frag)))[1]
    }

    @Test
    fun `top-level elements are counted as XML nests them`() {
        assertEquals(0, elements(""))
        assertEquals(1, elements("<path d=\"M1.0 2.0L3.0 4.0Z\" fill=\"#fff\"/>"))
        assertEquals(2, elements("<g opacity=\".5\"><path d=\"M0 0\"/><circle cx=\"1\" cy=\"2\" r=\"3\"/></g><rect width=\"4\" height=\"5\"/>"))
        assertEquals("a quoted > or / is not a tag's end", 2, elements("<text x=\"1\" a=\"b>c/\" d='e>'>A &amp; B</text><path d=\"M0 0\"/>"))
        assertEquals("comments are not elements", 2, elements("<g><!-- <path/> > --><g><path/></g></g><path/><!-- <path/> -->"))
    }

    @Test
    fun `items keep what their makers knew, and the cut drops exactly what the far test drops`() {
        val t = TapeItems()
        val out = arrayListOf("<path d=\"M0 0\"/>", "<path d=\"M1 1\"/><path d=\"M2 2\"/>")
        val b = Any()
        t.add("building", 3, 120.5, note("building", b, 10.0, 0.0), out, 0)
        t.add("railing", 4, 100.0, ItemNote("railing", null, Double.NaN, Double.NaN, Double.NaN, Double.NaN, 1.0, 1.5, 2.0, 0.0), out, 2)
        t.add("power", 5, 90.0, note("power", null, 0.0, 300.0, 0.0, 30.0), out, 1)
        t.add("svg", 0, 80.0, null, out, 2)
        assertEquals(4, t.size)
        assertEquals(listOf("building", "railing", "power", "svg"), (0 until 4).map { t.kind(it) })
        assertEquals(listOf(3, 4, 5, 0), (0 until 4).map { t.owner(it) })
        assertEquals(listOf(0, 48, 48, 80), (0 until 4).map { t.firstChar(it) })
        assertEquals(listOf(48, 0, 32, 0), (0 until 4).map { t.charCount(it) })
        assertEquals(80, t.chars)
        assertEquals(listOf(1, 0, 2, 0), (0 until 4).map { t.farPoints(it) })
        assertEquals(120.5, t.depth(0), 0.0); assertEquals(10.0, t.sortX(0), 0.0); assertEquals(1.0, t.sortY(0), 0.0); assertEquals(-0.03, t.sortOffset(0), 0.0)
        assertEquals(30.0, t.farZ(2, 1), 0.0); assertTrue(t.sortX(3).isNaN())
        // hypot(x − TX, z − TZ) > RAD, every tested point beyond; untested never cut
        assertFalse(t.cut(0, 0.0, 0.0, 10.0)); assertTrue(t.cut(0, 0.0, 0.0, 9.999))
        assertFalse(t.cut(1, 0.0, 0.0, 0.0))
        assertFalse("a segment stays while either end is within", t.cut(2, 0.0, 0.0, 30.0)); assertTrue(t.cut(2, 0.0, 0.0, 29.0))
        try { t.kind(4); fail() } catch (_: IndexOutOfBoundsException) { }
        t.clear()
        assertEquals(0, t.size); assertEquals(0, t.chars)
        for (k in 0 until 600) t.add("tree", k, -k.toDouble(), null, out, 0) // grows past its first arrays
        assertEquals(600, t.size); assertEquals(599, t.owner(599)); assertEquals(599 * 48, t.firstChar(599))
        assertEquals("1024 items' arrays", 1024L * (3 * 4 + 9 * 8 + 8), t.bytes())
    }

    private fun layer(frags: String) = "<svg viewBox=\"0 0 4 4\"><defs><clipPath id=\"vb\"><rect width=\"4\" height=\"4\"/></clipPath></defs>" +
        "<g clip-path=\"url(#vb)\"><path d=\"M0 0\" fill=\"#000\"/><g stroke-linejoin=\"round\" filter=\"url(#mat)\">$frags</g><rect width=\"4\" fill=\"url(#haze)\"/></g></svg>"

    @Test
    fun `element runs map items onto the objects group, across o and o2`() {
        val frags = listOf("<path d=\"M1 1\"/>", "<g opacity=\".4\"><path d=\"M2 2\"/></g>", "<circle cx=\"3\" cy=\"3\" r=\"1\"/>", "<path d=\"M4 4\"/><path d=\"M5 5\"/>", "")
        val t = TapeItems()
        t.add("a", 1, 9.0, null, frags.subList(0, 2), 0) // two fragments, two elements
        t.add("b", 2, 8.0, null, frags.subList(0, 3), 2)
        t.add("c", 3, 7.0, null, frags.subList(0, 4), 3) // straddles o and o2 below
        t.add("d", 4, 6.0, null, frags, 4) // nothing
        val o = layer(frags[0] + frags[1] + frags[2] + "<path d=\"M4 4\"/>"); val o2 = layer("<path d=\"M5 5\"/>")
        assertArrayEquals(intArrayOf(0, 2, 2, 1, 3, 2, 5, 0), t.elementRuns(listOf("<svg><g clip-path=\"url(#vb)\"><path d=\"M9 9\"/></g></svg>", o, null, o2)))
        try {
            t.elementRuns(listOf(o)); fail("a group shorter than the items")
        } catch (e: IllegalStateException) { assertTrue(e.message!!, e.message!!.contains("characters")) }
        for ((a, b) in listOf("<path d=\"M1 1\"" to "/><circle/>", "<g><path/>" to "<path/></g>")) { // a cut through an element, an element left open
            val u = TapeItems()
            u.add("a", 1, 9.0, null, listOf(a), 0); u.add("b", 1, 8.0, null, listOf(b), 0)
            try {
                u.elementRuns(listOf(layer(a + b))); fail("an item that does not start an element: $a | $b")
            } catch (e: IllegalStateException) { assertTrue(e.message!!, e.message!!.contains("does not start")) }
        }
    }

    @Test
    fun `the tape logs items by owner while open, and forgets them on reset`() {
        val cam = City.Camera(V3(0.0, 0.0, 0.0), 0.0, 30.0, 100.0, 36.0, 412.0, 915.0)
        val tape = WorldTape(frame = cam)
        val house = Any(); val tree = Any()
        tape.setOwner(house)
        tape.item("building", house, 50.0, null, listOf("<path/>"), 0)
        tape.item("tree", tree, 40.0, null, listOf("<circle/>"), 0) // not the current owner: registered all the same
        tape.item("svg", null, 30.0, null, listOf("<path/>"), 0)
        assertEquals(3, tape.items.size)
        assertSame(house, tape.ownerObject(tape.items.owner(0))); assertSame(tree, tape.ownerObject(tape.items.owner(1))); assertEquals(0, tape.items.owner(2))
        assertEquals(tape.ownerId(tree), tape.items.owner(1)); assertEquals(2, tape.ownerCount)
        tape.seal()
        tape.item("late", house, 1.0, null, listOf("<path/>"), 0)
        assertEquals("a closed tape logs nothing", 3, tape.items.size)
        tape.reset()
        assertEquals(0, tape.items.size); assertEquals(0, tape.ownerCount)
    }
}

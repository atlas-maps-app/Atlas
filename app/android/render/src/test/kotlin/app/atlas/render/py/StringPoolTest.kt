package app.atlas.render.py

import app.atlas.render.Oracle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** StringPool hands out one copy per distinct string, and PyJson builds its dicts and strings through it. */
class StringPoolTest {
    @Test
    fun `one copy per distinct string, short ones the literal itself`() {
        val p = StringPool(2)
        val a = p.of(String(charArrayOf('y', 'e', 's')))
        assertSame("yes", a) // interned: the literal in the code
        assertSame(a, p.of(StringBuilder("ye").append('s').toString()))
        val long = "a street name longer than the interned strings go, kept once".repeat(2)
        val l1 = p.of(String(long.toCharArray()))
        assertSame(l1, p.of(String(long.toCharArray())))
        assertNotSame(long, l1) // pooled here, not with the JVM
        assertEquals(long, l1)
        assertSame(p.of(""), p.of(String(CharArray(0))))
        // growth keeps every string findable
        val made = (0 until 5000).map { p.of("s$it") }
        for (i in 0 until 5000) assertSame(made[i], p.of(StringBuilder("s").append(i).toString()))
        assertEquals(5000 + 3, p.size)
    }

    @Test
    fun `character ranges meet the strings they spell`() {
        val p = StringPool()
        val chars = "xxbuilding:levelsyy".toCharArray()
        val s = p.of(chars, 2, 17)
        assertSame("building:levels", s)
        assertSame(s, p.of("building:levels"))
        val q = p.of("Prinsengracht".toCharArray(), 0, 13)
        assertSame(q, p.of(String("Prinsengracht".toCharArray())))
        val e = p.of(chars, 5, 5)
        assertSame(p.of(""), e)
        // strings with equal hashes but different text stay apart
        assertEquals("Aa".hashCode(), "BB".hashCode())
        val aa = p.of("Aa".toCharArray(), 0, 2); val bb = p.of("BB".toCharArray(), 0, 2)
        assertEquals("Aa", aa); assertEquals("BB", bb)
    }

    @Suppress("UNCHECKED_CAST")
    @Test
    fun `PyJson reads objects as compact dicts with pooled strings`() {
        val o = PyJson.loads("""{"elements": [{"type": "way", "tags": {"building": "yes", "name": "Westerkerk"}}, {"type": "way", "tags": {"building": "yes"}}],
            "n": 1, "big": 12345678901, "f": 1.5e3, "t": true, "z": null}""") as Map<String, Any?>
        assertTrue(o is CompactDict<*, *>)
        assertEquals(listOf("elements", "n", "big", "f", "t", "z"), o.keys.toList())
        assertEquals(1, o["n"]); assertEquals(12345678901L, o["big"]); assertEquals(1500.0, o["f"]); assertEquals(true, o["t"]); assertTrue("z" in o)
        val els = o["elements"] as List<Map<String, Any?>>
        val t0 = els[0]["tags"] as Map<String, Any?>; val t1 = els[1]["tags"] as Map<String, Any?>
        assertTrue(t0 is CompactDict<*, *>)
        assertSame(t0.keys.first(), t1.keys.first())
        assertSame("building", t0.keys.first())
        assertSame(t0["building"], t1["building"])
        assertSame(els[0]["type"], els[1]["type"])
        assertEquals(linkedMapOf("building" to "yes", "name" to "Westerkerk"), t0)
    }

    @Suppress("UNCHECKED_CAST")
    @Test
    fun `the Westerkerk extract reads as before, key for key`() {
        val f = File(Oracle.repo, "data/ams/raw.json")
        val got = PyJson.load(f) as Map<String, Any?>
        val want = Oracle.py(kotlinx.serialization.json.Json.parseToJsonElement(f.readText())) as Map<String, Any?>
        assertEquals(want, got)
        assertEquals(want.hashCode(), got.hashCode())
        fun keysOf(v: Any?, out: MutableList<Any?>) {
            when (v) {
                is Map<*, *> -> for ((k, x) in v) { out.add(k); keysOf(x, out) }
                is List<*> -> for (x in v) keysOf(x, out)
                else -> out.add(v?.javaClass?.simpleName)
            }
        }
        val a = ArrayList<Any?>(); val b = ArrayList<Any?>()
        keysOf(want, a); keysOf(got, b)
        assertEquals("the order of every key and the type of every value", a, b)
    }
}

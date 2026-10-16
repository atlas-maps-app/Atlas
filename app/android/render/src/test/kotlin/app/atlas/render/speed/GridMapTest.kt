package app.atlas.render.speed

import app.atlas.render.py.GridMap
import app.atlas.render.py.GridSet
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.SplittableRandom

/** GridMap and GridSet against HashMap and HashSet on grid keys, and the spread of their hash over a city's cells. */
class GridMapTest {
    @Test
    fun `grid map gives what a hash map gives, in insertion order`() {
        val rnd = SplittableRandom(3)
        val ref = LinkedHashMap<Long, MutableList<Int>>()
        val g = GridMap<MutableList<Int>>()
        for (n in 0 until 200_000) {
            val i = rnd.nextLong(-300, 300); val j = rnd.nextLong(-300, 300)
            val k = GridMap.key(i, j)
            ref.getOrPut(k) { ArrayList() }.add(n)
            g.getOrPut(k) { ArrayList() }.add(n)
        }
        assertEquals(ref.size, g.size)
        for (i in -310L..310L) for (j in -310L..310L) {
            val k = GridMap.key(i, j)
            assertEquals(ref[k], g[k]); assertEquals(k in ref, k in g)
        }
        val order = ArrayList<Long>(); g.forEach { k, v -> order.add(k); assertEquals(ref[k], v); assertSame(g[k], v) }
        assertEquals(ref.keys.toList(), order)
        // set replaces in place and keeps the order
        val first = order[0]
        val repl = mutableListOf(-1); g[first] = repl
        assertSame(repl, g[first]); assertEquals(ref.size, g.size); assertEquals(first, g.keyAt(0))
        assertNull(GridMap<String>()[GridMap.key(0L, 0L)])
    }

    @Test
    fun `keys pack as the port always packed them`() {
        for (i in listOf(-70000L, -1L, 0L, 1L, 5383L, 70000L)) for (j in listOf(-70000L, -1L, 0L, 1L, 8414L, 70000L)) {
            val k = GridMap.key(i, j)
            assertEquals((i shl 32) xor (j and 0xffffffffL), k)
            assertEquals(k, GridMap.key(i.toInt(), j.toInt()))
            assertEquals(i.toInt(), (k shr 32).toInt()); assertEquals(j.toInt(), k.toInt())
        }
    }

    @Test
    fun `grid set gives what a hash set gives`() {
        val rnd = SplittableRandom(5)
        val ref = HashSet<Long>(); val s = GridSet()
        for (n in 0 until 100_000) {
            val k = GridMap.key(rnd.nextInt(0, 400), rnd.nextInt(0, 400))
            assertEquals(ref.add(k), s.add(k))
        }
        assertEquals(ref.size, s.size)
        for (i in -2..402) for (j in -2..402) GridMap.key(i, j).let { assertEquals(it in ref, it in s) }
        assertFalse(GridMap.key(1, 1) in GridSet())
    }

    /** The reason for the map: Long.hashCode of a packed key is `i xor j`, which a 300 × 300 grid fills with only 512 values. */
    @Test
    fun `the mixed hash spreads a city's cells`() {
        val boxed = HashSet<Int>(); val mixed = HashSet<Int>()
        for (i in -150L until 150L) for (j in -150L until 150L) {
            val k = GridMap.key(i, j)
            boxed.add(java.lang.Long.hashCode(k)); mixed.add(GridMap.mix(k) and ((1 shl 17) - 1))
        }
        assertTrue("Long.hashCode gives ${boxed.size} values", boxed.size <= 512)
        assertTrue("the mixed hash gives ${mixed.size} of 90 000 low-17-bit slots", mixed.size > 50_000)
    }
}

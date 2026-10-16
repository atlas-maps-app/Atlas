package app.atlas.render.py

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random

/**
 * CompactDict keeps the Map contract as LinkedHashMap does in insertion order: random operation streams are replayed on both and
 * must agree on every answer, every order and every equals and hashCode, across the scan, byte-index and int-index sizes.
 */
class CompactDictTest {
    private fun <K, V> assertSameMap(want: LinkedHashMap<K, V>, got: CompactDict<K, V>, ctx: String) {
        assertEquals("$ctx size", want.size, got.size)
        assertEquals("$ctx keys", want.keys.toList(), got.keys.toList())
        assertEquals("$ctx values", want.values.toList(), got.values.toList())
        assertEquals("$ctx entries", want.entries.map { it.key to it.value }, got.entries.map { it.key to it.value })
        assertTrue("$ctx equals", got == want && want == got)
        assertEquals("$ctx hashCode", want.hashCode(), got.hashCode())
        assertEquals("$ctx toString", want.toString(), got.toString())
    }

    @Test
    fun `order after remove and reinsert`() {
        val d = dictOf("a" to 1, "b" to 2, "c" to 3)
        d.remove("a")
        d["a"] = 4
        d["b"] = 5 // an existing key keeps its place
        assertEquals(listOf("b", "c", "a"), d.keys.toList())
        assertEquals(listOf(5, 3, 4), d.values.toList())
        val m = linkedMapOf<String, Any?>("a" to 1, "b" to 2, "c" to 3).also { it.remove("a"); it["a"] = 4; it["b"] = 5 }
        assertEquals(m.toString(), d.toString())
    }

    @Test
    fun `setValue on an entry writes through`() {
        val d = dictOf("x" to 1.0, "y" to 2.0, "z" to null)
        for (e in d.entries) if (e.key == "y") assertEquals(2.0, e.setValue(9.0))
        assertEquals(9.0, d["y"])
        assertEquals(listOf("x", "y", "z"), d.keys.toList())
        val e = d.entries.first { it.key == "z" }
        assertNull(e.setValue("now"))
        assertEquals("now", d["z"])
        // a detached entry keeps its last value, as a HashMap node does
        val x = d.entries.first()
        d.remove("x")
        assertEquals(1.0, x.value)
        assertEquals(1.0, x.setValue(3.0))
        assertEquals(3.0, x.value)
        assertFalse("x" in d)
    }

    @Test
    fun `remove through the iterators`() {
        val d = CompactDict<String, Int>()
        for (i in 0 until 40) d["k$i"] = i
        val it = d.entries.iterator()
        while (it.hasNext()) if (it.next().value % 3 == 0) it.remove()
        assertEquals((0 until 40).filter { it % 3 != 0 }.map { "k$it" }, d.keys.toList())
        val k = d.keys.iterator()
        while (k.hasNext()) if (k.next().endsWith("1")) k.remove()
        val v = d.values.iterator()
        while (v.hasNext()) if (v.next() > 30) v.remove()
        val want = (0 until 40).filter { it % 3 != 0 && !"k$it".endsWith("1") && it <= 30 }
        assertEquals(want, d.values.toList())
        // removing every entry, the last first or not, leaves an empty map that fills again
        val all = d.keys.iterator()
        while (all.hasNext()) { all.next(); all.remove() }
        assertTrue(d.isEmpty())
        d["again"] = 1
        assertEquals(mapOf("again" to 1), d)
    }

    @Test(expected = ConcurrentModificationException::class)
    fun `iterators are fail-fast`() {
        val d = dictOf("a" to 1, "b" to 2)
        for (k in d.keys) d["c"] = 3
    }

    @Test(expected = IllegalStateException::class)
    fun `iterator remove needs next first`() {
        dictOf("a" to 1).entries.iterator().remove()
    }

    @Test
    fun `replacing a value while iterating is allowed`() {
        val d = dictOf("a" to 1, "b" to 2, "c" to 3)
        for (k in d.keys) d[k] = (d[k] as Int) * 10
        assertEquals(mapOf("a" to 10, "b" to 20, "c" to 30), d)
    }

    @Test
    fun `equals and hashCode against LinkedHashMap`() {
        val d = dictOf("name" to "Westerkerk", "h" to 85.0, "tags" to dictOf("building" to "church"), "none" to null)
        val m = linkedMapOf<String, Any?>("name" to "Westerkerk", "h" to 85.0, "tags" to linkedMapOf("building" to "church"), "none" to null)
        assertEquals(m, d)
        assertEquals(d, m)
        assertEquals(m.hashCode(), d.hashCode())
        assertEquals(m.toString(), d.toString())
        // order does not matter to equals, as for any Map
        val r = dictOf("none" to null, "tags" to dictOf("building" to "church"), "h" to 85.0, "name" to "Westerkerk")
        assertEquals(d, r)
        assertEquals(d.hashCode(), r.hashCode())
        assertNotEquals(d, dictOf("name" to "Westerkerk", "h" to 85.0, "tags" to dictOf("building" to "church")))
        assertNotEquals(d, dictOf("name" to "Westerkerk", "h" to 85.0, "tags" to dictOf("building" to "church"), "other" to null))
        assertNotEquals(dictOf("a" to null), dictOf("b" to null))
        // a null value against a missing key, and maps of other key types
        assertNotEquals(dictOf("a" to null) as Any, mapOf(1 to null))
        assertTrue(dictOf().isEmpty() && dictOf() == emptyMap<String, Any?>())
        assertEquals(m.keys, d.keys)
        assertEquals(m.entries, d.entries)
        assertEquals(m.keys.hashCode(), d.keys.hashCode())
        assertEquals(m.entries.hashCode(), d.entries.hashCode())
    }

    @Test
    fun `null keys, null values and views`() {
        val d = CompactDict<String?, String?>()
        d[null] = "n"
        d["a"] = null
        assertEquals("n", d[null])
        assertTrue(d.containsKey("a") && d["a"] == null && d.containsValue(null) && d.containsValue("n"))
        assertEquals("dflt", d.getOrDefault("b", "dflt"))
        assertNull(d.getOrDefault("a", "dflt"))
        assertTrue(d.keys.remove(null))
        assertFalse(d.keys.remove(null))
        assertTrue(d.entries.contains(java.util.AbstractMap.SimpleEntry<String?, String?>("a", null)))
        assertTrue(d.entries.remove(java.util.AbstractMap.SimpleEntry<String?, String?>("a", null)))
        assertTrue(d.isEmpty())
        // Java's default methods work through get and put
        val j = CompactDict<String, Int>()
        assertEquals(1, j.computeIfAbsent("x") { 1 })
        assertEquals(3, j.merge("x", 2, Integer::sum))
        j.putIfAbsent("y", 7)
        j.replaceAll { _, v -> v + 1 }
        assertEquals(mapOf("x" to 4, "y" to 8), j)
        assertEquals(listOf("x", "y"), j.keys.toList())
    }

    @Test
    fun `a self reference prints as LinkedHashMap's does`() {
        val d = CompactDict<String, Any?>()
        d["me"] = d
        val m = LinkedHashMap<String, Any?>()
        m["me"] = m
        assertEquals(m.toString(), d.toString())
    }

    @Test
    fun `random operation streams agree with LinkedHashMap at every size`() {
        val rnd = Random(20261016)
        for (round in 0 until 400) {
            val keys = 1 + rnd.nextInt(if (round % 4 == 0) 600 else 40) // scan, byte index and int index sizes
            val m = LinkedHashMap<String, Int?>()
            val d = if (rnd.nextBoolean()) CompactDict<String, Int?>() else CompactDict(rnd.nextInt(keys + 1))
            repeat(2000) { step ->
                val k = "k" + rnd.nextInt(keys)
                when (rnd.nextInt(10)) {
                    0, 1, 2, 3 -> assertEquals(m.put(k, step), d.put(k, step))
                    4 -> assertEquals(m.remove(k), d.remove(k))
                    5 -> assertEquals(m[k], d[k])
                    6 -> assertEquals(m.containsKey(k), d.containsKey(k))
                    7 -> if (rnd.nextInt(50) == 0) { m.clear(); d.clear() } else { m[k] = null; d[k] = null }
                    8 -> {
                        val cut = rnd.nextInt(5)
                        val a = m.entries.iterator(); while (a.hasNext()) if ((a.next().value ?: 0) % 5 == cut) a.remove()
                        val b = d.entries.iterator(); while (b.hasNext()) if ((b.next().value ?: 0) % 5 == cut) b.remove()
                    }
                    9 -> {
                        val o = linkedMapOf("k${rnd.nextInt(keys)}" to step, "k${rnd.nextInt(keys)}" to -step)
                        m.putAll(o); d.putAll(if (rnd.nextBoolean()) CompactDict(o) else o)
                    }
                }
            }
            assertSameMap(m, d, "round $round")
            assertSameMap(LinkedHashMap(m), CompactDict(d), "round $round copy")
        }
    }

    @Test
    fun `keys whose hashes collide`() {
        // (i shl 32) xor j hashes to i xor j: many keys, few hashes
        val m = LinkedHashMap<Long, Int>()
        val d = CompactDict<Long, Int>()
        var n = 0
        for (i in 0L until 60L) for (j in 0L until 60L) { val k = (i shl 32) xor j; m[k] = n; d[k] = n; n++ }
        for (i in 0L until 60L step 3) for (j in 0L until 60L step 2) { val k = (i shl 32) xor j; assertEquals(m.remove(k), d.remove(k)) }
        for (i in 0L until 60L) for (j in 0L until 60L) { val k = (i shl 32) xor j; assertEquals(m[k], d[k]) }
        assertSameMap(m, d, "collisions")
    }

    @Test
    fun `strings whose hashes collide, scanned and indexed`() {
        // "Aa" and "BB" share a hash, so every word of n such pairs has the same hash as all 2^n of them
        val words = (0 until 512).map { i -> (0 until 9).joinToString("") { b -> if (i shr b and 1 == 0) "Aa" else "BB" } }
        assertEquals(1, words.map { it.hashCode() }.toSet().size)
        for (n in listOf(3, 8, 9, 200, 512)) {
            val m = LinkedHashMap<String, Int>(); val d = CompactDict<String, Int>()
            for ((i, w) in words.take(n).withIndex()) { m[w] = i; d[String(w.toCharArray())] = i } // equal keys, other objects
            for (w in words.take(n).filterIndexed { i, _ -> i % 3 == 0 }) assertEquals(m.remove(w), d.remove(String(w.toCharArray())))
            for (w in words) { assertEquals(m[w], d[w]); assertEquals(m.containsKey(w), d.containsKey(w)) }
            assertSameMap(m, d, "$n colliding words")
        }
    }

    @Test
    fun `keys whose hash tags all match, at every index width`() {
        // Integers i * 256 hash to multiples of 256, so every key has the same eight-bit tag and each probe compares keys; above 127
        // each boxing is a new object, so lookups meet equal keys that are not the same object
        for (n in listOf(9, 200, 255, 256, 300, 3000)) {
            val m = LinkedHashMap<Int, Int>(); val d = CompactDict<Int, Int>()
            for (i in 0 until n) { m[i * 256] = i; d[i * 256] = i }
            for (i in 0 until n step 3) assertEquals(m.remove(i * 256), d.remove(i * 256))
            for (i in -5 until n + 5) {
                assertEquals(m[i * 256], d[i * 256]); assertEquals(m[i * 256 + 1], d[i * 256 + 1]); assertEquals(m.containsKey(i * 256), d.containsKey(i * 256))
            }
            for (i in 0 until n step 3) { m[i * 256] = -i; d[i * 256] = -i } // back in, last
            assertSameMap(m, d, "$n keys of one tag")
        }
    }

    @Test
    fun `capacities at the edges of the index widths`() {
        for (cap in listOf(0, 1, 8, 9, 254, 255, 256, 257)) {
            val m = LinkedHashMap<String, Int>(); val d = CompactDict<String, Int>(cap)
            for (i in 0 until cap + 300) {
                m["k$i"] = i; d["k$i"] = i
                if (i % 7 == 3) assertEquals(m.remove("k${i / 2}"), d.remove("k${i / 2}"))
                if (i == cap) assertSameMap(m, d, "capacity $cap, full")
            }
            assertSameMap(m, d, "capacity $cap, grown")
        }
        assertThrows(IllegalArgumentException::class.java) { CompactDict<String, Int>(-1) }
        assertThrows(IllegalArgumentException::class.java) { CompactDict<String, Int>(0x1000000) }
    }

    @Test
    fun `null keys in an indexed map`() {
        val m = LinkedHashMap<String?, Int?>(); val d = CompactDict<String?, Int?>()
        for (i in 0 until 40) { m["k$i"] = i; d["k$i"] = i }
        m[null] = -1; d[null] = -1
        assertEquals(-1, d[null]); assertTrue(d.containsKey(null))
        m[null] = null; d[null] = null
        assertTrue(d.containsKey(null) && d[null] == null)
        assertEquals(m.remove(null), d.remove(null))
        assertFalse(d.containsKey(null))
        assertSameMap(m, d, "an indexed map without its null key")
    }

    @Test
    fun `bulk changes through the views`() {
        val m = LinkedHashMap<String, Int?>(); val d = CompactDict<String, Int?>()
        for (i in 0 until 30) { m["k$i"] = i % 7; d["k$i"] = i % 7 }
        m["n"] = null; d["n"] = null
        assertEquals(m.keys.removeAll(setOf("k1", "k2", "zz")), d.keys.removeAll(setOf("k1", "k2", "zz")))
        assertEquals(m.values.removeAll(setOf(3)), d.values.removeAll(setOf(3)))
        assertEquals(m.values.remove(null), d.values.remove(null))
        assertEquals(m.keys.retainAll((0 until 30 step 2).map { "k$it" }.toSet()), d.keys.retainAll((0 until 30 step 2).map { "k$it" }.toSet()))
        assertEquals(m.entries.removeIf { it.value == 0 }, d.entries.removeIf { it.value == 0 })
        assertSameMap(m, d, "after bulk changes")
        d.clear()
        assertTrue(d.isEmpty() && !d.keys.iterator().hasNext() && d.toString() == "{}")
        d["after"] = 1
        assertEquals(listOf("after"), d.keys.toList())
    }

    @Test
    fun `dictOf, copyDict and emptyDict are compact and ordered`() {
        val d = dictOf("poly" to listOf(1), "h" to 3.0, "h" to 4.0)
        assertTrue(d is CompactDict<*, *>)
        assertEquals(linkedMapOf("poly" to listOf(1), "h" to 4.0), d)
        val c = d.copyDict()
        assertTrue(c is CompactDict<*, *>)
        c["new"] = 1
        assertFalse("new" in d)
        assertEquals(listOf("poly", "h", "new"), c.keys.toList())
        assertSame(d["poly"], c["poly"])
        val e = emptyDict(3)
        assertTrue(e is CompactDict<*, *> && e.isEmpty())
        val lhm = linkedMapOf<String, Any?>("b" to 1, "a" to 2)
        assertEquals(listOf("b", "a"), lhm.copyDict().keys.toList())
    }
}

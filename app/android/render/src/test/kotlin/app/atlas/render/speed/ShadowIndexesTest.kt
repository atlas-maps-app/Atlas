package app.atlas.render.speed

import app.atlas.render.Amsterdam
import app.atlas.render.Oracle
import app.atlas.render.Real
import app.atlas.render.V3
import app.atlas.render.py.Dict
import app.atlas.render.py.truthy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Test
import java.io.File
import java.util.SplittableRandom

/**
 * The shadows' grid kept per scene: the same index for every sun while the solids are unchanged, a new one when a solid, its
 * footprint, its height or its box changes; sunlight read through a kept index equals sunlight through a fresh one; and the
 * index's flat footprint test answers as pip does.
 */
class ShadowIndexesTest {
    @Suppress("UNCHECKED_CAST")
    private fun solidsOf(S: Dict): List<Dict> =
        (S["buildings"] as List<Dict>).filter { !truthy(it["hidden"]) && truthy(it["h"]) } + (S["parts"] as List<Dict>).filter { truthy(it["h"]) }

    private fun gridOf(S: Dict): Any {
        // the Grid is private to Shade's construction: reach it through the kept index's identity, as a frame would
        val sh = Amsterdam.Shade.of(S, solidsOf(S), V3(0.3, 0.6, 0.2))
        return sh.shadows.index
    }

    @Test
    fun `a scene keeps its grid for every sun until its solids change`() {
        Real.ShadowIndexes.clear()
        Amsterdam.amsRaw = File(Oracle.repo, "data/ams/raw.json")
        val S = Amsterdam.load()
        val a = gridOf(S)
        assertSame("same scene, same solids", a, gridOf(S))
        assertSame("another sun", a, Amsterdam.Shade.of(S, solidsOf(S), V3(-0.5, 0.2, 0.7)).shadows.index)
        val b0 = solidsOf(S)[10]
        b0["poly"] = ArrayList(Amsterdam.polyOf(b0)) // a new footprint (equal, but not the same object)
        val b = gridOf(S); assertNotSame("footprint replaced", a, b)
        assertSame(b, gridOf(S))
        b0["h"] = (b0["h"] as Number).toDouble() + 1.0
        val c = gridOf(S); assertNotSame("height changed", b, c)
        b0["bb"] = doubleArrayOf(0.0, 0.0, 0.0, 0.0)
        val d = gridOf(S); assertNotSame("box rewritten", c, d)
        solidsOf(S)[20]["hidden"] = true
        val e = gridOf(S); assertNotSame("a solid fewer", d, e)
        val S2 = Amsterdam.load()
        assertNotSame("another scene", e, gridOf(S2))
        assertSame("both scenes kept", e, gridOf(S))
        Real.ShadowIndexes.clear()
        assertNotSame("cleared", e, gridOf(S))
    }

    @Test
    fun `the flat footprint test answers as pip does`() {
        Amsterdam.amsRaw = File(Oracle.repo, "data/ams/raw.json")
        val S = Amsterdam.load()
        val rnd = SplittableRandom(4)
        var n = 0; var inside = 0
        for (b in solidsOf(S)) {
            val poly = Amsterdam.polyOf(b)
            val xz = DoubleArray(2 * poly.size).also { a -> poly.forEachIndexed { i, p -> a[2 * i] = p.x; a[2 * i + 1] = p.y } }
            fun same(x: Double, z: Double) {
                val want = Real.pip(x, z, poly)
                assertEquals("pip($x, $z) on ${b["id"]}", want, Real.pipXZ(x, z, xz)); n++; if (want) inside++
            }
            for (p in poly) {
                same(p.x, p.y) // on a corner
                same(p.x + rnd.nextDouble(-4.0, 4.0), p.y + rnd.nextDouble(-4.0, 4.0))
            }
            for (i in poly.indices) { val a = poly[i]; val c = poly[(i + 1) % poly.size]; same((a.x + c.x) / 2, (a.y + c.y) / 2) } // on a side
        }
        assertEquals(false, Real.pipXZ(0.0, 0.0, DoubleArray(0)))
        println("pipXZ: $n points, $inside inside, all as pip")
    }

    @Test
    fun `sunlight through a kept grid equals sunlight through a fresh one`() {
        Real.ShadowIndexes.clear()
        Amsterdam.amsRaw = File(Oracle.repo, "data/ams/raw.json")
        val S = Amsterdam.load()
        val solids = solidsOf(S)
        val rnd = SplittableRandom(1)
        for (L in listOf(V3(0.3, 0.6, 0.2), V3(-0.62, 0.35, 0.7), V3(0.0, -1.0, 0.0))) {
            val kept = Amsterdam.Shade.of(S, solids, L); Amsterdam.Shade.of(S, solids, L)
            val fresh = Amsterdam.Shade(solids, L)
            repeat(20_000) {
                val b = solids[rnd.nextInt(solids.size)]; val p = Amsterdam.polyOf(b)[0]
                val q = V3(p.x + rnd.nextDouble(-3.0, 3.0), rnd.nextDouble(0.0, 25.0), p.y + rnd.nextDouble(-3.0, 3.0))
                assertEquals(fresh.lit(q, b), kept.lit(q, b)); assertEquals(fresh.lit(q), kept.lit(q))
                assertEquals(fresh.shadowHeight(q.x, q.z, b), kept.shadowHeight(q.x, q.z, b), 0.0)
            }
        }
    }
}

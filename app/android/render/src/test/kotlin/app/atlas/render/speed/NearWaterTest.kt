package app.atlas.render.speed

import app.atlas.render.Amsterdam
import app.atlas.render.Oracle
import app.atlas.render.Regions
import app.atlas.render.V2
import app.atlas.render.py.Dict
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.SplittableRandom

/**
 * near_water through the edge grid (an [Amsterdam.EdgeList] from waterEdges) answers as the linear scan over the same edges does:
 * for every building, part and tree of the Westerkerk extract and of grachten (where it is local), at the distances the render
 * asks, and for random points and boxes around the water.
 */
class NearWaterTest {
    @Suppress("UNCHECKED_CAST")
    private fun check(name: String, S: Dict) {
        val edges = Amsterdam.waterEdges(S)
        assertTrue("$name: waterEdges gives an EdgeList", edges is Amsterdam.EdgeList)
        val linear = ArrayList(edges)
        assertTrue("$name has water edges", linear.size > 100)
        var n = 0; var near = 0
        fun same(pts: List<V2>, dd: Double) {
            val want = Amsterdam.nearWater(linear, pts, dd)
            assertEquals("$name near_water(${pts.take(2)}…, $dd)", want, Amsterdam.nearWater(edges, pts, dd))
            n++; if (want) near++
        }
        for (b in (S["buildings"] as List<Dict>) + (S["parts"] as List<Dict>)) {
            val poly = Amsterdam.polyOf(b)
            if (poly.isNotEmpty()) for (dd in doubleArrayOf(16.0, 0.5, 60.0)) same(poly, dd)
        }
        for (t in S["trees"] as List<Dict>) same(listOf(V2((t["x"] as Number).toDouble(), (t["z"] as Number).toDouble())), 9.0)
        val rnd = SplittableRandom(11)
        val xs = linear.map { it.a.x }; val zs = linear.map { it.a.y }
        val x0 = xs.min() - 200; val x1 = xs.max() + 200; val z0 = zs.min() - 200; val z1 = zs.max() + 200
        repeat(50_000) {
            val p = V2(rnd.nextDouble(x0, x1), rnd.nextDouble(z0, z1))
            same(listOf(p), rnd.nextDouble(0.0, 40.0))
            if (it % 5 == 0) same(listOf(p, V2(p.x + rnd.nextDouble(-90.0, 90.0), p.y + rnd.nextDouble(-90.0, 90.0))), 12.0)
        }
        // an edge on the points themselves, and an edge whose box covers many cells (kept apart from the grid)
        val e = linear[0]
        same(listOf(e.a), 0.0); same(listOf(e.a), 1e-9); same(listOf(V2(e.a.x + 3000.0, e.a.y)), 3001.0)
        println("near_water $name: $n questions, $near near, grid and scan agree")
    }

    @Test
    fun `the grid answers as the scan on the Westerkerk extract`() {
        Amsterdam.amsRaw = File(Oracle.repo, "data/ams/raw.json")
        check("westerkerk", Amsterdam.load())
    }

    @Test
    fun `the grid answers as the scan on grachten`() {
        Regions.root = File(Oracle.repo, "data/regions")
        Regions.parkRoot = File(Oracle.repo, "data/park")
        Regions.streetCfgDir = File(Oracle.repo, "cache/render")
        org.junit.Assume.assumeTrue("the grachten extract is local only", File(Regions.root, "grachten/raw.json").exists())
        check("grachten", Regions.load("grachten_home"))
    }

    @Test
    fun `an edge list changed after its grid was built is indexed again`() {
        val list = Amsterdam.EdgeList()
        list.add(Amsterdam.Edge(V2(0.0, 0.0), V2(10.0, 0.0), 0.0, 1.0, "r"))
        val far = listOf(V2(500.0, 500.0))
        assertEquals(false, Amsterdam.nearWater(list, far, 5.0))
        list.add(Amsterdam.Edge(V2(498.0, 500.0), V2(510.0, 500.0), 0.0, 1.0, "r"))
        assertEquals(true, Amsterdam.nearWater(list, far, 5.0))
        list.removeAt(1)
        assertEquals(false, Amsterdam.nearWater(list, far, 5.0))
        // an edge replaced in place (ArrayList.set keeps modCount) is seen too
        list[0] = Amsterdam.Edge(V2(499.0, 499.0), V2(501.0, 499.0), 0.0, 1.0, "r")
        assertEquals(true, Amsterdam.nearWater(list, far, 5.0))
        list[0] = Amsterdam.Edge(V2(0.0, 0.0), V2(10.0, 0.0), 0.0, 1.0, "r")
        assertEquals(false, Amsterdam.nearWater(list, far, 5.0))
        // a box over more cells than there are edges (an infinite point, a huge distance) reads every edge, at once
        val inf = Double.POSITIVE_INFINITY
        for ((pts, dd) in listOf(listOf(V2(inf, 0.0)) to 5.0, listOf(V2(-inf, 0.0), V2(5.0, 1.0)) to 2.0, far to 1e7, far to inf,
            listOf(V2(5.0, inf)) to inf, far to -1.0, far to Double.NaN)) {
            assertEquals("$pts $dd", Amsterdam.nearWater(ArrayList(list), pts, dd), Amsterdam.nearWater(list, pts, dd))
        }
        assertEquals(true, Amsterdam.nearWater(list, listOf(V2(-inf, 0.0), V2(5.0, 1.0)), 2.0))
        assertEquals(true, Amsterdam.nearWater(list, far, 1e7))
        // a long edge (more cells than the grid keeps) and a NaN point fall back as the scan does
        list.add(Amsterdam.Edge(V2(-5000.0, -5000.0), V2(5000.0, 5000.0), 0.0, 1.0, "r"))
        assertEquals(Amsterdam.nearWater(ArrayList(list), far, 5.0), Amsterdam.nearWater(list, far, 5.0))
        assertEquals(Amsterdam.nearWater(ArrayList(list), listOf(V2(Double.NaN, 1.0)), 5.0), Amsterdam.nearWater(list, listOf(V2(Double.NaN, 1.0)), 5.0))
    }
}

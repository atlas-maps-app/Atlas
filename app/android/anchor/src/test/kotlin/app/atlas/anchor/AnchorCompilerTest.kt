package app.atlas.anchor

import app.atlas.mapview.svg.SvgParser
import app.atlas.mapview.svg.SvgPathData
import app.atlas.mapview.svg.SvgShape
import app.atlas.render.live.LiveView
import app.atlas.render.live.Moment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.awt.Shape
import java.awt.geom.AffineTransform
import java.awt.geom.Area
import java.awt.geom.Ellipse2D
import java.awt.geom.Path2D
import java.awt.geom.PathIterator
import java.awt.geom.Rectangle2D
import java.util.Locale
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max

/**
 * The anchor compiler on the real renders (plan-gpu G1): the Westerkerk home at its four moments from the app's
 * bundled pack, and the Main board (grachten_home) where its pack is on the machine. Every vertex lands on its SVG
 * coordinate at the anchor's camera; ≥ 99 % of path vertices are taped and no owned element has ground points; each
 * fill's faces cover exactly its path's area; every item has an owner and a reference point; billboards follow their
 * crowns and masts; compiles are fast and anchors small.
 */
class AnchorCompilerTest {
    companion object {
        /** The largest distance, CSS px, between a point's position at the anchor's camera (with its residual) and its SVG coordinate. */
        const val LANDING = 0.0005

        /** A slower machine (CI) gets this much more time for the speed gates, which then only catch regressions. */
        val SLACK: Double = if (System.getenv("CI") == "true") 3.0 else 1.0

        fun fmt(v: Double) = String.format(Locale.ROOT, "%.4f", v)

        /** The worst landing of [a]'s points against their SVG coordinates [svg]. */
        fun landing(a: Anchor, svg: DoubleArray): Double {
            val cam = a.head.camera
            var worst = 0.0
            for (i in 0 until a.points.size) {
                worst = max(worst, abs(a.points.screenX(cam, i) - svg[i * 2]))
                worst = max(worst, abs(a.points.screenY(cam, i) - svg[i * 2 + 1]))
            }
            return worst
        }

        /** The path of [s] under [ctm], as Java2D reads it. */
        fun shapeOf(doc: app.atlas.mapview.svg.SvgDocument, s: SvgShape, ctm: FloatArray?): Shape {
            val g = s.geometry
            val raw: Shape = when (s.kind) {
                SvgShape.Kind.PATH -> {
                    val p = Path2D.Double(if (s.style.evenOdd) Path2D.WIND_EVEN_ODD else Path2D.WIND_NON_ZERO)
                    val verbs = doc.path.verbs; val xy = doc.path.coords
                    var k = s.coordStart
                    var open = false
                    for (v in s.verbStart until s.verbEnd) {
                        when (verbs[v]) {
                            SvgPathData.MOVE -> { p.moveTo(xy[k].toDouble(), xy[k + 1].toDouble()); k += 2; open = true }
                            SvgPathData.LINE -> {
                                if (!open) { val c = p.currentPoint; p.moveTo(c.x, c.y); open = true }
                                p.lineTo(xy[k].toDouble(), xy[k + 1].toDouble()); k += 2
                            }
                            else -> { p.closePath(); open = false }
                        }
                    }
                    p
                }
                SvgShape.Kind.RECT -> Rectangle2D.Double(g[0].toDouble(), g[1].toDouble(), g[2].toDouble(), g[3].toDouble())
                else -> Ellipse2D.Double(0.0, 0.0, 0.0, 0.0)
            }
            if (ctm == null) return raw
            return AffineTransform(ctm[0].toDouble(), ctm[1].toDouble(), ctm[2].toDouble(), ctm[3].toDouble(), ctm[4].toDouble(), ctm[5].toDouble()).createTransformedShape(raw)
        }

        /** The area a shape fills under its winding rule (Java2D's Area of it). */
        fun filledArea(shape: Shape): Double {
            val it = Area(shape).getPathIterator(null)
            val c = DoubleArray(6)
            var total = 0.0; var x0 = 0.0; var y0 = 0.0; var px = 0.0; var py = 0.0
            while (!it.isDone) {
                when (it.currentSegment(c)) {
                    PathIterator.SEG_MOVETO -> { x0 = c[0]; y0 = c[1]; px = x0; py = y0 }
                    PathIterator.SEG_LINETO -> { total += px * c[1] - c[0] * py; px = c[0]; py = c[1] }
                    PathIterator.SEG_CLOSE -> { total += px * y0 - x0 * py; px = x0; py = y0 }
                    else -> error("curves in an engine path")
                }
                it.next()
            }
            return abs(total) / 2
        }

        /**
         * The area [a]'s primitives [first] until [last] cover together (the union of their faces and triangles, as drawn),
         * from their points' SVG coordinates [svg]; null when one goes through the stencil.
         */
        fun primArea(a: Anchor, svg: DoubleArray, first: Int, last: Int): Double? {
            val union = Area()
            val pr = a.prims
            fun poly(ps: IntArray) {
                val p = Path2D.Double()
                p.moveTo(svg[ps[0] * 2], svg[ps[0] * 2 + 1])
                for (k in 1 until ps.size) p.lineTo(svg[ps[k] * 2], svg[ps[k] * 2 + 1])
                p.closePath()
                union.add(Area(p))
            }
            for (i in first until last) {
                when (pr.kind[i]) {
                    PrimKind.FACE -> poly(IntArray(pr.corners(i)) { pr.corner(i, it) })
                    PrimKind.TRI -> {
                        val f = pr.int(i, 0); val c = pr.int(i, 1)
                        var t = 0
                        while (t < c) { poly(intArrayOf(a.triangles[f + t], a.triangles[f + t + 1], a.triangles[f + t + 2])); t += 3 }
                    }
                    else -> return null
                }
            }
            return filledArea(union)
        }
    }

    /** What one render's gates found. */
    private class Gates(val name: String, val report: CompileReport, val anchor: Anchor) {
        var landing = 0.0
        var fillsChecked = 0
        var stencilled = 0
        var worstArea = 0.0
        var worstAreaAt = ""
    }

    private fun gates(name: String, made: AnchorCases.Made): Gates {
        val r = CompileReport().also { it.keepSvg = true; it.keepFills = true }
        val a = AnchorCases.anchor(made, r)
        val g = Gates(name, r, a)
        val svg = r.svg!!
        // landing: at the anchor's camera every vertex, billboards included, lands on its SVG coordinate
        g.landing = landing(a, svg)
        assertTrue("$name: every vertex lands within $LANDING px (worst ${g.landing})", g.landing <= LANDING + 1e-9)
        assertEquals("$name: no residual overflows", 0, r.residualOverflow)
        // lift classes
        val pv = r.pathVertices.sum()
        val taped = r.pathVertices[LiftClass.TAPED.toInt()].toDouble() / pv
        assertTrue("$name: ≥ 99 % of path vertices taped (${fmt(taped * 100)} %)", taped >= 0.99)
        assertEquals("$name: no ground points on owned elements", 0, r.groundOnOwners)
        for (c in 0 until LiftClass.COUNT) assertEquals("$name: the head counts ${LiftClass.NAMES[c]}", a.points.lift.count { it.toInt() == c }, a.head.liftCounts[c])
        // areas: each fill's faces and triangles cover its path's own area (Java2D's Area of it)
        val docs = listOf(made.source.ground) + made.source.objects
        for (f in r.fillRecords) {
            val got = primArea(a, svg, f.first, f.last)
            if (got == null) { g.stencilled++; continue }
            val want = filledArea(shapeOf(docs[f.doc], f.node, f.ctm))
            val err = abs(got - want)
            g.fillsChecked++
            // relative to the fill's area, with 0.001 px² for slivers that have next to none
            val rel = err / max(want, 1.0)
            if (rel > g.worstArea) { g.worstArea = rel; g.worstAreaAt = "fill of ${fmt(want)} px² drawn as ${fmt(got)}" }
        }
        assertTrue("$name: every fill's primitives cover its path's area within 0.1 % (worst ${fmt(g.worstArea * 100)} %: ${g.worstAreaAt})", g.worstArea <= 1e-3)
        // items: an owner and a reference point each, their primitives in painter's order
        val it = a.items
        for (i in 0 until it.size) {
            assertTrue("$name: item $i (${it.kinds[it.kind[i]]}) has an owner", it.ownerType[i] != OwnerType.NONE)
            assertFalse("$name: item $i has its sort point", it.sort[i * 3].isNaN() || it.sort[i * 3 + 2].isNaN())
            assertTrue("$name: item $i's primitives are a range of the drawn ones", it.first[i] <= it.last[i] && it.last[i] <= a.prims.drawn)
            if (i > 0) assertTrue("$name: items follow painter's order", it.first[i] >= it.last[i - 1])
            assertTrue("$name: item $i's depth is sorted far to near", i == 0 || it.depth[i] <= it.depth[i - 1])
        }
        for (p in 0 until a.prims.drawn) {
            val k = a.prims.item[p]
            if (k >= 0) assertTrue("$name: primitive $p lies in its item's range", p >= it.first[k] && p < it.last[k])
        }
        // at the anchor's camera no wall flips
        cullOnFlip(name, a)
        println("$name: ${r.summary()}")
        println("$name: landing ${g.landing} px; fills ${g.fillsChecked} checked (+${g.stencilled} stencilled), worst area ${fmt(g.worstArea * 100)} %")
        return g
    }

    /** At the anchor's camera, every cull-on-flip face winds as its flag says, with and without its residuals. */
    private fun cullOnFlip(name: String, a: Anchor) {
        val cam = a.head.camera
        val pr = a.prims
        var faces = 0
        for (i in 0 until pr.drawn) {
            if (pr.kind[i] != PrimKind.FACE || !pr.has(i, PrimFlag.CULL_ON_FLIP)) continue
            faces++
            val n = pr.corners(i)
            var s = 0.0; var t = 0.0
            for (k in 0 until n) {
                val p = pr.corner(i, k); val q = pr.corner(i, (k + 1) % n)
                s += a.points.screenX(cam, p) * a.points.screenY(cam, q) - a.points.screenX(cam, q) * a.points.screenY(cam, p)
                t += a.points.projectedX(cam, p) * a.points.projectedY(cam, q) - a.points.projectedX(cam, q) * a.points.projectedY(cam, p)
            }
            val positive = pr.has(i, PrimFlag.POSITIVE)
            assertEquals("$name: cull-on-flip face $i is not flipped at K", positive, s > 0)
            assertEquals("$name: cull-on-flip face $i is not flipped at K without its residuals", positive, t > 0)
        }
        assertTrue("$name: walls and roofs carry cull-on-flip ($faces)", faces > 100)
    }

    @Test
    fun `the Westerkerk home lands, lifts, fills and keeps its items at every moment`() {
        val region = AnchorCases.westerkerk()
        for (m in AnchorCases.MOMENTS) {
            val made = AnchorCases.render(region, AnchorCases.homeView(region), Moment.Named(m))
            val g = gates("Westerkerk $m", made)
            // the Westerkerk's own numbers (M6): the crown highlights and lamp heads are billboards
            if (m == "sunny") assertTrue("Westerkerk $m has billboards", g.report.centres[LiftClass.BILLBOARD.toInt()] > 100)
            if (m == "sunny") assertTrue("Westerkerk $m draws its canal names", g.report.tiles >= 4)
        }
    }

    @Test
    fun `the Main board grachten_home lands, lifts, fills and keeps its items`() {
        val region = AnchorCases.grachten()
        assumeTrue("the grachten pack is local only", region != null)
        val made = AnchorCases.render(region!!, AnchorCases.boardView(region, 915), Moment.Named("sunny"))
        gates("grachten_home sunny", made)
    }

    /** The view [v] turned [dyaw] degrees about its target and tilted [dpitch] degrees (map pitch). */
    private fun turned(v: LiveView, dyaw: Double, dpitch: Double): LiveView =
        LiveView(v.lat, v.lon, v.zoom, (v.bearing + dyaw + 360) % 360, v.pitch + dpitch, v.widthDp, v.heightDp, v.cyDp)

    @Test
    fun `billboards follow their crowns and masts after a 20 degree orbit and a 10 degree tilt`() {
        val region = AnchorCases.westerkerk()
        val home = AnchorCases.homeView(region)
        val k = AnchorCases.anchor(AnchorCases.render(region, home, Moment.Named("sunny")))
        for ((dy, dp) in listOf(20.0 to 0.0, 0.0 to 10.0, 20.0 to 10.0)) {
            val bv = turned(home, dy, dp)
            val b = AnchorCases.anchor(AnchorCases.render(region, bv, Moment.Named("sunny")))
            val (count, worst, kinds) = billboardDrift(k, b)
            println("billboards after yaw +$dy°, map pitch +$dp°: $count crown highlights and lamp heads compared ($kinds), worst drift ${fmt(worst)} CSS px")
            assertTrue("billboards compared after yaw +$dy°, pitch +$dp° ($count)", count > 100)
            assertTrue("crown highlights and lamp heads within 1 CSS px of the exact render's (worst ${fmt(worst)})", worst <= 1.0)
        }
    }

    /**
     * Matches items of [k] and [b] by their sort point, and compares where [k]'s points land at [b]'s camera (by the GPU's
     * rules) with where the exact render at [b] puts them, each relative to its item's first taped point. Gated: a lamp's
     * head (billboards, paired in drawing order, which a lamp keeps) and a tree's crown circles and highlights (paired by
     * their world point, as the engine sorts them by depth anew at every camera). Other billboards (bollards, trunks) are
     * reported. Returns how many were compared, the worst, and the item kinds compared.
     */
    private fun billboardDrift(k: Anchor, b: Anchor): Triple<Int, Double, Set<String>> {
        fun key(a: Anchor, i: Int) = Triple(a.items.sort[i * 3].toRawBits(), a.items.sort[i * 3 + 1].toRawBits(), a.items.sort[i * 3 + 2].toRawBits())
        val bItems = HashMap<Triple<Long, Long, Long>, Int>()
        for (i in 0 until b.items.size) bItems[key(b, i)] = i
        val proj = Coverage.Projection(k, b.head.camera)
        val kc = k.head.camera; val bc = b.head.camera
        val xy = DoubleArray(2); val rxy = DoubleArray(2)
        var count = 0; var worst = 0.0
        val kinds = HashSet<String>()
        val byKind = HashMap<String, ArrayList<Double>>()
        fun points(a: Anchor, i: Int): IntArray {
            val out = IntList()
            for (p in a.items.first[i] until a.items.last[i]) {
                val pr = a.prims
                when (pr.kind[p]) {
                    PrimKind.FACE -> for (c in 0 until pr.corners(p)) out.add(pr.corner(p, c))
                    PrimKind.ELL, PrimKind.JOIN -> out.add(pr.int(p, 0))
                    PrimKind.SEG -> { out.add(pr.int(p, 0)); out.add(pr.int(p, 1)) }
                    else -> Unit
                }
            }
            return out.toArray()
        }
        fun signature(a: Anchor, i: Int) = (a.items.first[i] until a.items.last[i]).map { a.prims.kind[it] }
        fun centres(a: Anchor, i: Int) = (a.items.first[i] until a.items.last[i]).filter { a.prims.kind[it] == PrimKind.ELL }.map { a.prims.int(it, 0) }
        fun world(a: Anchor, p: Int, out: DoubleArray) {
            val q = if (a.points.lift[p] == LiftClass.BILLBOARD) a.points.hang(p) else p
            a.head.camera.world(a.points.x(q).toDouble(), a.points.y(q).toDouble(), a.points.z(q).toDouble(), out)
        }
        val wk = DoubleArray(3); val wb = DoubleArray(3)
        fun drift(kp: Int, kref: Int, bp: Int, bref: Int): Double? {
            if (!proj.project(kp, xy) || !proj.project(kref, rxy)) return null
            val ex = b.points.screenX(bc, bp) - b.points.screenX(bc, bref); val ey = b.points.screenY(bc, bp) - b.points.screenY(bc, bref)
            return hypot(xy[0] - rxy[0] - ex, xy[1] - rxy[1] - ey)
        }
        for (i in 0 until k.items.size) {
            val j = bItems[key(k, i)] ?: continue
            val kind = k.items.kinds[k.items.kind[i]]
            val pk = points(k, i); val pb = points(b, j)
            val same = signature(k, i) == signature(b, j) && pk.size == pb.size
            val ref = pk.indexOfFirst { k.points.lift[it] == LiftClass.TAPED }
            if (ref < 0) continue
            if (kind == "tree") {
                // crown circles and highlights: paired by world point; the reference is the crown point nearest the trunk
                val ck = centres(k, i).filter { k.points.lift[it] == LiftClass.TAPED }; val cb = centres(b, j).filter { b.points.lift[it] == LiftClass.TAPED }
                if (ck.isEmpty() || cb.isEmpty()) continue
                fun pair(p: Int): Int? {
                    world(k, p, wk)
                    var best: Int? = null; var bd = 0.05
                    for (q in cb) { world(b, q, wb); val d = hypot(hypot(wk[0] - wb[0], wk[1] - wb[1]), wk[2] - wb[2]); if (d < bd) { bd = d; best = q } }
                    return best
                }
                val r0 = ck.firstNotNullOfOrNull { p -> pair(p)?.let { p to it } } ?: continue
                for (p in ck) {
                    val q = pair(p) ?: continue
                    val d = drift(p, r0.first, q, r0.second) ?: continue
                    worst = max(worst, d); count++; kinds.add(kind)
                    byKind.getOrPut("tree crowns") { ArrayList() }.add(d)
                }
            }
            if (!same) continue
            for (n in pk.indices) {
                if (n == ref || k.points.lift[pk[n]] != LiftClass.BILLBOARD) continue
                val d = drift(pk[n], pk[ref], pb[n], pb[ref]) ?: continue
                if (kind == "street_lamp") {
                    worst = max(worst, d); count++; kinds.add(kind)
                    byKind.getOrPut("lamp heads") { ArrayList() }.add(d)
                } else {
                    byKind.getOrPut("$kind billboards (reported)") { ArrayList() }.add(d)
                }
            }
        }
        for ((kd, ds) in byKind) { ds.sort(); println("  $kd: ${ds.size}, median ${fmt(ds[ds.size / 2])}, p95 ${fmt(ds[(ds.size * 95) / 100])}, max ${fmt(ds.last())}") }
        return Triple(count, worst, kinds)
    }

    @Test
    fun `compiles are fast and anchors small`() {
        val region = AnchorCases.westerkerk()
        val made = AnchorCases.render(region, AnchorCases.homeView(region), Moment.Named("sunny"))
        val (ms, a) = timed(made)
        val disk = AnchorCodec.encode(a).size
        println("Westerkerk: compile median ${fmt(ms)} ms on one thread; ${a.bytes() / 1e6} MB in memory, ${disk / 1e6} MB on disk")
        assertTrue("Westerkerk compiles in ≤ 80 ms on one thread (${fmt(ms)})", ms <= 80 * SLACK)
        assertTrue("Westerkerk anchor ≤ 8 MB in memory (${a.bytes()})", a.bytes() <= 8_000_000)
        assertTrue("Westerkerk anchor ≤ 2.5 MB on disk ($disk)", disk <= 2_500_000)
        val gr = AnchorCases.grachten() ?: return
        val gm = AnchorCases.render(gr, AnchorCases.boardView(gr, 915), Moment.Named("sunny"))
        val (gms, ga) = timed(gm)
        val gdisk = AnchorCodec.encode(ga).size
        println("grachten_home: compile median ${fmt(gms)} ms; ${ga.bytes() / 1e6} MB in memory, ${gdisk / 1e6} MB on disk")
        assertTrue("grachten_home compiles in ≤ 400 ms (${fmt(gms)})", gms <= 400 * SLACK)
        assertTrue("grachten_home anchor ≤ 30 MB in memory (${ga.bytes()})", ga.bytes() <= 30_000_000)
        assertTrue("grachten_home anchor ≤ 10 MB on disk ($gdisk)", gdisk <= 10_000_000)
    }

    /** The median compile time of [made] after warming up, and the anchor. */
    private fun timed(made: AnchorCases.Made): Pair<Double, Anchor> {
        var a: Anchor? = null
        repeat(5) { a = AnchorCases.anchor(made) }
        val times = DoubleArray(11) {
            val t0 = System.nanoTime(); a = AnchorCases.anchor(made); (System.nanoTime() - t0) / 1e6
        }
        times.sort()
        return times[times.size / 2] to a!!
    }

    @Test
    fun `the plan covers the drawn primitives once, in order, and the screen part reads back`() {
        val region = AnchorCases.westerkerk()
        val a = AnchorCases.anchor(AnchorCases.render(region, AnchorCases.homeView(region), Moment.Named("sunny")))
        val seen = BooleanArray(a.prims.drawn)
        var next = 0
        for ((n, node) in a.plan.nodes.withIndex()) {
            assertTrue("node $n ends after itself", node.end > n && node.end <= a.plan.nodes.size)
            when (node) {
                is PlanNode.Run -> {
                    assertEquals("runs follow painter's order", next, node.first)
                    for (p in node.first until node.last) { assertFalse(seen[p]); seen[p] = true }
                    next = node.last
                }
                is PlanNode.Group -> {
                    assertTrue("a group's painter index is its first primitive", node.painter <= a.prims.drawn)
                    if (node.filter >= 0) assertTrue(node.filter < a.filters.size)
                    if (node.clip >= 0) assertTrue(node.clip < a.plan.clips.size)
                }
                is PlanNode.Layer -> Unit
            }
        }
        assertTrue("every drawn primitive is in one run", seen.all { it })
        for (c in a.plan.clips) for (p in c.first until c.last) assertTrue("clip primitives are clip primitives", a.prims.has(p, PrimFlag.CLIP))
        // the generators are the engine's grain, cloud shadows and glints
        val gens = a.filters.filter { it.generatorOnly }.map { it.filter.id }.toSet()
        assertTrue("generator-only filters found: $gens", gens.containsAll(listOf("grain", "cloudsh")))
        assertFalse("mat reads SourceGraphic", a.filters.first { it.filter.id == "mat" }.generatorOnly)
        // the screen part: the horizon, an SVG that parses, the camera's configuration
        assertEquals(a.head.camera.horizonY(), a.screen.horizon, 1e-9)
        val doc = SvgParser.parse(a.screen.svg)
        assertTrue("the screen-fixed elements are written out", doc.drawables > 0)
        assertTrue(a.screen.spec.startsWith("{\"cfg\":"))
        // screen-fixed groups hold only screen-fixed primitives
        for ((n, node) in a.plan.nodes.withIndex()) {
            if (node !is PlanNode.Group || !node.screenFixed) continue
            for (c in n + 1 until node.end) (a.plan.nodes[c] as? PlanNode.Run)?.let { r -> for (p in r.first until r.last) assertTrue(a.prims.has(p, PrimFlag.SCREEN_FIXED)) }
        }
    }

    @Test
    fun `generator-only filters are told from those that read the source`() {
        val doc = SvgParser.parse(
            "<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"10\" height=\"10\"><defs>" +
                "<filter id=\"g\"><feTurbulence baseFrequency=\".1\"/><feColorMatrix type=\"matrix\" values=\"0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 1 0 0 0 0\"/></filter>" +
                "<filter id=\"f\"><feFlood flood-color=\"#fff\" result=\"a\"/><feComposite in=\"a\" in2=\"SourceAlpha\" operator=\"in\"/></filter>" +
                "<filter id=\"s\"><feGaussianBlur stdDeviation=\"2\"/></filter>" +
                "<filter id=\"m\"><feTurbulence baseFrequency=\".1\" result=\"n\"/><feDisplacementMap in=\"SourceGraphic\" in2=\"n\" scale=\"3\"/></filter>" +
                "</defs><rect width=\"10\" height=\"10\" filter=\"url(#g)\"/></svg>",
        )
        assertTrue(AnchorCompiler.generatorOnly(doc.filters.getValue("g")))
        assertFalse(AnchorCompiler.generatorOnly(doc.filters.getValue("f")))
        assertFalse(AnchorCompiler.generatorOnly(doc.filters.getValue("s")))
        assertFalse(AnchorCompiler.generatorOnly(doc.filters.getValue("m")))
    }
}

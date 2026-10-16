package app.atlas.anchor

import app.atlas.render.live.LiveView
import app.atlas.render.live.Moment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * How far anchors reach (plan-gpu D9): the completeness rect, the share of another camera's frame outside it (what
 * triggers a new anchor), the backdrop's items by the engine's own radius cut (each Westerkerk building drawn by
 * exactly one of the home anchor and the 1.3 km backdrop), the ground disc and the coverage mask, and the projection
 * of an anchor's points at another camera.
 */
class CoverageTest {
    private fun home(): Anchor {
        val region = AnchorCases.westerkerk()
        return AnchorCases.anchor(AnchorCases.render(region, AnchorCases.homeView(region), Moment.Named("sunny")))
    }

    @Test
    fun `the completeness rect is the frame and the trees' 20 px cut, and a camera's own frame lies inside it`() {
        val a = home()
        val c = a.head.camera
        assertEquals(listOf(-20.0, -20.0, c.width + 20, c.height + 20), Coverage.completeness(a).toList())
        assertEquals("the anchor's own camera sees nothing outside it", 0.0, Coverage.outsideShare(listOf(a), c), 0.0)
    }

    @Test
    fun `a pan towards the camera opens what lies outside the anchor, and an anchor at the new camera closes it`() {
        val region = AnchorCases.westerkerk()
        val v = AnchorCases.homeView(region)
        val a = home()
        // a third of the screen down: the target moves towards the camera along the bearing
        val panned = moved(v, -120.0)
        val made = AnchorCases.render(region, panned, Moment.Named("sunny"))
        val cam = anchorCamera(made.source.camera)
        val share = Coverage.outsideShare(listOf(a), cam)
        println("a 120 m pan towards the camera leaves ${"%.2f".format(share * 100)} % of the frame outside the home anchor")
        assertTrue("the pan opens more than 1 % (the trigger)", share > 0.01)
        val b = AnchorCases.anchor(made)
        assertEquals("the anchor at the new camera covers it", 0.0, Coverage.outsideShare(listOf(a, b), cam), 0.0)
    }

    /** [v] moved [m] metres along its bearing (negative: towards the camera). */
    private fun moved(v: LiveView, m: Double): LiveView {
        val b = Math.toRadians(v.bearing)
        val dLat = m * Math.cos(b) / 110540.0
        val dLon = m * Math.sin(b) / (111320.0 * Math.cos(Math.toRadians(v.lat)))
        return LiveView(v.lat + dLat, v.lon + dLon, v.zoom, v.bearing, v.pitch, v.widthDp, v.heightDp, v.cyDp)
    }

    @Test
    fun `the home anchor and its 1,3 km backdrop draw each Westerkerk building once`() {
        val region = AnchorCases.westerkerk()
        val homeMade = AnchorCases.render(region, AnchorCases.homeView(region), Moment.Named("sunny"))
        val far = AnchorCases.render(region, AnchorCases.homeView(region, dist = 1300.0), Moment.Named("sunny"), AnchorKind.BACKDROP)
        val h = AnchorCases.anchor(homeMade)
        val b = AnchorCases.anchor(far)
        val sel = Coverage.backdropItems(b, h)
        println("home RAD ${h.head.radius} m, backdrop RAD ${b.head.radius} m; backdrop items ${b.items.size}, selected ${sel.count { it }}")
        fun buildings(a: Anchor, pick: (Int) -> Boolean): Map<Pair<Byte, Long>, Int> {
            val out = HashMap<Pair<Byte, Long>, Int>()
            for (i in 0 until a.items.size) {
                if (a.items.kinds[a.items.kind[i]] !in Faces.WALL_KINDS || !pick(i)) continue
                if (a.items.ownerId[i] == 0L) continue
                out.merge(a.items.ownerType[i] to a.items.ownerId[i], 1, Int::plus)
            }
            return out
        }
        val inHome = buildings(h) { true }
        val fromBackdrop = buildings(b) { sel[it] }
        val both = inHome.keys.intersect(fromBackdrop.keys)
        assertTrue("no building is drawn by both: ${both.take(5)}", both.isEmpty())
        // every home item lies within the home's radius, every selected one beyond it: the engine's own cut
        for (i in 0 until h.items.size) assertTrue(!h.items.cut(i, h.head.radiusX, h.head.radiusZ, h.head.radius))
        // a backdrop building that is neither drawn by the home nor selected stands outside the home's frame
        val cam = h.head.camera
        val proj = Coverage.Projection(b, cam)
        val xy = DoubleArray(2)
        var unseen = 0
        val homeIds = inHome.keys
        for (i in 0 until b.items.size) {
            if (b.items.kinds[b.items.kind[i]] !in Faces.WALL_KINDS || sel[i] || b.items.ownerId[i] == 0L) continue
            if ((b.items.ownerType[i] to b.items.ownerId[i]) in homeIds) continue
            var x0 = Double.MAX_VALUE; var y0 = Double.MAX_VALUE; var x1 = -Double.MAX_VALUE; var y1 = -Double.MAX_VALUE
            for (p in b.items.first[i] until b.items.last[i]) {
                if (b.prims.kind[p] != PrimKind.FACE) continue
                for (k in 0 until b.prims.corners(p)) if (proj.project(b.prims.corner(p, k), xy)) {
                    x0 = min(x0, xy[0]); x1 = max(x1, xy[0]); y0 = min(y0, xy[1]); y1 = max(y1, xy[1])
                }
            }
            if (x0 > x1) continue
            unseen++
            val inside = x1 >= 0 && x0 <= cam.width && y1 >= 0 && y0 <= cam.height
            assertTrue("backdrop building ${b.items.ownerId[i]} within the home's radius but not drawn by it lies off the home's frame ($x0..$x1, $y0..$y1)", !inside)
        }
        println("home buildings ${inHome.size}, backdrop buildings beyond the home's radius ${fromBackdrop.size}, within it but off the home's frame $unseen")
        assertTrue("the backdrop gives buildings beyond the radius", fromBackdrop.isNotEmpty())
    }

    @Test
    fun `the coverage mask leaves out the ground beyond ground_r and the backdrop's buildings`() {
        val region = AnchorCases.westerkerk()
        val h = home()
        val b = AnchorCases.anchor(AnchorCases.render(region, AnchorCases.homeView(region, dist = 1300.0), Moment.Named("sunny"), AnchorKind.BACKDROP))
        val d = AnchorCases.Size.S1.density
        val w = AnchorCases.Size.S1.wPx; val hh = AnchorCases.Size.S1.hPx
        val alone = Coverage.mask(h, null, d, w, hh)
        val withBackdrop = Coverage.mask(h, b, d, w, hh)
        val s0 = Coverage.maskedShare(alone); val s1 = Coverage.maskedShare(withBackdrop)
        println("Westerkerk home: masked share ${"%.3f".format(s0 * 100)} % without a backdrop, ${"%.3f".format(s1 * 100)} % with the 1.3 km backdrop's buildings")
        assertEquals("the home's ground reaches past everything it shows", 0.0, s0, 0.0)
        assertTrue("the backdrop's buildings beyond the radius are masked", s1 >= s0)
        for (i in alone.indices) if (!alone[i]) assertTrue(!withBackdrop[i])
        // the Main board's ground covers its whole view too
        val gr = AnchorCases.grachten()
        assumeTrue("the grachten pack is local only", gr != null)
        val g = AnchorCases.anchor(AnchorCases.render(gr!!, AnchorCases.boardView(gr, 916), Moment.Named("sunny")))
        assertEquals("the Main board's masked share", 0.0, Coverage.maskedShare(Coverage.mask(g, null, d, w, hh)), 0.0)
    }

    @Test
    fun `points project at another camera as the region frame does, billboards by their depth ratio`() {
        val region = AnchorCases.westerkerk()
        val a = home()
        val k = a.head.camera
        val view = AnchorCases.render(region, AnchorCases.homeView(region).let { LiveView(it.lat, it.lon, it.zoom, it.bearing + 15, it.pitch + 5, 412, 916, 440.0) }, Moment.Named("sunny")).source.camera
        val v = anchorCamera(view)
        val same = Coverage.Projection(a, k)
        val other = Coverage.Projection(a, v)
        val xy = DoubleArray(2); val w = DoubleArray(3); val e = DoubleArray(3)
        var checked = 0; var billboards = 0
        for (i in 0 until a.points.size) {
            assertTrue(same.project(i, xy))
            assertEquals(a.points.screenX(k, i), xy[0], 1e-9); assertEquals(a.points.screenY(k, i), xy[1], 1e-9)
            when (a.points.lift[i]) {
                LiftClass.TAPED, LiftClass.PLANE, LiftClass.GROUND -> {
                    k.world(a.points.x(i).toDouble(), a.points.y(i).toDouble(), a.points.z(i).toDouble(), w)
                    v.eye(w[0], w[1], w[2], e)
                    if (e[2] < 1.0 || !other.project(i, xy)) continue
                    assertEquals(v.sx(e[0], e[2]), xy[0], 1e-6 * max(1.0, abs(xy[0])))
                    assertEquals(v.sy(e[1], e[2]), xy[1], 1e-6 * max(1.0, abs(xy[1])))
                    checked++
                }
                LiftClass.BILLBOARD -> {
                    val h = a.points.hang(i)
                    val hx = DoubleArray(2)
                    if (!other.project(i, xy) || !other.project(h, hx)) continue
                    k.world(a.points.x(h).toDouble(), a.points.y(h).toDouble(), a.points.z(h).toDouble(), w)
                    v.eye(w[0], w[1], w[2], e)
                    val s = (v.focal / e[2]) / (k.focal / a.points.z(h))
                    val b = a.points.billboard(i)
                    assertEquals(hx[0] + a.points.offsets[b * 2] * s, xy[0], 1e-6)
                    assertEquals(hx[1] + a.points.offsets[b * 2 + 1] * s, xy[1], 1e-6)
                    billboards++
                }
                else -> Unit
            }
        }
        assertTrue("points checked ($checked) and billboards ($billboards)", checked > 50_000 && billboards > 100)
    }
}

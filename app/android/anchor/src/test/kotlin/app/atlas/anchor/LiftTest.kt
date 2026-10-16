package app.atlas.anchor

import app.atlas.render.City
import app.atlas.render.V3
import app.atlas.render.live.WorldTape
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.max

/**
 * Lifting on a tape of known world points (plan-gpu D2, "Lifting"): taped points sit on the engine's own world points,
 * a key's alternatives go to the element's owner, untaped points on a plane, the ground, a billboard or the screen, a
 * dash's end on its 3D segment, and residuals put every point back on its SVG coordinate.
 */
class LiftTest {
    private val camera: City.Camera
    private val tape: WorldTape
    private val owners = arrayOf(Any(), Any(), Any())
    private val printed = HashMap<V3, DoubleArray>()

    init {
        val t = WorldTape(City.Camera(V3(10.0, 0.0, -20.0), 40.0, 35.0, 300.0, 36.0, 412.0, 916.0, cy = 440.0))
        tape = t
        camera = WorldTape.recording(t) {
            val cam = City.Camera(V3(10.0, 0.0, -20.0), 40.0, 35.0, 300.0, 36.0, 412.0, 916.0, cy = 440.0)
            fun draw(owner: Int, vararg ps: V3) {
                WorldTape.owner(if (owner < 0) null else owners[owner])
                for (p in ps) {
                    val s = cam.proj(p)!!
                    // as the SVG prints it: %.1f
                    printed[p] = doubleArrayOf(Math.round(s.x * 10) / 10.0, Math.round(s.y * 10) / 10.0)
                }
            }
            // a wall of owner 0 (x = 0 plane), a roof of owner 1, ground points of no owner, a lamp top of owner 2
            draw(0, V3(0.0, 0.0, 0.0), V3(0.0, 0.0, 12.0), V3(0.0, 8.0, 12.0))
            draw(1, V3(3.0, 9.0, 3.0), V3(9.0, 9.0, 3.0), V3(9.0, 11.0, 9.0))
            draw(-1, V3(-20.0, -1.5, -10.0), V3(30.0, -1.5, 15.0))
            draw(2, V3(20.0, 4.2, -5.0))
            cam
        }
        tape.seal()
    }

    private val cam = anchorCamera(camera)

    private fun table(vararg xy: DoubleArray): Pair<PointTable, Int> {
        val pts = PointTable(16)
        val first = pts.size
        for (p in xy) pts.add(p[0], p[1])
        return pts to first
    }

    private fun eyeOf(p: V3): DoubleArray = DoubleArray(3).also { cam.eye(p.x, p.y, p.z, it) }

    @Test
    fun `taped points sit on the engine's world points, along the ray of their printed position`() {
        val wall = listOf(V3(0.0, 0.0, 0.0), V3(0.0, 0.0, 12.0), V3(0.0, 8.0, 12.0))
        val (pts, first) = table(*wall.map { printed.getValue(it) }.toTypedArray())
        val lifter = Lifter(tape, cam, pts)
        val owner = lifter.tapeElement(first, 3, -1, true)
        assertEquals("the wall's owner", tape.ownerId(owners[0]), owner)
        for ((k, p) in wall.withIndex()) {
            assertEquals(LiftClass.TAPED, pts.liftOf(first + k))
            val e = eyeOf(p)
            // the depth is the engine's (a float); across, the printed position's ray at that depth
            assertEquals(e[2], pts.eyeZ(first + k).toDouble(), 1e-4 * e[2])
            assertEquals(e[0], pts.eyeX(first + k).toDouble(), 0.05 * e[2] / cam.focal + 1e-4)
            assertEquals(e[1], pts.eyeY(first + k).toDouble(), 0.05 * e[2] / cam.focal + 1e-4)
        }
        val built = pts.build(cam)
        for (k in 0 until 3) {
            assertEquals(pts.svgX(first + k), built.screenX(cam, first + k), 0.0005)
            assertEquals(pts.svgY(first + k), built.screenY(cam, first + k), 0.0005)
        }
    }

    @Test
    fun `a key printed by two owners goes to the element's owner, else to the majority's nearest`() {
        // two owners print the same screen point at different depths
        val t = WorldTape(camera)
        val a = Any(); val b = Any()
        val near = V3(5.0, 2.0, 1.0)
        val k = camera.cam(near)
        val far = V3(camera.C.x + (near.x - camera.C.x) * 2, camera.C.y + (near.y - camera.C.y) * 2, camera.C.z + (near.z - camera.C.z) * 2)
        val other = V3(6.0, 2.0, 1.0)
        WorldTape.recording(t) {
            val c = City.Camera(V3(10.0, 0.0, -20.0), 40.0, 35.0, 300.0, 36.0, 412.0, 916.0, cy = 440.0)
            WorldTape.owner(a); c.proj(near); c.proj(other)
            WorldTape.owner(b); c.proj(far)
        }
        t.seal()
        val s = camera.pc(k)
        val key = doubleArrayOf(Math.round(s.x * 10) / 10.0, Math.round(s.y * 10) / 10.0)
        val so = camera.pc(camera.cam(other))
        val keyOther = doubleArrayOf(Math.round(so.x * 10) / 10.0, Math.round(so.y * 10) / 10.0)
        // by the item's owner
        run {
            val (pts, first) = table(key)
            Lifter(t, cam, pts).tapeElement(first, 1, t.ownerId(b), true)
            assertEquals("owner b's depth", eyeOf(far)[2], pts.eyeZ(first).toDouble(), 1e-3 * eyeOf(far)[2])
        }
        // by the element's other, unambiguous point: owner a
        run {
            val (pts, first) = table(key, keyOther)
            val owner = Lifter(t, cam, pts).tapeElement(first, 2, -1, true)
            assertEquals(t.ownerId(a), owner)
            assertEquals("owner a's depth", eyeOf(near)[2], pts.eyeZ(first).toDouble(), 1e-3 * eyeOf(near)[2])
        }
    }

    @Test
    fun `an untaped corner of a planar element lies on its plane, and lands`() {
        // the roof: three taped corners and a fourth that no camera call printed
        val roof = listOf(V3(3.0, 9.0, 3.0), V3(9.0, 9.0, 3.0), V3(9.0, 11.0, 9.0))
        val fourth = V3(3.0, 11.0, 9.0)
        val s4 = camera.pc(camera.cam(fourth))
        val (pts, first) = table(*roof.map { printed.getValue(it) }.toTypedArray(), doubleArrayOf(s4.x, s4.y))
        val lifter = Lifter(tape, cam, pts)
        val owner = lifter.tapeElement(first, 4, -1, true)
        lifter.liftRest(first, 4, owner, false, false)
        assertEquals(LiftClass.PLANE, pts.liftOf(first + 3))
        val w = DoubleArray(3)
        cam.world(pts.eyeX(first + 3).toDouble(), pts.eyeY(first + 3).toDouble(), pts.eyeZ(first + 3).toDouble(), w)
        // on the roof's plane (through the taped corners, which carry the print's rounding): within a centimetre of the corner
        assertEquals(fourth.x, w[0], 0.02); assertEquals(fourth.y, w[1], 0.02); assertEquals(fourth.z, w[2], 0.02)
        val built = pts.build(cam)
        assertEquals(s4.x, built.screenX(cam, first + 3), 0.0005); assertEquals(s4.y, built.screenY(cam, first + 3), 0.0005)
        assertTrue(lifter.stats.found == 3L)
    }

    @Test
    fun `the ground own elements fall on the ground at their taped height, others never do`() {
        val g0 = V3(-20.0, -1.5, -10.0)
        val g1 = V3(30.0, -1.5, 15.0)
        val loose = V3(0.0, -1.5, 30.0)
        val sl = camera.pc(camera.cam(loose))
        val (pts, first) = table(printed.getValue(g0), printed.getValue(g1), doubleArrayOf(sl.x, sl.y))
        val lifter = Lifter(tape, cam, pts)
        val owner = lifter.tapeElement(first, 3, -1, true)
        assertEquals(0, owner)
        lifter.liftRest(first, 3, owner, true, false)
        assertEquals(LiftClass.GROUND, pts.liftOf(first + 2))
        val w = DoubleArray(3)
        cam.world(pts.eyeX(first + 2).toDouble(), pts.eyeY(first + 2).toDouble(), pts.eyeZ(first + 2).toDouble(), w)
        assertEquals(-1.5, w[1], 0.01); assertEquals(loose.x, w[0], 0.05); assertEquals(loose.z, w[2], 0.05)
        // an owned element with the same points is hung on its owner's taped point instead
        val (pts2, f2) = table(printed.getValue(g0), printed.getValue(g1), doubleArrayOf(sl.x, sl.y))
        val l2 = Lifter(tape, cam, pts2)
        l2.tapeElement(f2, 3, -1, true)
        l2.liftRest(f2, 3, tape.ownerId(owners[2]), true, false)
        assertEquals(LiftClass.BILLBOARD, pts2.liftOf(f2 + 2))
    }

    @Test
    fun `a billboard hangs on its owner projected point and lands, a screen element stays fixed`() {
        val top = V3(20.0, 4.2, -5.0)
        val p = printed.getValue(top)
        // a lamp head: offsets from the lamp's top, none of them printed by a camera
        val (pts, first) = table(doubleArrayOf(p[0] - 1.3, p[1]), doubleArrayOf(p[0] + 1.3, p[1]), doubleArrayOf(p[0], p[1] + 2.1))
        val lifter = Lifter(tape, cam, pts)
        val owner = tape.ownerId(owners[2])
        lifter.tapeElement(first, 3, owner, true)
        lifter.liftRest(first, 3, owner, false, false)
        val built = pts.build(cam)
        for (k in 0 until 3) {
            assertEquals(LiftClass.BILLBOARD, pts.liftOf(first + k))
            val h = built.hang(first + k)
            assertEquals("hangs on the lamp's top", p[0], pts.svgX(h), 1e-9); assertEquals(p[1], pts.svgY(h), 1e-9)
            assertEquals(pts.svgX(first + k), built.screenX(cam, first + k), 0.0005)
            assertEquals(pts.svgY(first + k), built.screenY(cam, first + k), 0.0005)
        }
        // the sky: no tape, no owner, fixed to the screen
        val (sp, sf) = table(doubleArrayOf(0.0, 0.0), doubleArrayOf(412.0, 0.0), doubleArrayOf(412.0, 300.0))
        val sl = Lifter(tape, cam, sp)
        sl.tapeElement(sf, 3, -1, false)
        sl.liftRest(sf, 3, 0, true, true)
        for (k in 0 until 3) assertEquals(LiftClass.SCREEN, sp.liftOf(sf + k))
    }

    @Test
    fun `a dash's end lies on its segment in space and lands where it was cut`() {
        val a = V3(0.0, 0.0, 0.0); val b = V3(0.0, 0.0, 12.0)
        val (pts, first) = table(printed.getValue(a), printed.getValue(b))
        val lifter = Lifter(tape, cam, pts)
        lifter.tapeElement(first, 2, -1, true)
        for (s in listOf(0.1, 0.37, 0.5, 0.92)) {
            val x = pts.svgX(first) + (pts.svgX(first + 1) - pts.svgX(first)) * s
            val y = pts.svgY(first) + (pts.svgY(first + 1) - pts.svgY(first)) * s
            val i = pts.add(x, y)
            lifter.liftBetween(i, first, first + 1, s)
            // on the 3D segment: collinear with its ends
            val ax = pts.eyeX(first).toDouble(); val ay = pts.eyeY(first).toDouble(); val az = pts.eyeZ(first).toDouble()
            val dx = pts.eyeX(first + 1) - ax; val dy = pts.eyeY(first + 1) - ay; val dz = pts.eyeZ(first + 1) - az
            val t = ((pts.eyeX(i) - ax) * dx + (pts.eyeY(i) - ay) * dy + (pts.eyeZ(i) - az) * dz) / (dx * dx + dy * dy + dz * dz)
            val off = Math.sqrt(Math.pow(pts.eyeX(i) - ax - t * dx, 2.0) + Math.pow(pts.eyeY(i) - ay - t * dy, 2.0) + Math.pow(pts.eyeZ(i) - az - t * dz, 2.0))
            assertTrue("on the segment ($off m)", off < 1e-3 && t in 0.0..1.0)
            val built = pts.build(cam)
            assertEquals(x, built.projectedX(cam, i), 0.01); assertEquals(y, built.projectedY(cam, i), 0.01)
        }
    }

    @Test
    fun `residuals take each point back to its SVG coordinate in thousandths, and an overflow is counted`() {
        val pts = PointTable(4)
        val a = pts.add(100.0, 200.0)
        // its point projects 3 px left and 2 px below of it
        pts.place(a, (103.0 - cam.cx) / cam.focal * 50, (cam.cy - 198.0) / cam.focal * 50, 50.0, LiftClass.PLANE)
        val b = pts.add(-3.0, 5.5)
        pts.screen(b)
        val far = pts.add(10.0, 10.0)
        // 80 px away from where its point projects: more than a short holds
        val e = DoubleArray(3)
        val ray = doubleArrayOf((90.0 - cam.cx) / cam.focal, (cam.cy - 10.0) / cam.focal)
        e[2] = 40.0; e[0] = ray[0] * e[2]; e[1] = ray[1] * e[2]
        pts.place(far, e[0], e[1], e[2], LiftClass.PLANE)
        val built = pts.build(cam)
        assertEquals(100.0, built.screenX(cam, a), 0.0005); assertEquals(200.0, built.screenY(cam, a), 0.0005)
        assertEquals(-3000.0, built.residual[a * 2].toDouble(), 1.0); assertEquals(2000.0, built.residual[a * 2 + 1].toDouble(), 1.0)
        assertEquals(0, built.residual[b * 2].toInt()); assertEquals(0, built.residual[b * 2 + 1].toInt())
        assertEquals(1, pts.overflow)
        assertTrue(max(abs(built.residual[a * 2].toInt()), abs(built.residual[a * 2 + 1].toInt())) < 32767)
    }
}

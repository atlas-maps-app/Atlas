// render/closeup.py: up close, at eye level. The ground in its mapped material (klinkers in herringbone, pavement
// tiles, setts), kerbs, church windows, trees in the form of their genus, brick and stone coursing, and clouds in
// perspective. Ported line by line.
package app.atlas.render

import app.atlas.render.City.Camera
import app.atlas.render.City.P
import app.atlas.render.City.area
import app.atlas.render.py.PyFmt.pf
import app.atlas.render.py.PyMath
import app.atlas.render.py.PyRandom
import app.atlas.render.py.dict
import app.atlas.render.py.flag
import app.atlas.render.py.pyNum
import app.atlas.render.py.pySortBy
import app.atlas.render.py.pySortedBy
import app.atlas.render.py.truthy

/** `light(th, base, n, lit)`: a colour lit by the theme's sun, as amsterdam.light gives it. */
typealias LightFn = (Map<String, Any?>, V3, V3, Boolean) -> V3

/** The eye-level details of render/closeup.py. */
object Closeup {
    // render/closeup.py:10 pattern_defs
    /** The three paving patterns, in centimetres: herringbone klinkers, 30 cm pavement tiles and setts. */
    fun patternDefs(night: Boolean = false, @Suppress("UNUSED_PARAMETER") wet: Boolean = false): String {
        val j = if (!night) "#2E2A26" else "#05070B"
        val jo = if (!night) .55 else .5
        val dk = "#000"
        val lt = if (!night) "#FFF" else "#3A4258"
        val out = ArrayList<String>()
        // herringbone, 20 x 10 cm klinkers; the lattice repeats every 160 cm, with a tone per brick
        var rnd = PyRandom(7)
        val br = ArrayList<IntArray>()
        for (m in -12..12) {
            for (k in -20..20) {
                val ox = 2 * m; val oy = -2 * m
                for (q in arrayOf(intArrayOf(k + ox, k + oy, 1, 2), intArrayOf(k + 1 + ox, k + oy, 2, 1))) {
                    val x0 = q[0]; val y0 = q[1]; val w = q[2]; val h = q[3]
                    if (x0 + w <= 0 || x0 >= 16 || y0 + h <= 0 || y0 >= 16) continue
                    br.add(intArrayOf(x0 * 10, y0 * 10, w * 10, h * 10))
                }
            }
        }
        val tone = HashMap<Long, Double>()
        fun key(b: IntArray): Long = (Math.floorMod(b[0], 160).toLong() shl 40) or (Math.floorMod(b[1], 160).toLong() shl 20) or b[2].toLong()
        for (b in br) {
            if (key(b) !in tone) tone[key(b)] = rnd.random()
        }
        fun rr(b: IntArray): String = "M${b[0]} ${b[1]}h${b[2]}v${b[3]}h${-b[2]}z"
        var dark = br.filter { tone[key(it)]!! < 0.3 }.joinToString("") { rr(it) }
        var light = br.filter { tone[key(it)]!! > 0.75 }.joinToString("") { rr(it) }
        out.add(
            pf(
                "<pattern id=\"pv_hb\" width=\"160\" height=\"160\" patternUnits=\"userSpaceOnUse\"><path d=\"%s\" fill=\"%s\" opacity=\".1\"/><path d=\"%s\" fill=\"%s\" opacity=\".1\"/><path d=\"%s\" fill=\"none\" stroke=\"%s\" stroke-width=\"1\" opacity=\"%.2f\"/></pattern>",
                dark, dk, light, lt, br.joinToString("") { rr(it) }, j, jo,
            ),
        )
        // pavement tiles, 30 x 30 cm
        rnd = PyRandom(11)
        val t = ArrayList<IntArray>()
        for (x in 0 until 8) for (y in 0 until 8) t.add(intArrayOf(x * 30, y * 30))
        fun tl(c: IntArray): String = "M${c[0]} ${c[1]}h30v30h-30z"
        dark = t.filter { rnd.random() < 0.25 }.joinToString("") { tl(it) }
        light = t.filter { rnd.random() < 0.2 }.joinToString("") { tl(it) }
        out.add(
            pf(
                "<pattern id=\"pv_tile\" width=\"240\" height=\"240\" patternUnits=\"userSpaceOnUse\"><path d=\"%s\" fill=\"%s\" opacity=\".07\"/><path d=\"%s\" fill=\"%s\" opacity=\".08\"/><path d=\"%s\" fill=\"none\" stroke=\"%s\" stroke-width=\".9\" opacity=\"%.2f\"/></pattern>",
                dark, dk, light, lt, t.joinToString("") { tl(it) }, j, jo * 0.8,
            ),
        )
        // setts, rows of 12 cm, stones 16 cm, half-offset rows, wide sand joints
        rnd = PyRandom(5)
        class Sett(val x: Int, val y: Int, val r: Double)
        val st = ArrayList<Sett>()
        for (row in 0 until 8) {
            val off = if (row % 2 != 0) 8 else 0
            for (c in -1 until 9) {
                val x = c * 16 + off
                if (x >= 128 || x + 16 <= 0) continue
                st.add(Sett(x, row * 12, rnd.random()))
            }
        }
        fun sp(s: Sett): String = "<rect x=\"${s.x}\" y=\"${s.y}\" width=\"15\" height=\"11\" rx=\"3\"/>"
        out.add(
            pf(
                "<pattern id=\"pv_sett\" width=\"128\" height=\"96\" patternUnits=\"userSpaceOnUse\"><rect width=\"128\" height=\"96\" fill=\"%s\" opacity=\".38\"/><g fill=\"#FFF\" opacity=\".16\">%s</g><g fill=\"#000\" opacity=\".1\">%s</g><g fill=\"#FFF\" opacity=\".12\">%s</g></pattern>",
                j, st.joinToString("") { sp(it) }, st.filter { it.r < 0.35 }.joinToString("") { sp(it) }, st.filter { it.r > 0.7 }.joinToString("") { sp(it) },
            ),
        )
        return out.joinToString("")
    }

    /** A paving pattern and its rotation in degrees. */
    data class Pat(val id: String, val rot: Int)

    val ROAD_PAT: Map<String, Pat> = linkedMapOf(
        "paving_stones" to Pat("pv_hb", 45), "bricks" to Pat("pv_hb", 45), "sett" to Pat("pv_sett", 0),
        "cobblestone" to Pat("pv_sett", 0), "unhewn_cobblestone" to Pat("pv_sett", 0),
    )
    val SIDE_PAT: Map<String, Pat> = linkedMapOf("paving_stones" to Pat("pv_tile", 0), "sett" to Pat("pv_sett", 0), "bricks" to Pat("pv_hb", 0))

    private val SIDES: Map<String, List<Int>> = linkedMapOf("both" to listOf(-1, 1), "left" to listOf(-1), "right" to listOf(1), "yes" to listOf(-1, 1))

    /** Python `a or b`. */
    private fun or(a: Any?, b: Any?): Any? = if (truthy(a)) a else b

    // render/closeup.py:53 sidewalk_sides
    /** The sides with a mapped pavement (-1 left, 1 right) and each one's mapped surface. */
    fun sidewalkSides(t: Map<String, Any?>): Pair<List<Int>, Map<Int, String?>> {
        val sw = or(t["sidewalk"], if (truthy(t["sidewalk:both"])) "both" else t["sidewalk:both"])
        var sides: List<Int> = SIDES[sw as? String] ?: emptyList()
        if (t["sidewalk:left"] in setOf("yes", "separate") && -1 !in sides) sides = sides + (-1)
        if (t["sidewalk:right"] in setOf("yes", "separate") && 1 !in sides) sides = sides + 1
        val surf = LinkedHashMap<Int, String?>()
        for (s in sides) {
            val nm = if (s < 0) "left" else "right"
            surf[s] = or(or(t["sidewalk:$nm:surface"], t["sidewalk:both:surface"]), t["sidewalk:surface"]) as String?
        }
        return sides to surf
    }

    private fun lerp(a: V3, b: V3, t: Double): V3 = V3(a[0] + (b[0] - a[0]) * t, a[1] + (b[1] - a[1]) * t, a[2] + (b[2] - a[2]) * t)

    // render/closeup.py:64 _clip_x
    /** Clips a camera-space polygon to the screen columns sx0..sx1 (planes through the eye). */
    fun _clipX(cam: Camera, cs0: List<V3>, sx0: Double, sx1: Double): List<V3>? {
        var cs = cs0
        for ((sx, sgn) in arrayOf(sx0 to 1, sx1 to -1)) {
            if (Math.abs(sx) > 1e8) continue
            val k = (sx - cam.cx) / cam.focal
            fun fn(c: V3): Double = sgn * (c[0] - k * c[2])
            val out = ArrayList<V3>(cs.size + 2); val n = cs.size
            for (i in 0 until n) {
                val a = cs[i]; val b = cs[(i + 1) % n]; val fa = fn(a); val fb = fn(b)
                if (fa >= 0) out.add(a)
                if ((fa >= 0) != (fb >= 0)) {
                    val t = fa / (fa - fb); out.add(lerp(a, b, t))
                }
            }
            cs = out
            if (cs.size < 3) return null
        }
        return cs
    }

    // render/closeup.py:80 _clip_z
    /** Clips a camera-space polygon to depths z0..z1. */
    fun _clipZ(cs0: List<V3>, z0: Double, z1: Double): List<V3>? {
        var cs = cs0
        for ((lo, sgn) in arrayOf(z0 to 1, z1 to -1)) {
            val out = ArrayList<V3>(cs.size + 2); val n = cs.size
            for (i in 0 until n) {
                val a = cs[i]; val b = cs[(i + 1) % n]
                val ai = sgn * (a[2] - lo) >= 0; val bi = sgn * (b[2] - lo) >= 0
                if (ai) out.add(a)
                if (ai != bi) {
                    val t = (lo - a[2]) / (b[2] - a[2]); out.add(lerp(a, b, t))
                }
            }
            cs = out
            if (cs.size < 3) return null
        }
        return cs
    }

    /** One carriageway segment near the eye: its road (compared by identity), ends and half width. */
    class Seg(val rid: Any, val ax: Double, val az: Double, val bx: Double, val bz: Double, val hw: Double)

    // render/closeup.py:93 _near_carriageway
    fun _nearCarriageway(segs: List<Seg>, x: Double, z: Double, skip: Any?): Boolean {
        for (s in segs) {
            if (s.rid === skip) continue
            val dx = s.bx - s.ax; val dz = s.bz - s.az
            val L2 = (dx * dx + dz * dz).let { if (it == 0.0) 1.0 else it }
            val t = PyMath.max(0.0, PyMath.min(1.0, ((x - s.ax) * dx + (z - s.az) * dz) / L2))
            if (PyMath.hypot(s.ax + dx * t - x, s.az + dz * t - z) < s.hw) return true
        }
        return false
    }

    private fun maxX(sp: List<V2>): Double { var m = sp[0].x; for (p in sp) if (p.x > m) m = p.x; return m }
    private fun minX(sp: List<V2>): Double { var m = sp[0].x; for (p in sp) if (p.x < m) m = p.x; return m }
    private fun maxY(sp: List<V2>): Double { var m = sp[0].y; for (p in sp) if (p.y > m) m = p.y; return m }
    private fun minY(sp: List<V2>): Double { var m = sp[0].y; for (p in sp) if (p.y < m) m = p.y; return m }

    // render/closeup.py:101 paving
    /**
     * Returns (under, over): pavements to draw before the carriageways, joints and kerbs to draw after.
     * [S] holds `roads` (dicts with `pts`, `w`, `bridge`, `tags`); [sideCol] colours a pavement by its surface.
     */
    fun paving(
        S: Map<String, Any?>, cam: Camera, th: Map<String, Any?>, @Suppress("UNUSED_PARAMETER") far: Any?,
        sideCol: (String?) -> String, dmax: Double = 34.0, sidewalkW: Double = 1.9,
    ): Pair<String, String> {
        val night = th.flag("night")
        val under = ArrayList<String>(); val over = ArrayList<String>()
        val kerbs = ArrayList<Pair<Char, String>>()
        val d0 = cam.groundDepth(cam.H).let { if (it == null || it == 0.0) 1.0 else it }
        val bands = ArrayList<DoubleArray>()
        var d = PyMath.max(0.9, d0 * 0.9)
        while (d < dmax) {
            bands.add(doubleArrayOf(d, d * 1.066)); d *= 1.066
        }
        val Cx = cam.C[0]; val Cz = cam.C[2]
        var nid = 0
        fun world(c: V3): V3 = V3(
            cam.C[0] + c[0] * cam.r[0] + c[1] * cam.u[0] + c[2] * cam.f[0],
            cam.C[1] + c[0] * cam.r[1] + c[1] * cam.u[1] + c[2] * cam.f[1],
            cam.C[2] + c[0] * cam.r[2] + c[1] * cam.u[2] + c[2] * cam.f[2],
        )
        // Fills a ground quad with a pattern, band by band, each band with its own affine fit.
        fun piece(dst: MutableList<String>, quad: List<V2>, y: Double, pat: String, rot: Int) {
            val cs0 = quad.map { (x, z) -> cam.cam(V3(x, y, z)) }
            var ux = quad[1][0] - quad[0][0]; var uz = quad[1][1] - quad[0][1]
            val L = PyMath.hypot(ux, uz).let { if (it == 0.0) 1.0 else it }
            ux /= L; uz /= L
            val ca = PyMath.cos(PyMath.radians(rot.toDouble())); val sa = PyMath.sin(PyMath.radians(rot.toDouble()))
            class Cell(val z0: Double, val z1: Double, val cs: List<V3>)
            val cells = ArrayList<Cell>()
            for (band in bands) {
                val z0 = band[0]; val z1 = band[1]
                val cs = _clipZ(cs0, z0, z1) ?: continue
                // and across: an affine fit is only good near its own centre, so no piece is wider than about 56 px
                val xs = cs.map { cam.pc(it).x }
                var mn = xs[0]; var mx = xs[0]
                for (v in xs) {
                    if (v < mn) mn = v
                    if (v > mx) mx = v
                }
                val x0 = PyMath.max(-10.0, mn); val x1 = PyMath.min(cam.W + 10, mx)
                if (x1 <= x0) continue
                val k = Math.max(1L, PyMath.ceilInt((x1 - x0) / 56)).toInt()
                for (j in 0 until k) {
                    val a_ = x0 + (x1 - x0) * j / k; val b_ = x0 + (x1 - x0) * (j + 1) / k
                    val c2 = _clipX(cam, cs, if (j != 0) a_ else -1e9, if (j < k - 1) b_ else 1e9)
                    if (!c2.isNullOrEmpty()) cells.add(Cell(z0, z1, c2))
                }
            }
            for (cell in cells) {
                val cs = cell.cs
                val sp = cs.map { cam.pc(it) }
                if (maxX(sp) < -20 || minX(sp) > cam.W + 20 || maxY(sp) < 0 || minY(sp) > cam.H + 20) continue
                val cen = V3(PyMath.sumOf(cs) { it.x } / cs.size, PyMath.sumOf(cs) { it.y } / cs.size, PyMath.sumOf(cs) { it.z } / cs.size)
                val G = world(cen)
                val s0 = cam.proj(G); val e = 0.05
                val s1 = cam.proj(V3(G[0] + ux * e, y, G[2] + uz * e)); val s2 = cam.proj(V3(G[0] - uz * e, y, G[2] + ux * e))
                if (s0 == null || s1 == null || s2 == null) continue
                val J00 = (s1[0] - s0[0]) / e; val J01 = (s2[0] - s0[0]) / e
                val J10 = (s1[1] - s0[1]) / e; val J11 = (s2[1] - s0[1]) / e
                val u0 = G[0] * ux + G[2] * uz; val v0 = -G[0] * uz + G[2] * ux
                // pattern centimetres -> road frame metres (rotated) -> screen
                val M00 = (J00 * ca - J01 * sa) / 100; val M01 = (J00 * sa + J01 * ca) / 100
                val M10 = (J10 * ca - J11 * sa) / 100; val M11 = (J10 * sa + J11 * ca) / 100
                val ex = s0[0] - (J00 * u0 + J01 * v0); val ey = s0[1] - (J10 * u0 + J11 * v0)
                val op = PyMath.max(0.0, PyMath.min(1.0, 1.3 - (cell.z0 + cell.z1) / 2 / dmax))
                val pid = "pv$nid"; nid += 1
                dst.add(
                    pf(
                        "<pattern id=\"%s\" href=\"#%s\" patternTransform=\"matrix(%.4f %.4f %.4f %.4f %.1f %.1f)\"/><path d=\"%s\" fill=\"url(#%s)\" opacity=\"%.2f\"/>",
                        pid, pat, M00, M10, M01, M11, ex, ey, P(sp), pid, op,
                    ),
                )
            }
        }

        class Near(val seg: Seg, val r: Map<String, Any?>)
        val near = ArrayList<Near>()
        @Suppress("UNCHECKED_CAST")
        for (r in S["roads"] as List<Map<String, Any?>>) {
            val pts = v2s(r["pts"])
            for (i in 0 until pts.size - 1) {
                val (ax, az) = pts[i]; val (bx, bz) = pts[i + 1]
                val dx = bx - ax; val dz = bz - az
                val L2 = (dx * dx + dz * dz).let { if (it == 0.0) 1.0 else it }
                val tt = PyMath.max(0.0, PyMath.min(1.0, ((Cx - ax) * dx + (Cz - az) * dz) / L2))
                if (PyMath.hypot(ax + dx * tt - Cx, az + dz * tt - Cz) < dmax + 12) near.add(Near(Seg(r, ax, az, bx, bz, pyNum(r["w"]) / 2), r))
            }
        }
        val segs = near.map { it.seg }
        for (nr in near) {
            val r = nr.r; val rid = nr.seg.rid
            val ax = nr.seg.ax; val az = nr.seg.az; val bx = nr.seg.bx; val bz = nr.seg.bz; val hw = nr.seg.hw
            if (truthy(r["bridge"])) continue
            val t = r.dict("tags")
            val dx = bx - ax; val dz = bz - az
            val L = PyMath.hypot(dx, dz).let { if (it == 0.0) 1.0 else it }
            val nx = -dz / L; val nz = dx / L // left normal
            val (sides, ssurf) = sidewalkSides(t)
            for (s in sides) {
                val o0: Double; val o1: Double
                if (s < 0) {
                    o0 = hw; o1 = hw + sidewalkW
                } else {
                    o0 = -hw - sidewalkW; o1 = -hw
                }
                val q = listOf(V2(ax + nx * o0, az + nz * o0), V2(bx + nx * o0, bz + nz * o0), V2(bx + nx * o1, bz + nz * o1), V2(ax + nx * o1, az + nz * o1))
                val sp = cam.poly(q.map { (x, z) -> V3(x, 0.13, z) }, margin = 200.0)
                if (sp.isNullOrEmpty()) continue
                under.add(pf("<path d=\"%s\" fill=\"%s\"/>", P(sp), sideCol(ssurf[s])))
                val pat = SIDE_PAT[ssurf[s].let { if (it.isNullOrEmpty()) "" else it }]
                if (pat != null) piece(under, q, 0.13, pat.id, pat.rot)
                // the kerb: a granite edge with its face toward the carriageway; it stops where another street crosses
                val ke = if (s < 0) hw else -hw; val ki = ke + (if (s < 0) 0.15 else -0.15)
                val n_ = Math.max(1L, PyMath.int(L / 1.5)).toInt()
                for (j in 0 until n_) {
                    val f0 = j.toDouble() / n_; val f1 = (j + 1).toDouble() / n_
                    val p0 = V2(ax + dx * f0, az + dz * f0); val p1 = V2(ax + dx * f1, az + dz * f1)
                    val mx = (p0[0] + p1[0]) / 2 + nx * ke; val mz = (p0[1] + p1[1]) / 2 + nz * ke
                    if (_nearCarriageway(segs, mx, mz, rid)) continue
                    val fq = cam.poly(
                        listOf(
                            V3(p0[0] + nx * ke, 0.03, p0[1] + nz * ke), V3(p1[0] + nx * ke, 0.03, p1[1] + nz * ke),
                            V3(p1[0] + nx * ke, 0.14, p1[1] + nz * ke), V3(p0[0] + nx * ke, 0.14, p0[1] + nz * ke),
                        ),
                        margin = 100.0,
                    )
                    val tq = cam.poly(
                        listOf(
                            V3(p0[0] + nx * ke, 0.14, p0[1] + nz * ke), V3(p1[0] + nx * ke, 0.14, p1[1] + nz * ke),
                            V3(p1[0] + nx * ki, 0.14, p1[1] + nz * ki), V3(p0[0] + nx * ki, 0.14, p0[1] + nz * ki),
                        ),
                        margin = 100.0,
                    )
                    if (!fq.isNullOrEmpty()) kerbs.add('f' to P(fq))
                    if (!tq.isNullOrEmpty()) kerbs.add('t' to P(tq))
                }
            }
            val rp = ROAD_PAT[t["surface"] as? String]
            if (rp != null) {
                val q = listOf(V2(ax + nx * hw, az + nz * hw), V2(bx + nx * hw, bz + nz * hw), V2(bx - nx * hw, bz - nz * hw), V2(ax - nx * hw, az - nz * hw))
                piece(over, q, 0.05, rp.id, rp.rot)
            }
        }
        if (kerbs.isNotEmpty()) {
            val kf = if (!night) "#77746E" else "#0C0E15"; val kt = if (!night) "#D5D2CB" else "#1E2232"
            over.add(
                pf(
                    "<path d=\"%s\" fill=\"%s\"/><path d=\"%s\" fill=\"%s\"/>",
                    kerbs.filter { it.first == 'f' }.joinToString("") { it.second }, kf,
                    kerbs.filter { it.first == 't' }.joinToString("") { it.second }, kt,
                ),
            )
        }
        return under.joinToString("") to over.joinToString("")
    }

    // render/closeup.py:185 church_windows
    /** building=church: tall round-arched windows, one per bay of about six metres (a generic detail of the type). */
    fun churchWindows(
        cam: Camera, th: Map<String, Any?>, a: V2, @Suppress("UNUSED_PARAMETER") b: V2, ex: Double, ez: Double, elen: Double, nrm: V3,
        y0: Double, top: Double, d: Double, reveal: String, night: Boolean,
    ): List<String> {
        val hgt = top - y0
        if (hgt < 6 || elen < 3) return emptyList()
        val nb = Math.max(1L, PyMath.int(elen / 6.2)).toInt(); val bw = elen / nb
        val wh = PyMath.min(1.1, bw * 0.2) // half the window's width
        val ys = y0 + PyMath.min(3.2, hgt * 0.22); val ye = top - PyMath.min(2.4, hgt * 0.14) - wh
        if (ye - ys < 2) return emptyList()
        val ux = ex / elen; val uz = ez / elen
        fun W(u: Double, y: Double, off: Double): V3 = V3(a[0] + ux * u + nrm[0] * off, y, a[1] + uz * u + nrm[2] * off)
        fun shape(uc: Double, off: Double, grow: Double = 0.0): List<V3> {
            val w = wh + grow
            val pts = arrayListOf(W(uc - w, ys - grow, off), W(uc + w, ys - grow, off))
            for (k in 0 until 9) {
                val t = Math.PI * k / 8
                pts.add(W(uc + w * PyMath.cos(t), ye + w * PyMath.sin(t), off))
            }
            return pts
        }
        val rev = ArrayList<String>(); val glass = ArrayList<String>(); val stone = ArrayList<String>(); val lead = ArrayList<String>()
        for (k in 0 until nb) {
            val uc = (k + 0.5) * bw
            var o = cam.poly(shape(uc, 0.0, 0.22), margin = 200.0)
            if (o.isNullOrEmpty() || area(o) < 4) continue
            stone.add(P(o))
            o = cam.poly(shape(uc, 0.0), margin = 200.0)
            if (!o.isNullOrEmpty()) rev.add(P(o))
            val g = cam.poly(shape(uc, -0.38), margin = 200.0)
            if (g.isNullOrEmpty()) continue
            glass.add(P(g))
            if (area(g) > 60) { // leading: a mullion and transoms every metre and a half
                val segs = ArrayList<String>()
                var p0 = cam.proj(W(uc, ys, -0.37)); var p1 = cam.proj(W(uc, ye + wh, -0.37))
                if (p0 != null && p1 != null) segs.add(pf("M%.1f %.1fL%.1f %.1f", p0[0], p0[1], p1[0], p1[1]))
                var y = ys + 1.5
                while (y < ye) {
                    p0 = cam.proj(W(uc - wh, y, -0.37)); p1 = cam.proj(W(uc + wh, y, -0.37))
                    if (p0 != null && p1 != null) segs.add(pf("M%.1f %.1fL%.1f %.1f", p0[0], p0[1], p1[0], p1[1]))
                    y += 1.5
                }
                lead.addAll(segs)
            }
        }
        if (glass.isEmpty()) return emptyList()
        val s = cam.focal / PyMath.max(d, 1.0)
        val gfill = if (!night) "#59636E" else "#121622"
        val out = arrayListOf(
            pf("<path d=\"%s\" fill=\"%s\"/>", stone.joinToString(""), if (!night) "#DCD6CA" else "#2A2E3A"),
            pf("<path d=\"%s\" fill=\"%s\"/>", rev.joinToString(""), reveal),
            pf("<path d=\"%s\" fill=\"%s\"/>", glass.joinToString(""), if (th.flag("wx") && !night) "url(#glass)" else gfill),
        )
        if (!night) out.add(pf("<path d=\"%s\" fill=\"#2A3038\" opacity=\".42\"/>", glass.joinToString("")))
        if (lead.isNotEmpty()) {
            out.add(pf("<path d=\"%s\" stroke=\"%s\" stroke-width=\"%.2f\" fill=\"none\"/>", lead.joinToString(""), if (!night) "#262A30" else "#05060A", PyMath.max(0.4, 0.05 * s)))
        }
        return out
    }

    val FORMS: Map<String, String> = linkedMapOf(
        "ulmus" to "vase", "tilia" to "ovoid", "platanus" to "broad", "quercus" to "broad", "aesculus" to "dome", "acer" to "dome",
        "populus" to "column", "salix" to "weep", "fraxinus" to "ovoid", "betula" to "ovoid", "robinia" to "broad",
    )

    // render/closeup.py:236 tree_form
    /** The crown's form from the genus (or species, or taxon), and the genus when it is known. */
    fun treeForm(t: Map<String, Any?>): Pair<String, String?> {
        if (t["leaf_type"] == "needleleaved") return "cone" to null
        val nm = (or(or(or(t["genus"], t["species"]), t["taxon"]), "") as String).split(' ')[0].lowercase()
        return (FORMS[nm] ?: "ovoid") to (if (nm in FORMS) nm else null)
    }

    // render/closeup.py:241 autumn
    /** leaf_cycle=evergreen and needleleaved trees stay green; others turn by the date of the view (a generic seasonal detail). */
    fun autumn(date: Any?, t: Map<String, Any?>): Double {
        if (t["leaf_cycle"] == "evergreen" || t["leaf_type"] == "needleleaved") return 0.0
        val m: Double; val dd: Double
        when (date) {
            is IntArray -> { m = date[1].toDouble(); dd = date[2].toDouble() }
            is List<*> -> { m = pyNum(date[1]); dd = pyNum(date[2]) }
            else -> throw IllegalArgumentException("not a (year, month, day) date: $date")
        }
        val doy = (m - 1) * 30.5 + dd
        return PyMath.max(0.0, PyMath.min(0.7, (doy - 258) / 75)) // mid-September on
    }

    private class Clump(val dd: Double, val x: Double, val y: Double, val z: Double, val br: Double, val nrm: V3)

    // render/closeup.py:248 tree_close
    /** A broadleaved tree up close: trunk and limbs, then a crown of lit and shaded clumps in the form of its genus. */
    fun treeClose(
        cam: Camera, th: Map<String, Any?>, sh: (V3) -> Boolean, n: Map<String, Any?>, d: Double, light: LightFn,
        @Suppress("UNUSED_PARAMETER") base: Any?,
    ): List<String> {
        var night = th.flag("night"); var L = v3(th["_L"])
        val h = pyNum(n["h"]); val r = pyNum(n["crown"]) / 2
        val nx = pyNum(n["x"]); val nz = pyNum(n["z"])
        @Suppress("UNCHECKED_CAST")
        val tags = n["tags"] as Map<String, Any?>
        val date = th["date"]
        if (r * cam.focal / PyMath.max(d, 1.0) < 6.5) { // a few pixels across: one crown, shaded and lit, no more
            val p = cam.proj(V3(nx, h * 0.65, nz)) ?: return emptyList()
            val rr = PyMath.max(0.8, r * cam.focal / PyMath.max(d, 1.0)); night = th.flag("night"); L = v3(th["_L"])
            val tint = autumn(date, tags); val leaf = City.mix(City.hx("#4E6B35"), City.hx("#9C8A36"), tint)
            val c0 = City.tohex(light(th, City.mulc(leaf, 0.7), V3(0.0, 0.3, 0.0), false))
            val c1 = City.tohex(light(th, leaf, City.vnorm(V3(L[0], 0.8, L[2])), !night))
            if (rr < 4.2) return listOf(pf("<circle cx=\"%.1f\" cy=\"%.1f\" r=\"%.1f\" fill=\"%s\"/>", p[0], p[1], rr, City.tohex(City.mix(City.hx(c0), City.hx(c1), 0.55))))
            return listOf(
                pf(
                    "<circle cx=\"%.1f\" cy=\"%.1f\" r=\"%.1f\" fill=\"%s\"/><circle cx=\"%.1f\" cy=\"%.1f\" r=\"%.1f\" fill=\"%s\"/>",
                    p[0], p[1], rr, c0, p[0] - rr * 0.2, p[1] - rr * 0.25, rr * 0.62, c1,
                ),
            )
        }
        val form = treeForm(tags).first
        val rnd = PyRandom(n["id"])
        val cb = h * (if (form != "weep") 0.38 else 0.3) // clear trunk below the crown
        val s = cam.focal / PyMath.max(d, 1.0)
        val out = ArrayList<String>()
        // trunk: tapered, lit on the sun side
        val tw0 = 0.22; val tw1 = 0.12
        val c0 = cam.proj(V3(nx, 0.0, nz)); val c1 = cam.proj(V3(nx, cb + (h - cb) * 0.3, nz))
        if (c0 == null || c1 == null) return emptyList()
        val w0 = PyMath.max(0.5, tw0 * s); val w1 = PyMath.max(0.35, tw1 * s)
        val bark = if (!night) "#4B4038" else "#0B0D10"
        out.add(pf("<path d=\"M%.1f %.1fL%.1f %.1fL%.1f %.1fL%.1f %.1fZ\" fill=\"%s\"/>", c0[0] - w0, c0[1], c0[0] + w0, c0[1], c1[0] + w1, c1[1], c1[0] - w1, c1[1], bark))
        if (!night && s > 4) {
            val sx = if (L[0] * cam.r[0] + L[2] * cam.r[2] > 0) 1 else -1
            out.add(
                pf(
                    "<path d=\"M%.1f %.1fL%.1f %.1fL%.1f %.1fL%.1f %.1fZ\" fill=\"#8A7B6C\" opacity=\".55\"/>",
                    c0[0] + sx * w0 * 0.15, c0[1], c0[0] + sx * w0, c0[1], c1[0] + sx * w1, c1[1], c1[0] + sx * w1 * 0.15, c1[1],
                ),
            )
        }
        // crown clumps
        val rpx = r * cam.focal / PyMath.max(d, 1.0) // crown radius on screen: fewer, larger clumps when it is small
        val nb = if (rpx > 70) 36 else (if (rpx > 34) 26 else (if (rpx > 16) 16 else 9))
        val cy = cb + (h - cb) * 0.5; val ry = (h - cb) / 2
        val cl = ArrayList<Clump>()
        var f = 0.0; var y = 0.0; var R = 0.0
        for (k in 0 until nb) {
            for (attempt in 0 until 8) {
                when (form) {
                    "vase" -> {
                        f = rnd.uniform(0.12, 1.0); y = cb + (h - cb) * f
                        R = r * (0.38 + 0.62 * PyMath.sin(PyMath.min(Math.PI / 2, f * 2.0))) * (1 - PyMath.max(0.0, f - 0.85) * 2.5)
                    }
                    "cone" -> {
                        f = rnd.uniform(0.0, 0.95); y = cb * 0.7 + (h - cb * 0.7) * f; R = r * (1 - f)
                    }
                    "column" -> {
                        f = rnd.uniform(-1.0, 1.0); y = cy + ry * f; R = r * 0.55 * PyMath.sqrt(PyMath.max(0.0, 1 - f * f))
                    }
                    "dome", "broad", "weep" -> {
                        f = rnd.uniform(-0.55, 1.0); y = cy + ry * f
                        R = r * (if (form == "broad") 1.08 else 1.0) * PyMath.sqrt(PyMath.max(0.0, 1 - f * f))
                    }
                    else -> {
                        f = rnd.uniform(-1.0, 1.0); y = cy + ry * f; R = r * PyMath.sqrt(PyMath.max(0.0, 1 - f * f))
                    }
                }
                if (R > 0.2) break
            }
            val ang = rnd.uniform(0.0, 2 * Math.PI); val rad = R * PyMath.sqrt(rnd.uniform(0.35, 1.0))
            val x = nx + rad * PyMath.cos(ang); val z = nz + rad * PyMath.sin(ang)
            val c = cam.cam(V3(x, y, z))
            if (c[2] < 1) continue
            val nrm = City.vnorm(V3(x - nx, (y - cy) * 1.4, z - nz))
            cl.add(Clump(c[2], x, y, z, r * rnd.uniform(0.27, 0.38) * (if (nb < 12) 1.25 else 1.0), nrm))
        }
        if (cl.isEmpty()) return out
        // limbs, from the top of the trunk to the lower clumps, half hidden by the crown
        if (s > 2.5) {
            val limbs = ArrayList<String>()
            for (q in cl.pySortedBy { it.y }.take(5)) {
                val p0 = c1; val p1 = cam.proj(V3(q.x, q.y, q.z))
                if (p1 != null) limbs.add(pf("M%.1f %.1fL%.1f %.1f", p0[0], p0[1], p1[0], p1[1]))
            }
            if (limbs.isNotEmpty()) {
                out.add(pf("<path d=\"%s\" stroke=\"%s\" stroke-width=\"%.2f\" stroke-linecap=\"round\" fill=\"none\"/>", limbs.joinToString(""), bark, PyMath.max(0.4, 0.07 * s)))
            }
        }
        val lit = (!night) && sh(V3(nx + L[0] * r, h * 0.75 + L[1] * r, nz + L[2] * r))
        val tint = autumn(date, tags)
        val leaf = City.mix(City.hx("#4E6B35"), City.hx("#9C8A36"), tint)
        val tones = listOf(
            City.tohex(light(th, City.mulc(leaf, 0.55), V3(0.0, -1.0, 0.0), false)),
            City.tohex(light(th, City.mulc(leaf, 0.8), V3(0.0, 0.3, 0.0), false)),
            City.tohex(light(th, City.mulc(leaf, 0.86), City.vnorm(V3(L[0], 0.8, L[2])), lit)),
            City.tohex(light(th, City.mulc(leaf, 0.93), City.vnorm(V3(L[0], 0.5, L[2])), lit)),
        )
        cl.pySortBy { -it.dd }
        val slices = (0 until 3).map { i -> cl.subList(i * cl.size / 3, (i + 1) * cl.size / 3) }
        val g = ArrayList<String>()
        for (sl in slices) {
            val groups = List(4) { ArrayList<String>() }
            for (q in sl) {
                val p = cam.proj(V3(q.x, q.y, q.z))!!; val rr = q.br * cam.focal / q.dd
                val lam = if (!night) City.vdot(q.nrm, L) else -1.0
                groups[if (q.nrm[1] < -0.45) 0 else 1].add(pf("<circle cx=\"%.1f\" cy=\"%.1f\" r=\"%.1f\"/>", p[0], p[1], rr))
                if (lam > -0.2) {
                    val pq = cam.proj(V3(q.x + L[0] * q.br * 0.35, q.y + L[1] * q.br * 0.35, q.z + L[2] * q.br * 0.35))
                    if (pq != null) groups[2].add(pf("<circle cx=\"%.1f\" cy=\"%.1f\" r=\"%.1f\"/>", pq[0], pq[1], rr * 0.72))
                }
                if (lam > 0.35 && lit) {
                    val pq = cam.proj(V3(q.x + L[0] * q.br * 0.55, q.y + L[1] * q.br * 0.55, q.z + L[2] * q.br * 0.55))
                    if (pq != null) groups[3].add(pf("<circle cx=\"%.1f\" cy=\"%.1f\" r=\"%.1f\"/>", pq[0], pq[1], rr * 0.36))
                }
            }
            for (i in groups.indices) {
                val gr = groups[i]
                if (gr.isNotEmpty()) g.add(pf("<g fill=\"%s\">%s</g>", tones[i], gr.joinToString("")))
            }
        }
        out.add(pf("<g filter=\"url(#crown)\">%s</g>", g.joinToString("")))
        return out
    }

    // render/closeup.py:334 crown_filter
    /** Ragged edges at the scale of leaf clusters, a few gaps of sky, a mottled tone; never per-pixel noise. */
    fun crownFilter(): String =
        "<filter id=\"crown\" x=\"-12%\" y=\"-12%\" width=\"124%\" height=\"124%\">" +
            "<feTurbulence type=\"fractalNoise\" baseFrequency=\".15\" numOctaves=\"2\" seed=\"5\" result=\"n\"/>" +
            "<feDisplacementMap in=\"SourceGraphic\" in2=\"n\" scale=\"10\" xChannelSelector=\"R\" yChannelSelector=\"G\" result=\"d\"/>" +
            "<feTurbulence type=\"fractalNoise\" baseFrequency=\".42\" numOctaves=\"2\" seed=\"2\" result=\"m\"/>" +
            "<feColorMatrix in=\"m\" type=\"matrix\" values=\"1 0 0 0 .5  1 0 0 0 .5  1 0 0 0 .5  0 0 0 0 1\" result=\"tone\"/>" +
            "<feComposite in=\"d\" in2=\"tone\" operator=\"arithmetic\" k1=\"1\" k2=\"0\" k3=\"0\" k4=\"0\" result=\"t\"/>" +
            "<feComposite in=\"t\" in2=\"d\" operator=\"in\"/></filter>"

    // render/closeup.py:344 _seg
    /** A segment in the world, clipped to the near plane, on screen; null when it is wholly behind. */
    fun _seg(cam: Camera, p: V3, q: V3): Pair<V2, V2>? {
        var a = cam.cam(p); var b = cam.cam(q)
        if (a[2] < cam.near && b[2] < cam.near) return null
        if (a[2] < cam.near) {
            val t = (cam.near - a[2]) / (b[2] - a[2]); a = lerp(a, b, t)
        }
        if (b[2] < cam.near) {
            val t = (cam.near - b[2]) / (a[2] - b[2]); b = lerp(b, a, t)
        }
        return cam.pc(a) to cam.pc(b)
    }

    // render/closeup.py:351 coursing
    /**
     * Brick walls up close: the bed joints, one every course (a 5 cm Dutch brick and its mortar), exact in perspective.
     * Drawn from building:material=brick, mapped or guessed; they fade out before they would merge into a moire.
     */
    fun coursing(
        cam: Camera, a: V2, @Suppress("UNUSED_PARAMETER") b: V2, ex: Double, ez: Double, elen: Double, nrm: V3,
        y0: Double, top: Double, course: Double = 0.0625,
    ): List<String> {
        if (top - y0 < 0.3) return emptyList()
        val out = ArrayList<String>()
        val n = Math.max(1L, PyMath.ceilInt(elen / 5.0)).toInt()
        for (k in 0 until n) {
            val u0 = k.toDouble() / n; val u1 = (k + 1).toDouble() / n
            val m = V3(a[0] + ex * (u0 + u1) / 2, (y0 + top) / 2, a[1] + ez * (u0 + u1) / 2)
            var dm = cam.depth(m)
            if (dm < cam.near) dm = cam.near
            val sp = cam.focal * course / dm // course height on screen
            if (sp < 1.6) continue
            val op = PyMath.min(1.0, (sp - 1.6) / 2.2)
            val segs = ArrayList<String>(); var y = y0 + course
            val p0x = a[0] + ex * u0 + nrm[0] * 0.012; val p0z = a[1] + ez * u0 + nrm[2] * 0.012
            val p1x = a[0] + ex * u1 + nrm[0] * 0.012; val p1z = a[1] + ez * u1 + nrm[2] * 0.012
            while (y < top - 0.02) {
                val r = _seg(cam, V3(p0x, y, p0z), V3(p1x, y, p1z))
                if (r != null) {
                    val (x0_, y0_) = r.first; val (x1_, y1_) = r.second
                    if (!(PyMath.max(x0_, x1_) < -5 || PyMath.min(x0_, x1_) > cam.W + 5 || PyMath.max(y0_, y1_) < -5 || PyMath.min(y0_, y1_) > cam.H + 5)) {
                        segs.add(pf("M%.1f %.1fl%.1f %.1f", x0_, y0_, x1_ - x0_, y1_ - y0_))
                    }
                }
                y += course
            }
            val stone = course >= 0.3
            if (stone && segs.isNotEmpty()) {
                // cut stone: the blocks' head joints too, about twice as long as a course is high, staggered course by course
                val bl = course * 2.1; y = y0; var row = 0; val segLen = elen * (u1 - u0)
                while (y < top - course * 0.5) {
                    val y1_ = PyMath.min(top, y + course); val off = (row % 2) * bl / 2; var t = off
                    while (t < segLen) {
                        val f = u0 + t / elen
                        val r = _seg(
                            cam, V3(a[0] + ex * f + nrm[0] * 0.012, y, a[1] + ez * f + nrm[2] * 0.012),
                            V3(a[0] + ex * f + nrm[0] * 0.012, y1_, a[1] + ez * f + nrm[2] * 0.012),
                        )
                        if (r != null) {
                            val (x0_, y0_) = r.first; val (x1_, y1s) = r.second
                            segs.add(pf("M%.1f %.1fl%.1f %.1f", x0_, y0_, x1_ - x0_, y1s - y0_))
                        }
                        t += bl
                    }
                    y += course; row += 1
                }
            }
            if (segs.isNotEmpty()) {
                out.add(
                    pf(
                        "<path d=\"%s\" stroke=\"%s\" stroke-width=\"%.2f\" opacity=\"%.2f\" fill=\"none\"/>",
                        segs.joinToString(""), if (stone) "#5E5548" else "#E2D9CB", PyMath.max(0.35, cam.focal * (if (stone) 0.008 else 0.011) / dm),
                        (if (stone) 0.26 else 0.3) * op,
                    ),
                )
            }
        }
        return out
    }

    private val CLOUD_EDGES = doubleArrayOf(89.0, 62.0, 44.0, 31.0, 22.0, 15.5, 11.0, 7.5, 5.0, 3.2, 2.0, 1.1)

    // render/closeup.py:391 sky_clouds
    /**
     * Clouds as a layer at height, seen in perspective: big heaps overhead, thin flat bands toward the horizon.
     * Coverage from the real cloud cover; relief lit from the real sun (feDiffuseLighting on the noise as a height field).
     * The filters, gradients and masks go into [defs]; the returned text holds the bands.
     */
    fun skyClouds(
        cam: Camera, th: Map<String, Any?>, W: Double, hy: Double, defs: MutableList<String>, eyeY: Double = 1.7, tile: Boolean = false,
    ): String {
        val w = th.dict("wx"); val night = th.flag("night"); val L = v3(th["_L"]); val over = th.flag("sky_k")
        val Hc: Double; val lam: Double; val k: Double; val t: Double
        if (night) {
            Hc = 600.0; lam = 1100.0; k = 3.2; t = 0.3
        } else if (over) {
            Hc = 800.0; lam = 1700.0; k = 2.4; t = -0.1
        } else {
            Hc = 1100.0; lam = 520.0; k = 6.0; t = 0.58 - pyNum(w["cloud"]) * 0.28
        }
        val sh_: V3; val li_: V3
        if (night) {
            sh_ = V3(0.085, 0.085, 0.115); li_ = V3(0.27, 0.22, 0.23)
        } else if (over) {
            sh_ = V3(0.55, 0.58, 0.63); li_ = V3(0.87, 0.89, 0.91)
        } else {
            sh_ = V3(0.71, 0.75, 0.83); li_ = V3(1.0, 0.99, 0.96)
        }
        // the light, in filter space (x right, y down, z toward the viewer)
        var lv: V3 = if (night) {
            V3(0.0, 0.55, 0.83) // the city lights the cloud from below
        } else if (over) {
            V3(0.0, -0.35, 0.94) // light through the deck from above
        } else {
            val lx = L[0] * cam.r[0] + L[1] * cam.r[1] + L[2] * cam.r[2]; val ly = L[0] * cam.u[0] + L[1] * cam.u[1] + L[2] * cam.u[2]
            val lz = L[0] * cam.f[0] + L[1] * cam.f[1] + L[2] * cam.f[2]
            V3(lx, -ly, -lz)
        }
        val n_ = PyMath.sqrt(PyMath.sum(doubleArrayOf(lv[0] * lv[0], lv[1] * lv[1], lv[2] * lv[2]))).let { if (it == 0.0) 1.0 else it }
        lv = V3(lv[0] / n_, lv[1] / n_, lv[2] / n_)
        val laz = PyMath.degrees(PyMath.atan2(lv[1], lv[0])); val lel = PyMath.degrees(PyMath.asin(PyMath.max(-1.0, PyMath.min(1.0, lv[2]))))
        fun elev(y: Double): Double {
            val d = DoubleArray(3) { q -> cam.u[q] * (-(y - cam.cy)) + cam.f[q] * cam.focal }
            val n = PyMath.sqrt(PyMath.sum(doubleArrayOf(d[0] * d[0], d[1] * d[1], d[2] * d[2])))
            return PyMath.degrees(PyMath.asin(d[1] / n))
        }
        fun yAt(e: Double): Double {
            var lo = -4000.0; var hi = hy
            for (i in 0 until 40) {
                val m = (lo + hi) / 2
                if (elev(m) > e) lo = m else hi = m
            }
            return (lo + hi) / 2
        }
        val edges = CLOUD_EDGES.filter { it < elev(0.0) - 0.5 }
        val ys0 = ArrayList<Double>()
        ys0.add(0.0); for (e in edges) ys0.add(yAt(e)); ys0.add(hy + 1)
        // sorted(set(round(y, 1) ...)): equal values (0.0 and -0.0 too) collapse into the first seen
        val uniq = ArrayList<Double>()
        for (y in ys0) {
            if (!(-1 <= y && y <= hy + 1)) continue
            val v = PyMath.round(y, 1)
            if (uniq.none { it == v }) uniq.add(v)
        }
        val ys = uniq.pySortedBy { it }
        val out = ArrayList<String>()
        val mr = sh_[0]; val mg = sh_[1]; val mb = sh_[2]
        val gr = li_[0] - sh_[0]; val gg = li_[1] - sh_[1]; val gb = li_[2] - sh_[2]
        for (i in 0 until ys.size - 1) {
            val y0 = ys[i]; val y1 = ys[i + 1]
            if (y1 - y0 < 1) continue
            val em = PyMath.max(0.6, elev((y0 + y1) / 2))
            val S = (Hc - eyeY) / PyMath.sin(PyMath.radians(em))
            val fx = PyMath.min(0.05, S / (cam.focal * lam)); val fy = PyMath.min(0.11, fx / PyMath.sin(PyMath.radians(em)) * 0.85)
            val ov = PyMath.max(6.0, (y1 - y0) * 0.35)
            val a0 = if (i != 0) y0 - ov else -40.0 // the top band starts above the frame: a lit edge never shows
            val a1 = PyMath.min(hy + 1, y1 + ov)
            val ss = PyMath.min(if (over || night) 22.0 else 14.0, 0.045 / ((fx + fy) / 2))
            // tiled: the noise repeats every W pixels across, so a sky twice as wide can slide by W and loop without a seam
            val turb = if (tile) {
                pf(
                    "<feTurbulence type=\"fractalNoise\" baseFrequency=\"%.5f %.5f\" numOctaves=\"5\" seed=\"31\" stitchTiles=\"stitch\" x=\"0\" y=\"%.0f\" width=\"%d\" height=\"%.0f\" result=\"n0\"/><feTile in=\"n0\" result=\"n\"/>",
                    fx, fy, a0 - 2, W, a1 - a0 + 4,
                )
            } else {
                pf("<feTurbulence type=\"fractalNoise\" baseFrequency=\"%.5f %.5f\" numOctaves=\"5\" seed=\"31\" result=\"n\"/>", fx, fy)
            }
            defs.add(
                pf(
                    "<filter id=\"ck%d\" filterUnits=\"userSpaceOnUse\" x=\"%d\" y=\"%.0f\" width=\"%d\" height=\"%.0f\" color-interpolation-filters=\"sRGB\">",
                    i, if (tile) -20 else 0, a0 - 2, if (tile) (2 * W + 40) else W, a1 - a0 + 4,
                ) + turb +
                    pf(
                        "<feColorMatrix in=\"n\" type=\"matrix\" values=\"0 0 0 0 0  0 0 0 0 0  0 0 0 0 0  1 0 0 0 0\" result=\"h\"/>" +
                            "<feDiffuseLighting in=\"h\" surfaceScale=\"%.1f\" diffuseConstant=\"1.1\" lighting-color=\"#FFFFFF\" result=\"l\"><feDistantLight azimuth=\"%.0f\" elevation=\"%.0f\"/></feDiffuseLighting>" +
                            "<feColorMatrix in=\"l\" type=\"matrix\" values=\"%.3f 0 0 0 %.3f  0 %.3f 0 0 %.3f  0 0 %.3f 0 %.3f  0 0 0 0 1\" result=\"c\"/>" +
                            "<feColorMatrix in=\"n\" type=\"matrix\" values=\"0 0 0 0 0  0 0 0 0 0  0 0 0 0 0  %.2f 0 0 0 %.2f\" result=\"a\"/>" +
                            "<feComposite in=\"c\" in2=\"a\" operator=\"in\"/></filter>",
                        ss, laz, lel, gr, mr, gg, mg, gb, mb, k, -k * t,
                    ),
            )
            val stops = StringBuilder()
            val span = a1 - a0
            val rows = arrayOf(
                doubleArrayOf(a0, if (i != 0) 0.0 else 1.0),
                doubleArrayOf(if (i != 0) PyMath.min(a1, a0 + 2 * ov) else a0, 1.0),
                doubleArrayOf(PyMath.max(a0, a1 - 2 * ov), 1.0),
                doubleArrayOf(a1, 0.0),
            )
            for (row in rows) {
                val yy = row[0]; val op = row[1]
                stops.append(pf("<stop offset=\"%.3f\" stop-color=\"#FFF\" stop-opacity=\"%.2f\"/>", if (span != 0.0) (yy - a0) / span else 0.0, op))
            }
            val WW = if (tile) 2 * W else W
            defs.add(
                pf(
                    "<linearGradient id=\"ckg%d\" gradientUnits=\"userSpaceOnUse\" x1=\"0\" y1=\"%.1f\" x2=\"0\" y2=\"%.1f\">%s</linearGradient><mask id=\"ckm%d\" maskUnits=\"userSpaceOnUse\" x=\"0\" y=\"%.1f\" width=\"%d\" height=\"%.1f\"><rect y=\"%.1f\" width=\"%d\" height=\"%.1f\" fill=\"url(#ckg%d)\"/></mask>",
                    i, a0, a1, stops.toString(), i, a0, WW, span, a0, WW, span, i,
                ),
            )
            out.add(pf("<rect y=\"%.1f\" width=\"%d\" height=\"%.1f\" filter=\"url(#ck%d)\" mask=\"url(#ckm%d)\"/>", a0, WW, span, i, i))
        }
        return out.joinToString("")
    }
}

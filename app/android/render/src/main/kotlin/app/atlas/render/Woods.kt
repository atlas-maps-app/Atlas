// render/woods.py: woods with trees in them. OSM maps a wood as its outline; the trees inside are planted generically, and
// marked so, kept off everything mapped inside the outline, and drawn like model-railway trees. Ported line by line.
package app.atlas.render

import app.atlas.render.City.mulc
import app.atlas.render.City.vnorm
import app.atlas.render.Real.pip
import app.atlas.render.py.Dict
import app.atlas.render.py.PyFmt.pf
import app.atlas.render.py.PyMath
import app.atlas.render.py.PyRandom
import app.atlas.render.py.dictOf
import app.atlas.render.py.pyNum
import app.atlas.render.py.truthy

/** render/woods.py. */
object Woods {
    val CLEAR = setOf("grass", "garden", "playground", "pitch", "pedestrian", "parking", "flowerbed", "sand", "park", "track", "meadow", "grassland", "bare_rock")

    // render/woods.py:11 Grid
    /** A 2 m occupancy grid: cells marked by what is mapped, where no tree is planted. */
    class Grid(val x0: Double, x1: Double, val z0: Double, z1: Double, val c: Double = 2.0) {
        val nx: Int = PyMath.int((x1 - x0) / c).toInt() + 1
        val nz: Int = PyMath.int((z1 - z0) / c).toInt() + 1
        val b = ByteArray(nx * nz)

        // render/woods.py:16 Grid.mark_disc
        fun markDisc(x: Double, z: Double, r: Double) {
            val i0 = PyMath.int((x - r - x0) / c).toInt(); val i1 = PyMath.int((x + r - x0) / c).toInt()
            val j0 = PyMath.int((z - r - z0) / c).toInt(); val j1 = PyMath.int((z + r - z0) / c).toInt()
            for (j in Math.max(0, j0) until Math.min(nz, j1 + 1)) {
                for (i in Math.max(0, i0) until Math.min(nx, i1 + 1)) {
                    if (PyMath.pow(x0 + i * c - x, 2) + PyMath.pow(z0 + j * c - z, 2) <= r * r) b[j * nx + i] = 1
                }
            }
        }

        // render/woods.py:21 Grid.mark_line
        fun markLine(pts: List<V2>, r: Double) {
            for (k in 0 until pts.size - 1) {
                val a = pts[k]; val bb = pts[k + 1]; val n = Math.max(1, PyMath.int(Amsterdam.dist(a, bb) / (c * 0.8)).toInt())
                for (t in 0..n) {
                    val f = t.toDouble() / n; markDisc(a[0] + (bb[0] - a[0]) * f, a[1] + (bb[1] - a[1]) * f, r)
                }
            }
        }

        // render/woods.py:26 Grid.mark_poly
        fun markPoly(poly: List<V2>, grow: Double = 0.0) {
            var zmin = poly[0].y; var zmax = poly[0].y
            for (p in poly) { if (p.y < zmin) zmin = p.y; if (p.y > zmax) zmax = p.y }
            for (j in Math.max(0, PyMath.int((zmin - z0) / c).toInt()) until Math.min(nz, PyMath.int((zmax - z0) / c).toInt() + 1)) {
                val z = z0 + j * c; val xs = ArrayList<Double>()
                for (k in poly.indices) {
                    val a = poly[k]; val bb = poly[(k + 1) % poly.size]
                    if ((a.y <= z && z < bb.y) || (bb.y <= z && z < a.y)) xs.add(a.x + (z - a.y) * (bb.x - a.x) / (bb.y - a.y))
                }
                xs.sortWith { p, q -> java.lang.Double.compare(p, q) }
                var q = 0
                while (q + 1 < xs.size) {
                    val a = xs[q]; val bb = xs[q + 1]
                    for (i in Math.max(0, PyMath.int((a - grow - x0) / c).toInt()) until Math.min(nx, PyMath.int((bb + grow - x0) / c).toInt() + 1)) b[j * nx + i] = 1
                    q += 2
                }
            }
            if (grow > 0) markLine(poly + listOf(poly[0]), grow)
        }

        // render/woods.py:37 Grid.free
        fun free(x: Double, z: Double): Boolean {
            val i = PyMath.round((x - x0) / c).toInt(); val j = PyMath.round((z - z0) / c).toInt()
            return 0 <= i && i < nx && 0 <= j && j < nz && b[j * nx + i].toInt() == 0
        }
    }

    // render/woods.py:41 plant
    /** Generic trees in the mapped woods within [rad] of (cx, cz): dicts x, z, h, crown, id, generic. */
    @Suppress("UNCHECKED_CAST")
    fun plant(S: Map<String, Any?>, cx: Double, cz: Double, rad: Double, spacing: Double = 8.5, seed: Long = 7): MutableList<Dict> {
        val G = Grid(cx - rad, cx + rad, cz - rad, cz + rad)
        val near: (List<V2>) -> Boolean = { pts ->
            (Regions.every(pts, Math.max(1, pts.size / 12)) + listOf(pts[pts.size - 1])).any { p -> Math.abs(p[0] - cx) < rad + 40 && Math.abs(p[1] - cz) < rad + 40 }
        }
        for (b in (S["buildings"] as List<Dict>) + (S["parts"] as List<Dict>)) {
            val poly = Amsterdam.polyOf(b); if (near(poly)) G.markPoly(poly, 3.0)
        }
        for (r in S["roads"] as List<Dict>) {
            val pts = Amsterdam.ptsOf(r)
            if (near(pts)) G.markLine(pts, (if (truthy(r["w"])) pyNum(r["w"]) else 2.0) / 2 + 1.6)
        }
        for (w in S["water"] as List<List<V2>>) if (near(w)) G.markPoly(w, 1.5)
        for (r in (S["rail"] as List<Dict>?) ?: emptyList()) { val pts = Amsterdam.ptsOf(r); if (near(pts)) G.markLine(pts, 2.6) }
        for (tr in (S["coasters"] as List<Coasters.Track>?) ?: emptyList()) if (near(tr.pts)) G.markLine(tr.pts, 3.2)
        for (a in S["land"] as List<Dict>) {
            val poly = Amsterdam.polyOf(a)
            if (a["kind"] in CLEAR && near(poly)) G.markPoly(poly)
        }
        val rnd = PyRandom(seed); val trees = ArrayList<Dict>()
        val woods = (S["land"] as List<Dict>).filter { (it["kind"] == "forest" || it["kind"] == "wood") && near(Amsterdam.polyOf(it)) }
        val dz = spacing * PyMath.sqrt(3.0) / 2
        for (a in woods) {
            val poly = Amsterdam.polyOf(a)
            val xs = poly.map { it.x }; val zs = poly.map { it.y }
            var j = 0; var z = PyMath.max(City.pyMinOf(zs), cz - rad)
            while (z <= PyMath.min(City.pyMaxOf(zs), cz + rad)) {
                var x = PyMath.max(City.pyMinOf(xs), cx - rad) + (if (j % 2 != 0) spacing / 2 else 0.0)
                while (x <= PyMath.min(City.pyMaxOf(xs), cx + rad)) {
                    val px = x + rnd.uniform(-2.6, 2.6); val pz = z + rnd.uniform(-2.2, 2.2)
                    if (PyMath.hypot(px - cx, pz - cz) <= rad && G.free(px, pz) && pip(px, pz, poly)) {
                        val h = rnd.uniform(13.0, 21.0)
                        val crown = rnd.uniform(6.0, 9.0)
                        trees.add(dictOf("x" to px, "z" to pz, "h" to h, "crown" to crown, "id" to rnd.randrange(1L shl 30), "generic" to true))
                    }
                    x += spacing
                }
                z += dz; j += 1
            }
        }
        return trees
    }

    /** woods.py's `light` parameter: amsterdam.py's light(th, base, n, lit). */
    fun interface Light {
        fun light(th: Map<String, Any?>, base: V3, n: V3, lit: Boolean): V3
    }

    // render/woods.py:73 draw
    /** Items (depth, "svg", body) for each generic tree, and the shadow paths for the ground. */
    fun draw(trees: List<Dict>, cam: City.Camera, th: Map<String, Any?>, far: (Double, Double) -> Boolean, light: Light): Pair<List<Amsterdam.Item>, List<String>> {
        val night = th["night"] == true; val L = v3(th["_L"]); val items = ArrayList<Amsterdam.Item>(); val shadows = ArrayList<String>()
        val sun = !night && L[1] > 0.05
        val tones = ArrayList<Triple<String, String, String>>()
        for (c0 in listOf("#3F5E34", "#4A6538", "#435F3C", "#526A3A", "#4B5F33")) { // a few generic greens, so a wood is not one colour
            val base = City.hx(c0)
            tones.add(Triple(City.tohex(light.light(th, mulc(base, 0.6), V3(0.0, 1.0, 0.0), false)), City.tohex(light.light(th, base, vnorm(V3(L[0], 0.8, L[2])), !night)),
                City.tohex(light.light(th, mulc(base, 1.12), vnorm(V3(L[0], 0.6, L[2])), !night))))
        }
        for (n in trees) {
            val id = (n["id"] as Number).toLong()
            val (dark, mid, hi) = tones[PyMath.mod(id, tones.size.toLong()).toInt()]
            val x = pyNum(n["x"]); val z = pyNum(n["z"]); val h = pyNum(n["h"])
            if (far(x, z)) continue
            val c = cam.cam(V3(x, h * 0.7, z))
            if (c[2] < 4) continue
            val s = cam.focal / c[2]; val r = pyNum(n["crown"]) / 2
            val q0 = cam.proj(V3(x, h * 0.7, z))
            if (q0 == null || !(-30 < q0[0] && q0[0] < cam.W + 30 && -30 < q0[1] && q0[1] < cam.H + 30)) continue // outside the picture: not written
            val rnd = PyRandom(id)
            if (sun) {
                val ox = -L[0] / L[1] * h * 0.72; val oz = -L[2] / L[1] * h * 0.72
                val sp = cam.poly((0 until 12).map { k -> val a = k * Math.PI / 6; V3(x + ox + r * 0.95 * PyMath.cos(a), 0.12, z + oz + r * 0.95 * PyMath.sin(a)) })
                if (sp != null) shadows.add(Regions.runD(sp) + "Z")
            }
            val under = StringBuilder(); val top = StringBuilder(); val glint = StringBuilder()
            var haveUnder = false
            for (k in 0 until 4) {
                val ang = rnd.uniform(0.0, 2 * Math.PI); val rad = r * 0.42 * PyMath.sqrt(rnd.random())
                val bx = x + rad * PyMath.cos(ang); val bz = z + rad * PyMath.sin(ang); val by = h * 0.7 + rnd.uniform(-0.25, 0.25) * r
                val p = cam.proj(V3(bx, by, bz)) ?: continue
                val rr = r * rnd.uniform(0.5, 0.66) * s
                under.append(pf("<circle cx=\"%.1f\" cy=\"%.1f\" r=\"%.1f\"/>", p[0], p[1], rr)); haveUnder = true
                val q = if (sun) cam.proj(V3(bx + L[0] * r * 0.3, by + L[1] * r * 0.3, bz + L[2] * r * 0.3)) else null
                if (q != null) top.append(pf("<circle cx=\"%.1f\" cy=\"%.1f\" r=\"%.1f\"/>", q[0], q[1], rr * 0.74))
                if (q != null && k == 0) glint.append(pf("<circle cx=\"%.1f\" cy=\"%.1f\" r=\"%.1f\"/>", q[0] + (q[0] - p[0]) * 0.5, q[1] + (q[1] - p[1]) * 0.5, rr * 0.36))
            }
            if (!haveUnder) continue
            var body = pf("<g fill=\"%s\">%s</g>", dark, under.toString())
            if (top.isNotEmpty()) body += pf("<g fill=\"%s\">%s</g>", mid, top.toString())
            if (glint.isNotEmpty()) body += pf("<g fill=\"%s\">%s</g>", hi, glint.toString())
            items.add(Amsterdam.Item(c[2], "svg", body))
        }
        return items to shadows
    }
}

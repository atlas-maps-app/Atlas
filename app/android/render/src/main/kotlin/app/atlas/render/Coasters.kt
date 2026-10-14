// render/coasters.py: coaster tracks standing up, from OpenStreetMap (roller_coaster=track). OSM maps a track's line, its layer and
// which pieces are the lift hill, the station and the brake run, not how high it runs: the heights are generic and marked guessed.
// Ported line by line.
package app.atlas.render

import app.atlas.render.Real.pip
import app.atlas.render.py.PyFmt.pf
import app.atlas.render.py.PyMath
import app.atlas.render.py.truthy

/** render/coasters.py. */
object Coasters {
    val LAYER_SHARE: Map<Int, Double?> = mapOf(0 to null, 1 to 0.45, 2 to 0.75, 3 to 1.0)
    const val LOW = 1.6 // a track at layer 0: just off the ground
    const val STATION = 1.2

    /** A track piece: its points, the height at each, its kind ("steel", "wood", "mono"), ride and role; heights guessed. */
    class Track(val pts: List<V2>, val ys: List<Double>, val kind: String, val ride: String?, val role: String, val guessed: Boolean = true)

    // render/coasters.py:17 smooth
    fun smooth(t0: Double): Double {
        val t = PyMath.max(0.0, PyMath.min(1.0, t0)); return t * t * (3 - 2 * t)
    }

    private class Way(val pts: List<V2>, val keys: List<V2>, val kind: String, val ride: String?, val h: Double?, val role: String, val H: Double)

    private fun d(v: Any?): Double = (v as Number).toDouble()

    /** Python `float(s)` of an OSM string, as `int(float(t.get('layer', '0') or 0))` reads it. */
    private fun pyFloat(v: Any?): Double = when (v) {
        null -> 0.0
        is Number -> v.toDouble()
        is String -> if (v.isEmpty()) 0.0 else v.trim().replace("_", "").toDouble()
        else -> throw IllegalArgumentException("float() of $v")
    }

    // render/coasters.py:19 load
    /**
     * raw: OSM elements. heights: ride name to top height in metres (published). kinds: ride name to "wood" or "steel".
     * Returns the tracks.
     */
    @Suppress("UNCHECKED_CAST")
    fun load(raw: List<Map<String, Any?>>, XZ: (Double, Double) -> V2, buildings: List<Map<String, Any?>>, heights: Map<String, Double>, kinds: Map<String, String>): List<Track> {
        val rides = ArrayList<Pair<String, V2>>()
        for (e in raw) {
            val t = (e["tags"] as Map<String, Any?>?) ?: emptyMap()
            if (t["attraction"] != "roller_coaster" || !truthy(t["name"])) continue
            if (e["type"] == "node") rides.add(t["name"] as String to XZ(d(e["lat"]), d(e["lon"])))
            else if (truthy(e["geometry"])) {
                val g = (e["geometry"] as List<Map<String, Any?>?>).filter { truthy(it) }.map { it!! }
                rides.add(t["name"] as String to XZ(PyMath.sumOf(g) { d(it["lat"]) } / g.size, PyMath.sumOf(g) { d(it["lon"]) } / g.size))
            }
        }
        val bpolys = ArrayList<Pair<DoubleArray, List<V2>>>()
        for (b in buildings) {
            val poly = Amsterdam.polyOf(b)
            val xs = poly.map { it.x }; val zs = poly.map { it.y }
            bpolys.add(doubleArrayOf(City.pyMinOf(xs), City.pyMaxOf(xs), City.pyMinOf(zs), City.pyMaxOf(zs)) to poly)
        }
        fun indoors(p: V2): Boolean = bpolys.any { (bb, poly) -> bb[0] <= p.x && p.x <= bb[1] && bb[2] <= p.y && p.y <= bb[3] && pip(p.x, p.y, poly) }
        val ways = ArrayList<Way>()
        for (e in raw) {
            val t = (e["tags"] as Map<String, Any?>?) ?: emptyMap()
            val mono = t["railway"] == "monorail"
            if (!(t["roller_coaster"] == "track" || mono) || e["type"] != "way" || !truthy(e["geometry"])) continue
            val lay = PyMath.int(pyFloat(t["layer"] ?: "0")).toInt()
            if (t["tunnel"] in setOf("yes", "building_passage") || lay < 0 || t["indoor"] == "yes") continue
            val geo = (e["geometry"] as List<Map<String, Any?>?>).filter { truthy(it) }.map { it!! }
            val pts = geo.map { XZ(d(it["lat"]), d(it["lon"])) }
            val keys = geo.map { V2(PyMath.round(d(it["lat"]), 7) + 0.0, PyMath.round(d(it["lon"]), 7) + 0.0) }
            val mid = pts[pts.size / 2]
            if (indoors(mid) && indoors(pts[0]) && indoors(pts[pts.size - 1])) continue
            if (mono) {
                ways.add(Way(pts, keys, "mono", (t["name"] as String?)?.takeIf { it.isNotEmpty() } ?: "Monorail", 3.5, "mono", 0.0)); continue
            }
            val cx = PyMath.sumOf(pts) { it.x } / pts.size; val cz = PyMath.sumOf(pts) { it.y } / pts.size
            var ride: String? = null
            if (rides.isNotEmpty()) {
                var best = rides[0]; var bd = Amsterdam.dist(best.second, V2(cx, cz))
                for (r in rides) { val dd = Amsterdam.dist(r.second, V2(cx, cz)); if (dd < bd) { bd = dd; best = r } }
                ride = best.first
            }
            if (t["name"] == "Max" || t["name"] == "Moritz") ride = "Max & Moritz"
            val H = heights[ride] ?: 12.0
            val role = (t["roller_coaster:track"] as String?)?.takeIf { it.isNotEmpty() } ?: "track"
            val h: Double? = when (role) {
                "station", "brake_run" -> STATION
                "lift_hill" -> null
                else -> { val sh = LAYER_SHARE[lay]; if (sh == null || sh == 0.0) LOW else PyMath.max(LOW, H * sh) }
            }
            ways.add(Way(pts, keys, kinds[ride] ?: "steel", ride, h, role, H))
        }
        // the height where pieces meet: the mean of the pieces meeting at that node
        val at = LinkedHashMap<V2, MutableList<Double>>()
        for (w in ways) {
            val h = w.h ?: continue
            for (k in listOf(w.keys[0], w.keys[w.keys.size - 1])) at.getOrPut(k) { ArrayList() }.add(h)
        }
        val nodeH = at.mapValues { (_, v) -> PyMath.sum(v.toDoubleArray()) / v.size }
        val out = ArrayList<Track>()
        for (w in ways) {
            val pts = w.pts; val s = ArrayList<Double>(pts.size); s.add(0.0)
            for (i in 1 until pts.size) s.add(s[s.size - 1] + Amsterdam.dist(pts[i - 1], pts[i]))
            val Lw = s[s.size - 1].let { if (it == 0.0) 1.0 else it }
            var h0 = nodeH[w.keys[0]]; var h1 = nodeH[w.keys[w.keys.size - 1]]
            val ys: List<Double>
            if (w.h == null) { // the lift hill: from the low end up to the ride's top
                val a = h0 ?: LOW; val b = h1 ?: w.H
                val lo = if (b < a) b else a; var hi = if (b < a) a else b
                hi = PyMath.max(hi, w.H * 0.95)
                val up = (h1.let { if (it == null || it == 0.0) 0.0 else it }) >= (h0.let { if (it == null || it == 0.0) 0.0 else it })
                ys = s.map { si -> lo + (hi - lo) * (if (up) si / Lw else 1 - si / Lw) }
            } else {
                val hw = w.h; if (h0 == null) h0 = hw; if (h1 == null) h1 = hw
                val ramp = PyMath.min(18.0, Lw / 2)
                ys = s.map { si -> hw + (h0 - hw) * (1 - smooth(si / ramp)) + (h1 - hw) * (1 - smooth((Lw - si) / ramp)) }
            }
            out.add(Track(pts, ys, w.kind, w.ride, w.role, true))
        }
        return out
    }

    // render/coasters.py:83 subdivide
    fun subdivide(pts: List<V2>, ys: List<Double>, step: Double): Pair<List<V2>, List<Double>> {
        val P = arrayListOf(pts[0]); val Y = arrayListOf(ys[0])
        for (i in 1 until pts.size) {
            val a = pts[i - 1]; val b = pts[i]; val n = Math.max(1, PyMath.int(Amsterdam.dist(a, b) / step).toInt())
            for (k in 1..n) {
                val f = k.toDouble() / n
                P.add(V2(a[0] + (b[0] - a[0]) * f, a[1] + (b[1] - a[1]) * f)); Y.add(ys[i - 1] + (ys[i] - ys[i - 1]) * f)
            }
        }
        return P to Y
    }

    val COL: Map<String, Pair<String, String>> = mapOf("steel" to ("#E6E3DC" to "#5F656D"), "wood" to ("#B9925E" to "#8A6640"), "mono" to ("#D6D2C8" to "#8C877C"))
    val NIGHT: Map<String, Pair<String, String>> = mapOf("steel" to ("#2A2E36" to "#1A1D22"), "wood" to ("#2E261C" to "#1E1912"), "mono" to ("#2A2A2A" to "#1A1A1A"))

    // render/coasters.py:95 draw
    /** Items (depth, "svg", body) for the tracks and their posts, and the shadow paths (path, width) for the ground. */
    fun draw(tracks: List<Track>, cam: City.Camera, th: Map<String, Any?>, far: (Double, Double) -> Boolean, L: V3, light: Woods.Light): Pair<List<Amsterdam.Item>, List<Pair<String, Double>>> {
        val night = th["night"] == true; val items = ArrayList<Amsterdam.Item>(); val shadow = ArrayList<Pair<String, Double>>()
        val sun = !night && L[1] > 0.05
        fun off(y: Double): V2 = V2(-L[0] / L[1] * y, -L[2] / L[1] * y)
        for (tr in tracks) {
            if ((Regions.every(tr.pts, 4) + listOf(tr.pts[tr.pts.size - 1])).all { far(it.x, it.y) }) continue
            val (P, Y) = subdivide(tr.pts, tr.ys, 3.0)
            val (top, side) = (if (night) NIGHT else COL).getValue(tr.kind)
            val lit = City.tohex(light.light(th, City.hx(top), V3(0.0, 1.0, 0.0), !night))
            val dark = City.tohex(light.light(th, City.hx(side), V3(0.0, 0.0, 1.0), false))
            val gap = if (tr.kind == "wood") 2.6 else 7.5
            var acc = 0.0; val posts = ArrayList<Pair<V2, V2>>()
            for (i in 1 until P.size) {
                val a = P[i - 1]; val b = P[i]; val ya = Y[i - 1]; val yb = Y[i]
                val m = V3((a[0] + b[0]) / 2, (ya + yb) / 2, (a[1] + b[1]) / 2)
                val c = cam.cam(m)
                if (c[2] < 3) continue
                val s = cam.focal / c[2]
                val pa = cam.proj(V3(a[0], ya, a[1])); val pb = cam.proj(V3(b[0], yb, b[1]))
                if (pa == null || pb == null) continue
                if (!listOf(pa, pb).any { q -> -40 < q[0] && q[0] < cam.W + 40 && -40 < q[1] && q[1] < cam.H + 40 }) continue // outside the picture
                val g = StringBuilder()
                acc += Amsterdam.dist(a, b)
                if (acc >= gap && yb > 2.2) {
                    acc = 0.0
                    val g0 = cam.proj(V3(b[0], 0.0, b[1]))
                    if (g0 != null) {
                        g.append(pf("<path d=\"M%.1f %.1fL%.1f %.1f\" stroke=\"%s\" stroke-width=\"%.2f\"/>", g0[0], g0[1], pb[0], pb[1], dark, PyMath.max(0.5, (if (tr.kind == "wood") 0.5 else 0.7) * s)))
                        if (tr.kind == "wood" && posts.isNotEmpty()) {
                            val (q0, q1) = posts[posts.size - 1]
                            g.append(pf("<path d=\"M%.1f %.1fL%.1f %.1fM%.1f %.1fL%.1f %.1f\" stroke=\"%s\" stroke-width=\"%.2f\" opacity=\".85\"/>", q0[0], q0[1], pb[0], pb[1], q1[0], q1[1], g0[0], g0[1], dark, PyMath.max(0.25, 0.3 * s)))
                        }
                        posts.add(g0 to pb)
                        if (sun) { // the post's shadow, from its foot along the ground
                            val o = off(yb); val q0 = cam.proj(V3(b[0], 0.12, b[1])); val q1 = cam.proj(V3(b[0] + o[0], 0.12, b[1] + o[1]))
                            if (q0 != null && q1 != null) shadow.add(pf("M%.1f %.1fL%.1f %.1f", q0[0], q0[1], q1[0], q1[1]) to s * 0.5)
                        }
                    }
                }
                // the track: a dark underside, the lit top over it
                val w = PyMath.max(1.1, 1.7 * s)
                g.append(pf("<path d=\"M%.1f %.1fL%.1f %.1f\" stroke=\"%s\" stroke-width=\"%.2f\" stroke-linecap=\"round\"/>", pa[0], pa[1] + 0.35 * s, pb[0], pb[1] + 0.35 * s, dark, w))
                g.append(pf("<path d=\"M%.1f %.1fL%.1f %.1f\" stroke=\"%s\" stroke-width=\"%.2f\" stroke-linecap=\"round\"/>", pa[0], pa[1], pb[0], pb[1], lit, w * 0.72))
                items.add(Amsterdam.Item(c[2], "svg", g.toString()))
            }
            if (sun) {
                val sp = ArrayList<V2?>()
                for (k in P.indices) {
                    val o = off(Y[k]); sp.add(cam.proj(V3(P[k][0] + o[0], 0.12, P[k][1] + o[1])))
                }
                var run = ArrayList<V2>()
                for (q in sp + listOf(null)) {
                    if (q != null) { run.add(q); continue }
                    if (run.size > 1) {
                        val pm = P[P.size / 2]
                        shadow.add(Regions.runD(run) to 1.1 * cam.focal / PyMath.max(1.0, cam.cam(V3(pm[0], 0.0, pm[1]))[2]))
                    }
                    run = ArrayList()
                }
            }
        }
        return items to shadow
    }
}

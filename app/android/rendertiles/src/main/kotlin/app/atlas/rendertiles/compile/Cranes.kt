package app.atlas.rendertiles.compile

import app.atlas.render.Amsterdam
import app.atlas.render.V2
import app.atlas.render.py.Dict
import app.atlas.render.py.PyMath

/**
 * A crane's form as regions.py's draw_items derives it from the whole scene (render/regions.py's crane loop, the port's
 * Regions.drawItems), which the tiles carry ([app.atlas.rendertiles.model.Schema.ITEM]'s `crane`, `ux` to `hv`): the
 * frame draws a crane from them alone, since what decides it lies anywhere in the scene.
 *   - On a quay: a crane mapped as a node within 60 m of a quay edge (water_edges, its nearest, the first of two as
 *     near) is the ship-to-shore shape, its boom along the edge's normal, turned to the other side where that side's
 *     point dw + 25 m out lies on water and this side's does not; `hu` is its distance to the edge.
 *   - In a yard: a portal gantry, along its outline's longest edge (its half sizes the outline's projections' half
 *     extents, at least 4 and 3 m) or, without an outline, along its nearest crane rail's segment (by the segment's
 *     middle, the first of two as near; 9 and 14 m); an outline more than 2.5 times longer than wide spans it with one
 *     portal 4 m deep.
 * Every value is the port's double, by its own arithmetic.
 */
internal object Cranes {
    /** A crane's form: 1 ship-to-shore, 2 a yard gantry; its axes, and its half sizes (a ship-to-shore crane's hu its distance to the water). */
    class Form(val kind: Int, val ux: Double, val uz: Double, val vx: Double, val vz: Double, val hu: Double, val hv: Double?)

    /** The forms of S['cranes'], in its order. */
    @Suppress("UNCHECKED_CAST")
    fun forms(S: Dict): List<Form> {
        val cranes = (S["cranes"] as List<Dict>?) ?: emptyList()
        if (cranes.isEmpty()) return emptyList()
        val edges = Amsterdam.waterEdges(S)
        val rails = ((S["rail"] as List<Dict>?) ?: emptyList()).filter { it["crane"] == true }
        return cranes.map { form(S, it, edges, rails) }
    }

    @Suppress("UNCHECKED_CAST")
    private fun form(S: Dict, cr: Dict, edges: List<Amsterdam.Edge>, rails: List<Dict>): Form {
        val x = cr["x"] as Double
        val z = cr["z"] as Double
        // to_water: the nearest quay edge and its normal
        var dw = 1e9
        var wn: V2? = null
        for (e in edges) {
            val ux = e.b.x - e.a.x
            val uz = e.b.y - e.a.y
            val l2 = (ux * ux + uz * uz).let { if (it == 0.0) 1.0 else it }
            val t = PyMath.max(0.0, PyMath.min(1.0, ((x - e.a.x) * ux + (z - e.a.y) * uz) / l2))
            val d = PyMath.hypot(e.a.x + ux * t - x, e.a.y + uz * t - z)
            if (d < dw) {
                dw = d
                wn = V2(e.nx, e.nz)
            }
        }
        val poly = cr["poly"] as List<V2>?
        if (poly == null && dw < 60 && wn != null) {
            var w: V2 = wn
            if (!Amsterdam.onWater(S, x + w.x * (dw + 25), z + w.y * (dw + 25)) && Amsterdam.onWater(S, x - w.x * (dw + 25), z - w.y * (dw + 25))) {
                w = V2(-w.x, -w.y) // the boom reaches over the side where the water is
            }
            return Form(1, w.x, w.y, -w.y, w.x, dw, null)
        }
        var ux: Double
        var uz: Double
        var hu: Double
        var hv: Double
        if (poly != null && poly.size >= 4) {
            val pts = if (poly[0].x == poly[poly.size - 1].x && poly[0].y == poly[poly.size - 1].y) poly.subList(0, poly.size - 1) else poly
            var e0 = 0
            var bd = dist(pts[0], pts[1 % pts.size])
            for (i in pts.indices) {
                val d = dist(pts[i], pts[(i + 1) % pts.size])
                if (d > bd) {
                    bd = d
                    e0 = i
                }
            }
            val a = pts[e0]
            val b = pts[(e0 + 1) % pts.size]
            ux = b.x - a.x
            uz = b.y - a.y
            val lg = PyMath.hypot(ux, uz).let { if (it == 0.0) 1.0 else it }
            ux /= lg
            uz /= lg
            val pu = pts.map { (it.x - x) * ux + (it.y - z) * uz }
            val pv = pts.map { -(it.x - x) * uz + (it.y - z) * ux }
            hu = PyMath.max(4.0, (maxOf(pu) - minOf(pu)) / 2)
            hv = PyMath.max(3.0, (maxOf(pv) - minOf(pv)) / 2)
        } else {
            ux = 1.0
            uz = 0.0
            var best = 1e9
            for (r in rails) {
                val rp = r["pts"] as List<V2>
                for (i in 0 until rp.size - 1) {
                    val p = rp[i]
                    val q = rp[i + 1]
                    val d = PyMath.hypot((p.x + q.x) / 2 - x, (p.y + q.y) / 2 - z)
                    if (d < best) {
                        best = d
                        val l = dist(p, q).let { if (it == 0.0) 1.0 else it }
                        ux = (q.x - p.x) / l
                        uz = (q.y - p.y) / l
                    }
                }
            }
            hu = 9.0
            hv = 14.0
        }
        var u = V2(ux, uz)
        var v = V2(-uz, ux)
        if (hu > 2.5 * hv) {
            // an outline much longer than wide is the crane's runway along its rails: one portal spans it, 4 m deep
            val t = u
            u = v
            v = t
            hu = hv
            hv = 4.0
        }
        return Form(2, u.x, u.y, v.x, v.y, hu, hv)
    }

    private fun dist(a: V2, b: V2): Double = PyMath.dist(doubleArrayOf(a.x, a.y), doubleArrayOf(b.x, b.y))

    /** Python's max(list) and min(list): the first of the largest and of the smallest. */
    private fun maxOf(xs: List<Double>): Double {
        var m = xs[0]
        for (i in 1 until xs.size) if (xs[i] > m) m = xs[i]
        return m
    }

    private fun minOf(xs: List<Double>): Double {
        var m = xs[0]
        for (i in 1 until xs.size) if (xs[i] < m) m = xs[i]
        return m
    }
}

package app.atlas.anchor

import app.atlas.render.py.PyMath
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/**
 * How far an anchor reaches (plan-gpu D9): its completeness rect (the frame plus the engine's smallest cull margin;
 * inside it the anchor holds everything the engine draws at its camera), the share of another camera's frame that
 * lies outside every drawn anchor's rect, the backdrop's item selection by the engine's own radius cut, and the
 * coverage mask the rest gates are computed in.
 */
object Coverage {
    /** The smallest margin by which the engine culls, CSS px: the trees' and water streaks' cut. */
    const val MARGIN = 20.0

    /**
     * Where the points of an anchor land at another camera ([view]), by the GPU's rules: spatial points projected from
     * the anchor's eye frame, billboards at their hang point's projection plus their offset scaled by the depth ratio,
     * screen points where they are. The residual is added only at the anchor's own camera.
     */
    class Projection(val anchor: Anchor, val view: AnchorCamera) {
        private val k = anchor.head.camera
        // the anchor's eye frame to the view's: R (3 × 3) and t
        private val m = DoubleArray(12)
        val same: Boolean = view.eyeX == k.eyeX && view.eyeY == k.eyeY && view.eyeZ == k.eyeZ && view.axes.contentEquals(k.axes) &&
            view.focal == k.focal && view.cx == k.cx && view.cy == k.cy

        init {
            val a = k.axes; val b = view.axes
            for (r in 0 until 3) for (c in 0 until 3) {
                // view row r · anchor axis c (both in the region frame)
                m[r * 4 + c] = b[r * 3] * a[c * 3] + b[r * 3 + 1] * a[c * 3 + 1] + b[r * 3 + 2] * a[c * 3 + 2]
            }
            val dx = k.eyeX - view.eyeX; val dy = k.eyeY - view.eyeY; val dz = k.eyeZ - view.eyeZ
            for (r in 0 until 3) m[r * 4 + 3] = b[r * 3] * dx + b[r * 3 + 1] * dy + b[r * 3 + 2] * dz
        }

        /** Point [i] in the view's eye frame, into [out]; for a billboard, its hang point's. */
        fun eye(i: Int, out: DoubleArray) {
            val p = anchor.points
            val j = if (p.lift[i] == LiftClass.BILLBOARD) p.hang(i) else i
            val x = p.xyz[j * 3].toDouble(); val y = p.xyz[j * 3 + 1].toDouble(); val z = p.xyz[j * 3 + 2].toDouble()
            out[0] = m[0] * x + m[1] * y + m[2] * z + m[3]
            out[1] = m[4] * x + m[5] * y + m[6] * z + m[7]
            out[2] = m[8] * x + m[9] * y + m[10] * z + m[11]
        }

        /** Point [i]'s screen position at the view, CSS px, into [out] (x, y); false when it lies behind the near plane. */
        fun project(i: Int, out: DoubleArray): Boolean {
            val p = anchor.points
            if (p.lift[i] == LiftClass.SCREEN) {
                out[0] = p.xyz[i * 3].toDouble(); out[1] = p.xyz[i * 3 + 1].toDouble()
                if (same) { out[0] += p.residual[i * 2] / 1000.0; out[1] += p.residual[i * 2 + 1] / 1000.0 }
                return true
            }
            if (same) {
                out[0] = p.screenX(k, i); out[1] = p.screenY(k, i)
                return true
            }
            val e = DoubleArray(3)
            eye(i, e)
            if (e[2] < MIN_Z) return false
            out[0] = view.sx(e[0], e[2]); out[1] = view.sy(e[1], e[2])
            if (p.lift[i] == LiftClass.BILLBOARD) {
                val b = p.billboard(i); val h = p.hangs[b]
                val zk = p.xyz[h * 3 + 2].toDouble()
                val s = (view.focal / e[2]) / (k.focal / zk)
                out[0] += p.offsets[b * 2] * s; out[1] += p.offsets[b * 2 + 1] * s
            }
            return true
        }
    }

    /** The completeness rect of [a] (left, top, right, bottom), anchor CSS px. */
    fun completeness(a: Anchor): DoubleArray = a.head.complete

    /**
     * The share of a frame at [view] (sampled every [step] CSS px) that lies outside every one of [anchors]' completeness
     * rects: each sample's ray meets the ground plane (y = 0) and that point is projected into each anchor; rays that
     * meet no ground (the sky, fixed to the screen) count as covered.
     */
    fun outsideShare(anchors: List<Anchor>, view: AnchorCamera, step: Double = 4.0): Double {
        var out = 0; var all = 0
        val w = DoubleArray(3); val e = DoubleArray(3)
        var y = step / 2
        while (y < view.height) {
            var x = step / 2
            while (x < view.width) {
                all++
                if (groundAt(view, x, y, 0.0, w)) {
                    var inside = false
                    for (a in anchors) {
                        val k = a.head.camera
                        k.eye(w[0], w[1], w[2], e)
                        if (e[2] <= 0) continue
                        val sx = k.sx(e[0], e[2]); val sy = k.sy(e[1], e[2]); val r = a.head.complete
                        if (sx >= r[0] && sx <= r[2] && sy >= r[1] && sy <= r[3]) { inside = true; break }
                    }
                    if (!inside) out++
                }
                x += step
            }
            y += step
        }
        return if (all == 0) 0.0 else out.toDouble() / all
    }

    /** The point of the plane y = [gy] seen at screen ([sx], [sy]) of [cam], region frame, into [out]; false when the ray misses it. */
    fun groundAt(cam: AnchorCamera, sx: Double, sy: Double, gy: Double, out: DoubleArray): Boolean {
        val a = cam.axes
        val ax = (sx - cam.cx) / cam.focal; val ay = (cam.cy - sy) / cam.focal
        val dx = a[6] + ax * a[0] + ay * a[3]; val dy = a[7] + ax * a[1] + ay * a[4]; val dz = a[8] + ax * a[2] + ay * a[5]
        if (dy >= -1e-9) return false
        val t = (gy - cam.eyeY) / dy
        if (t <= 0) return false
        out[0] = cam.eyeX + dx * t; out[1] = gy; out[2] = cam.eyeZ + dz * t
        return true
    }

    /**
     * The backdrop's items for [current] (plan-gpu D9, "Objects by the engine's own cut"): the items of [backdrop] whose
     * reference point lies beyond the current anchor's radius about its target, as the engine's `far` test puts it, so
     * an item is drawn by exactly one of them.
     */
    fun backdropItems(backdrop: Anchor, current: Anchor): BooleanArray {
        val h = current.head
        val it = backdrop.items
        return BooleanArray(it.size) { i -> it.cut(i, h.radiusX, h.radiusZ, h.radius) }
    }

    /** Whether region-frame ([x], [z]) lies on [a]'s own ground (within `ground_r` of its target); the backdrop draws the rest. */
    fun onGround(a: Anchor, x: Double, z: Double): Boolean = PyMath.hypot(x - a.head.radiusX, z - a.head.radiusZ) <= a.head.groundR

    /**
     * The coverage mask of [current] at its own camera, [wPx] × [hPx] device pixels at density [d] (true: the current
     * anchor draws the pixel): false where the ground lies beyond `ground_r` and, with a [backdrop], where the backdrop's
     * selected items stand (their primitives' footprints at the current camera).
     */
    fun mask(current: Anchor, backdrop: Anchor?, d: Double, wPx: Int, hPx: Int): BooleanArray {
        val m = BooleanArray(wPx * hPx) { true }
        val cam = current.head.camera
        val w = DoubleArray(3)
        for (py in 0 until hPx) for (px in 0 until wPx) {
            if (groundAt(cam, (px + 0.5) / d, (py + 0.5) / d, 0.0, w) && !onGround(current, w[0], w[2])) m[py * wPx + px] = false
        }
        if (backdrop != null) {
            val sel = backdropItems(backdrop, current)
            val proj = Projection(backdrop, cam)
            val tri = Tri(m, wPx, hPx, d)
            val xy = DoubleArray(2)
            val px = DoubleArray(PrimKind.MAX_FACE * 2)
            val pr = backdrop.prims
            for (i in 0 until backdrop.items.size) {
                if (!sel[i]) continue
                for (p in backdrop.items.first[i] until backdrop.items.last[i]) {
                    when (pr.kind[p]) {
                        PrimKind.FACE -> {
                            val n = pr.corners(p)
                            var ok = true
                            for (k in 0 until n) { if (!proj.project(pr.int(p, 2 + k), xy)) ok = false; px[k * 2] = xy[0]; px[k * 2 + 1] = xy[1] }
                            if (ok) for (k in 1 until n - 1) tri.fill(px[0], px[1], px[k * 2], px[k * 2 + 1], px[k * 2 + 2], px[k * 2 + 3])
                        }
                        PrimKind.TRI, PrimKind.STENCIL -> {
                            val f = pr.int(p, 0); val c = pr.int(p, 1)
                            var t = 0
                            while (t < c) {
                                var ok = true
                                for (k in 0 until 3) { if (!proj.project(backdrop.triangles[f + t + k], xy)) ok = false; px[k * 2] = xy[0]; px[k * 2 + 1] = xy[1] }
                                if (ok) tri.fill(px[0], px[1], px[2], px[3], px[4], px[5])
                                t += 3
                            }
                        }
                        PrimKind.ELL -> if (proj.project(pr.int(p, 0), xy)) {
                            val r = max(pr.float(p, 1), pr.float(p, 2)).toDouble()
                            tri.fill(xy[0] - r, xy[1] - r, xy[0] + r, xy[1] - r, xy[0] + r, xy[1] + r)
                            tri.fill(xy[0] - r, xy[1] - r, xy[0] + r, xy[1] + r, xy[0] - r, xy[1] + r)
                        }
                        else -> Unit
                    }
                }
            }
        }
        return m
    }

    /** The share of [mask] that is false: the masked share the rest gates report. */
    fun maskedShare(mask: BooleanArray): Double = mask.count { !it }.toDouble() / mask.size

    /** Clears mask pixels whose centres lie in triangles given in CSS px. */
    private class Tri(val m: BooleanArray, val w: Int, val h: Int, val d: Double) {
        fun fill(ax: Double, ay: Double, bx: Double, by: Double, cx: Double, cy: Double) {
            val x0 = max(0, floor(min(ax, min(bx, cx)) * d).toInt()); val x1 = min(w - 1, ceil(max(ax, max(bx, cx)) * d).toInt())
            val y0 = max(0, floor(min(ay, min(by, cy)) * d).toInt()); val y1 = min(h - 1, ceil(max(ay, max(by, cy)) * d).toInt())
            val area = (bx - ax) * (cy - ay) - (by - ay) * (cx - ax)
            if (abs(area) < 1e-12) return
            for (py in y0..y1) for (px in x0..x1) {
                val x = (px + 0.5) / d; val y = (py + 0.5) / d
                val w0 = (bx - ax) * (y - ay) - (by - ay) * (x - ax)
                val w1 = (cx - bx) * (y - by) - (cy - by) * (x - bx)
                val w2 = (ax - cx) * (y - cy) - (ay - cy) * (x - cx)
                if ((w0 >= 0 && w1 >= 0 && w2 >= 0) || (w0 <= 0 && w1 <= 0 && w2 <= 0)) m[py * w + px] = false
            }
        }
    }

    private const val MIN_Z = 1e-3
}

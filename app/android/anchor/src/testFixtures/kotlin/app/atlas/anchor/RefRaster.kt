package app.atlas.anchor

import app.atlas.mapview.svg.SvgBlend
import app.atlas.mapview.svg.SvgGradient
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/** A tile in device pixels: its top-left pixel in the frame ([x0], [y0]), [w] × [h], premultiplied ARGB. */
class Tile(val x0: Int, val y0: Int, val w: Int, val h: Int, val px: IntArray)

/** Rasterises an anchor's text runs and pattern tiles: the device's own Skia in Robolectric, Java2D on the JVM. */
interface TileSource {
    /** Text run [i] of [anchor] at [density], pixel-aligned in the frame at the anchor's camera, painted with its paint. */
    fun text(anchor: Anchor, i: Int, density: Double): Tile?

    /** Pattern [i]'s tile at [pxPerUnit] device pixels per pattern unit: one period, its origin at 0, 0. */
    fun pattern(anchor: Anchor, i: Int, pxPerUnit: Double): Tile?
}

/**
 * The GPU's rules emulated on the CPU (plan-gpu D3), at the anchor's own camera, so the likeness thresholds can be
 * calibrated before the first GL run: a 4x multisampled target (the standard sample positions) with 8-bit
 * premultiplied colour per sample and painter depth; fragments blended in floating point and rounded once, as a GPU's
 * blender does; FACEs (their internal edges by the pixel's centre) and ELLs with analytic coverage at the pixel's
 * centre, their opaque interiors (inset by [Faces.INSET]) at the samples in the opaque pass and their fringes in the
 * translucent pass; TRIs and STENCIL fills at the samples; strokes as segments with their exact-area coverages, trims,
 * joins and caps, and those no wider than a device pixel as Skia's hairlines ([Strokes.hairline]); clips through a
 * per-sample mask; groups that need it drawn into sprites and composited at their painter index; gradients and
 * patterns evaluated per fragment. Filters are off: generator-only elements are left out, the others drawn as if they
 * had none.
 *
 * [twoPass] false draws in strict painter's order instead: no depth, opaque faces and ellipses whole in one pass,
 * the reference the two passes and the inset split must equal.
 */
class RefRaster(
    val anchor: Anchor,
    val density: Double,
    val wPx: Int,
    val hPx: Int,
    val tiles: TileSource? = null,
    val background: Int = 0xFFFFFFFF.toInt(),
    val twoPass: Boolean = true,
    /**
     * An experiment, not the GPU's rule: the paint's alpha rounded to 8 bits (its colour premultiplied by it) before it
     * blends, and group opacities too, as Skia's raster does; the calibration measured it no closer to Skia.
     */
    val quantise: Boolean = false,
) {
    private val cam = anchor.head.camera
    private val pr = anchor.prims
    private val px: DoubleArray
    private val py: DoubleArray
    private val clipMasks = HashMap<Int, BooleanArray>()
    private val textTiles = HashMap<Int, Tile?>()
    private val patternTiles = HashMap<Int, Pair<Tile, Double>?>()

    /** Primitives drawn, by kind, for reports. */
    val drawnCounts = IntArray(PrimKind.COUNT)

    init {
        val n = anchor.points.size
        px = DoubleArray(n); py = DoubleArray(n)
        for (i in 0 until n) { px[i] = anchor.points.screenX(cam, i) * density; py[i] = anchor.points.screenY(cam, i) * density }
    }

    /** One drawing target: 4 samples per pixel of premultiplied ARGB and a painter index (−1 cleared). */
    private class Target(val x0: Int, val y0: Int, val w: Int, val h: Int, clear: Int) {
        val col = IntArray(w * h * 4) { clear }
        val dep = IntArray(w * h * 4) { -1 }

        /** The resolve: each pixel's samples averaged (premultiplied). */
        fun resolve(): IntArray {
            val out = IntArray(w * h)
            for (i in 0 until w * h) {
                var a = 0; var r = 0; var g = 0; var b = 0
                for (s in 0 until 4) {
                    val c = col[i * 4 + s]
                    a += c ushr 24; r += (c ushr 16) and 0xFF; g += (c ushr 8) and 0xFF; b += c and 0xFF
                }
                out[i] = (((a + 2) / 4) shl 24) or (((r + 2) / 4) shl 16) or (((g + 2) / 4) shl 8) or ((b + 2) / 4)
            }
            return out
        }
    }

    private sealed class Step {
        class Prim(val i: Int, val clip: BooleanArray?) : Step()
        class Sprite(val node: Int, val group: PlanNode.Group, val clip: BooleanArray?, val units: List<Step>, val painter: Int) : Step()
    }

    /** The frame: opaque ARGB pixels, [wPx] × [hPx]. */
    fun render(): IntArray {
        val t = Target(0, 0, wPx, hPx, background)
        val units = units(-1, null)
        draw(t, units)
        val out = t.resolve()
        for (i in out.indices) out[i] = out[i] or (0xFF shl 24)
        return out
    }

    // ------------------------------------------------------------------ the plan

    /** The drawing units under plan node [parent] (−1: the root), with the clip in force. */
    private fun units(parent: Int, clip: BooleanArray?): List<Step> {
        val out = ArrayList<Step>()
        for (c in anchor.plan.children(parent)) {
            when (val n = anchor.plan.nodes[c]) {
                is PlanNode.Layer -> out.addAll(units(c, clip))
                is PlanNode.Run -> for (i in n.first until n.last) out.add(Step.Prim(i, clip))
                is PlanNode.Group -> {
                    val f = if (n.filter >= 0) anchor.filters[n.filter] else null
                    if (f != null && f.generatorOnly) continue // filters off: what only a filter makes is left out
                    val cl = if (n.clip >= 0) intersect(clip, clipMask(n.clip)) else clip
                    val sprite = n.opacity < 1f || n.blend != SvgBlend.NORMAL
                    if (sprite) out.add(Step.Sprite(c, n, null, units(c, cl), n.painter)) else out.addAll(units(c, cl))
                }
            }
        }
        return out
    }

    private fun intersect(a: BooleanArray?, b: BooleanArray): BooleanArray {
        if (a == null) return b
        return BooleanArray(b.size) { a[it] && b[it] }
    }

    /** Clip [k]'s per-sample mask over the frame: the union of its children, within its parent's. */
    private fun clipMask(k: Int): BooleanArray = clipMasks.getOrPut(k) {
        val c = anchor.plan.clips[k]
        val m = BooleanArray(wPx * hPx * 4)
        for (i in c.first until c.last) {
            when (pr.kind[i]) {
                PrimKind.STENCIL -> stencilMask(i, m)
                PrimKind.ELL -> {
                    val cx = px[pr.int(i, 0)]; val cy = py[pr.int(i, 0)]
                    val rx = pr.float(i, 1) * density; val ry = pr.float(i, 2) * density
                    val x0 = max(0, floor(cx - rx).toInt()); val x1 = min(wPx - 1, ceil(cx + rx).toInt())
                    val y0 = max(0, floor(cy - ry).toInt()); val y1 = min(hPx - 1, ceil(cy + ry).toInt())
                    for (y in y0..y1) for (x in x0..x1) for (s in 0 until 4) {
                        val dx = (x + S[s * 2] - cx) / rx; val dy = (y + S[s * 2 + 1] - cy) / ry
                        if (dx * dx + dy * dy <= 1) m[(y * wPx + x) * 4 + s] = true
                    }
                }
                else -> Unit
            }
        }
        if (c.parent >= 0) { val p = clipMask(c.parent); for (j in m.indices) m[j] = m[j] && p[j] }
        m
    }

    /** Marks the samples inside STENCIL primitive [i] (its fans under its fill rule) in [m], frame-sized. */
    private fun stencilMask(i: Int, m: BooleanArray) {
        val first = pr.int(i, 0); val count = pr.int(i, 1)
        if (count == 0) return
        val evenOdd = pr.has(i, PrimFlag.EVEN_ODD)
        var x0 = Double.MAX_VALUE; var y0 = Double.MAX_VALUE; var x1 = -Double.MAX_VALUE; var y1 = -Double.MAX_VALUE
        for (k in 0 until count) { val p = anchor.triangles[first + k]; x0 = min(x0, px[p]); x1 = max(x1, px[p]); y0 = min(y0, py[p]); y1 = max(y1, py[p]) }
        val bx0 = max(0, floor(x0).toInt()); val bx1 = min(wPx - 1, ceil(x1).toInt())
        val by0 = max(0, floor(y0).toInt()); val by1 = min(hPx - 1, ceil(y1).toInt())
        if (bx0 > bx1 || by0 > by1) return
        val bw = bx1 - bx0 + 1; val bh = by1 - by0 + 1
        val wind = IntArray(bw * bh * 4)
        var t = 0
        while (t < count) {
            val a = anchor.triangles[first + t]; val b = anchor.triangles[first + t + 1]; val c = anchor.triangles[first + t + 2]
            val area = (px[b] - px[a]) * (py[c] - py[a]) - (py[b] - py[a]) * (px[c] - px[a])
            if (area != 0.0) {
                val sgn = if (area > 0) 1 else -1
                forSamplesInTriangle(px[a], py[a], px[b], py[b], px[c], py[c], bx0, by0, bx1, by1) { x, y, s -> wind[((y - by0) * bw + (x - bx0)) * 4 + s] += sgn }
            }
            t += 3
        }
        for (y in by0..by1) for (x in bx0..bx1) for (s in 0 until 4) {
            val w = wind[((y - by0) * bw + (x - bx0)) * 4 + s]
            if (if (evenOdd) (w and 1) != 0 else w != 0) m[(y * wPx + x) * 4 + s] = true
        }
    }

    // ------------------------------------------------------------------ drawing

    private fun draw(t: Target, units: List<Step>) {
        if (twoPass) {
            // opaque pass: front to back, depth written
            for (k in units.indices.reversed()) {
                val u = units[k]
                if (u is Step.Prim && !pr.has(u.i, PrimFlag.TRANSLUCENT) && opaqueKind(u.i)) prim(t, u.i, u.clip, OPAQUE)
            }
            // translucent pass: back to front, depth tested
            for (u in units) {
                when (u) {
                    is Step.Prim -> prim(t, u.i, u.clip, TRANSLUCENT)
                    is Step.Sprite -> sprite(t, u)
                }
            }
        } else {
            for (u in units) {
                when (u) {
                    is Step.Prim -> prim(t, u.i, u.clip, ALL)
                    is Step.Sprite -> sprite(t, u)
                }
            }
        }
    }

    private fun opaqueKind(i: Int): Boolean = when (pr.kind[i]) {
        PrimKind.FACE, PrimKind.TRI, PrimKind.STENCIL -> true
        PrimKind.ELL -> pr.float(i, 4) == 0f
        else -> false
    }

    /** A group drawn into its own target over its primitives' box, resolved, and composited at its painter index. */
    private fun sprite(t: Target, u: Step.Sprite) {
        val box = bounds(u.units) ?: return
        val x0 = max(t.x0, box[0]); val y0 = max(t.y0, box[1]); val x1 = min(t.x0 + t.w - 1, box[2]); val y1 = min(t.y0 + t.h - 1, box[3])
        if (x0 > x1 || y0 > y1) return
        val s = Target(x0, y0, x1 - x0 + 1, y1 - y0 + 1, 0)
        draw(s, u.units)
        val img = s.resolve()
        val op = if (quantise) Math.round(u.group.opacity * 255f) / 255f else u.group.opacity
        val blend = u.group.blend
        for (y in y0..y1) for (x in x0..x1) {
            val src = img[(y - y0) * s.w + (x - x0)]
            if (src == 0) continue
            val o = ((y - t.y0) * t.w + (x - t.x0)) * 4
            for (k in 0 until 4) {
                if (u.clip != null && !u.clip[(y * wPx + x) * 4 + k]) continue
                if (twoPass && u.painter <= t.dep[o + k]) continue
                t.col[o + k] = composite(src, op, t.col[o + k], blend)
            }
        }
    }

    /** The device-pixel box of [units]' primitives (inclusive), or null. */
    private fun bounds(units: List<Step>): IntArray? {
        var x0 = Double.MAX_VALUE; var y0 = Double.MAX_VALUE; var x1 = -Double.MAX_VALUE; var y1 = -Double.MAX_VALUE
        val b = DoubleArray(4)
        fun add(us: List<Step>) {
            for (u in us) when (u) {
                is Step.Prim -> if (primBox(u.i, b)) { x0 = min(x0, b[0]); y0 = min(y0, b[1]); x1 = max(x1, b[2]); y1 = max(y1, b[3]) }
                is Step.Sprite -> add(u.units)
            }
        }
        add(units)
        if (x0 > x1) return null
        return intArrayOf(max(0, floor(x0).toInt() - 2), max(0, floor(y0).toInt() - 2), min(wPx - 1, ceil(x1).toInt() + 2), min(hPx - 1, ceil(y1).toInt() + 2))
    }

    /** Primitive [i]'s device box, dilated by its reach, into [b]. */
    private fun primBox(i: Int, b: DoubleArray): Boolean {
        b[0] = Double.MAX_VALUE; b[1] = Double.MAX_VALUE; b[2] = -Double.MAX_VALUE; b[3] = -Double.MAX_VALUE
        fun pt(p: Int, r: Double) {
            if (p < 0) return
            b[0] = min(b[0], px[p] - r); b[1] = min(b[1], py[p] - r); b[2] = max(b[2], px[p] + r); b[3] = max(b[3], py[p] + r)
        }
        when (pr.kind[i]) {
            PrimKind.FACE -> for (k in 0 until pr.corners(i)) pt(pr.corner(i, k), 1.0)
            PrimKind.TRI, PrimKind.STENCIL -> { val f = pr.int(i, 0); for (k in 0 until pr.int(i, 1)) pt(anchor.triangles[f + k], 1.0) }
            PrimKind.SEG -> { val r = pr.float(i, 4) * density / 2 * max(1.5, pr.float(i, 6).toDouble()) + 1.5; pt(pr.int(i, 0), r); pt(pr.int(i, 1), r) }
            PrimKind.JOIN -> pt(pr.int(i, 0), pr.float(i, 1) * density / 2 + 1.5)
            PrimKind.ELL -> pt(pr.int(i, 0), max(pr.float(i, 1), pr.float(i, 2)) * density + pr.float(i, 4) * density / 2 + 1.5)
            PrimKind.TILE -> { val tile = textTile(pr.int(i, 0)) ?: return false; b[0] = tile.x0.toDouble(); b[1] = tile.y0.toDouble(); b[2] = (tile.x0 + tile.w).toDouble(); b[3] = (tile.y0 + tile.h).toDouble() }
        }
        return b[0] <= b[2]
    }

    private fun textTile(i: Int): Tile? = textTiles.getOrPut(i) { tiles?.text(anchor, i, density) }

    /** Draws primitive [i] into [t] in [pass]. */
    private fun prim(t: Target, i: Int, clip: BooleanArray?, pass: Int) {
        drawnCounts[pr.kind[i].toInt()]++
        when (pr.kind[i]) {
            PrimKind.FACE -> face(t, i, clip, pass)
            PrimKind.TRI -> if (pass != TRANSLUCENT || pr.has(i, PrimFlag.TRANSLUCENT)) triangles(t, i, clip)
            PrimKind.STENCIL -> if (pass != TRANSLUCENT || pr.has(i, PrimFlag.TRANSLUCENT)) stencil(t, i, clip)
            PrimKind.ELL -> ellipse(t, i, clip, pass)
            PrimKind.SEG -> if (pass != OPAQUE) seg(t, i, clip)
            PrimKind.JOIN -> if (pass != OPAQUE) dot(t, i, clip)
            PrimKind.TILE -> if (pass != OPAQUE) tile(t, i, clip)
        }
    }

    /** The fragment's colour: premultiplied a, r, g, b in 0 … 255, unrounded, as a shader outputs it. */
    private val src = DoubleArray(4)

    private fun setColour(c: Int) {
        src[0] = (c ushr 24).toDouble(); src[1] = ((c ushr 16) and 0xFF).toDouble(); src[2] = ((c ushr 8) and 0xFF).toDouble(); src[3] = (c and 0xFF).toDouble()
    }

    /** Blends the fragment colour at [cov] into the samples of pixel ([x], [y]) that [mask] lets through (bit s). */
    private inline fun pixel(t: Target, x: Int, y: Int, i: Int, clip: BooleanArray?, cov: Double, write: Boolean, mask: Int = 15) {
        if (cov <= 0) return
        if (quantise) {
            // an 8-bit alpha, the colour premultiplied by it
            val a8 = Math.rint(src[0])
            val k = if (src[0] > 0) a8 / src[0] else 0.0
            src[0] = a8; src[1] *= k; src[2] *= k; src[3] *= k
        }
        val o = ((y - t.y0) * t.w + (x - t.x0)) * 4
        val f = (y * wPx + x) * 4
        for (s in 0 until 4) {
            if (mask and (1 shl s) == 0) continue
            if (clip != null && !clip[f + s]) continue
            if (twoPass && i <= t.dep[o + s]) continue
            t.col[o + s] = over(src, cov, t.col[o + s])
            if (write) t.dep[o + s] = i
        }
    }

    private fun range(t: Target, b0: Double, b1: Double, lo: Int, hi: Int): IntRange = max(lo, floor(b0).toInt())..min(hi, ceil(b1).toInt())

    private fun face(t: Target, i: Int, clip: BooleanArray?, pass: Int) {
        val n = pr.corners(i)
        val hard = pr.hardEdges(i)
        val ids = IntArray(n) { pr.corner(i, it) }
        val xs = DoubleArray(n) { px[ids[it]] }; val ys = DoubleArray(n) { py[ids[it]] }
        val area = Faces.area2(xs, ys, n)
        if (area == 0.0) return
        val s = if (area > 0) 1.0 else -1.0
        // anti-aliased edges as distance equations; internal ones by the shared orientation test
        val e = DoubleArray(n * 3); var m = 0
        val hu = IntArray(n); var hn = 0
        val side = DoubleArray(n)
        val gx = xs.average(); val gy = ys.average()
        for (k in 0 until n) {
            val j = (k + 1) % n
            if (hard and (1 shl k) != 0) {
                hu[hn] = k
                side[hn] = if (ids[k] < ids[j]) (xs[j] - xs[k]) * (gy - ys[k]) - (ys[j] - ys[k]) * (gx - xs[k]) else (xs[k] - xs[j]) * (gy - ys[j]) - (ys[k] - ys[j]) * (gx - xs[j])
                hn++
                continue
            }
            val dx = xs[j] - xs[k]; val dy = ys[j] - ys[k]; val l = hypot(dx, dy)
            if (l < 1e-12) continue
            val a = -dy / l * s; val b = dx / l * s
            e[m * 3] = a; e[m * 3 + 1] = b; e[m * 3 + 2] = -(a * xs[k] + b * ys[k]); m++
        }
        fun inHard(x: Double, y: Double): Boolean {
            for (h in 0 until hn) {
                val k = hu[h]; val j = (k + 1) % n
                if (!Faces.hardSide(xs[k], ys[k], xs[j], ys[j], ids[k], ids[j], x, y, side[h])) return false
            }
            return true
        }
        val paint = pr.paint[i]
        val xr = range(t, xs.min() - 1, xs.max() + 1, t.x0, t.x0 + t.w - 1)
        val yr = range(t, ys.min() - 1, ys.max() + 1, t.y0, t.y0 + t.h - 1)
        for (y in yr) for (x in xr) {
            val cx = x + 0.5; val cy = y + 0.5
            // internal edges are tested at the pixel's centre in both passes: the face that owns the centre draws the pixel
            if (!inHard(cx, cy)) continue
            if (pass == OPAQUE) {
                // the inset interior, at the sample positions
                var mask = 0
                for (q in 0 until 4) if (Faces.inside(e, m, x + S[q * 2], y + S[q * 2 + 1]) >= Faces.INSET) mask = mask or (1 shl q)
                if (mask == 0) continue
                paintAt(paint, cx, cy); pixel(t, x, y, i, clip, 1.0, true, mask)
            } else {
                // the whole face: over its own interior the depth test rejects it
                val cov = Faces.coverage(e, m, cx, cy)
                if (cov > 0) { paintAt(paint, cx, cy); pixel(t, x, y, i, clip, cov, false) }
            }
        }
    }

    private fun ellipse(t: Target, i: Int, clip: BooleanArray?, pass: Int) {
        val c = pr.int(i, 0)
        val cx0 = px[c]; val cy0 = py[c]
        val rx = pr.float(i, 1) * density; val ry = pr.float(i, 2) * density
        val rot = pr.float(i, 3).toDouble(); val ring = pr.float(i, 4) * density
        if (rx <= 0 || ry <= 0) return
        val cs = cos(rot); val sn = sin(rot)
        val reach = max(rx, ry) + ring / 2 + 1.5
        val paint = pr.paint[i]
        val opaque = !pr.has(i, PrimFlag.TRANSLUCENT) && ring == 0.0
        if (pass == OPAQUE && !opaque) return
        val inset = Faces.ellipseInset(rx, ry)
        val xr = range(t, cx0 - reach, cx0 + reach, t.x0, t.x0 + t.w - 1)
        val yr = range(t, cy0 - reach, cy0 + reach, t.y0, t.y0 + t.h - 1)
        for (y in yr) for (x in xr) {
            val cx = x + 0.5; val cy = y + 0.5
            if (pass == OPAQUE) {
                // the interior: the samples inside the ellipse shrunk by its inset (true distance, not the first-order one)
                if (rx <= inset || ry <= inset) continue
                var mask = 0
                for (q in 0 until 4) {
                    val dx0 = x + S[q * 2] - cx0; val dy0 = y + S[q * 2 + 1] - cy0
                    val ex = (dx0 * cs + dy0 * sn) / (rx - inset); val ey = (-dx0 * sn + dy0 * cs) / (ry - inset)
                    if (ex * ex + ey * ey <= 1) mask = mask or (1 shl q)
                }
                if (mask != 0) { paintAt(paint, cx, cy); pixel(t, x, y, i, clip, 1.0, true, mask) }
                continue
            }
            val cov = if (ring > 0) {
                val hw = ring / 2
                val out = clampD(0.5 + ellipseDistance(cx - cx0, cy - cy0, cs, sn, rx + hw, ry + hw))
                val inn = if (rx - hw > 0 && ry - hw > 0) clampD(0.5 + ellipseDistance(cx - cx0, cy - cy0, cs, sn, rx - hw, ry - hw)) else 0.0
                out - inn
            } else {
                Faces.ellipseCoverage(ellipseDistance(cx - cx0, cy - cy0, cs, sn, rx, ry), rx, ry)
            }
            if (cov <= 0) continue
            paintAt(paint, cx, cy); pixel(t, x, y, i, clip, cov, false)
        }
    }

    private fun triangles(t: Target, i: Int, clip: BooleanArray?) {
        val first = pr.int(i, 0); val count = pr.int(i, 1)
        val paint = pr.paint[i]
        val write = !pr.has(i, PrimFlag.TRANSLUCENT)
        var k = 0
        while (k < count) {
            val a = anchor.triangles[first + k]; val b = anchor.triangles[first + k + 1]; val c = anchor.triangles[first + k + 2]
            forSamplesInTriangle(px[a], py[a], px[b], py[b], px[c], py[c], t.x0, t.y0, t.x0 + t.w - 1, t.y0 + t.h - 1) { x, y, s ->
                paintAt(paint, x + 0.5, y + 0.5); pixel(t, x, y, i, clip, 1.0, write, 1 shl s)
            }
            k += 3
        }
    }

    private fun stencil(t: Target, i: Int, clip: BooleanArray?) {
        val m = BooleanArray(wPx * hPx * 4)
        stencilMask(i, m)
        val paint = pr.paint[i]
        val write = !pr.has(i, PrimFlag.TRANSLUCENT)
        val b = DoubleArray(4)
        if (!primBox(i, b)) return
        for (y in range(t, b[1], b[3], t.y0, t.y0 + t.h - 1)) for (x in range(t, b[0], b[2], t.x0, t.x0 + t.w - 1)) {
            var mask = 0
            for (s in 0 until 4) if (m[(y * wPx + x) * 4 + s]) mask = mask or (1 shl s)
            if (mask != 0) { paintAt(paint, x + 0.5, y + 0.5); pixel(t, x, y, i, clip, 1.0, write, mask) }
        }
    }

    private fun seg(t: Target, i: Int, clip: BooleanArray?) {
        val a = pr.int(i, 0); val b = pr.int(i, 1); val prev = pr.int(i, 2); val next = pr.int(i, 3)
        val hw = pr.float(i, 4) * density / 2
        val ends = pr.int(i, 5)
        val capA = ends and 3; val capB = (ends shr 2) and 3; val join = (ends shr 4) and 3
        val limit = pr.float(i, 6).toDouble()
        if (2 * hw <= Strokes.HAIRLINE) { hairline(t, i, clip, a, b, prev < 0 && capA != Cap.BUTT, next < 0 && capB != Cap.BUTT, 2 * hw); return }
        val ax = px[a]; val ay = py[a]; val bx = px[b]; val by = py[b]
        val len = hypot(bx - ax, by - ay)
        if (len < 1e-9) return
        val ux = (bx - ax) / len; val uy = (by - ay) / len
        val nx = -uy; val ny = ux
        // the bisector with the predecessor (this segment owns (p − A)·(u_prev + u) ≥ 0) and with the successor ((p − B)·(u + u_next) < 0)
        var sax = 0.0; var say = 0.0; var sbx = 0.0; var sby = 0.0
        var vx = 0.0; var vy = 0.0
        if (prev >= 0) {
            val l = hypot(ax - px[prev], ay - py[prev])
            if (l > 1e-9) { sax = (ax - px[prev]) / l + ux; say = (ay - py[prev]) / l + uy }
            if (hypot(sax, say) < 1e-6) { sax = nx; say = ny }
        }
        if (next >= 0) {
            val l = hypot(px[next] - bx, py[next] - by)
            if (l > 1e-9) { vx = (px[next] - bx) / l; vy = (py[next] - by) / l }
            sbx = ux + vx; sby = uy + vy
            if (hypot(sbx, sby) < 1e-6) { sbx = nx; sby = ny }
        }
        // the join's wedge at b: the outer side of the turn for each segment
        val turn = ux * vy - uy * vx
        val sgn = if (turn > 0) -1.0 else 1.0
        val oix = -uy * sgn; val oiy = ux * sgn
        val oox = -vy * sgn; val ooy = vx * sgn
        val ox = oix + oox; val oy = oiy + ooy
        val ol = hypot(ox, oy)
        val half = sqrt(max(0.0, (1 + ux * vx + uy * vy) / 2))
        val bevel = join == Join.BEVEL || (join == Join.MITER && (half == 0.0 || 1 / half > limit))
        val extA = if (prev < 0 && capA == Cap.SQUARE) hw else 0.0
        val extB = if (next < 0 && capB == Cap.SQUARE) hw else 0.0
        val reach = hw * (if (next >= 0 && join == Join.MITER) max(1.0, limit) else 1.0) + max(extA, extB) + 1.5
        val paint = pr.paint[i]
        val xr = range(t, min(ax, bx) - reach, max(ax, bx) + reach, t.x0, t.x0 + t.w - 1)
        val yr = range(t, min(ay, by) - reach, max(ay, by) + reach, t.y0, t.y0 + t.h - 1)
        for (y in yr) for (x in xr) {
            val cx = x + 0.5; val cy = y + 0.5
            val qx = cx - ax; val qy = cy - ay
            val al = qx * ux + qy * uy
            val pe = qx * nx + qy * ny
            // a joined start: from a, on this side of the bisector
            if (prev >= 0 && (al < 0 || qx * sax + qy * say < 0)) continue
            var cov: Double
            val rx = cx - bx; val ry = cy - by
            if (next >= 0 && al > len) {
                // beyond the end: the join's wedge, where it also lies before the successor's start
                if (rx * vx + ry * vy >= 0) continue
                cov = when {
                    join == Join.ROUND -> Strokes.radial(hypot(rx, ry), hw)
                    bevel -> if (ol < 1e-9) 0.0 else clampD(0.5 + hw * abs(oix * ox + oiy * oy) / ol - (rx * ox + ry * oy) / ol)
                    else -> clampD(0.5 + hw - (rx * oix + ry * oiy)) * clampD(0.5 + hw - (rx * oox + ry * ooy))
                }
            } else {
                if (next >= 0 && rx * sbx + ry * sby >= 0) continue
                // free round ends: their caps
                if (prev < 0 && capA == Cap.ROUND && al < 0) {
                    cov = Strokes.radial(hypot(qx, qy), hw)
                } else if (next < 0 && capB == Cap.ROUND && al > len) {
                    cov = Strokes.radial(hypot(rx, ry), hw)
                } else {
                    if (abs(pe) > hw + 1) continue
                    val strip = Strokes.boxCdf(hw - pe, nx, ny) - Strokes.boxCdf(-hw - pe, nx, ny)
                    val lo = if (prev < 0 && capA != Cap.ROUND) -extA else -1e9
                    val hi = if (next < 0 && capB != Cap.ROUND) len + extB else 1e9
                    cov = strip * (Strokes.boxCdf(hi - al, ux, uy) - Strokes.boxCdf(lo - al, ux, uy))
                }
            }
            if (cov > 0) { paintAt(paint, cx, cy); pixel(t, x, y, i, clip, cov, false) }
        }
    }

    /**
     * A segment no wider than a device pixel, as Skia draws it: an anti-aliased hairline (the two pixels straddling the
     * line along its minor axis share the coverage) with its alpha scaled by the width, from end to end along the major
     * axis (a capped free end half a pixel further), with no trims or joins: each segment of a polyline is its own line.
     */
    private fun hairline(t: Target, i: Int, clip: BooleanArray?, a: Int, b: Int, extendA: Boolean, extendB: Boolean, width: Double) {
        val paint = pr.paint[i]
        Strokes.hairline(px[a], py[a], px[b], py[b], extendA, extendB, width) { x, y, cov ->
            if (x >= t.x0 && y >= t.y0 && x < t.x0 + t.w && y < t.y0 + t.h) { paintAt(paint, x + 0.5, y + 0.5); pixel(t, x, y, i, clip, cov, false) }
        }
    }

    /** A dot: a zero-length subpath's round cap. */
    private fun dot(t: Target, i: Int, clip: BooleanArray?) {
        val p = pr.int(i, 0)
        val hw = pr.float(i, 1) * density / 2
        val x0 = px[p]; val y0 = py[p]
        val reach = hw + 1.5
        val paint = pr.paint[i]
        for (y in range(t, y0 - reach, y0 + reach, t.y0, t.y0 + t.h - 1)) for (x in range(t, x0 - reach, x0 + reach, t.x0, t.x0 + t.w - 1)) {
            val cov = Strokes.radial(hypot(x + 0.5 - x0, y + 0.5 - y0), hw)
            if (cov > 0) { paintAt(paint, x + 0.5, y + 0.5); pixel(t, x, y, i, clip, cov, false) }
        }
    }

    private fun tile(t: Target, i: Int, clip: BooleanArray?) {
        val tile = textTile(pr.int(i, 0)) ?: return
        for (yy in 0 until tile.h) for (xx in 0 until tile.w) {
            val c = tile.px[yy * tile.w + xx]
            if (c == 0) continue
            val x = tile.x0 + xx; val y = tile.y0 + yy
            if (x < t.x0 || y < t.y0 || x >= t.x0 + t.w || y >= t.y0 + t.h) continue
            setColour(c)
            pixel(t, x, y, i, clip, 1.0, false)
        }
    }

    // ------------------------------------------------------------------ paints

    /** Sets the fragment colour to paint [p] at device pixel centre ([x], [y]), opacity included. */
    private fun paintAt(p: Int, x: Double, y: Double) {
        val ps = anchor.paints
        val op = ps.opacity[p]
        when (ps.kind[p]) {
            PaintKind.COLOR -> straight(ps.argb[p], op)
            PaintKind.GRADIENT -> {
                val sx = x / density; val sy = y / density
                val t = ps.transform
                val gx = t[p * 6] * sx + t[p * 6 + 2] * sy + t[p * 6 + 4]
                val gy = t[p * 6 + 1] * sx + t[p * 6 + 3] * sy + t[p * 6 + 5]
                gradientAt(anchor.gradients[ps.ref[p]], gx, gy, src)
                premultiplyInPlace(src, op)
            }
            else -> {
                val pt = patternTile(ps.ref[p], p)
                if (pt == null) { src.fill(0.0); return }
                val (tile, ppu) = pt
                val sx = x / density; val sy = y / density
                val t = ps.transform
                val gx = t[p * 6] * sx + t[p * 6 + 2] * sy + t[p * 6 + 4]
                val gy = t[p * 6 + 1] * sx + t[p * 6 + 3] * sy + t[p * 6 + 5]
                val tx = Math.floorMod(floor(gx * ppu).toInt(), tile.w); val ty = Math.floorMod(floor(gy * ppu).toInt(), tile.h)
                setColour(tile.px[ty * tile.w + tx])
                for (k in 0 until 4) src[k] *= op
            }
        }
    }

    /** Sets the fragment colour to [argb] (not premultiplied) at [op]acity, premultiplied without rounding. */
    private fun straight(argb: Int, op: Float) {
        val a = ((argb ushr 24) / 255.0) * op
        src[0] = a * 255; src[1] = ((argb ushr 16) and 0xFF) * a; src[2] = ((argb ushr 8) and 0xFF) * a; src[3] = (argb and 0xFF) * a
    }

    private fun patternTile(k: Int, paint: Int): Pair<Tile, Double>? = patternTiles.getOrPut(k) {
        val ps = anchor.paints
        val t = ps.transform
        // device pixels per pattern unit: the density over the paint transform's scale
        val det = abs(t[paint * 6].toDouble() * t[paint * 6 + 3] - t[paint * 6 + 1].toDouble() * t[paint * 6 + 2])
        val ppu = density / sqrt(max(det, 1e-12))
        tiles?.pattern(anchor, k, ppu)?.let { it to (it.w / patternWidth(k)) }
    }

    private fun patternWidth(k: Int): Double {
        val s = anchor.patterns[k].svg
        val m = Regex("width=\"([0-9.]+)\"").find(s) ?: return 1.0
        return m.groupValues[1].toDouble()
    }

    companion object {
        private const val OPAQUE = 0
        private const val TRANSLUCENT = 1
        private const val ALL = 2

        /** The 4x sample positions. */
        @PublishedApi
        internal val S = Faces.SAMPLES_4X

        private fun clampD(v: Double) = if (v < 0) 0.0 else if (v > 1) 1.0 else v

        /** The first-order signed distance to the ellipse (positive inside), device px. */
        fun ellipseDistance(dx0: Double, dy0: Double, cs: Double, sn: Double, rx: Double, ry: Double): Double {
            val dx = dx0 * cs + dy0 * sn; val dy = -dx0 * sn + dy0 * cs
            val f = dx * dx / (rx * rx) + dy * dy / (ry * ry) - 1
            val g = 2 * sqrt(dx * dx / (rx * rx * rx * rx) + dy * dy / (ry * ry * ry * ry))
            return if (g > 1e-12) -f / g else min(rx, ry)
        }

        /** [argb] (not premultiplied) at [op]acity, premultiplied. */
        fun premultiply(argb: Int, op: Float): Int {
            val a = ((argb ushr 24) / 255.0) * op
            if (a <= 0) return 0
            val r = ((argb ushr 16) and 0xFF) * a; val g = ((argb ushr 8) and 0xFF) * a; val b = (argb and 0xFF) * a
            return ((a * 255).roundToInt() shl 24) or (r.roundToInt() shl 16) or (g.roundToInt() shl 8) or b.roundToInt()
        }

        fun scalePremul(c: Int, op: Float): Int {
            if (op >= 1f) return c
            fun ch(v: Int) = (v * op).roundToInt()
            return (ch(c ushr 24) shl 24) or (ch((c ushr 16) and 0xFF) shl 16) or (ch((c ushr 8) and 0xFF) shl 8) or ch(c and 0xFF)
        }

        /** Source-over of premultiplied [src] (a, r, g, b in 0 … 255) at coverage [cov] onto premultiplied [dst], rounded to 8 bits a channel. */
        fun over(src: DoubleArray, cov: Double, dst: Int): Int {
            val k = 1 - src[0] * cov / 255.0
            fun ch(v: Double, sh: Int): Int = (v * cov + ((dst ushr sh) and 0xFF) * k).roundToInt().coerceIn(0, 255)
            return (ch(src[0], 24) shl 24) or (ch(src[1], 16) shl 16) or (ch(src[2], 8) shl 8) or ch(src[3], 0)
        }

        /** Source-over of premultiplied [src] at coverage [cov] onto premultiplied [dst], rounded to 8 bits a channel. */
        fun over(src: Int, cov: Double, dst: Int): Int {
            val sa = (src ushr 24) * cov
            val k = 1 - sa / 255.0
            fun ch(sh: Int): Int = (((src ushr sh) and 0xFF) * cov + ((dst ushr sh) and 0xFF) * k).roundToInt().coerceIn(0, 255)
            return (ch(24) shl 24) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
        }

        /** [src] (premultiplied) at [op]acity composited onto [dst] with [blend] (W3C compositing, premultiplied). */
        fun composite(src: Int, op: Float, dst: Int, blend: SvgBlend): Int {
            if (blend == SvgBlend.NORMAL) return over(src, op.toDouble(), dst)
            val sa = (src ushr 24) / 255.0 * op; val da = (dst ushr 24) / 255.0
            fun un(c: Int, sh: Int, a: Double, k: Double) = if (a <= 0) 0.0 else ((c ushr sh) and 0xFF) / 255.0 * k / a
            val oa = sa + da * (1 - sa)
            fun ch(sh: Int): Int {
                val cs = ((src ushr sh) and 0xFF) / 255.0 * op; val cb = ((dst ushr sh) and 0xFF) / 255.0
                val us = un(src, sh, sa, op.toDouble()); val ub = un(dst, sh, da, 1.0)
                val b = when (blend) {
                    SvgBlend.MULTIPLY -> us * ub
                    SvgBlend.SCREEN -> us + ub - us * ub
                    SvgBlend.OVERLAY -> if (ub <= 0.5) 2 * us * ub else 1 - 2 * (1 - us) * (1 - ub)
                    SvgBlend.DARKEN -> min(us, ub)
                    SvgBlend.LIGHTEN -> max(us, ub)
                    SvgBlend.SOFT_LIGHT -> if (us <= 0.5) ub - (1 - 2 * us) * ub * (1 - ub) else {
                        val dd = if (ub <= 0.25) ((16 * ub - 12) * ub + 4) * ub else sqrt(ub)
                        ub + (2 * us - 1) * (dd - ub)
                    }
                    SvgBlend.NORMAL -> us
                }
                val co = cs * (1 - da) + cb * (1 - sa) + sa * da * b
                return (co * 255).roundToInt().coerceIn(0, 255)
            }
            return ((oa * 255).roundToInt().coerceIn(0, 255) shl 24) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
        }

        /**
         * Gradient [g]'s colour at gradient-space ([x], [y]) into [out] as a, r, g, b in 0 … 255, not premultiplied and not
         * rounded (as a shader computes it): unpremultiplied interpolation between the stops, as browsers do.
         */
        fun gradientAt(g: SvgGradient, x: Double, y: Double, out: DoubleArray) {
            val k = g.coords
            var t: Double
            if (!g.radial) {
                val dx = (k[2] - k[0]).toDouble(); val dy = (k[3] - k[1]).toDouble()
                val l2 = dx * dx + dy * dy
                t = if (l2 > 0) ((x - k[0]) * dx + (y - k[1]) * dy) / l2 else 0.0
            } else {
                val cx = k[0].toDouble(); val cy = k[1].toDouble(); val r = k[2].toDouble(); val fx = k[3].toDouble(); val fy = k[4].toDouble()
                if (fx == cx && fy == cy) {
                    t = hypot(x - cx, y - cy) / r
                } else {
                    // the focal form: the circle through the point, centred on the line from the focus to the centre
                    val pdx = x - fx; val pdy = y - fy; val cdx = cx - fx; val cdy = cy - fy
                    val a = cdx * cdx + cdy * cdy - r * r
                    val b = pdx * cdx + pdy * cdy
                    val c = pdx * pdx + pdy * pdy
                    // t = 1 / s, s the root that puts the point on the circle through the focus
                    t = if (c == 0.0) 0.0 else if (abs(a) < 1e-12) c / (2 * b) else (b - sqrt(max(0.0, b * b - a * c))) / a
                }
            }
            t = when (g.spread) {
                SvgGradient.Spread.PAD -> t.coerceIn(0.0, 1.0)
                SvgGradient.Spread.REPEAT -> t - floor(t)
                SvgGradient.Spread.REFLECT -> { val m = Math.floorMod(floor(t).toInt(), 2); val f = t - floor(t); if (m == 0) f else 1 - f }
            }
            val off = g.offsets; val col = g.colors
            fun put(c: Int) { out[0] = (c ushr 24).toDouble(); out[1] = ((c ushr 16) and 0xFF).toDouble(); out[2] = ((c ushr 8) and 0xFF).toDouble(); out[3] = (c and 0xFF).toDouble() }
            if (t <= off[0]) { put(col[0]); return }
            if (t >= off[off.size - 1]) { put(col[col.size - 1]); return }
            var j = 1
            while (j < off.size && off[j] < t) j++
            val o0 = off[j - 1]; val o1 = off[j]
            val f = if (o1 > o0) (t - o0) / (o1 - o0) else 1.0
            val a = col[j - 1]; val b = col[j]
            for ((k, sh) in intArrayOf(24, 16, 8, 0).withIndex()) out[k] = ((a ushr sh) and 0xFF) + (((b ushr sh) and 0xFF) - ((a ushr sh) and 0xFF)) * f
        }

        /** Premultiplies a, r, g, b (0 … 255, not premultiplied) in [c] at [op]acity, in place, without rounding. */
        fun premultiplyInPlace(c: DoubleArray, op: Float) {
            val a = c[0] / 255.0 * op
            c[0] = a * 255; c[1] *= a; c[2] *= a; c[3] *= a
        }

        /** Calls [f] for every sample of the pixels in the box whose position lies inside the triangle (top-left rule). */
        inline fun forSamplesInTriangle(
            ax: Double, ay: Double, bx: Double, by: Double, cx: Double, cy: Double, bx0: Int, by0: Int, bx1: Int, by1: Int, f: (Int, Int, Int) -> Unit,
        ) {
            val area = (bx - ax) * (cy - ay) - (by - ay) * (cx - ax)
            if (area == 0.0 || area.isNaN()) return
            // orient counter-clockwise in these coordinates
            var x1 = bx; var y1 = by; var x2 = cx; var y2 = cy
            if (area < 0) { x1 = cx; y1 = cy; x2 = bx; y2 = by }
            val x0 = max(bx0, floor(min(ax, min(bx, cx))).toInt()); val xe = min(bx1, ceil(max(ax, max(bx, cx))).toInt())
            val y0 = max(by0, floor(min(ay, min(by, cy))).toInt()); val ye = min(by1, ceil(max(ay, max(by, cy))).toInt())
            for (y in y0..ye) for (x in x0..xe) for (s in 0 until 4) {
                val sx = x + S[s * 2]; val sy = y + S[s * 2 + 1]
                if (edge(ax, ay, x1, y1, sx, sy) && edge(x1, y1, x2, y2, sx, sy) && edge(x2, y2, ax, ay, sx, sy)) f(x, y, s)
            }
        }

        /** Whether (px, py) is inside the edge a→b of a positively oriented triangle, with the top-left tie rule. */
        fun edge(ax: Double, ay: Double, bx: Double, by: Double, px: Double, py: Double): Boolean {
            val w = (bx - ax) * (py - ay) - (by - ay) * (px - ax)
            if (w > 0) return true
            if (w < 0) return false
            val dy = by - ay; val dx = bx - ax
            return dy < 0 || (dy == 0.0 && dx > 0)
        }
    }
}

/**
 * The likeness metrics of plan-gpu D8, over two frames of opaque ARGB pixels ([a] the candidate, [b] the reference),
 * optionally inside a [mask]: MAD over R, G and B; the share of pixels whose largest channel difference is ≤ 2; the
 * p99 of that difference; the worst 32 × 32 block's MAD; and the flat misses (of the reference pixels whose 3 × 3
 * neighbourhood spans ≤ 2 levels in every channel, the share the candidate misses by more than 16). G3's `Likeness`
 * takes these over; the definitions are the same.
 */
class RefMetrics(val mad: Double, val within2: Double, val p99: Int, val block: Double, val flatMisses: Double, val masked: Double) {
    override fun toString() = "MAD %.3f within2 %.2f%% p99 %d block %.2f flat %.4f%% masked %.2f%%".format(mad, within2 * 100, p99, block, flatMisses * 100, masked * 100)

    companion object {
        fun of(a: IntArray, b: IntArray, w: Int, h: Int, mask: BooleanArray? = null): RefMetrics {
            var sum = 0L; var n = 0L; var w2 = 0L
            val hist = LongArray(256)
            for (i in 0 until w * h) {
                if (mask != null && !mask[i]) continue
                var m = 0
                for (s in 0 until 24 step 8) { val d = abs(((a[i] ushr s) and 0xFF) - ((b[i] ushr s) and 0xFF)); sum += d; if (d > m) m = d }
                hist[m]++; n++
                if (m <= 2) w2++
            }
            var c = 0L; var p99 = 0
            for (v in 0 until 256) { c += hist[v]; if (c >= 0.99 * n) { p99 = v; break } }
            var worst = 0.0
            val bs = 32
            for (by in 0 until h / bs) for (bx in 0 until w / bs) {
                var s2 = 0L; var k = 0
                for (y in by * bs until by * bs + bs) for (x in bx * bs until bx * bs + bs) {
                    val i = y * w + x
                    if (mask != null && !mask[i]) continue
                    for (s in 0 until 24 step 8) s2 += abs(((a[i] ushr s) and 0xFF) - ((b[i] ushr s) and 0xFF))
                    k++
                }
                if (k > bs * bs / 2) worst = max(worst, s2 / (3.0 * k))
            }
            var flat = 0L; var miss = 0L
            for (y in 1 until h - 1) for (x in 1 until w - 1) {
                val i = y * w + x
                if (mask != null && !mask[i]) continue
                var fl = true
                var s = 0
                while (s < 24 && fl) {
                    var lo = 255; var hi = 0
                    for (dy in -1..1) for (dx in -1..1) { val v = (b[i + dy * w + dx] ushr s) and 0xFF; lo = min(lo, v); hi = max(hi, v) }
                    if (hi - lo > 2) fl = false
                    s += 8
                }
                if (!fl) continue
                flat++
                var m = 0
                for (q in 0 until 24 step 8) m = max(m, abs(((a[i] ushr q) and 0xFF) - ((b[i] ushr q) and 0xFF)))
                if (m > 16) miss++
            }
            val masked = if (mask == null) 0.0 else mask.count { !it }.toDouble() / mask.size
            return RefMetrics(sum / (3.0 * max(1L, n)), w2.toDouble() / max(1L, n), p99, worst, if (flat == 0L) 0.0 else miss.toDouble() / flat, masked)
        }
    }
}

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
 * A filter [RefRaster] applies instead of drawing its group as if it had none: the group is drawn alone into a target of
 * its own under the frame's coverage rules, resolved, handed to [apply], and the result composited at the group's
 * painter index. plan-vector K6 applies `mat` so, with the port's own filter code: its coverage rule then thins each
 * variant's own coverage of the objects group (MSAA's quarters or halves, or the analytic coverage).
 */
interface GroupFilter {
    /** Whether [filter] is applied; a filter that is not is drawn as absent. */
    fun applies(filter: AnchorFilter): Boolean

    /**
     * [filter] on its group, drawn alone in [layer]: [px] premultiplied ARGB over the frame's device pixels [x0], [y0],
     * [w] × [h] (transparent where the group draws nothing). Returns the result over the same pixels.
     */
    fun apply(filter: AnchorFilter, layer: LayerKind?, px: IntArray, x0: Int, y0: Int, w: Int, h: Int): IntArray
}

/**
 * The GPU's rules emulated on the CPU at the anchor's own camera, so likeness thresholds can be calibrated before a GPU
 * draws (plan-gpu D3 and G1; plan-vector D7 and K6). [variant] says how coverage is found ([Variant]). In every variant
 * a multisampled target holds 8-bit premultiplied colour and the painter index per sample; fragments are blended in
 * floating point and rounded once, as a GPU's blender does; each pixel's samples are averaged at the end (the resolve);
 * clips are masks; groups that need it are drawn into sprites and composited at their painter index; gradients and
 * patterns are evaluated per fragment at the pixel's centre; strokes no wider than a device pixel are Skia's hairlines
 * ([Strokes.hairline]), wider ones segments with their exact-area coverage, trims, joins and caps (with MSAA, strokes
 * from [WIDE] px cover their samples). Filters are off: generator-only elements are left out, the others drawn as if
 * they had none, except those [groupFilter] applies.
 *
 * [twoPass] (every variant but all-analytic, which draws in painter's order) draws the opaque primitives front to back
 * with depth first, then the rest back to front with the depth test; false draws in strict painter's order instead,
 * the reference the two passes equal.
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
    /** The coverage rules: G1's hyb4 unless another is asked for. */
    val variant: Variant = Variant.HYB4,
    /** The filters applied to their groups ([GroupFilter]); every other filter is drawn as absent. */
    val groupFilter: GroupFilter? = null,
) {
    /**
     * The coverage rules a GPU frame can be drawn with (plan-vector D7), each emulated exactly. An anchor's areas are
     * FACEs (convex, at most eight corners; a concave fill is cut into FACEs whose cuts are internal edges), ELLs, and
     * STENCIL (and TRI) fills; its strokes are SEGs and JOIN dots; its text is TILEs, drawn alike in every variant.
     */
    enum class Variant(val id: String, internal val positions: DoubleArray) {
        /**
         * G1's hyb4 (plan-gpu D3): 4x samples at the standard positions; FACE and ELL edges with analytic coverage at
         * the pixel's centre, their opaque interiors (inset by [Faces.INSET]) at the samples in the opaque pass and their
         * fringes in the translucent pass; cuts by the pixel's centre; STENCIL and TRI fills at the samples.
         */
        HYB4("hyb4", Faces.SAMPLES_4X),

        /**
         * 4x MSAA at Vulkan's standard 4x sample positions (frame configurations A and C, plan-vector D5.3): every area,
         * and every stroke, dot or ring at least [WIDE] device px wide (a ribbon at world width), covers the samples
         * inside it, a fill's cuts shared out by their orientation at each sample; thinner strokes keep G1's coverage.
         */
        MSAA4_STANDARD("msaa4-standard", doubleArrayOf(0.375, 0.125, 0.875, 0.375, 0.125, 0.625, 0.625, 0.875)),

        /** The same at Vulkan's standard 2x sample positions (frame configuration B). */
        MSAA2_STANDARD("msaa2-standard", doubleArrayOf(0.75, 0.75, 0.25, 0.25)),

        /**
         * No MSAA (the fidelity design's VF1; frame configuration D): one sample, at the pixel's centre, in painter's
         * order; FACEs and ELLs with analytic coverage, cuts by the pixel's centre; STENCIL and TRI fills (what is not
         * cut into faces) and clips by their area, measured over an 8 × 8 grid of sub-samples; strokes as hyb4's.
         */
        ALL_ANALYTIC("all-analytic", doubleArrayOf(0.5, 0.5));

        /** Samples per pixel. */
        val samples: Int get() = positions.size / 2

        /** The sample positions in a pixel: x, y pairs in 0 … 1, y down. */
        fun positions(): DoubleArray = positions.copyOf()

        companion object {
            /** The variant named [id]: `hyb4`, `msaa4-standard`, `msaa2-standard` or `all-analytic`. */
            fun of(id: String): Variant = entries.firstOrNull { it.id == id } ?: throw IllegalArgumentException("no RefRaster variant '$id'")
        }
    }

    private val cam = anchor.head.camera
    private val pr = anchor.prims
    private val px: DoubleArray
    private val py: DoubleArray

    /** Samples per pixel, their positions, and the bits of all of them. */
    private val ns = variant.samples
    private val sp = variant.positions
    private val allSamples = (1 shl ns) - 1

    /** Areas, and strokes from [WIDE] px, cover the samples inside them. */
    private val msaa = variant == Variant.MSAA4_STANDARD || variant == Variant.MSAA2_STANDARD

    /** One sample, every coverage computed. */
    private val analytic = variant == Variant.ALL_ANALYTIC

    /** Two passes with painter depth; otherwise painter's order. */
    private val depthOrder = twoPass && !analytic

    private val clipMasks = HashMap<Int, Mask>()
    private val textTiles = HashMap<Int, Tile?>()
    private val patternTiles = HashMap<Int, Pair<Tile, Double>?>()

    /** Primitives drawn, by kind, for reports. */
    val drawnCounts = IntArray(PrimKind.COUNT)

    init {
        val n = anchor.points.size
        px = DoubleArray(n); py = DoubleArray(n)
        for (i in 0 until n) { px[i] = anchor.points.screenX(cam, i) * density; py[i] = anchor.points.screenY(cam, i) * density }
    }

    /** One drawing target: [ns] samples per pixel of premultiplied ARGB and a painter index (−1 cleared). */
    private class Target(val x0: Int, val y0: Int, val w: Int, val h: Int, clear: Int, val ns: Int) {
        val col = IntArray(w * h * ns) { clear }
        val dep = IntArray(w * h * ns) { -1 }

        /** The resolve: each pixel's samples averaged (premultiplied), rounded half up. */
        fun resolve(): IntArray {
            val out = IntArray(w * h)
            val half = ns / 2
            for (i in 0 until w * h) {
                var a = 0; var r = 0; var g = 0; var b = 0
                for (s in 0 until ns) {
                    val c = col[i * ns + s]
                    a += c ushr 24; r += (c ushr 16) and 0xFF; g += (c ushr 8) and 0xFF; b += c and 0xFF
                }
                out[i] = (((a + half) / ns) shl 24) or (((r + half) / ns) shl 16) or (((g + half) / ns) shl 8) or ((b + half) / ns)
            }
            return out
        }
    }

    /**
     * A clip over the frame: per sample ([samples], at `(y·wPx + x)·ns + s`), or with all-analytic per pixel, the bits
     * of the 8 × 8 grid's sub-samples inside it ([grid], bit `row·8 + column`).
     */
    private class Mask(val samples: BooleanArray?, val grid: LongArray?)

    private sealed class Step {
        class Prim(val i: Int, val clip: Mask?) : Step()
        class Sprite(
            val node: Int, val group: PlanNode.Group, val clip: Mask?, val units: List<Step>, val painter: Int,
            /** The filter [groupFilter] applies to the group, or null. */
            val filter: AnchorFilter?,
            val layer: LayerKind?,
        ) : Step()
    }

    /** The frame: opaque ARGB pixels, [wPx] × [hPx]. */
    fun render(): IntArray {
        val t = Target(0, 0, wPx, hPx, background, ns)
        val units = units(-1, null, null)
        draw(t, units)
        val out = t.resolve()
        for (i in out.indices) out[i] = out[i] or (0xFF shl 24)
        return out
    }

    // ------------------------------------------------------------------ the plan

    /** The drawing units under plan node [parent] (−1: the root), in [layer], with the clip in force. */
    private fun units(parent: Int, clip: Mask?, layer: LayerKind?): List<Step> {
        val out = ArrayList<Step>()
        for (c in anchor.plan.children(parent)) {
            when (val n = anchor.plan.nodes[c]) {
                is PlanNode.Layer -> out.addAll(units(c, clip, n.layer))
                is PlanNode.Run -> for (i in n.first until n.last) out.add(Step.Prim(i, clip))
                is PlanNode.Group -> {
                    val f = if (n.filter >= 0) anchor.filters[n.filter] else null
                    if (f != null && f.generatorOnly) continue // filters off: what only a filter makes is left out
                    val cl = if (n.clip >= 0) intersect(clip, clipMask(n.clip)) else clip
                    val filtered = f != null && groupFilter?.applies(f) == true
                    val sprite = n.opacity < 1f || n.blend != SvgBlend.NORMAL || filtered
                    if (sprite) out.add(Step.Sprite(c, n, null, units(c, cl, layer), n.painter, if (filtered) f else null, layer)) else out.addAll(units(c, cl, layer))
                }
            }
        }
        return out
    }

    private fun intersect(a: Mask?, b: Mask): Mask {
        if (a == null) return b
        val bg = b.grid
        if (bg != null) {
            val ag = a.grid!!
            return Mask(null, LongArray(bg.size) { ag[it] and bg[it] })
        }
        val bs = b.samples!!; val aSamples = a.samples!!
        return Mask(BooleanArray(bs.size) { aSamples[it] && bs[it] }, null)
    }

    /** Clip [k]'s mask over the frame: the union of its children, within its parent's. */
    private fun clipMask(k: Int): Mask = clipMasks.getOrPut(k) {
        val c = anchor.plan.clips[k]
        if (analytic) {
            val g = LongArray(wPx * hPx)
            for (i in c.first until c.last) {
                when (pr.kind[i]) {
                    PrimKind.STENCIL -> fillGrid(i, 0, 0, wPx - 1, hPx - 1) { x, y, bits -> g[y * wPx + x] = g[y * wPx + x] or bits }
                    PrimKind.ELL -> {
                        val cx = px[pr.int(i, 0)]; val cy = py[pr.int(i, 0)]
                        val rx = pr.float(i, 1) * density; val ry = pr.float(i, 2) * density
                        val x0 = max(0, floor(cx - rx).toInt()); val x1 = min(wPx - 1, ceil(cx + rx).toInt())
                        val y0 = max(0, floor(cy - ry).toInt()); val y1 = min(hPx - 1, ceil(cy + ry).toInt())
                        for (y in y0..y1) for (x in x0..x1) {
                            var bits = 0L
                            for (s in 0 until GRID_SAMPLES) {
                                val dx = (x + GRID[s * 2] - cx) / rx; val dy = (y + GRID[s * 2 + 1] - cy) / ry
                                if (dx * dx + dy * dy <= 1) bits = bits or (1L shl s)
                            }
                            g[y * wPx + x] = g[y * wPx + x] or bits
                        }
                    }
                    else -> Unit
                }
            }
            if (c.parent >= 0) { val p = clipMask(c.parent).grid!!; for (j in g.indices) g[j] = g[j] and p[j] }
            Mask(null, g)
        } else {
            val m = BooleanArray(wPx * hPx * ns)
            for (i in c.first until c.last) {
                when (pr.kind[i]) {
                    PrimKind.STENCIL -> stencilSamples(i, 0, 0, wPx - 1, hPx - 1) { x, y, mask ->
                        for (s in 0 until ns) if (mask and (1 shl s) != 0) m[(y * wPx + x) * ns + s] = true
                    }
                    PrimKind.ELL -> {
                        val cx = px[pr.int(i, 0)]; val cy = py[pr.int(i, 0)]
                        val rx = pr.float(i, 1) * density; val ry = pr.float(i, 2) * density
                        val x0 = max(0, floor(cx - rx).toInt()); val x1 = min(wPx - 1, ceil(cx + rx).toInt())
                        val y0 = max(0, floor(cy - ry).toInt()); val y1 = min(hPx - 1, ceil(cy + ry).toInt())
                        for (y in y0..y1) for (x in x0..x1) for (s in 0 until ns) {
                            val dx = (x + sp[s * 2] - cx) / rx; val dy = (y + sp[s * 2 + 1] - cy) / ry
                            if (dx * dx + dy * dy <= 1) m[(y * wPx + x) * ns + s] = true
                        }
                    }
                    else -> Unit
                }
            }
            if (c.parent >= 0) { val p = clipMask(c.parent).samples!!; for (j in m.indices) m[j] = m[j] && p[j] }
            Mask(m, null)
        }
    }

    /**
     * The samples inside STENCIL primitive [i] (its fans under its fill rule) in the pixels of the box [cx0] … [cx1],
     * [cy0] … [cy1]: [f] gets each pixel that has some, with their bits.
     */
    private inline fun stencilSamples(i: Int, cx0: Int, cy0: Int, cx1: Int, cy1: Int, f: (Int, Int, Int) -> Unit) {
        val first = pr.int(i, 0); val count = pr.int(i, 1)
        if (count == 0) return
        val evenOdd = pr.has(i, PrimFlag.EVEN_ODD)
        var x0 = Double.MAX_VALUE; var y0 = Double.MAX_VALUE; var x1 = -Double.MAX_VALUE; var y1 = -Double.MAX_VALUE
        for (k in 0 until count) { val p = anchor.triangles[first + k]; x0 = min(x0, px[p]); x1 = max(x1, px[p]); y0 = min(y0, py[p]); y1 = max(y1, py[p]) }
        val bx0 = max(cx0, floor(x0).toInt()); val bx1 = min(cx1, ceil(x1).toInt())
        val by0 = max(cy0, floor(y0).toInt()); val by1 = min(cy1, ceil(y1).toInt())
        if (bx0 > bx1 || by0 > by1) return
        val bw = bx1 - bx0 + 1; val bh = by1 - by0 + 1
        val wind = IntArray(bw * bh * ns)
        var t = 0
        while (t < count) {
            val a = anchor.triangles[first + t]; val b = anchor.triangles[first + t + 1]; val c = anchor.triangles[first + t + 2]
            val area = (px[b] - px[a]) * (py[c] - py[a]) - (py[b] - py[a]) * (px[c] - px[a])
            if (area != 0.0) {
                val sgn = if (area > 0) 1 else -1
                forSamplesInTriangle(px[a], py[a], px[b], py[b], px[c], py[c], bx0, by0, bx1, by1, sp) { x, y, s -> wind[((y - by0) * bw + (x - bx0)) * ns + s] += sgn }
            }
            t += 3
        }
        for (y in by0..by1) for (x in bx0..bx1) {
            var mask = 0
            for (s in 0 until ns) {
                val w = wind[((y - by0) * bw + (x - bx0)) * ns + s]
                if (if (evenOdd) (w and 1) != 0 else w != 0) mask = mask or (1 shl s)
            }
            if (mask != 0) f(x, y, mask)
        }
    }

    /**
     * The 8 × 8 grid's sub-samples inside TRI or STENCIL primitive [i] (a TRI's triangles, or a STENCIL's fans under its
     * fill rule) in the pixels of the box [cx0] … [cx1], [cy0] … [cy1]: [f] gets each pixel that has some, with their
     * bits. Measured a strip of [STRIP] rows at a time, each triangle's rows of sub-samples scanned over its own span.
     */
    private inline fun fillGrid(i: Int, cx0: Int, cy0: Int, cx1: Int, cy1: Int, f: (Int, Int, Long) -> Unit) {
        val first = pr.int(i, 0); val count = pr.int(i, 1)
        if (count < 3) return
        val stencil = pr.kind[i] == PrimKind.STENCIL
        val evenOdd = stencil && pr.has(i, PrimFlag.EVEN_ODD)
        var x0 = Double.MAX_VALUE; var y0 = Double.MAX_VALUE; var x1 = -Double.MAX_VALUE; var y1 = -Double.MAX_VALUE
        for (k in 0 until count) { val p = anchor.triangles[first + k]; x0 = min(x0, px[p]); x1 = max(x1, px[p]); y0 = min(y0, py[p]); y1 = max(y1, py[p]) }
        val bx0 = max(cx0, floor(x0).toInt()); val bx1 = min(cx1, ceil(x1).toInt())
        val by0 = max(cy0, floor(y0).toInt()); val by1 = min(cy1, ceil(y1).toInt())
        if (bx0 > bx1 || by0 > by1) return
        val bw = bx1 - bx0 + 1
        val tris = count / 3
        val top = IntArray(tris); val bottom = IntArray(tris)
        for (k in 0 until tris) {
            val a = anchor.triangles[first + k * 3]; val b = anchor.triangles[first + k * 3 + 1]; val c = anchor.triangles[first + k * 3 + 2]
            top[k] = floor(min(py[a], min(py[b], py[c]))).toInt(); bottom[k] = ceil(max(py[a], max(py[b], py[c]))).toInt()
        }
        val wind = IntArray(bw * STRIP * GRID_SAMPLES)
        var ys = by0
        while (ys <= by1) {
            val ye = min(by1, ys + STRIP - 1)
            wind.fill(0, 0, bw * (ye - ys + 1) * GRID_SAMPLES)
            for (k in 0 until tris) {
                if (bottom[k] < ys || top[k] > ye) continue
                val a = anchor.triangles[first + k * 3]; val b = anchor.triangles[first + k * 3 + 1]; val c = anchor.triangles[first + k * 3 + 2]
                val area = (px[b] - px[a]) * (py[c] - py[a]) - (py[b] - py[a]) * (px[c] - px[a])
                if (area == 0.0 || area.isNaN()) continue
                // a TRI's triangles tile its fill; a STENCIL's fans count with their winding
                val sgn = if (!stencil || area > 0) 1 else -1
                val row0 = ys
                gridInTriangle(px[a], py[a], px[b], py[b], px[c], py[c], bx0, ys, bx1, ye) { x, y, s -> wind[((y - row0) * bw + (x - bx0)) * GRID_SAMPLES + s] += sgn }
            }
            for (y in ys..ye) for (x in bx0..bx1) {
                var bits = 0L
                val o = ((y - ys) * bw + (x - bx0)) * GRID_SAMPLES
                for (s in 0 until GRID_SAMPLES) {
                    val w = wind[o + s]
                    if (if (evenOdd) (w and 1) != 0 else w != 0) bits = bits or (1L shl s)
                }
                if (bits != 0L) f(x, y, bits)
            }
            ys = ye + 1
        }
    }

    /**
     * Calls [f] for every sub-sample of the 8 × 8 grid, in the pixels of the box, whose position lies inside the
     * triangle (the top-left rule, as [forSamplesInTriangle]): row by row of sub-samples, over the triangle's span on
     * each, its ends tested exactly.
     */
    private inline fun gridInTriangle(ax: Double, ay: Double, bx: Double, by: Double, cx: Double, cy: Double, bx0: Int, by0: Int, bx1: Int, by1: Int, f: (Int, Int, Int) -> Unit) {
        val area = (bx - ax) * (cy - ay) - (by - ay) * (cx - ax)
        // orient counter-clockwise in these coordinates
        var x1 = bx; var y1 = by; var x2 = cx; var y2 = cy
        if (area < 0) { x1 = cx; y1 = cy; x2 = bx; y2 = by }
        val ylo = max(by0, floor(min(ay, min(by, cy))).toInt()); val yhi = min(by1, ceil(max(ay, max(by, cy))).toInt())
        val xmin = min(ax, min(bx, cx)); val xmax = max(ax, max(bx, cx))
        for (y in ylo..yhi) for (r in 0 until 8) {
            val sy = y + (r + 0.5) / 8
            var lo = xmin; var hi = xmax
            lo = narrow(ax, ay, x1, y1, sy, lo, hi, true); hi = narrow(ax, ay, x1, y1, sy, lo, hi, false)
            lo = narrow(x1, y1, x2, y2, sy, lo, hi, true); hi = narrow(x1, y1, x2, y2, sy, lo, hi, false)
            lo = narrow(x2, y2, ax, ay, sy, lo, hi, true); hi = narrow(x2, y2, ax, ay, sy, lo, hi, false)
            if (lo > hi) continue
            val c0 = max(bx0 * 8, floor(lo * 8 - 0.5).toInt() - 1); val c1 = min(bx1 * 8 + 7, ceil(hi * 8 - 0.5).toInt() + 1)
            for (col in c0..c1) {
                val sx = (col + 0.5) / 8
                if (edge(ax, ay, x1, y1, sx, sy) && edge(x1, y1, x2, y2, sx, sy) && edge(x2, y2, ax, ay, sx, sy)) f(Math.floorDiv(col, 8), y, r * 8 + Math.floorMod(col, 8))
            }
        }
    }

    // ------------------------------------------------------------------ drawing

    private fun draw(t: Target, units: List<Step>) {
        if (depthOrder) {
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

    /**
     * A group drawn into its own target over its primitives' box, resolved, through [groupFilter] when it applies the
     * group's filter (the result kept within the box), and composited at its painter index.
     */
    private fun sprite(t: Target, u: Step.Sprite) {
        val box = bounds(u.units) ?: return
        val x0 = max(t.x0, box[0]); val y0 = max(t.y0, box[1]); val x1 = min(t.x0 + t.w - 1, box[2]); val y1 = min(t.y0 + t.h - 1, box[3])
        if (x0 > x1 || y0 > y1) return
        val s = Target(x0, y0, x1 - x0 + 1, y1 - y0 + 1, 0, ns)
        draw(s, u.units)
        var img = s.resolve()
        if (u.filter != null) {
            img = groupFilter!!.apply(u.filter, u.layer, img, x0, y0, s.w, s.h)
            check(img.size == s.w * s.h) { "the group filter returned ${img.size} pixels for ${s.w} × ${s.h}" }
        }
        val op = if (quantise) Math.round(u.group.opacity * 255f) / 255f else u.group.opacity
        val blend = u.group.blend
        val cs = u.clip?.samples
        for (y in y0..y1) for (x in x0..x1) {
            val src = img[(y - y0) * s.w + (x - x0)]
            if (src == 0) continue
            val o = ((y - t.y0) * t.w + (x - t.x0)) * ns
            for (k in 0 until ns) {
                if (cs != null && !cs[(y * wPx + x) * ns + k]) continue
                if (depthOrder && u.painter <= t.dep[o + k]) continue
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
    private fun prim(t: Target, i: Int, clip: Mask?, pass: Int) {
        drawnCounts[pr.kind[i].toInt()]++
        when (pr.kind[i]) {
            PrimKind.FACE -> if (msaa) faceSamples(t, i, clip, pass) else face(t, i, clip, pass)
            PrimKind.TRI -> if (pass != TRANSLUCENT || pr.has(i, PrimFlag.TRANSLUCENT)) { if (analytic) fillAnalytic(t, i, clip) else triangles(t, i, clip) }
            PrimKind.STENCIL -> if (pass != TRANSLUCENT || pr.has(i, PrimFlag.TRANSLUCENT)) { if (analytic) fillAnalytic(t, i, clip) else stencil(t, i, clip) }
            PrimKind.ELL -> if (msaa) ellipseSamples(t, i, clip, pass) else ellipse(t, i, clip, pass)
            PrimKind.SEG -> if (pass != OPAQUE) { if (msaa && pr.float(i, 4) * density >= WIDE) segSamples(t, i, clip) else seg(t, i, clip) }
            PrimKind.JOIN -> if (pass != OPAQUE) { if (msaa && pr.float(i, 1) * density >= WIDE) dotSamples(t, i, clip) else dot(t, i, clip) }
            PrimKind.TILE -> if (pass != OPAQUE) tile(t, i, clip)
        }
    }

    /** The fragment's colour: premultiplied a, r, g, b in 0 … 255, unrounded, as a shader outputs it. */
    private val src = DoubleArray(4)

    private fun setColour(c: Int) {
        src[0] = (c ushr 24).toDouble(); src[1] = ((c ushr 16) and 0xFF).toDouble(); src[2] = ((c ushr 8) and 0xFF).toDouble(); src[3] = (c and 0xFF).toDouble()
    }

    /**
     * Blends the fragment colour at [cov0] into the samples of pixel ([x], [y]) that [mask] lets through (bit s), within
     * [clip]: its samples, or with all-analytic its area, which scales the coverage.
     */
    private inline fun pixel(t: Target, x: Int, y: Int, i: Int, clip: Mask?, cov0: Double, write: Boolean, mask: Int = allSamples) {
        if (cov0 <= 0) return
        if (quantise) {
            // an 8-bit alpha, the colour premultiplied by it
            val a8 = Math.rint(src[0])
            val k = if (src[0] > 0) a8 / src[0] else 0.0
            src[0] = a8; src[1] *= k; src[2] *= k; src[3] *= k
        }
        val f = y * wPx + x
        var cov = cov0
        val cg = clip?.grid
        if (cg != null) {
            cov *= cg[f].countOneBits() / GRID_SAMPLES.toDouble()
            if (cov <= 0) return
        }
        val cs = clip?.samples
        val o = ((y - t.y0) * t.w + (x - t.x0)) * ns
        for (s in 0 until ns) {
            if (mask and (1 shl s) == 0) continue
            if (cs != null && !cs[f * ns + s]) continue
            if (depthOrder && i <= t.dep[o + s]) continue
            t.col[o + s] = over(src, cov, t.col[o + s])
            if (write) t.dep[o + s] = i
        }
    }

    private fun range(t: Target, b0: Double, b1: Double, lo: Int, hi: Int): IntRange = max(lo, floor(b0).toInt())..min(hi, ceil(b1).toInt())

    /** FACE [i]'s screen geometry: its corners, its anti-aliased edges as distance equations ([e], [m] of them), its cuts. */
    private inner class FaceGeom(i: Int) {
        val n = pr.corners(i)
        private val hard = pr.hardEdges(i)
        private val ids = IntArray(n) { pr.corner(i, it) }
        val xs = DoubleArray(n) { px[ids[it]] }
        val ys = DoubleArray(n) { py[ids[it]] }
        val area = Faces.area2(xs, ys, n)
        val e = DoubleArray(n * 3)
        var m = 0
            private set
        private val hu = IntArray(n)
        private var hn = 0
        private val side = DoubleArray(n)

        init {
            if (area != 0.0) {
                val s = if (area > 0) 1.0 else -1.0
                // anti-aliased edges as distance equations; internal ones by the shared orientation test
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
            }
        }

        /** Whether ([x], [y]) lies on the face's side of each of its cuts. */
        fun inHard(x: Double, y: Double): Boolean {
            for (h in 0 until hn) {
                val k = hu[h]; val j = (k + 1) % n
                if (!Faces.hardSide(xs[k], ys[k], xs[j], ys[j], ids[k], ids[j], x, y, side[h])) return false
            }
            return true
        }
    }

    /** FACE [i] with analytic coverage (hyb4, all-analytic): in the opaque pass its inset interior at the samples, else the whole face. */
    private fun face(t: Target, i: Int, clip: Mask?, pass: Int) {
        val g = FaceGeom(i)
        if (g.area == 0.0) return
        val paint = pr.paint[i]
        val xr = range(t, g.xs.min() - 1, g.xs.max() + 1, t.x0, t.x0 + t.w - 1)
        val yr = range(t, g.ys.min() - 1, g.ys.max() + 1, t.y0, t.y0 + t.h - 1)
        for (y in yr) for (x in xr) {
            val cx = x + 0.5; val cy = y + 0.5
            // internal edges are tested at the pixel's centre in both passes: the face that owns the centre draws the pixel
            if (!g.inHard(cx, cy)) continue
            if (pass == OPAQUE) {
                // the inset interior, at the sample positions
                var mask = 0
                for (q in 0 until ns) if (Faces.inside(g.e, g.m, x + sp[q * 2], y + sp[q * 2 + 1]) >= Faces.INSET) mask = mask or (1 shl q)
                if (mask == 0) continue
                paintAt(paint, cx, cy); pixel(t, x, y, i, clip, 1.0, true, mask)
            } else {
                // the whole face: over its own interior the depth test rejects it
                val cov = Faces.coverage(g.e, g.m, cx, cy)
                if (cov > 0) { paintAt(paint, cx, cy); pixel(t, x, y, i, clip, cov, false) }
            }
        }
    }

    /**
     * FACE [i] at the samples (MSAA): the samples inside its outline and on its side of each cut, tested there as its
     * neighbours in the fill test them, so the faces of a fill share every sample out. Opaque faces are drawn whole in
     * the opaque pass.
     */
    private fun faceSamples(t: Target, i: Int, clip: Mask?, pass: Int) {
        val opaque = !pr.has(i, PrimFlag.TRANSLUCENT)
        if (pass == TRANSLUCENT && opaque) return
        val g = FaceGeom(i)
        if (g.area == 0.0) return
        val paint = pr.paint[i]
        val write = opaque && pass != TRANSLUCENT
        val xr = range(t, g.xs.min() - 1, g.xs.max() + 1, t.x0, t.x0 + t.w - 1)
        val yr = range(t, g.ys.min() - 1, g.ys.max() + 1, t.y0, t.y0 + t.h - 1)
        for (y in yr) for (x in xr) {
            var mask = 0
            for (q in 0 until ns) {
                val sx = x + sp[q * 2]; val sy = y + sp[q * 2 + 1]
                if (Faces.inside(g.e, g.m, sx, sy) >= 0 && g.inHard(sx, sy)) mask = mask or (1 shl q)
            }
            if (mask == 0) continue
            paintAt(paint, x + 0.5, y + 0.5); pixel(t, x, y, i, clip, 1.0, write, mask)
        }
    }

    /** ELL [i] with analytic coverage (hyb4 and all-analytic; and rings thinner than [WIDE] px in every variant). */
    private fun ellipse(t: Target, i: Int, clip: Mask?, pass: Int) {
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
                for (q in 0 until ns) {
                    val dx0 = x + sp[q * 2] - cx0; val dy0 = y + sp[q * 2 + 1] - cy0
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

    /**
     * ELL [i] at the samples (MSAA): a filled ellipse's samples, or a ring's between its outer and inner ellipses (the
     * shapes hyb4 bounds it by); a ring thinner than [WIDE] px keeps the analytic coverage.
     */
    private fun ellipseSamples(t: Target, i: Int, clip: Mask?, pass: Int) {
        val c = pr.int(i, 0)
        val cx0 = px[c]; val cy0 = py[c]
        val rx = pr.float(i, 1) * density; val ry = pr.float(i, 2) * density
        val rot = pr.float(i, 3).toDouble(); val ring = pr.float(i, 4) * density
        if (rx <= 0 || ry <= 0) return
        if (ring > 0 && ring < WIDE) { ellipse(t, i, clip, pass); return }
        val opaque = !pr.has(i, PrimFlag.TRANSLUCENT) && ring == 0.0
        if (pass == OPAQUE && !opaque) return
        if (pass == TRANSLUCENT && opaque) return
        val cs = cos(rot); val sn = sin(rot)
        val hw = ring / 2
        val ox = rx + hw; val oy = ry + hw
        val ix = rx - hw; val iy = ry - hw
        val hole = ring > 0 && ix > 0 && iy > 0
        val reach = max(rx, ry) + hw + 1.5
        val paint = pr.paint[i]
        val write = opaque && pass != TRANSLUCENT
        val xr = range(t, cx0 - reach, cx0 + reach, t.x0, t.x0 + t.w - 1)
        val yr = range(t, cy0 - reach, cy0 + reach, t.y0, t.y0 + t.h - 1)
        for (y in yr) for (x in xr) {
            var mask = 0
            for (q in 0 until ns) {
                val dx0 = x + sp[q * 2] - cx0; val dy0 = y + sp[q * 2 + 1] - cy0
                val ex = dx0 * cs + dy0 * sn; val ey = -dx0 * sn + dy0 * cs
                if ((ex / ox) * (ex / ox) + (ey / oy) * (ey / oy) > 1) continue
                if (hole && (ex / ix) * (ex / ix) + (ey / iy) * (ey / iy) < 1) continue
                mask = mask or (1 shl q)
            }
            if (mask != 0) { paintAt(paint, x + 0.5, y + 0.5); pixel(t, x, y, i, clip, 1.0, write, mask) }
        }
    }

    private fun triangles(t: Target, i: Int, clip: Mask?) {
        val first = pr.int(i, 0); val count = pr.int(i, 1)
        val paint = pr.paint[i]
        val write = !pr.has(i, PrimFlag.TRANSLUCENT)
        var k = 0
        while (k < count) {
            val a = anchor.triangles[first + k]; val b = anchor.triangles[first + k + 1]; val c = anchor.triangles[first + k + 2]
            forSamplesInTriangle(px[a], py[a], px[b], py[b], px[c], py[c], t.x0, t.y0, t.x0 + t.w - 1, t.y0 + t.h - 1, sp) { x, y, s ->
                paintAt(paint, x + 0.5, y + 0.5); pixel(t, x, y, i, clip, 1.0, write, 1 shl s)
            }
            k += 3
        }
    }

    private fun stencil(t: Target, i: Int, clip: Mask?) {
        val paint = pr.paint[i]
        val write = !pr.has(i, PrimFlag.TRANSLUCENT)
        stencilSamples(i, t.x0, t.y0, t.x0 + t.w - 1, t.y0 + t.h - 1) { x, y, mask -> paintAt(paint, x + 0.5, y + 0.5); pixel(t, x, y, i, clip, 1.0, write, mask) }
    }

    /** TRI or STENCIL [i] with its area for coverage (all-analytic): the share of the 8 × 8 grid inside it. */
    private fun fillAnalytic(t: Target, i: Int, clip: Mask?) {
        val paint = pr.paint[i]
        fillGrid(i, t.x0, t.y0, t.x0 + t.w - 1, t.y0 + t.h - 1) { x, y, bits ->
            paintAt(paint, x + 0.5, y + 0.5); pixel(t, x, y, i, clip, bits.countOneBits() / GRID_SAMPLES.toDouble(), false)
        }
    }

    /**
     * SEG [i]'s screen geometry: its ends, its trims (the bisectors with its neighbours), its join's wedge and its caps.
     * [coverage] is G1's exact-area coverage of a pixel, [inside] the same shape tested at a point.
     */
    private inner class SegGeom(i: Int) {
        val a = pr.int(i, 0)
        val b = pr.int(i, 1)
        val prev = pr.int(i, 2)
        val next = pr.int(i, 3)
        val hw = pr.float(i, 4) * density / 2
        private val ends = pr.int(i, 5)
        val capA = ends and 3
        val capB = (ends shr 2) and 3
        private val join = (ends shr 4) and 3
        private val limit = pr.float(i, 6).toDouble()
        val ax = px[a]
        val ay = py[a]
        val bx = px[b]
        val by = py[b]
        val len = hypot(bx - ax, by - ay)
        private val ux = if (len < 1e-9) 0.0 else (bx - ax) / len
        private val uy = if (len < 1e-9) 0.0 else (by - ay) / len
        private val nx = -uy
        private val ny = ux

        // the bisector with the predecessor (this segment owns (p − A)·(u_prev + u) ≥ 0) and with the successor ((p − B)·(u + u_next) < 0)
        private var sax = 0.0
        private var say = 0.0
        private var sbx = 0.0
        private var sby = 0.0
        private var vx = 0.0
        private var vy = 0.0

        // the join's wedge at b: the outer side of the turn for each segment
        private val oix: Double
        private val oiy: Double
        private val oox: Double
        private val ooy: Double
        private val ox: Double
        private val oy: Double
        private val ol: Double
        private val bevel: Boolean
        private val extA: Double
        private val extB: Double

        /** How far beyond its ends (device px) the segment can reach. */
        val reach: Double

        init {
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
            val turn = ux * vy - uy * vx
            val sgn = if (turn > 0) -1.0 else 1.0
            oix = -uy * sgn; oiy = ux * sgn
            oox = -vy * sgn; ooy = vx * sgn
            ox = oix + oox; oy = oiy + ooy
            ol = hypot(ox, oy)
            val half = sqrt(max(0.0, (1 + ux * vx + uy * vy) / 2))
            bevel = join == Join.BEVEL || (join == Join.MITER && (half == 0.0 || 1 / half > limit))
            extA = if (prev < 0 && capA == Cap.SQUARE) hw else 0.0
            extB = if (next < 0 && capB == Cap.SQUARE) hw else 0.0
            reach = hw * (if (next >= 0 && join == Join.MITER) max(1.0, limit) else 1.0) + max(extA, extB) + 1.5
        }

        /** G1's coverage of the pixel centred at ([cx], [cy]): the strip's exact area, trimmed, its caps' and its join's. */
        fun coverage(cx: Double, cy: Double): Double {
            val qx = cx - ax; val qy = cy - ay
            val al = qx * ux + qy * uy
            val pe = qx * nx + qy * ny
            // a joined start: from a, on this side of the bisector
            if (prev >= 0 && (al < 0 || qx * sax + qy * say < 0)) return 0.0
            val rx = cx - bx; val ry = cy - by
            if (next >= 0 && al > len) {
                // beyond the end: the join's wedge, where it also lies before the successor's start
                if (rx * vx + ry * vy >= 0) return 0.0
                return when {
                    join == Join.ROUND -> Strokes.radial(hypot(rx, ry), hw)
                    bevel -> if (ol < 1e-9) 0.0 else clampD(0.5 + hw * abs(oix * ox + oiy * oy) / ol - (rx * ox + ry * oy) / ol)
                    else -> clampD(0.5 + hw - (rx * oix + ry * oiy)) * clampD(0.5 + hw - (rx * oox + ry * ooy))
                }
            }
            if (next >= 0 && rx * sbx + ry * sby >= 0) return 0.0
            // free round ends: their caps
            if (prev < 0 && capA == Cap.ROUND && al < 0) return Strokes.radial(hypot(qx, qy), hw)
            if (next < 0 && capB == Cap.ROUND && al > len) return Strokes.radial(hypot(rx, ry), hw)
            if (abs(pe) > hw + 1) return 0.0
            val strip = Strokes.boxCdf(hw - pe, nx, ny) - Strokes.boxCdf(-hw - pe, nx, ny)
            val lo = if (prev < 0 && capA != Cap.ROUND) -extA else -1e9
            val hi = if (next < 0 && capB != Cap.ROUND) len + extB else 1e9
            return strip * (Strokes.boxCdf(hi - al, ux, uy) - Strokes.boxCdf(lo - al, ux, uy))
        }

        /** Whether the point ([x], [y]) lies in the same shape: the band between its trims, its caps, its join's wedge. */
        fun inside(x: Double, y: Double): Boolean {
            val qx = x - ax; val qy = y - ay
            val al = qx * ux + qy * uy
            val pe = qx * nx + qy * ny
            if (prev >= 0 && (al < 0 || qx * sax + qy * say < 0)) return false
            val rx = x - bx; val ry = y - by
            if (next >= 0 && al > len) {
                if (rx * vx + ry * vy >= 0) return false
                return when {
                    join == Join.ROUND -> hypot(rx, ry) <= hw
                    bevel -> ol >= 1e-9 && rx * ox + ry * oy <= hw * abs(oix * ox + oiy * oy)
                    else -> rx * oix + ry * oiy <= hw && rx * oox + ry * ooy <= hw
                }
            }
            if (next >= 0 && rx * sbx + ry * sby >= 0) return false
            if (prev < 0 && capA == Cap.ROUND && al < 0) return hypot(qx, qy) <= hw
            if (next < 0 && capB == Cap.ROUND && al > len) return hypot(rx, ry) <= hw
            val lo = if (prev < 0 && capA != Cap.ROUND) -extA else -1e9
            val hi = if (next < 0 && capB != Cap.ROUND) len + extB else 1e9
            return abs(pe) <= hw && al >= lo && al <= hi
        }
    }

    private fun seg(t: Target, i: Int, clip: Mask?) {
        val g = SegGeom(i)
        if (2 * g.hw <= Strokes.HAIRLINE) { hairline(t, i, clip, g.a, g.b, g.prev < 0 && g.capA != Cap.BUTT, g.next < 0 && g.capB != Cap.BUTT, 2 * g.hw); return }
        if (g.len < 1e-9) return
        val paint = pr.paint[i]
        val xr = range(t, min(g.ax, g.bx) - g.reach, max(g.ax, g.bx) + g.reach, t.x0, t.x0 + t.w - 1)
        val yr = range(t, min(g.ay, g.by) - g.reach, max(g.ay, g.by) + g.reach, t.y0, t.y0 + t.h - 1)
        for (y in yr) for (x in xr) {
            val cx = x + 0.5; val cy = y + 0.5
            val cov = g.coverage(cx, cy)
            if (cov > 0) { paintAt(paint, cx, cy); pixel(t, x, y, i, clip, cov, false) }
        }
    }

    /** SEG [i], at least [WIDE] px wide, at the samples (MSAA): a ribbon at world width, its samples those inside its shape. */
    private fun segSamples(t: Target, i: Int, clip: Mask?) {
        val g = SegGeom(i)
        if (g.len < 1e-9) return
        val paint = pr.paint[i]
        val xr = range(t, min(g.ax, g.bx) - g.reach, max(g.ax, g.bx) + g.reach, t.x0, t.x0 + t.w - 1)
        val yr = range(t, min(g.ay, g.by) - g.reach, max(g.ay, g.by) + g.reach, t.y0, t.y0 + t.h - 1)
        for (y in yr) for (x in xr) {
            var mask = 0
            for (q in 0 until ns) if (g.inside(x + sp[q * 2], y + sp[q * 2 + 1])) mask = mask or (1 shl q)
            if (mask != 0) { paintAt(paint, x + 0.5, y + 0.5); pixel(t, x, y, i, clip, 1.0, false, mask) }
        }
    }

    /**
     * A segment no wider than a device pixel, as Skia draws it: an anti-aliased hairline (the two pixels straddling the
     * line along its minor axis share the coverage) with its alpha scaled by the width, from end to end along the major
     * axis (a capped free end half a pixel further), with no trims or joins: each segment of a polyline is its own line.
     */
    private fun hairline(t: Target, i: Int, clip: Mask?, a: Int, b: Int, extendA: Boolean, extendB: Boolean, width: Double) {
        val paint = pr.paint[i]
        Strokes.hairline(px[a], py[a], px[b], py[b], extendA, extendB, width) { x, y, cov ->
            if (x >= t.x0 && y >= t.y0 && x < t.x0 + t.w && y < t.y0 + t.h) { paintAt(paint, x + 0.5, y + 0.5); pixel(t, x, y, i, clip, cov, false) }
        }
    }

    /** A dot: a zero-length subpath's round cap. */
    private fun dot(t: Target, i: Int, clip: Mask?) {
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

    /** A dot at least [WIDE] px wide at the samples (MSAA): the samples within its radius. */
    private fun dotSamples(t: Target, i: Int, clip: Mask?) {
        val p = pr.int(i, 0)
        val hw = pr.float(i, 1) * density / 2
        val x0 = px[p]; val y0 = py[p]
        val reach = hw + 1.5
        val paint = pr.paint[i]
        for (y in range(t, y0 - reach, y0 + reach, t.y0, t.y0 + t.h - 1)) for (x in range(t, x0 - reach, x0 + reach, t.x0, t.x0 + t.w - 1)) {
            var mask = 0
            for (q in 0 until ns) if (hypot(x + sp[q * 2] - x0, y + sp[q * 2 + 1] - y0) <= hw) mask = mask or (1 shl q)
            if (mask != 0) { paintAt(paint, x + 0.5, y + 0.5); pixel(t, x, y, i, clip, 1.0, false, mask) }
        }
    }

    private fun tile(t: Target, i: Int, clip: Mask?) {
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

        /**
         * The width, device px, from which a stroke, a dot or a ring covers its samples under MSAA (a ribbon at world
         * width) instead of keeping G1's exact-area coverage (plan-vector D7: "strokes under 2 device px").
         */
        const val WIDE = 2.0

        /** The 4x sample positions (hyb4's, which are Vulkan's standard 4x positions). */
        @PublishedApi
        internal val S = Faces.SAMPLES_4X

        /** All-analytic's grid: 8 × 8 sub-samples a pixel, sub-sample k at ((k mod 8 + ½) / 8, (k div 8 + ½) / 8). */
        private const val GRID_SAMPLES = 64
        private val GRID = DoubleArray(GRID_SAMPLES * 2) { j -> (if (j % 2 == 0) (j / 2) % 8 else (j / 2) / 8) / 8.0 + 1 / 16.0 }

        /** Rows of pixels all-analytic's fills are measured over at once. */
        private const val STRIP = 16

        private fun clampD(v: Double) = if (v < 0) 0.0 else if (v > 1) 1.0 else v

        /**
         * [lo] or [hi] ([low] says which), the bounds of x on the row y = [y], narrowed by the edge from ([ax], [ay]) to
         * ([bx], [by]) of a positively oriented triangle (inside where (b − a) × (p − a) ≥ 0), with a sixteenth of a
         * pixel to spare: the exact test at the ends decides.
         */
        private fun narrow(ax: Double, ay: Double, bx: Double, by: Double, y: Double, lo: Double, hi: Double, low: Boolean): Double {
            val dy = by - ay
            if (dy == 0.0) return if (low) lo else hi
            val xc = ax + (bx - ax) * (y - ay) / dy
            return if (dy > 0) (if (low) lo else min(hi, xc + 0.0625)) else (if (low) max(lo, xc - 0.0625) else hi)
        }

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

        /**
         * Calls [f] for every sample of the pixels in the box, at [samples] (x, y pairs in a pixel: the 4x positions
         * unless given), whose position lies inside the triangle (top-left rule).
         */
        inline fun forSamplesInTriangle(
            ax: Double, ay: Double, bx: Double, by: Double, cx: Double, cy: Double, bx0: Int, by0: Int, bx1: Int, by1: Int,
            samples: DoubleArray = S, f: (Int, Int, Int) -> Unit,
        ) {
            val area = (bx - ax) * (cy - ay) - (by - ay) * (cx - ax)
            if (area == 0.0 || area.isNaN()) return
            // orient counter-clockwise in these coordinates
            var x1 = bx; var y1 = by; var x2 = cx; var y2 = cy
            if (area < 0) { x1 = cx; y1 = cy; x2 = bx; y2 = by }
            val x0 = max(bx0, floor(min(ax, min(bx, cx))).toInt()); val xe = min(bx1, ceil(max(ax, max(bx, cx))).toInt())
            val y0 = max(by0, floor(min(ay, min(by, cy))).toInt()); val ye = min(by1, ceil(max(ay, max(by, cy))).toInt())
            val n = samples.size / 2
            for (y in y0..ye) for (x in x0..xe) for (s in 0 until n) {
                val sx = x + samples[s * 2]; val sy = y + samples[s * 2 + 1]
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

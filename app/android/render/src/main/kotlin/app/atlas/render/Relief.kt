// render/relief.py: the ground on its real shape, drawn per pixel: every view ray traced onto the survey's heights, coloured by
// the mapped land cover where it lands, lit from the hour's sun with the shadows the dunes cast; and for flat drawings, the
// relief as light and shade over the flat ground. Heights come from the survey, colours only from OSM tags. Ported line by line.
package app.atlas.render

import app.atlas.render.City.P
import app.atlas.render.City.hx
import app.atlas.render.City.mulc
import app.atlas.render.City.tohex
import app.atlas.render.City.vnorm
import app.atlas.render.py.Dict
import app.atlas.render.py.PyFmt.pf
import app.atlas.render.py.PyMath
import app.atlas.render.py.pyNum
import app.atlas.render.py.truthy
import java.io.File
import java.nio.file.Files

/**
 * A ground image that stands on the board as its own file (`info['raster']`): "png" or "jpg", its bytes, its CSS clip or null;
 * for a JPEG also the PNG it was encoded from ([source]), so the pixels can be checked apart from the encoder.
 */
class Raster(val ext: String, val data: ByteArray, val clip: String?, val source: ByteArray? = null)

/** Turns the lit ground (a PNG of RGB rows) into the JPEG relief.py writes. */
fun interface JpegEncoder {
    fun encode(png: ByteArray): ByteArray
}

/** render/relief.py. */
object Relief {
    // render/relief.py:9 Grid
    /** Heights on a local 5 m grid (x east, z south), filled where the survey is empty, with lakes and sea set to their water level. */
    class Grid(dem: Terrain.DEM, val x0: Double, x1: Double, val z0: Double, z1: Double, val step: Double = 5.0) {
        val nx: Int = PyMath.int((x1 - x0) / step).toInt() + 2
        val nz: Int = PyMath.int((z1 - z0) / step).toInt() + 2

        /** Row-major heights; NaN (Python's None) where the survey is empty until [fill]. */
        val g: DoubleArray = DoubleArray(nx * nz)
        var top: Double = 0.0

        /** The land cover at [cs] cells per grid step, as indices into [names] (relief.build). */
        var cover: ByteArray = ByteArray(0)
        var cs: Int = 0
        var names: List<String> = emptyList()
        var lakeLevels: List<Double?> = emptyList()

        /** The water mask at twice the grid's resolution (relief.build_flat). */
        var wet: ByteArray = ByteArray(0)

        init {
            var k = 0
            for (j in 0 until nz) for (i in 0 until nx) g[k++] = dem.hn(x0 + i * step, z0 + j * step)
        }

        // render/relief.py:16 Grid.cells
        /** Grid cells inside a polygon, row by row (scanline): calls [f] with (row start index, i0, i1). */
        inline fun cells(poly: List<V2>, scale: Int = 1, f: (Int, Int, Int) -> Unit) {
            val st = step / scale; val nx = this.nx * scale; val nz = this.nz * scale
            val n = poly.size
            var zmin = poly[0].y; var zmax = poly[0].y
            for (p in poly) { if (p.y < zmin) zmin = p.y; if (p.y > zmax) zmax = p.y }
            val xs = DoubleArray(n + 1)
            for (j in Math.max(0, PyMath.int((zmin - z0) / st).toInt()) until Math.min(nz, PyMath.int((zmax - z0) / st).toInt() + 2)) {
                val z = z0 + j * st; var c = 0
                for (k in 0 until n) {
                    val a = poly[k]; val b = poly[(k + 1) % n]
                    val ax = a.x; val az = a.y; val bx = b.x; val bz = b.y
                    if ((az <= z && z < bz) || (bz <= z && z < az)) xs[c++] = ax + (z - az) * (bx - ax) / (bz - az)
                }
                java.util.Arrays.sort(xs, 0, c)
                var q = 0
                while (q + 1 < c) {
                    val a = xs[q]; val b = xs[q + 1]
                    val i0 = Math.max(0, PyMath.int(Math.ceil((a - x0) / st)).toInt()); val i1 = Math.min(nx, PyMath.int((b - x0) / st).toInt() + 1)
                    if (i1 > i0) f(j * nx, i0, i1)
                    q += 2
                }
            }
        }

        // render/relief.py:30 Grid.set_level
        fun setLevel(poly: List<V2>, y: Double) {
            cells(poly) { o, i0, i1 -> for (i in o + i0 until o + i1) g[i] = y }
        }

        // render/relief.py:35 Grid.fill
        /** Empty cells (buildings, gaps) take the average of their surveyed neighbours, spreading inward. */
        fun fill() {
            val nx = nx; val nz = nz
            var todo = IntArrayList()
            for (k in 0 until nx * nz) if (g[k].isNaN()) todo.add(k)
            val v = DoubleArray(4)
            while (todo.size > 0) {
                val nxt = IntArrayList(); val updK = IntArrayList(); val updV = DoubleArrayList()
                for (t in 0 until todo.size) {
                    val k = todo[t]
                    val i = k % nx; val j = k / nx; var c = 0
                    if (i > 0 && !g[k - 1].isNaN()) v[c++] = g[k - 1]
                    if (i < nx - 1 && !g[k + 1].isNaN()) v[c++] = g[k + 1]
                    if (j > 0 && !g[k - nx].isNaN()) v[c++] = g[k - nx]
                    if (j < nz - 1 && !g[k + nx].isNaN()) v[c++] = g[k + nx]
                    if (c > 0) { updK.add(k); updV.add(PyMath.sum(v, c) / c) } else nxt.add(k)
                }
                if (updK.size == 0) break
                for (t in 0 until updK.size) g[updK[t]] = updV[t]
                todo = nxt
            }
            for (t in 0 until todo.size) g[todo[t]] = 0.0
            var m = g[0]
            for (k in 1 until g.size) if (g[k] > m) m = g[k]
            top = m + 1.0
        }

        // render/relief.py:55 Grid.h
        fun h(x: Double, z: Double): Double {
            val fx = (x - x0) / step; val fz = (z - z0) / step
            val i = PyMath.int(fx).toInt(); val j = PyMath.int(fz).toInt()
            if (i < 0 || j < 0 || i >= nx - 1 || j >= nz - 1) return 0.0
            val tx = fx - i; val tz = fz - j; val k = j * nx + i; val g = g
            return (g[k] * (1 - tx) + g[k + 1] * tx) * (1 - tz) + (g[k + nx] * (1 - tx) + g[k + nx + 1] * tx) * tz
        }
    }

    /** A growable list of ints, without boxing. */
    class IntArrayList {
        private var a = IntArray(16)
        var size = 0; private set
        fun add(v: Int) { if (size == a.size) a = a.copyOf(size * 2); a[size++] = v }
        operator fun get(i: Int): Int = a[i]
    }

    /** A growable list of doubles, without boxing. */
    class DoubleArrayList {
        private var a = DoubleArray(16)
        var size = 0; private set
        fun add(v: Double) { if (size == a.size) a = a.copyOf(size * 2); a[size++] = v }
        operator fun get(i: Int): Double = a[i]
    }

    // render/relief.py:63 build
    /** Heights and land cover on grids over the surveyed area (or the part of it a view needs, [extent] = x0, x1, z0, z1). */
    fun build(dem: Terrain.DEM, lakes: List<List<V2>>, seas: List<List<V2>>, land: List<Map<String, Any?>>, kinds: Map<String, Pair<String, Int>>,
              extent: DoubleArray? = null, step: Double = 5.0): Grid {
        // the survey's corners in local metres
        val corners = ArrayList<V2>()
        for (fx in doubleArrayOf(0.0, 1.0)) {
            for (fy in doubleArrayOf(0.0, 1.0)) {
                if (dem.crs == "rd") {
                    val X = dem.x0 + (dem.num("x1") - dem.x0) * fx; val Y = dem.num("y0") + (dem.y1 - dem.num("y0")) * fy
                    corners.add(rdToLocal(X, Y))
                } else {
                    val lat = dem.num("s") + (dem.num("n") - dem.num("s")) * fy; val lon = dem.num("w") + (dem.num("e") - dem.num("w")) * fx
                    corners.add(V2((lon - Amsterdam.LON0) * Amsterdam.KX, (Amsterdam.LAT0 - lat) * Amsterdam.KZ))
                }
            }
        }
        var x0 = City.pyMinOf(corners.map { it.x }); var x1 = City.pyMaxOf(corners.map { it.x })
        var z0 = City.pyMinOf(corners.map { it.y }); var z1 = City.pyMaxOf(corners.map { it.y })
        if (extent != null) {
            x0 = PyMath.max(x0, extent[0]); x1 = PyMath.min(x1, extent[1]); z0 = PyMath.max(z0, extent[2]); z1 = PyMath.min(z1, extent[3])
        }
        val G = Grid(dem, x0, x1, z0, z1, step)
        // water first: the sea at NAP 0, each lake at the height of its own shore
        for (poly in seas) G.setLevel(poly, 0.0)
        val levels = ArrayList<Double?>()
        for (poly in lakes) {
            val v = ArrayList<Double>()
            val n = poly.size
            for (s in 0 until n) {
                val a = poly[s]; val b = poly[(s + 1) % n]
                val m = Math.max(1, PyMath.int(Amsterdam.dist(a, b) / 8).toInt())
                for (q in 0 until m) {
                    val y = dem.hn(a[0] + (b[0] - a[0]) * q / m, a[1] + (b[1] - a[1]) * q / m)
                    if (!y.isNaN()) v.add(y)
                }
            }
            val lvl = if (v.isNotEmpty()) v.sortedWith { p, q -> java.lang.Double.compare(p, q) }[v.size / 10] else null
            levels.add(lvl)
            if (lvl != null) G.setLevel(poly, lvl)
        }
        G.fill()
        // land cover at 1.25 m: mapped areas in their draw order, then lakes and the sea
        val CS = 4; G.cs = CS
        val names = listOf("ground") + kinds.keys + listOf("lake", "sea")
        val idx = HashMap<String, Int>(); for ((i, k) in names.withIndex()) idx[k] = i
        val cv = ByteArray(G.nx * CS * G.nz * CS)
        for (a in land) {
            val k = idx.getValue(a["kind"] as String).toByte()
            G.cells(Amsterdam.polyOf(a), CS) { o, i0, i1 -> java.util.Arrays.fill(cv, o + i0, o + i1, k) }
        }
        val lake = idx.getValue("lake").toByte(); val sea = idx.getValue("sea").toByte()
        for (poly in lakes) G.cells(poly, CS) { o, i0, i1 -> java.util.Arrays.fill(cv, o + i0, o + i1, lake) }
        for (poly in seas) G.cells(poly, CS) { o, i0, i1 -> java.util.Arrays.fill(cv, o + i0, o + i1, sea) }
        G.cover = cv; G.names = names
        G.lakeLevels = levels
        return G
    }

    // render/relief.py:109 _rd_to_local
    /** RD (Dutch grid) to local metres, by inverting the forward conversion with Newton steps. */
    fun rdToLocal(X: Double, Y: Double): V2 {
        var lat = Amsterdam.LAT0; var lon = Amsterdam.LON0
        for (q in 0 until 4) {
            val p0 = FetchDem.rd(lat, lon); val x1 = FetchDem.rd(lat, lon + 1e-4).x; val y1 = FetchDem.rd(lat + 1e-4, lon).y
            lon += (X - p0.x) / ((x1 - p0.x) / 1e-4); lat += (Y - p0.y) / ((y1 - p0.y) / 1e-4)
        }
        return V2((lon - Amsterdam.LON0) * Amsterdam.KX, (Amsterdam.LAT0 - lat) * Amsterdam.KZ)
    }

    // render/relief.py:119 _shadows
    /** Cells the sun cannot reach: a walk from each cell toward the sun until the ray clears the highest dune. */
    fun shadows(G: Grid, L: V3): ByteArray? {
        if (L[1] <= 0.02) return null
        val hl = PyMath.hypot(L[0], L[2]); val sx = L[0] / hl; val sz = L[2] / hl; val rise = L[1] / hl
        val g = G.g; val nx = G.nx; val nz = G.nz; val st = G.step; val top = G.top
        val sh = ByteArray(nx * nz); val step = st
        for (j in 0 until nz) {
            for (i in 0 until nx) {
                val h0 = g[j * nx + i]; var d = step
                while (true) {
                    val y = h0 + rise * d
                    if (y >= top) break
                    val fi = i + sx * d / st; val fj = j + sz * d / st
                    val ii = PyMath.int(fi).toInt(); val jj = PyMath.int(fj).toInt()
                    if (ii < 0 || jj < 0 || ii >= nx - 1 || jj >= nz - 1) break
                    val tx = fi - ii; val tz = fj - jj; val k = jj * nx + ii
                    if ((g[k] * (1 - tx) + g[k + 1] * tx) * (1 - tz) + (g[k + nx] * (1 - tx) + g[k + nx + 1] * tx) * tz > y + 0.3) {
                        sh[j * nx + i] = 1; break
                    }
                    d += step
                }
            }
        }
        return sh
    }

    // render/relief.py:141 _png_rgb
    /** An RGB PNG from rows of `3 * w` bytes, deflated at level 6. */
    fun pngRgb(w: Int, h: Int, rows: List<ByteArray>): ByteArray = Terrain.png(w, h, rows, 2, 6)

    /** The JPEG step of [raster]: macOS `sips` at quality 80, as relief.py runs it. The app sets its own encoder. */
    var jpeg: JpegEncoder = JpegEncoder { png -> sips(png) }

    /** `sips -s format jpeg -s formatOptions 80 g.png --out g.jpg` (macOS only). */
    fun sips(png: ByteArray): ByteArray {
        val tmp = Files.createTempDirectory("relief").toFile()
        try {
            val pp = File(tmp, "g.png"); val jp = File(tmp, "g.jpg")
            pp.writeBytes(png)
            val p = ProcessBuilder("sips", "-s", "format", "jpeg", "-s", "formatOptions", "80", pp.path, "--out", jp.path).redirectErrorStream(true).start()
            p.inputStream.readBytes()
            check(p.waitFor() == 0) { "sips failed: the JPEG encoder of relief.py is macOS sips; set Relief.jpeg elsewhere" }
            return jp.readBytes()
        } finally {
            tmp.deleteRecursively()
        }
    }

    /** What [raster] makes: the image, its clip, the depth of every pixel, the scale; and the PNG handed to the JPEG encoder. */
    class Traced(val data: ByteArray, val clip: String?, val depth: Array<DoubleArray>, val scale: Int, val png: ByteArray)

    // render/relief.py:147 raster
    /** The lit, coloured ground as one JPEG, its outline on screen (or null), and a depth per pixel for hiding lines. */
    @Suppress("UNCHECKED_CAST")
    fun raster(S: Dict, cam: City.Camera, th: Map<String, Any?>, cfg: Map<String, Any?>, kinds: Map<String, Pair<String, Int>>, scale: Int = 2, farM: Double = 14000.0): Traced {
        val G = S["_grid"] as Grid; val g = G.g; val nx = G.nx; val nz = G.nz; val st = G.step
        val cov = G.cover; val CS = G.cs; val cnx = G.nx * CS; val cnz = G.nz * CS
        val night = th["night"] == true; val L = v3(th["_L"])
        val sunny = !night && L[1] > 0 && !truthy(th["sky_k"])
        // how each kind looks on flat ground (the same colours as the flat regions), and how much light shapes it
        val wc = if (!night) (th["water_top"] ?: th["water"]) as String else "#0A1220"
        val nk = G.names.size
        val br = DoubleArray(nk); val bg = DoubleArray(nk); val bb = DoubleArray(nk)
        for ((i, k) in G.names.withIndex()) {
            var c = if (k == "lake" || k == "sea") hx(wc) else if (k == "ground") hx(th["ground"] as String) else hx(kinds.getValue(k).first)
            if (night && k != "lake" && k != "sea" && k != "ground") c = mulc(c, 0.2) // the theme's ground is already its night colour
            br[i] = c.x; bg[i] = c.y; bb[i] = c.z
        }
        val flat = BooleanArray(nk) { G.names[it] == "lake" || G.names[it] == "sea" }
        val lv: V3; val kL: Double
        if (sunny) { lv = vnorm(L); kL = 0.8 } // direct sun: slopes facing it brighten, the far side and shadows darken
        else if (!night) { lv = vnorm(V3(-0.6, 0.9, -0.6)); kL = 0.55 } // overcast: a soft light from the north-west sky
        else { lv = V3(0.0, 1.0, 0.0); kL = 0.25 }
        val key = (th["date"] as List<*>).joinToString(",") + "|" + (th["hhmm"] as List<*>).joinToString(",")
        var sh: ByteArray? = if (sunny) (S.getOrPut("_shadow") { LinkedHashMap<String, ByteArray?>() } as Map<String, ByteArray?>)[key] else null
        if (sunny && sh == null) {
            sh = shadows(G, L); (S["_shadow"] as MutableMap<String, ByteArray?>)[key] = sh
        }
        val seaI = G.names.indexOf("sea")
        val cool0 = 0.93; val cool1 = 0.97; val cool2 = 1.07 // shade takes a little of the blue sky's colour
        val W2 = PyMath.int(cam.W * scale).toInt(); val H2 = PyMath.int(cam.H * scale).toInt()
        val C = cam.C; val r_ = cam.r; val u_ = cam.u; val f_ = cam.f
        val rows = Array(H2) { ByteArray(W2 * 3) }
        val depth = Array(H2) { DoubleArray(W2) }
        val tops = IntArray(W2)
        val top = G.top
        val x0 = G.x0; val z0 = G.z0
        val C0 = C[0]; val C1 = C[1]; val C2 = C[2]
        for (px in 0 until W2) {
            val dxs = ((px + 0.5) / scale - cam.cx) / cam.focal
            var tPrev = 0.0; var havePrev = false; var ytop = H2
            for (py in H2 - 1 downTo 0) {
                val dys = -((py + 0.5) / scale - cam.cy) / cam.focal
                var d0 = r_[0] * dxs + u_[0] * dys + f_[0]; var d1 = r_[1] * dxs + u_[1] * dys + f_[1]; var d2 = r_[2] * dxs + u_[2] * dys + f_[2]
                if (d1 >= -1e-4) break
                val dn = PyMath.sqrt(d0 * d0 + d1 * d1 + d2 * d2); d0 /= dn; d1 /= dn; d2 /= dn
                val hz = PyMath.hypot(d0, d2)
                val tTop = (top - C1) / d1
                var t = if (!havePrev) tTop else PyMath.max(tTop, tPrev - 30.0)
                for (q in 0 until 40) { // the ray above this one hit there: start just before, above the ground
                    if (t <= tTop) break
                    val x = C0 + d0 * t; val z = C2 + d2 * t
                    if (C1 + d1 * t > G.h(x, z)) break
                    t = PyMath.max(tTop, t - 60.0)
                }
                var hit = Double.NaN; var out = false; var tLast = Double.NaN
                while (true) {
                    val x = C0 + d0 * t; val z = C2 + d2 * t
                    val fi = (x - x0) / st; val fj = (z - z0) / st
                    val ii = if (fi >= 0) PyMath.int(fi).toInt() else -1; val jj = if (fj >= 0) PyMath.int(fj).toInt() else -1
                    if (ii < 0 || jj < 0 || ii >= nx - 1 || jj >= nz - 1) {
                        // beyond the survey: the sea, flat at NAP 0
                        val t0 = -C1 / d1
                        if (t0 >= (if (!tLast.isNaN()) tLast else tTop)) { hit = PyMath.max(t0, t); out = true }
                        break
                    }
                    val tx = fi - ii; val tz = fj - jj; val k = jj * nx + ii
                    val h = (g[k] * (1 - tx) + g[k + 1] * tx) * (1 - tz) + (g[k + nx] * (1 - tx) + g[k + nx + 1] * tx) * tz
                    val y = C1 + d1 * t
                    if (y <= h) {
                        if (tLast.isNaN()) { hit = t; break }
                        var a_ = tLast; var b_ = t
                        for (q in 0 until 5) {
                            val m = (a_ + b_) / 2; val xm = C0 + d0 * m; val zm = C2 + d2 * m
                            if (C1 + d1 * m <= G.h(xm, zm)) b_ = m else a_ = m
                        }
                        hit = b_; break
                    }
                    tLast = t
                    t += PyMath.max(1.2, PyMath.min(40.0, (y - h) / (-d1 + hz * 0.9))) // safe step: the slope ahead can rise at most this fast
                }
                if (hit.isNaN()) break
                if (hit > farM) { hit = -C1 / d1; out = true } // far out the sea is all there is: flat, at NAP 0
                tPrev = hit; havePrev = true; ytop = py
                val x = C0 + d0 * hit; val z = C2 + d2 * hit
                val o = px * 3; val row = rows[py]
                depth[py][px] = hit
                if (out) {
                    row[o] = PyMath.int(br[seaI]).toInt().toByte(); row[o + 1] = PyMath.int(bg[seaI]).toInt().toByte(); row[o + 2] = PyMath.int(bb[seaI]).toInt().toByte(); continue
                }
                // the cover read between its 1.25 m cells, colours blended, so edges are soft rather than stepped
                val fci = (x - x0) / st * CS - 0.5; val fcj = (z - z0) / st * CS - 0.5
                val ci = PyMath.int(fci).toInt(); val cj = PyMath.int(fcj).toInt()
                val c0: Double; val c1: Double; val c2: Double; val kind: Int
                if (0 <= ci && ci < cnx - 1 && 0 <= cj && cj < cnz - 1) {
                    val q0 = cj * cnx + ci
                    val k00 = cov[q0].toInt() and 0xff; val k10 = cov[q0 + 1].toInt() and 0xff; val k01 = cov[q0 + cnx].toInt() and 0xff; val k11 = cov[q0 + cnx + 1].toInt() and 0xff
                    if (k00 == k10 && k10 == k01 && k01 == k11) {
                        c0 = br[k00]; c1 = bg[k00]; c2 = bb[k00]; kind = k00
                    } else {
                        val ax = fci - ci; val az = fcj - cj
                        val w00 = (1 - ax) * (1 - az); val w10 = ax * (1 - az); val w01 = (1 - ax) * az; val w11 = ax * az
                        c0 = br[k00] * w00 + br[k10] * w10 + br[k01] * w01 + br[k11] * w11
                        c1 = bg[k00] * w00 + bg[k10] * w10 + bg[k01] * w01 + bg[k11] * w11
                        c2 = bb[k00] * w00 + bb[k10] * w10 + bb[k01] * w01 + bb[k11] * w11
                        // max(((w00, k00), (w10, k10), (w01, k01), (w11, k11)))[1]: the heaviest, the higher kind on a tie
                        var bw = w00; var bk = k00
                        if (w10 > bw || (w10 == bw && k10 > bk)) { bw = w10; bk = k10 }
                        if (w01 > bw || (w01 == bw && k01 > bk)) { bw = w01; bk = k01 }
                        if (w11 > bw || (w11 == bw && k11 > bk)) { bw = w11; bk = k11 }
                        kind = bk
                    }
                } else {
                    kind = 0; c0 = br[0]; c1 = bg[0]; c2 = bb[0]
                }
                var f: Double
                if (flat[kind]) {
                    f = 1.0
                } else {
                    val e = 3.0
                    val gx = (G.h(x + e, z) - G.h(x - e, z)) / (2 * e); val gz = (G.h(x, z + e) - G.h(x, z - e)) / (2 * e)
                    val nn = 1.0 / PyMath.sqrt(gx * gx + 1 + gz * gz)
                    var nl = (-gx * lv[0] + lv[1] - gz * lv[2]) * nn
                    if (sunny) {
                        if (sh != null) { // soft-edged: the shadow mask read between its cells
                            val fi = (x - x0) / st; val fj = (z - z0) / st; val ii = PyMath.int(fi).toInt(); val jj = PyMath.int(fj).toInt(); val tx = fi - ii; val tz = fj - jj; val k = jj * nx + ii
                            val s_ = if (0 <= ii && ii < nx - 1 && 0 <= jj && jj < nz - 1) (sh[k] * (1 - tx) + sh[k + 1] * tx) * (1 - tz) + (sh[k + nx] * (1 - tx) + sh[k + nx + 1] * tx) * tz else 0.0
                            nl *= 1.0 - s_
                        }
                        f = 1.0 + kL * (PyMath.max(0.0, nl) - lv[1])
                    } else {
                        f = 1.0 + kL * (nl - lv[1])
                    }
                    f = PyMath.max(0.58, PyMath.min(1.16, f))
                }
                if (f < 1.0) {
                    val q = (1.0 - f) * 1.6
                    row[o] = minByte(c0 * f * (1 + (cool0 - 1) * q)); row[o + 1] = minByte(c1 * f * (1 + (cool1 - 1) * q)); row[o + 2] = minByte(c2 * f * (1 + (cool2 - 1) * q))
                } else {
                    row[o] = minByte(c0 * f); row[o + 1] = minByte(c1 * f); row[o + 2] = minByte(c2 * f)
                }
            }
            tops[px] = ytop
        }
        // JPEG through macOS sips: far smaller than PNG for a lit landscape
        val png = pngRgb(W2, H2, rows.asList())
        val data = jpeg.encode(png)
        // the outline of what the image covers (a CSS polygon on the board), or null where it fills the frame;
        // each edge point takes the lower of the two columns it joins, so no unpainted pixel slips inside
        var clip: String? = null
        if (tops.max() > 0) {
            val pts = ArrayList<String>()
            for (px in 0 until W2 step 2) pts.add(pf("%.1fpx %.1fpx", px.toDouble() / scale, (Math.max(tops[Math.max(0, px - 1)], tops[px]) + 0.5) / scale))
            pts.add(pf("%dpx %.1fpx", cam.W, (tops[W2 - 1] + 0.5) / scale))
            clip = pf("polygon(0px %dpx, %s, %dpx %dpx)", cam.H, pts.joinToString(", "), cam.W, cam.H)
        }
        return Traced(data, clip, depth, scale, png)
    }

    /** `min(255, int(v))` as a byte. */
    private fun minByte(v: Double): Byte = Math.min(255L, PyMath.int(v)).toInt().toByte()

    // render/relief.py:276 draw
    /** The traced ground image, then the paths, roads and park edge along it, broken where the ground hides them. */
    @Suppress("UNCHECKED_CAST")
    fun draw(S: Dict, cam: City.Camera, th: Dict, far: (Double, Double) -> Boolean, defs: MutableList<String>, cfg: Map<String, Any?>,
             kinds: Map<String, Pair<String, Int>>, lineItems: List<Terrain.Line>): String {
        val G = S["_grid"] as Grid; val night = th["night"] == true
        val target = v3(cfg["target"]); val TX = target[0]; val TZ = target[2]; val R = pyNum(cfg["radius"])
        val tr = raster(S, cam, th, cfg, kinds)
        val depth = tr.depth; val sc = tr.scale
        val W2 = depth[0].size; val H2 = depth.size
        val out = ArrayList<String>()
        // the sea beyond the image, flat, so the horizon continues where the survey ends
        val wc = if (!night) (th["water_top"] ?: th["water"]) as String else "#0A1220"
        val ws = ArrayList<String>()
        for (poly in S["_seas"] as List<List<V2>>) {
            val sp = cam.poly(poly.map { V3(it.x, 0.0, it.y) }, margin = 900.0)
            if (sp != null) ws.add(P(sp))
        }
        if (ws.isNotEmpty()) out.add(pf("<path d=\"%s\" fill=\"%s\"/>", ws.joinToString(""), wc))
        // the image is its own file on the board, between this ground and the lines (the asset store keeps no images inside SVGs)
        th["_raster"] = Raster("jpg", tr.data, tr.clip, tr.png)
        val C = cam.C
        val above = ArrayList<String>()
        fun seen(x: Double, y: Double, z: Double): V2? {
            val p = cam.proj(V3(x, y, z)) ?: return null
            val i = PyMath.int(p[0] * sc).toInt(); val j = PyMath.int(p[1] * sc).toInt()
            if (!(0 <= i && i < W2 && 0 <= j && j < H2)) return null
            val d = PyMath.dist(doubleArrayOf(x, y, z), doubleArrayOf(C.x, C.y, C.z)); val dd = depth[j][i]
            if (dd != 0.0 && d > dd + 4.0 + d * 0.002) return null // a dune stands in front
            return p
        }
        val lb = LinkedHashMap<Triple<String, Double, Boolean>, MutableList<String>>()
        val town = truthy(S["_town"])
        for (item in lineItems) {
            val pts = item.pts; val lit = item.lit
            if (!Regions.every(pts, Math.max(1, pts.size / 12)).any { p -> PyMath.hypot(p[0] - TX, p[1] - TZ) < R * 1.1 }) continue
            val dp = ArrayList<String>(); var run = ArrayList<V2>()
            for (s in 0 until pts.size - 1) {
                val a = pts[s]; val b = pts[s + 1]
                val Lg = Amsterdam.dist(a, b); val m = Math.max(1, PyMath.int(Lg / 8).toInt())
                for (q in 0 until m + (if (s + 1 == pts.size - 1) 1 else 0)) {
                    val x = a[0] + (b[0] - a[0]) * q / m; val z = a[1] + (b[1] - a[1]) * q / m
                    val pp = if (PyMath.hypot(x - TX, z - TZ) < R * 1.05) seen(x, G.h(x, z) + 0.4, z) else null
                    if (pp != null) run.add(pp)
                    else {
                        if (run.size > 1) dp.add(Regions.runD(run))
                        run = ArrayList()
                    }
                }
            }
            if (run.size > 1) dp.add(Regions.runD(run))
            if (dp.isEmpty()) continue
            val mid = pts[pts.size / 2]; val d = cam.depth(V3(mid[0], 0.0, mid[1]))
            val w = if (!town) PyMath.min(if (item.dash) 4.0 else 7.0, PyMath.max(0.5, item.width * cam.focal / PyMath.max(d, 600.0)))
            else PyMath.max(0.5, item.width * cam.focal / PyMath.max(d, 1.0)) // in a town, streets at their width
            val col = if (night) (if (lit) "#B98F58" else tohex(mulc(hx(item.colour), 0.3))) else item.colour // lit=yes streets glow at night
            lb.getOrPut(Triple(col, PyMath.round(w, 1), item.dash)) { ArrayList() }.add(dp.joinToString(""))
        }
        for ((key, ds) in lb) {
            val (col, w, dash) = key
            above.add(pf("<path d=\"%s\" fill=\"none\" stroke=\"%s\" stroke-width=\"%.1f\" stroke-linecap=\"round\" stroke-linejoin=\"round\"%s/>", ds.joinToString(""), col, w,
                if (dash) " stroke-dasharray=\"6 4\"" else ""))
        }
        th["_above"] = above.joinToString("")
        return out.joinToString("")
    }

    // render/relief.py:327 build_flat
    /** For a flat drawing: heights on the 5 m grid, water held at the land's usual level so quays cast no false slope, and a water mask. */
    fun buildFlat(dem: Terrain.DEM, water: List<List<V2>>, base: Double, extent: DoubleArray? = null): Grid {
        val corners = ArrayList<V2>()
        if (dem.crs == "rd") {
            for (fx in doubleArrayOf(0.0, 1.0)) for (fy in doubleArrayOf(0.0, 1.0)) {
                corners.add(rdToLocal(dem.x0 + (dem.num("x1") - dem.x0) * fx, dem.num("y0") + (dem.y1 - dem.num("y0")) * fy))
            }
        } else {
            for (fx in doubleArrayOf(0.0, 1.0)) for (fy in doubleArrayOf(0.0, 1.0)) {
                corners.add(V2((dem.num("w") + (dem.num("e") - dem.num("w")) * fx - Amsterdam.LON0) * Amsterdam.KX,
                    (Amsterdam.LAT0 - (dem.num("s") + (dem.num("n") - dem.num("s")) * fy)) * Amsterdam.KZ))
            }
        }
        var x0 = City.pyMinOf(corners.map { it.x }); var x1 = City.pyMaxOf(corners.map { it.x })
        var z0 = City.pyMinOf(corners.map { it.y }); var z1 = City.pyMaxOf(corners.map { it.y })
        if (extent != null) {
            x0 = PyMath.max(x0, extent[0]); x1 = PyMath.min(x1, extent[1]); z0 = PyMath.max(z0, extent[2]); z1 = PyMath.min(z1, extent[3])
        }
        val G = Grid(dem, x0, x1, z0, z1)
        val wet = ByteArray(G.nx * 2 * G.nz * 2)
        for (poly in water) {
            G.setLevel(poly, base)
            G.cells(poly, 2) { o, i0, i1 -> java.util.Arrays.fill(wet, o + i0, o + i1, 1.toByte()) }
        }
        G.fill(); G.wet = wet
        return G
    }

    // render/relief.py:344 shade_flat
    /**
     * Light and shade from the real relief, for a drawing that keeps the ground flat: each pixel's spot on the ground plane, lit by
     * the slope the survey measured there. A PNG with transparency, to lie over the flat ground; water is left clear. Null at night.
     */
    fun shadeFlat(S: Dict, cam: City.Camera, th: Map<String, Any?>, cfg: Map<String, Any?>, scale: Int = 2): ByteArray? {
        val night = th["night"] == true; val L = v3(th["_L"])
        if (night) return null
        val G = S["_flat"] as Grid; val nx = G.nx; val nz = G.nz; val st = G.step
        val sunny = L[1] > 0 && !truthy(th["sky_k"])
        val lv = if (sunny) vnorm(L) else vnorm(V3(-0.6, 0.9, -0.6)); val kL = if (sunny) 0.8 else 0.55
        val target = v3(cfg["target"]); val TX = target[0]; val TZ = target[2]; val R = pyNum(cfg["radius"]) * 1.05
        val W2 = PyMath.int(cam.W * scale).toInt(); val H2 = PyMath.int(cam.H * scale).toInt()
        val C = cam.C; val r_ = cam.r; val u_ = cam.u; val f_ = cam.f
        val x0 = G.x0; val z0 = G.z0; val wet = G.wet; val wnx = nx * 2
        val rows = ArrayList<ByteArray>(H2)
        for (py in 0 until H2) {
            val row = ByteArray(W2 * 4)
            val dys = -((py + 0.5) / scale - cam.cy) / cam.focal
            for (px in 0 until W2) {
                val dxs = ((px + 0.5) / scale - cam.cx) / cam.focal
                val d1 = r_[1] * dxs + u_[1] * dys + f_[1]
                if (d1 >= -1e-4) continue
                val t = -C[1] / d1
                val x = C[0] + (r_[0] * dxs + u_[0] * dys + f_[0]) * t; val z = C[2] + (r_[2] * dxs + u_[2] * dys + f_[2]) * t
                if (PyMath.pow(x - TX, 2) + PyMath.pow(z - TZ, 2) > R * R) continue
                val wi = PyMath.int((x - x0) / st * 2).toInt(); val wj = PyMath.int((z - z0) / st * 2).toInt()
                if (!(2 <= wi && wi < wnx - 2 && 2 <= wj && wj < nz * 2 - 2)) continue
                val q = wj * wnx + wi // water, and a 5 m margin along it, stay clear
                if (wet[q].toInt() != 0 || wet[q - 2].toInt() != 0 || wet[q + 2].toInt() != 0 || wet[q - 2 * wnx].toInt() != 0 || wet[q + 2 * wnx].toInt() != 0) continue
                val e = 3.0
                val gx = (G.h(x + e, z) - G.h(x - e, z)) / (2 * e); val gz = (G.h(x, z + e) - G.h(x, z - e)) / (2 * e)
                if (gx * gx + gz * gz < 1e-4) continue // level ground: nothing to show
                val nl = (-gx * lv[0] + lv[1] - gz * lv[2]) / PyMath.sqrt(gx * gx + 1 + gz * gz)
                val v = kL * ((if (sunny) PyMath.max(0.0, nl) else nl) - lv[1])
                val a = PyMath.min(0.42, Math.abs(v) * 0.9)
                if (a < 0.015) continue
                val o = px * 4
                val alpha = PyMath.int(a * 255).toInt().toByte()
                if (v > 0) { row[o] = 255.toByte(); row[o + 1] = 248.toByte(); row[o + 2] = 232.toByte(); row[o + 3] = alpha } else { row[o] = 28; row[o + 1] = 36; row[o + 2] = 50; row[o + 3] = alpha }
            }
            rows.add(row)
        }
        return Terrain.pngRgba(W2, H2, rows)
    }
}

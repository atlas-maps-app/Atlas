// render/terrain.py: terrain where OpenStreetMap has none: the ground's shape from AHN (bare earth), with OSM's land cover
// draped over it, drawn as rows of cells; and the DEM reader every terrain and relief module uses. Ported line by line.
package app.atlas.render

import app.atlas.render.City.P
import app.atlas.render.City.hx
import app.atlas.render.City.mulc
import app.atlas.render.City.tohex
import app.atlas.render.City.vnorm
import app.atlas.render.Real.pip
import app.atlas.render.py.Dict
import app.atlas.render.py.PyFmt.pf
import app.atlas.render.py.PyJson
import app.atlas.render.py.PyMath
import app.atlas.render.py.pyNum
import app.atlas.render.py.truthy
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.Base64
import java.util.zip.CRC32
import java.util.zip.Deflater

/** render/terrain.py: the DEM, its local resampling, the per-pixel hillshade and the draped terrain drawing. */
object Terrain {
    // render/terrain.py:12 DEM
    /**
     * A height survey as fetch/dem.py stores it (`dem.json`, `dem5.json`): rows north first, on the Dutch RD grid (`crs` "rd")
     * or a latitude/longitude grid ("wgs84"). Empty cells (AHN leaves water and buildings empty) are NaN, Python's None.
     */
    class DEM(val d: Map<String, Any?>) {
        constructor(path: File) : this(readJson(path))

        /** The heights, row by row; NaN where the survey is empty. */
        val z: Array<DoubleArray>
        val crs: String = (d["crs"] as String?) ?: "rd"
        val cell: Double = pyNum(d["cell"])
        val W: Int = (d["W"] as Number).toInt()
        val H: Int = (d["H"] as Number).toInt()
        val x0: Double = if (crs == "rd") pyNum(d["x0"]) else 0.0
        val y1: Double = if (crs == "rd") pyNum(d["y1"]) else 0.0
        private val s_ = if (crs == "rd") 0.0 else pyNum(d["s"])
        private val w_ = if (crs == "rd") 0.0 else pyNum(d["w"])
        private val n_ = if (crs == "rd") 0.0 else pyNum(d["n"])
        private val e_ = if (crs == "rd") 0.0 else pyNum(d["e"])

        init {
            @Suppress("UNCHECKED_CAST")
            val rows = d["z"] as List<List<Any?>>
            z = Array(rows.size) { i -> val r = rows[i]; DoubleArray(r.size) { j -> (r[j] as Number?)?.toDouble() ?: Double.NaN } }
        }

        /** `d['x1']`, `d['y0']`, … : the survey's own numbers. */
        fun num(k: String): Double = pyNum(d[k])

        // render/terrain.py:17 DEM.h
        /** The height at local (x, z), 0 outside the survey. */
        fun h(x: Double, z: Double): Double {
            val lat = Amsterdam.LAT0 - z / Amsterdam.KZ; val lon = Amsterdam.LON0 + x / Amsterdam.KX
            val c: Double; val r: Double
            if (crs == "rd") {
                val xy = FetchDem.rd(lat, lon)
                c = (xy.x - x0) / cell - 0.5; r = (y1 - xy.y) / cell - 0.5
            } else { // a latitude/longitude grid, north row first
                c = (lon - w_) / (e_ - w_) * W - 0.5; r = (n_ - lat) / (n_ - s_) * H - 0.5
            }
            if (c < 0 || r < 0 || c >= W - 1 || r >= H - 1) return 0.0
            return at(r, c)
        }

        // render/terrain.py:26 DEM.hn
        /** The height, or NaN (Python's None) where the survey has no ground. */
        fun hn(x: Double, z: Double): Double {
            val lat = Amsterdam.LAT0 - z / Amsterdam.KZ; val lon = Amsterdam.LON0 + x / Amsterdam.KX
            val c: Double; val r: Double
            if (crs == "rd") {
                val xy = FetchDem.rd(lat, lon); c = (xy.x - x0) / cell - 0.5; r = (y1 - xy.y) / cell - 0.5
            } else {
                c = (lon - w_) / (e_ - w_) * W - 0.5; r = (n_ - lat) / (n_ - s_) * H - 0.5
            }
            if (c < 0 || r < 0 || c >= W - 1 || r >= H - 1) return Double.NaN
            val i = r.toInt(); val j = c.toInt()
            if (this.z[i][j].isNaN() || this.z[i][j + 1].isNaN() || this.z[i + 1][j].isNaN() || this.z[i + 1][j + 1].isNaN()) return Double.NaN
            return at(r, c)
        }

        // render/terrain.py:37 DEM._at
        private fun at(r: Double, c: Double): Double {
            val i = r.toInt(); val j = c.toInt(); val fr = r - i; val fc = c - j
            val v0 = z[i][j]; val v1 = z[i][j + 1]; val v2 = z[i + 1][j]; val v3 = z[i + 1][j + 1]
            if (v0.isNaN() || v1.isNaN() || v2.isNaN() || v3.isNaN()) {
                val ok = doubleArrayOf(v0, v1, v2, v3).filter { !it.isNaN() }
                return if (ok.isNotEmpty()) PyMath.sum(ok.toDoubleArray()) / ok.size else 0.0
            }
            return (v0 * (1 - fc) + v1 * fc) * (1 - fr) + (v2 * (1 - fc) + v3 * fc) * fr
        }

        companion object {
            @Suppress("UNCHECKED_CAST")
            fun readJson(path: File): Map<String, Any?> = PyJson.load(path) as Map<String, Any?>
        }
    }

    // render/terrain.py:45 Local
    /** The DEM resampled once into local metres (x east, z south), for fast bilinear lookups. */
    class Local(dem: DEM, val x0: Double, x1: Double, val z0: Double, z1: Double, val step: Double = 15.0, base: Double = 0.0, fill: Boolean = false) {
        val nx: Int = PyMath.int((x1 - x0) / step).toInt() + 2
        val nz: Int = PyMath.int((z1 - z0) / step).toInt() + 2

        /** Rows of heights above `base`. */
        val g: Array<DoubleArray>

        /** Cells under water: `i + j * nx` (Python's set of (i, j)). */
        var wet: HashSet<Long> = HashSet()

        init {
            if (!fill) {
                g = Array(nz) { j -> DoubleArray(nx) { i -> dem.h(x0 + i * step, z0 + j * step) - base } }
            } else {
                // where the survey is empty (water, outside), the nearest surveyed ground spreads in, so empty cells read as level, not as pits
                val gg = Array(nz) { j -> DoubleArray(nx) { i -> dem.hn(x0 + i * step, z0 + j * step) } }
                var todo = ArrayList<Long>()
                for (j in 0 until nz) for (i in 0 until nx) if (gg[j][i].isNaN()) todo.add(key(i, j))
                for (round in 0 until 400) {
                    if (todo.isEmpty()) break
                    val nxt = ArrayList<Long>(); val upd = ArrayList<Pair<Long, Double>>()
                    for (k in todo) {
                        val i = (k shr 32).toInt(); val j = k.toInt()
                        val v = ArrayList<Double>(4)
                        for ((a, b) in arrayOf(i - 1 to j, i + 1 to j, i to j - 1, i to j + 1)) {
                            if (a in 0 until nx && b in 0 until nz && !gg[b][a].isNaN()) v.add(gg[b][a])
                        }
                        if (v.isNotEmpty()) upd.add(k to PyMath.sum(v.toDoubleArray()) / v.size) else nxt.add(k)
                    }
                    for ((k, v) in upd) gg[k.toInt()][(k shr 32).toInt()] = v
                    todo = nxt
                }
                g = Array(nz) { j -> DoubleArray(nx) { i -> (if (!gg[j][i].isNaN()) gg[j][i] else base) - base } }
            }
        }

        // render/terrain.py:17 Local.h
        fun h(x: Double, z: Double): Double {
            val fx = (x - x0) / step; val fz = (z - z0) / step
            val i = PyMath.int(fx).toInt(); val j = PyMath.int(fz).toInt()
            if (i < 0 || j < 0 || i >= nx - 1 || j >= nz - 1) return 0.0
            val tx = fx - i; val tz = fz - j
            return (g[j][i] * (1 - tx) + g[j][i + 1] * tx) * (1 - tz) + (g[j + 1][i] * (1 - tx) + g[j + 1][i + 1] * tx) * tz
        }

        companion object {
            fun key(i: Int, j: Int): Long = (i.toLong() shl 32) or (j.toLong() and 0xffffffffL)
        }
    }

    // render/terrain.py:73 png_rgba
    /** An RGBA PNG (8 bits, no interlace) from rows of `4 * w` bytes, deflated at level 9 as zlib.compress does. */
    fun pngRgba(w: Int, h: Int, rows: List<ByteArray>): ByteArray = png(w, h, rows, 6, 9)

    /** A PNG of colour type [colourType] (2 RGB, 6 RGBA), its rows each led by filter byte 0, deflated at [level]. */
    fun png(w: Int, h: Int, rows: List<ByteArray>, colourType: Int, level: Int): ByteArray {
        val raw = ByteArrayOutputStream(rows.sumOf { it.size + 1 })
        for (r in rows) { raw.write(0); raw.write(r) }
        val out = ByteArrayOutputStream()
        out.write(byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(), '\r'.code.toByte(), '\n'.code.toByte(), 0x1a, '\n'.code.toByte()))
        val ihdr = ByteArrayOutputStream()
        writeInt(ihdr, w); writeInt(ihdr, h); ihdr.write(8); ihdr.write(colourType); ihdr.write(0); ihdr.write(0); ihdr.write(0)
        chunk(out, "IHDR", ihdr.toByteArray())
        chunk(out, "IDAT", zlibCompress(raw.toByteArray(), level))
        chunk(out, "IEND", ByteArray(0))
        return out.toByteArray()
    }

    /** `zlib.compress(data, level)`: a zlib stream (15-bit window, default memory level and strategy). */
    fun zlibCompress(data: ByteArray, level: Int): ByteArray {
        val d = Deflater(level)
        try {
            d.setInput(data); d.finish()
            val out = ByteArrayOutputStream(data.size / 4 + 64)
            val buf = ByteArray(1 shl 16)
            while (!d.finished()) {
                val n = d.deflate(buf)
                out.write(buf, 0, n)
            }
            return out.toByteArray()
        } finally {
            d.end()
        }
    }

    private fun writeInt(o: ByteArrayOutputStream, v: Int) {
        o.write((v ushr 24) and 0xff); o.write((v ushr 16) and 0xff); o.write((v ushr 8) and 0xff); o.write(v and 0xff)
    }

    private fun chunk(o: ByteArrayOutputStream, t: String, d: ByteArray) {
        val tb = t.toByteArray(Charsets.US_ASCII)
        writeInt(o, d.size); o.write(tb); o.write(d)
        val crc = CRC32(); crc.update(tb); crc.update(d)
        writeInt(o, crc.value.toInt())
    }

    // render/terrain.py:79 wet_mask
    /** Grid cells under water, filled row by row (scanline), so even the sea is quick: keys [Local.key]. */
    fun wetMask(loc: Local, rings: List<List<V2>>): HashSet<Long> {
        val wet = HashSet<Long>()
        for (poly in rings) {
            val n = poly.size
            val zmin = City.pyMinOf(poly.map { it.y }); val zmax = City.pyMaxOf(poly.map { it.y })
            for (j in Math.max(0, PyMath.int((zmin - loc.z0) / loc.step).toInt()) until Math.min(loc.nz, PyMath.int((zmax - loc.z0) / loc.step).toInt() + 2)) {
                val z = loc.z0 + j * loc.step; val xs = ArrayList<Double>()
                for (k in 0 until n) {
                    val a = poly[k]; val b = poly[(k + 1) % n]
                    val ax = a.x; val az = a.y; val bx = b.x; val bz = b.y
                    if ((az <= z && z < bz) || (bz <= z && z < az)) xs.add(ax + (z - az) * (bx - ax) / (bz - az))
                }
                xs.sortWith { p, q -> java.lang.Double.compare(p, q) }
                var q = 0
                while (q + 1 < xs.size) {
                    val a = xs[q]; val b = xs[q + 1]
                    for (i in Math.max(0, PyMath.int(Math.ceil((a - loc.x0) / loc.step)).toInt()) until Math.min(loc.nx, PyMath.int((b - loc.x0) / loc.step).toInt() + 1)) wet.add(Local.key(i, j))
                    q += 2
                }
            }
        }
        return wet
    }

    // render/terrain.py:94 hillshade
    /**
     * Light and shade on the real slopes, per pixel: each view ray is traced to where it meets the terrain (so ridges hide what
     * is behind). trace=false keeps the shade on the flat ground plane. Returns the `<image>` element, or with [raw] the PNG
     * itself (a ByteArray), or null / "" at night.
     */
    fun hillshade(S: Dict, cam: City.Camera, th: Map<String, Any?>, cfg: Map<String, Any?>, scale: Double = 0.5, trace: Boolean = true, raw: Boolean = false): Any? {
        val L = v3(th["_L"]); val night = th["night"] == true
        if (night) return if (raw) null else ""
        val target = v3(cfg["target"]); val TX = target[0]; val TZ = target[2]; val R = pyNum(cfg["radius"])
        var loc = S["_local"] as Local?
        if (loc == null) {
            loc = Local(S["dem"] as DEM, TX - R * 1.6, TX + R * 1.6, TZ - R * 1.6, TZ + R * 1.6, base = (S["_base"] as Number?)?.toDouble() ?: 0.0, fill = !trace)
            S["_local"] = loc
            // where there is water the ground has no slope to show: a mask of the lakes on the same grid
            @Suppress("UNCHECKED_CAST")
            val lakes = S["_lakes"] as List<List<V2>>
            loc.wet = if (!trace) wetMask(loc, lakes) else HashSet()
            for (poly in (if (trace) lakes else emptyList())) {
                val xs = poly.map { it.x }; val zs = poly.map { it.y }
                val xmin = City.pyMinOf(xs); val xmax = City.pyMaxOf(xs); val zmin = City.pyMinOf(zs); val zmax = City.pyMaxOf(zs)
                for (j in Math.max(0, PyMath.int((zmin - loc.z0) / loc.step).toInt()) until Math.min(loc.nz, PyMath.int((zmax - loc.z0) / loc.step).toInt() + 2)) {
                    for (i in Math.max(0, PyMath.int((xmin - loc.x0) / loc.step).toInt()) until Math.min(loc.nx, PyMath.int((xmax - loc.x0) / loc.step).toInt() + 2)) {
                        if (pip(loc.x0 + i * loc.step, loc.z0 + j * loc.step, poly)) loc.wet.add(Local.key(i, j))
                    }
                }
            }
        }
        val sunny = L[1] > 0 && !truthy(th["sky_k"])
        val lv = if (sunny) vnorm(V3(L[0], PyMath.max(L[1], 0.3), L[2])) else vnorm(V3(-0.6, 0.9, -0.6))
        val k = if (sunny) 3.0 else 2.0; val flat = lv[1]
        val W = PyMath.int(cam.W * scale).toInt(); val H = PyMath.int(cam.H * scale).toInt()
        val C = cam.C; val rows = ArrayList<ByteArray>(H)
        for (py in 0 until H) {
            val row = ByteArray(W * 4)
            val sy = (py + 0.5) / scale
            for (px in 0 until W) {
                val sx = (px + 0.5) / scale
                val dx = (sx - cam.cx) / cam.focal; val dy = -(sy - cam.cy) / cam.focal
                val d0 = cam.r[0] * dx + cam.u[0] * dy + cam.f[0]; val d1 = cam.r[1] * dx + cam.u[1] * dy + cam.f[1]; val d2 = cam.r[2] * dx + cam.u[2] * dy + cam.f[2]
                if (d1 >= -1e-4) continue
                var t = -C[1] / d1
                for (q in 0 until (if (trace) 4 else 0)) { // walk onto the surface
                    val x = C[0] + d0 * t; val z = C[2] + d2 * t
                    t = (loc.h(x, z) - C[1]) / d1
                }
                val x = C[0] + d0 * t; val z = C[2] + d2 * t
                if (PyMath.hypot(x - TX, z - TZ) > R * 1.05) continue
                if (Local.key(PyMath.round((x - loc.x0) / loc.step).toInt(), PyMath.round((z - loc.z0) / loc.step).toInt()) in loc.wet) continue
                val e = 8.0
                val gx = (loc.h(x + e, z) - loc.h(x - e, z)) / (2 * e); val gz = (loc.h(x, z + e) - loc.h(x, z - e)) / (2 * e)
                val nl = (-gx * lv[0] + lv[1] - gz * lv[2]) / PyMath.sqrt(gx * gx + 1 + gz * gz)
                val v = (nl - flat) * k
                val a = PyMath.min(0.5, Math.abs(v))
                if (a < 0.02) continue
                val o = px * 4
                val alpha = PyMath.int(a * 255).toInt().toByte()
                if (v > 0) { row[o] = 255.toByte(); row[o + 1] = 244.toByte(); row[o + 2] = 220.toByte(); row[o + 3] = alpha } else { row[o] = 24; row[o + 1] = 34; row[o + 2] = 22; row[o + 3] = alpha }
            }
            rows.add(row)
        }
        if (raw) return pngRgba(W, H, rows) // the PNG itself, to stand on the board as its own file
        val data = Base64.getEncoder().encodeToString(pngRgba(W, H, rows))
        return pf("<image href=\"data:image/png;base64,%s\" x=\"0\" y=\"0\" width=\"%d\" height=\"%d\" preserveAspectRatio=\"none\" style=\"image-rendering:auto\"/>", data, cam.W, cam.H)
    }

    // render/terrain.py:146 Cover
    /** Which mapped land cover (or water) lies under a point: polygons indexed on a coarse grid. */
    class Cover(land: List<Map<String, Any?>>, water: List<List<V2>>, sea: List<List<V2>>, kinds: Map<String, Pair<String, Int>>) {
        private class It(val pr: Int, val k: String, val poly: List<V2>)
        private class Ref(val idx: Int, val bb: DoubleArray)

        private val items = ArrayList<It>()
        private val grid = HashMap<Long, MutableList<Ref>>()
        private val big = ArrayList<Ref>()
        val G = 150.0

        init {
            for (a in land) items.add(It(kinds.getValue(a["kind"] as String).second, a["kind"] as String, Amsterdam.polyOf(a)))
            for (r in water) items.add(It(8, "lake", r))
            for (r in sea) items.add(It(9, "sea", r))
            for ((idx, it) in items.withIndex()) {
                val xs = it.poly.map { p -> p.x }; val zs = it.poly.map { p -> p.y }
                val bb = doubleArrayOf(City.pyMinOf(xs), City.pyMaxOf(xs), City.pyMinOf(zs), City.pyMaxOf(zs))
                if ((bb[1] - bb[0]) * (bb[3] - bb[2]) > 4e7) { // the sea: too big to index, tested last
                    big.add(Ref(idx, bb)); continue
                }
                for (gx in PyMath.floorDiv(bb[0], G).toLong()..PyMath.floorDiv(bb[1], G).toLong()) {
                    for (gz in PyMath.floorDiv(bb[2], G).toLong()..PyMath.floorDiv(bb[3], G).toLong()) grid.getOrPut(cell(gx, gz)) { ArrayList() }.add(Ref(idx, bb))
                }
            }
        }

        private fun cell(gx: Long, gz: Long): Long = (gx shl 32) xor (gz and 0xffffffffL)

        // render/terrain.py:162 Cover.at
        fun at(x: Double, z: Double): String? {
            var bestPr = 0; var bestK: String? = null; var have = false
            val here = grid[cell(PyMath.floorDiv(x, G).toLong(), PyMath.floorDiv(z, G).toLong())] ?: emptyList<Ref>()
            for (ref in here + big) {
                val bb = ref.bb
                if (!(bb[0] <= x && x <= bb[1] && bb[2] <= z && z <= bb[3])) continue
                val it = items[ref.idx]
                if (have && it.pr <= bestPr) continue
                if (pip(x, z, it.poly)) { bestPr = it.pr; bestK = it.k; have = true }
            }
            return if (have) bestK else null
        }
    }

    // render/terrain.py:171 drape
    /** A ring or line laid on the ground: resampled every few metres and lifted to the terrain (or to a water level, [flat]). */
    fun drape(dem: DEM, pts: List<V2>, step: Double = 18.0, lift: Double = 0.0, flat: Double? = null): List<V3> {
        val out = ArrayList<V3>()
        val n = pts.size
        for (q0 in 0 until n) {
            val a = pts[q0]; val b = pts[(q0 + 1) % n]
            val Lg = Amsterdam.dist(a, b); val m = Math.max(1, PyMath.int(Lg / step).toInt())
            for (q in 0 until m) {
                val x = a[0] + (b[0] - a[0]) * q / m; val z = a[1] + (b[1] - a[1]) * q / m
                out.add(V3(x, (flat ?: dem.h(x, z)) + lift, z))
            }
        }
        return out
    }

    /** A line to drape (regions.py's `S['_lines']` tuples): points, width in metres, colour, dashed, lit at night. */
    class Line(val pts: List<V2>, val width: Double, val colour: String, val dash: Boolean, val lit: Boolean = false)

    // render/terrain.py:181 draw
    /** The ground on its real shape: mapped land cover draped over the terrain, then a soft hillshade from the slopes, then draped lines. */
    @Suppress("UNCHECKED_CAST")
    fun draw(S: Dict, cam: City.Camera, th: Dict, far: (Double, Double) -> Boolean, defs: MutableList<String>, cfg: Map<String, Any?>,
             kinds: Map<String, Pair<String, Int>>, lineItems: List<Line>): String {
        val dem = S["dem"] as DEM; val night = th["night"] == true
        val target = v3(cfg["target"]); val TX = target[0]; val TZ = target[2]; val R = pyNum(cfg["radius"])
        val near: (List<V2>) -> Boolean = { pts -> Regions.every(pts, Math.max(1, pts.size / 12)).any { p -> PyMath.hypot(p[0] - TX, p[1] - TZ) < R * 1.1 } }
        val out = arrayListOf("<g filter=\"url(#mat)\">")
        // land cover, in its draw order, each outline lifted onto the ground
        val groups = LinkedHashMap<String, MutableList<String>>()
        for (a in S["land"] as List<Dict>) {
            val poly = Amsterdam.polyOf(a)
            if (!near(poly)) continue
            val sp = cam.poly(drape(dem, poly), margin = 300.0)
            if (sp != null && City.area(sp) > 0.4) groups.getOrPut(a["kind"] as String) { ArrayList() }.add(P(sp))
        }
        for ((kind, ds) in groups) {
            val c = kinds.getValue(kind).first
            out.add(pf("<path d=\"%s\" fill=\"%s\"/>", ds.joinToString(""), if (night) tohex(mulc(hx(c), 0.2)) else c))
        }
        out.add("</g>")
        // water: lakes at their own level, the sea at zero
        val wc = if (!night) (th["water_top"] ?: th["water"]) as String else "#0A1220"
        val ws = ArrayList<String>()
        for (poly in S["_lakes"] as List<List<V2>>) {
            if (!near(poly)) continue
            val lvl = City.pyMinOf(Regions.every(poly, Math.max(1, poly.size / 20)).map { dem.h(it.x, it.y) })
            val sp = cam.poly(drape(dem, poly, flat = lvl), margin = 300.0)
            if (sp != null && City.area(sp) > 0.3) ws.add(P(sp))
        }
        for (poly in S["_seas"] as List<List<V2>>) {
            val sp = cam.poly(poly.map { V3(it.x, 0.0, it.y) }, margin = 900.0)
            if (sp != null) ws.add(P(sp))
        }
        if (ws.isNotEmpty()) out.add(pf("<path d=\"%s\" fill=\"%s\"/>", ws.joinToString(""), wc))
        // hillshade: per pixel, from the real slopes (drawn over land and water, under the paths)
        out.add(hillshade(S, cam, th, cfg) as String)
        // paths, roads and the park's edge, laid along the ground
        val lb = LinkedHashMap<Triple<String, Double, Boolean>, MutableList<String>>()
        for (item in lineItems) {
            val pts = item.pts
            if (!near(pts)) continue
            val dp = ArrayList<String>()
            var run = ArrayList<V2>()
            for (s in 0 until pts.size - 1) {
                val a = pts[s]; val b = pts[s + 1]
                val Lg = Amsterdam.dist(a, b); val m = Math.max(1, PyMath.int(Lg / 15).toInt())
                for (q in 0..m) {
                    val x = a[0] + (b[0] - a[0]) * q / m; val z = a[1] + (b[1] - a[1]) * q / m
                    val pp = if (PyMath.hypot(x - TX, z - TZ) < R * 1.05) cam.proj(V3(x, dem.h(x, z) + 0.4, z)) else null
                    if (pp != null) run.add(pp)
                    else if (run.size > 1) { dp.add(Regions.runD(run)); run = ArrayList() }
                    else run = ArrayList()
                }
            }
            if (run.size > 1) dp.add(Regions.runD(run))
            if (dp.isEmpty()) continue
            val mid = pts[pts.size / 2]; val d = cam.depth(V3(mid[0], 0.0, mid[1]))
            val w = PyMath.min(if (item.dash) 4.0 else 7.0, PyMath.max(0.5, item.width * cam.focal / PyMath.max(d, 600.0)))
            lb.getOrPut(Triple(if (night) tohex(mulc(hx(item.colour), 0.3)) else item.colour, PyMath.round(w, 1), item.dash)) { ArrayList() }.add(dp.joinToString(""))
        }
        for ((key, ds) in lb) {
            val (col, w, dash) = key
            out.add(pf("<path d=\"%s\" fill=\"none\" stroke=\"%s\" stroke-width=\"%.1f\" stroke-linecap=\"round\" stroke-linejoin=\"round\"%s/>", ds.joinToString(""), col, w,
                if (dash) " stroke-dasharray=\"6 4\"" else ""))
        }
        return out.joinToString("")
    }
}

// render/city.py: the tiny software renderer (procedural 3D city -> layered SVG, painter's algorithm),
// and the vector, colour and camera helpers every other module imports. Ported line by line.
package app.atlas.render

import app.atlas.render.live.WorldTape
import app.atlas.render.py.Dict
import app.atlas.render.py.PyFmt
import app.atlas.render.py.PyHash
import app.atlas.render.py.PyMath
import app.atlas.render.py.PyRandom
import app.atlas.render.py.dictOf
import app.atlas.render.py.flag
import app.atlas.render.py.int
import app.atlas.render.py.list
import app.atlas.render.py.num
import app.atlas.render.py.numOrNull
import app.atlas.render.py.pyCompare
import app.atlas.render.py.pyNum
import app.atlas.render.py.pySortBy
import app.atlas.render.py.str
import app.atlas.render.py.strOrNull
import app.atlas.render.py.truthy

/** A Python 2-tuple of floats: (x, z) on the ground, or (x, y) on screen. */
data class V2(val x: Double, val y: Double) {
    operator fun get(i: Int): Double = when (i) {
        0 -> x
        1 -> y
        else -> throw IndexOutOfBoundsException("tuple index out of range")
    }
}

/** A Python 3-tuple of floats: a point (x, y, z), a direction, or a colour (r, g, b). */
data class V3(val x: Double, val y: Double, val z: Double) {
    operator fun get(i: Int): Double = when (i) {
        0 -> x
        1 -> y
        2 -> z
        else -> throw IndexOutOfBoundsException("tuple index out of range")
    }
}

/** A Python tuple or list of numbers (or a [V2] / [V3]) as a [V2]. */
fun v2(o: Any?): V2 = when (o) {
    is V2 -> o
    is List<*> -> V2(pyNum(o[0]), pyNum(o[1]))
    is DoubleArray -> V2(o[0], o[1])
    else -> throw IllegalArgumentException("not a 2-tuple: $o")
}

/** A Python tuple or list of numbers (or a [V3]) as a [V3]. */
fun v3(o: Any?): V3 = when (o) {
    is V3 -> o
    is List<*> -> V3(pyNum(o[0]), pyNum(o[1]), pyNum(o[2]))
    is DoubleArray -> V3(o[0], o[1], o[2])
    else -> throw IllegalArgumentException("not a 3-tuple: $o")
}

/** A Python list of 2-tuples as a list of [V2]. */
fun v2s(o: Any?): List<V2> = (o as List<*>).map { v2(it) }

/** The engine's synthetic city and its helpers. */
object City {
    // ---------------- vectors / colors ----------------

    // render/city.py:6 vsub
    fun vsub(a: V3, b: V3): V3 = V3(a[0] - b[0], a[1] - b[1], a[2] - b[2])

    // render/city.py:7 vdot
    fun vdot(a: V3, b: V3): Double = a[0] * b[0] + a[1] * b[1] + a[2] * b[2]

    // render/city.py:8 vcross
    fun vcross(a: V3, b: V3): V3 = V3(a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0])

    // render/city.py:9 vnorm
    fun vnorm(a: V3): V3 {
        val l = PyMath.sqrt(vdot(a, a)); return V3(a[0] / l, a[1] / l, a[2] / l)
    }

    // render/city.py:11 hx
    fun hx(h0: String): V3 {
        val h = h0.trimStart('#')
        return V3(h.substring(0, 2).toInt(16).toDouble(), h.substring(2, 4).toInt(16).toDouble(), h.substring(4, 6).toInt(16).toDouble())
    }

    // render/city.py:13 tohex
    fun tohex(c: V3): String {
        val sb = StringBuilder(7).append('#')
        for (i in 0 until 3) {
            val v = Math.max(0L, Math.min(255L, PyMath.round(c[i])))
            if (v < 16) sb.append('0')
            sb.append(java.lang.Long.toHexString(v))
        }
        return sb.toString()
    }

    // render/city.py:14 mix
    fun mix(a: V3, b: V3, t: Double): V3 = V3(a[0] + (b[0] - a[0]) * t, a[1] + (b[1] - a[1]) * t, a[2] + (b[2] - a[2]) * t)

    // render/city.py:15 mulc
    fun mulc(a: V3, s: Double): V3 = V3(a[0] * s, a[1] * s, a[2] * s)

    // render/city.py:16 clamp
    fun clamp(x: Double, a: Double = 0.0, b: Double = 1.0): Double = PyMath.max(a, PyMath.min(b, x))

    // render/city.py:17 P
    @JvmName("pathOf")
    fun P(sp: List<V2>): String {
        val sb = StringBuilder(sp.size * 14 + 2)
        sb.append('M')
        for (i in sp.indices) {
            if (i > 0) sb.append('L')
            val p = sp[i]
            PyFmt.appendFixed(sb, p.x, 1).append(' ')
            PyFmt.appendFixed(sb, p.y, 1)
        }
        return sb.append('Z').toString()
    }

    // render/city.py:20 Camera
    /** The perspective camera: a target, a yaw and pitch in degrees, a distance, a vertical field of view. */
    open class Camera(
        val target: V3, val yaw: Double, val pitch: Double, val dist: Double, val fov: Double, W: Double, H: Double,
        cx: Double? = null, cy: Double? = null, near: Double = 0.6,
    ) {
        val C: V3
        val f: V3
        val r: V3
        val u: V3
        val W: Double = W
        val H: Double = H
        val focal: Double
        val cx: Double
        val cy: Double
        val near: Double = near

        init {
            val yr = PyMath.radians(yaw)
            val pr = PyMath.radians(pitch)
            val d = V3(PyMath.sin(yr) * PyMath.cos(pr), -PyMath.sin(pr), -PyMath.cos(yr) * PyMath.cos(pr))
            C = V3(target[0] - d[0] * dist, target[1] - d[1] * dist, target[2] - d[2] * dist)
            val f = vnorm(d)
            val r = vnorm(V3(-f[2], 0.0, f[0]))
            val u = vcross(r, f)
            this.f = f; this.r = r; this.u = u
            focal = (H / 2) / PyMath.tan(PyMath.radians(fov) / 2)
            this.cx = cx ?: (W / 2)
            this.cy = cy ?: (H / 2)
        }

        /** The world tape this camera records its projections into (a render's, see [WorldTape]), or null: no effect on output. */
        val tape: WorldTape? = WorldTape.attach(this)

        // render/city.py:33 Camera.cam
        open fun cam(p: V3): V3 {
            val d = vsub(p, C); return V3(vdot(d, r), vdot(d, u), vdot(d, f))
        }

        // render/city.py:36 Camera.pc
        fun pc(c: V3): V2 {
            val p = V2(cx + focal * c[0] / c[2], cy - focal * c[1] / c[2])
            tape?.record(c.z, p.x, p.y)
            return p
        }

        // render/city.py:38 Camera.proj
        fun proj(p: V3): V2? {
            val c = cam(p)
            if (c[2] < near) return null
            return pc(c)
        }

        // render/city.py:43 Camera.depth
        fun depth(p: V3): Double = cam(p)[2]

        // render/city.py:45 Camera.poly
        fun poly(pts: List<V3>, margin: Double = 40.0): List<V2>? {
            val cs = pts.map { cam(it) }
            val out = ArrayList<V3>(cs.size + 2)
            val n = cs.size
            for (i in 0 until n) {
                val a = cs[i]; val b = cs[(i + 1) % n]
                val ai = a[2] >= near; val bi = b[2] >= near
                if (ai) out.add(a)
                if (ai != bi) {
                    val t = (near - a[2]) / (b[2] - a[2])
                    out.add(V3(a[0] + (b[0] - a[0]) * t, a[1] + (b[1] - a[1]) * t, near))
                }
            }
            if (out.size < 3) return null
            val sp = out.map { pc(it) }
            var maxX = sp[0].x; var minX = sp[0].x; var maxY = sp[0].y; var minY = sp[0].y
            for (k in 1 until sp.size) {
                val p = sp[k]
                if (p.x > maxX) maxX = p.x
                if (p.x < minX) minX = p.x
                if (p.y > maxY) maxY = p.y
                if (p.y < minY) minY = p.y
            }
            if (maxX < -margin || minX > W + margin || maxY < -margin || minY > H + margin) return null
            return sp
        }

        // render/city.py:61 Camera.ground_depth
        fun groundDepth(sy: Double, gy: Double = 0.0): Double? {
            val b = (cy - sy) / focal
            val den = f[1] + b * u[1]
            if (den >= -1e-6) return null
            return -(C[1] - gy) / den
        }

        // render/city.py:67 Camera.horizon_y
        fun horizonY(): Double {
            val b = -f[1] / u[1]
            return cy - b * focal
        }
    }

    // render/city.py:72 area
    fun area(sp: List<V2>): Double {
        var a = 0.0
        for (i in sp.indices) {
            val (x0, y0) = sp[i]; val (x1, y1) = sp[(i + 1) % sp.size]
            a += x0 * y1 - x1 * y0
        }
        return Math.abs(a) / 2
    }

    // render/city.py:80 hull2
    fun hull2(pts0: List<V2>): List<V2> {
        // a set of the rounded points: 0.0 and -0.0 are equal in Python, and the first one added stays
        val keys = HashSet<V2>()
        val pts = ArrayList<V2>()
        for (p in pts0) {
            val q = V2(PyMath.round(p[0], 3), PyMath.round(p[1], 3))
            if (keys.add(V2(q.x + 0.0, q.y + 0.0))) pts.add(q)
        }
        pts.sortWith { a, b -> pyCompare(a.x, a.y, b.x, b.y) }
        if (pts.size <= 2) return pts
        fun cr(o: V2, a: V2, b: V2): Double = (a[0] - o[0]) * (b[1] - o[1]) - (a[1] - o[1]) * (b[0] - o[0])
        val lo = ArrayList<V2>()
        for (p in pts) {
            while (lo.size >= 2 && cr(lo[lo.size - 2], lo[lo.size - 1], p) <= 0) lo.removeAt(lo.size - 1)
            lo.add(p)
        }
        val up = ArrayList<V2>()
        for (p in pts.asReversed()) {
            while (up.size >= 2 && cr(up[up.size - 2], up[up.size - 1], p) <= 0) up.removeAt(up.size - 1)
            up.add(p)
        }
        return lo.subList(0, lo.size - 1) + up.subList(0, up.size - 1)
    }

    // render/city.py:95 smooth
    /** Fillet corners of a 2D polyline. */
    fun smooth(pts: List<V2>, r: Double = 12.0, seg: Int = 6): List<V2> {
        if (pts.size < 3) return pts
        val out = ArrayList<V2>()
        out.add(pts[0])
        for (i in 1 until pts.size - 1) {
            val p0 = pts[i - 1]; val p1 = pts[i]; val p2 = pts[i + 1]
            val d0 = V2(p1[0] - p0[0], p1[1] - p0[1]); val l0 = PyMath.hypot(d0.x, d0.y)
            val d1 = V2(p2[0] - p1[0], p2[1] - p1[1]); val l1 = PyMath.hypot(d1.x, d1.y)
            val rr = PyMath.min(r, l0 / 2, l1 / 2)
            val a = V2(p1[0] - d0[0] / l0 * rr, p1[1] - d0[1] / l0 * rr)
            val b = V2(p1[0] + d1[0] / l1 * rr, p1[1] + d1[1] / l1 * rr)
            for (k in 0..seg) {
                val t = k.toDouble() / seg
                // quadratic bezier a -> p1 -> b
                val x = PyMath.pow(1 - t, 2) * a[0] + 2 * (1 - t) * t * p1[0] + t * t * b[0]
                val y = PyMath.pow(1 - t, 2) * a[1] + 2 * (1 - t) * t * p1[1] + t * t * b[1]
                out.add(V2(x, y))
            }
        }
        out.add(pts[pts.size - 1])
        return out
    }

    // render/city.py:116 ribbon
    fun ribbon(pts: List<V2>, w: Double): List<V2> {
        val left = ArrayList<V2>(pts.size); val right = ArrayList<V2>(pts.size)
        val n = pts.size
        for (i in 0 until n) {
            val d = if (i == 0) {
                V2(pts[1][0] - pts[0][0], pts[1][1] - pts[0][1])
            } else if (i == n - 1) {
                V2(pts[n - 1][0] - pts[n - 2][0], pts[n - 1][1] - pts[n - 2][1])
            } else {
                V2(pts[i + 1][0] - pts[i - 1][0], pts[i + 1][1] - pts[i - 1][1])
            }
            val l = PyMath.hypot(d.x, d.y).let { if (it == 0.0) 1.0 else it }
            val nx = -d[1] / l; val nz = d[0] / l
            left.add(V2(pts[i][0] + nx * w / 2, pts[i][1] + nz * w / 2))
            right.add(V2(pts[i][0] - nx * w / 2, pts[i][1] - nz * w / 2))
        }
        return left + right.asReversed()
    }

    // render/city.py:133 densify
    fun densify(pts: List<V2>, step: Double = 15.0): List<V2> {
        val out = ArrayList<V2>()
        for (i in 0 until pts.size - 1) {
            val a = pts[i]; val b = pts[i + 1]
            val l = PyMath.hypot(b[0] - a[0], b[1] - a[1]); val n = Math.max(1, (l / step).toInt())
            for (k in 0 until n) {
                val t = k.toDouble() / n; out.add(V2(a[0] + (b[0] - a[0]) * t, a[1] + (b[1] - a[1]) * t))
            }
        }
        out.add(pts[pts.size - 1])
        return out
    }

    // ---------------- world ----------------
    const val AV_SP = 110
    const val AV_W = 20
    const val ST_SP = 70
    const val ST_W = 14
    const val SIDEWALK = 4.5

    // render/city.py:148 shore_e
    fun shoreE(z: Double): Double = -120 + 40 * PyMath.sin(z / 260) + 20 * PyMath.sin(z / 97)

    // render/city.py:149 shore_w
    fun shoreW(z: Double): Double = shoreE(z) - 560 + 25 * PyMath.sin(z / 180 + 1.3)

    /** Linden Park x0, x1, z0, z1. */
    val PARK = doubleArrayOf(450.0, 650.0, -553.0, -357.0)
    const val BRIDGE_Z = -1610

    /** A named building: its footprint (x0, x1, z0, z1), height and kind. */
    class Named(val foot: DoubleArray, val h: Double, val kind: String)

    val NAMED: LinkedHashMap<String, Named> = linkedMapOf(
        "lume" to Named(doubleArrayOf(236.0, 264.0, -829.0, -803.0), 13.0, "lume"),
        "hall" to Named(doubleArrayOf(16.0, 96.0, -1039.0, -991.0), 15.0, "hall"),
        "marlow" to Named(doubleArrayOf(-88.0, -44.0, -619.0, -583.0), 118.0, "glass"),
        "fern" to Named(doubleArrayOf(404.0, 426.0, -128.0, -100.0), 11.0, "normal"),
        "home" to Named(doubleArrayOf(398.0, 426.0, 12.0, 40.0), 16.0, "normal"),
    )

    val ANCHORS: LinkedHashMap<String, V3> = linkedMapOf(
        "home" to V3(440.0, 0.0, 30.0), "lume" to V3(250.0, 13.0, -816.0), "lume_door" to V3(250.0, 0.0, -838.0),
        "hall" to V3(56.0, 15.0, -1015.0), "marlow" to V3(-66.0, 118.0, -601.0), "fern" to V3(415.0, 11.0, -114.0),
        "maya" to V3(330.0, 0.0, -300.0), "charger" to V3(550.0, 0.0, -205.0), "park" to V3(550.0, 0.0, -455.0),
    )

    val ROUTE_MAIN = listOf(V2(440.0, 30.0), V2(440.0, -840.0), V2(250.0, -840.0))
    val ROUTE_ALT = listOf(V2(440.0, 30.0), V2(440.0, -140.0), V2(330.0, -140.0), V2(330.0, -840.0), V2(250.0, -840.0))

    val CAR_COLS = listOf("#D8DDE4", "#3A3F46", "#B8322A", "#2E5BFF", "#F4F4F2", "#6B7282", "#1E3A5F", "#C9A36A", "#E8E8E4", "#2A2D33")
    val DAY_PAL = listOf("#F8F3EA", "#F4EEE4", "#F1E9DD", "#F6F1E8", "#EDE6DA", "#EBD9C8", "#F3ECE2", "#E6DCCF")
    val NIGHT_PAL = listOf("#1B2238", "#1E2540", "#192035", "#20283F", "#1C2236")

    // render/city.py:176 rng_for
    /** `random.Random(hash(k) & 0xffffffff)`: a generator seeded by the hash of a tuple. */
    fun rngFor(vararg k: Any?): PyRandom = PyRandom(PyHash.hashTuple(k.asList()) and 0xffffffffL)

    // render/city.py:180 Bldg
    /** A building: footprint, base height [y0], height [h], kind, colour index and id (a Python tuple). */
    class Bldg(
        val foot: List<V2>, val h: Double, val kind: String, val col: Int, val id: List<Any?>,
        val y0: Double = 0.0, val named: String? = null,
    )

    // render/city.py:186 rect
    fun rect(x0: Double, x1: Double, z0: Double, z1: Double): List<V2> = listOf(V2(x0, z0), V2(x1, z0), V2(x1, z1), V2(x0, z1))

    // render/city.py:189 overlaps
    fun overlaps(a: DoubleArray, b: DoubleArray): Boolean =
        !(a[1] <= b[0] || b[1] <= a[0] || a[3] <= b[2] || b[3] <= a[2])

    /** A tree: position and crown radius. */
    data class Tree(val x: Double, val z: Double, val r: Double)

    /** What [buildCity] makes. */
    class CityData(
        val pads: List<List<V2>>, val parks: List<Pair<String, List<V2>>>, val bldgs: List<Bldg>,
        val trees: List<Tree>, val water: List<V2>, val esp: List<V2>,
    )

    // render/city.py:193 build_city
    /** region: (xmin, xmax, zmin, zmax) to generate in. */
    fun buildCity(region: DoubleArray): CityData {
        val rx0 = region[0]; val rx1 = region[1]; val rz0 = region[2]; val rz1 = region[3]
        val pads = ArrayList<List<V2>>(); val parks = ArrayList<Pair<String, List<V2>>>()
        val bldgs = ArrayList<Bldg>(); val trees = ArrayList<Tree>()
        // random.seed(7): the module generator is not drawn from here
        val namedRects = NAMED.values.map { it.foot }
        // ---- Brooklyn side
        for (i in -3 until 22) {
            for (j in -70 until 18) {
                var x0 = i * AV_SP + AV_W / 2.0; val x1 = (i + 1) * AV_SP - AV_W / 2.0
                val z0 = j * ST_SP + ST_W / 2.0; val z1 = (j + 1) * ST_SP - ST_W / 2.0
                if (x1 < rx0 || x0 > rx1 || z1 < rz0 || z0 > rz1) continue
                val xl = PyMath.max(shoreE(z0), shoreE((z0 + z1) / 2), shoreE(z1)) + 38
                if (x1 < xl + 22) continue
                if (x0 < xl) x0 = xl + 2
                if (Math.abs((z0 + z1) / 2 - BRIDGE_Z) < 40 && x0 < 330) continue // bridge ramp street
                if (x0 >= PARK[0] - 1 && x1 <= PARK[1] + 1 && z0 >= PARK[2] - 1 && z1 <= PARK[3] + 1) continue
                val rg = rngFor("b", i, j)
                pads.add(rect(x0, x1, z0, z1))
                if (rg.random() < 0.035 && i > 1) {
                    parks.add("plaza" to rect(x0, x1, z0, z1))
                    for (q in 0 until 10) {
                        val tx = rg.uniform(x0 + 4, x1 - 4); val tz = rg.uniform(z0 + 4, z1 - 4); val tr = rg.uniform(3.0, 4.5)
                        trees.add(Tree(tx, tz, tr))
                    }
                    continue
                }
                val bx0 = x0 + SIDEWALK; val bx1 = x1 - SIDEWALK; val bz0 = z0 + SIDEWALK; val bz1 = z1 - SIDEWALK
                val waterfront = (bx0 < 170 && -1500 < z0 && z0 < -150)
                val north = z0 < -2600
                var rows = listOf(doubleArrayOf(bz0, (bz0 + bz1) / 2 - 0.6), doubleArrayOf((bz0 + bz1) / 2 + 0.6, bz1))
                if (rg.random() < 0.12) rows = listOf(doubleArrayOf(bz0, bz1))
                for (row in rows) {
                    val rz_0 = row[0]; val rz_1 = row[1]
                    var x = bx0
                    while (x < bx1 - 6) {
                        var w = rg.choice(BLOCK_WIDTHS).toDouble()
                        if (waterfront && rg.random() < 0.15) w = rg.choice(WIDE_WIDTHS).toDouble()
                        var xe = PyMath.min(bx1, x + w)
                        if (bx1 - xe < 9) xe = bx1
                        val fr = doubleArrayOf(x, xe, rz_0, rz_1)
                        x = xe + 0.8
                        if (namedRects.any { overlaps(fr, it) }) continue
                        if (rg.random() < 0.05) continue
                        val r = rg.random()
                        val h: Double; val kind: String
                        if (waterfront && bx0 < 120 && r < 0.13) {
                            h = rg.uniform(55.0, 135.0); kind = "glass"
                        } else if (north && r < 0.45) {
                            h = rg.uniform(70.0, 210.0); kind = "glass"
                        } else if (r < 0.07) {
                            h = rg.uniform(28.0, 46.0); kind = "normal"
                        } else {
                            val c = rg.choice(LOW_HEIGHTS).toDouble()
                            h = c + rg.uniform(-1.0, 1.0); kind = "normal"
                        }
                        val foot = rect(fr[0], fr[1], fr[2], fr[3])
                        val col = rg.randrange(1000).toInt()
                        bldgs.add(Bldg(foot, h, kind, col, listOf("b", i, j, PyMath.round(x))))
                    }
                }
            }
        }
        // named
        for ((k, v) in NAMED) {
            val f = v.foot
            bldgs.add(Bldg(rect(f[0], f[1], f[2], f[3]), v.h, v.kind, 3, listOf("n", k), named = k))
            if (k == "marlow") {
                bldgs.add(Bldg(rect(f[0] - 6, f[1] + 6, f[2] - 6, f[3] + 6), 14.0, "normal", 1, listOf("n", "marlow-pod"), named = "marlow"))
            }
        }
        // ---- far shore (the big city)
        for (i in -40 until 0) {
            for (j in -60 until 20) {
                val x0 = (i * 150 + 12).toDouble(); var x1 = ((i + 1) * 150 - 12).toDouble()
                val z0 = (j * 80 + 8).toDouble(); val z1 = ((j + 1) * 80 - 8).toDouble()
                if (x1 < rx0 || x0 > rx1 || z1 < rz0 || z0 > rz1) continue
                val xl = PyMath.min(shoreW(z0), shoreW(z1)) - 34
                if (x0 > xl - 30) continue
                if (x1 > xl) x1 = xl
                val rg = rngFor("m", i, j)
                pads.add(rect(x0, x1, z0, z1))
                val bx0 = x0 + 4; val bx1 = x1 - 4; val bz0 = z0 + 4; val bz1 = z1 - 4
                var x = bx0
                while (x < bx1 - 10) {
                    val w = rg.choice(FAR_WIDTHS).toDouble()
                    var xe = PyMath.min(bx1, x + w)
                    if (bx1 - xe < 14) xe = bx1
                    val zs = if (rg.random() < 0.5) listOf(doubleArrayOf(bz0, bz1))
                    else listOf(doubleArrayOf(bz0, (bz0 + bz1) / 2 - 1), doubleArrayOf((bz0 + bz1) / 2 + 1, bz1))
                    for (ab in zs) {
                        val a = ab[0]; val b = ab[1]
                        val r = rg.random()
                        val h = if (r < 0.05) rg.uniform(300.0, 430.0)
                        else if (r < 0.45) rg.uniform(90.0, 220.0)
                        else rg.uniform(35.0, 90.0)
                        val kind = if (h > 120 || rg.random() < 0.3) "glass" else "normal"
                        val col = rg.randrange(1000).toInt()
                        bldgs.add(Bldg(rect(x, xe, a, b), h, kind, col, listOf("m", i, j, PyMath.round(x), PyMath.round(a))))
                    }
                    x = xe + 1.5
                }
            }
        }
        // ---- park + esplanade trees
        parks.add("park" to rect(PARK[0], PARK[1], PARK[2], PARK[3]))
        val rg = PyRandom(11)
        run {
            var gx = PARK[0].toInt() + 6
            while (gx < PARK[1].toInt() - 4) {
                var gz = PARK[2].toInt() + 6
                while (gz < PARK[3].toInt() - 4) {
                    val x = gx + rg.uniform(-4.0, 4.0); val z = gz + rg.uniform(-4.0, 4.0)
                    if (520 < x && x < 600 && -500 < z && z < -420) {
                        gz += 11; continue // lawn / fields
                    }
                    if (Math.abs(x - 550) < 4 || Math.abs(z - (-455)) < 4) {
                        gz += 11; continue // paths
                    }
                    if (rg.random() < 0.8) trees.add(Tree(x, z, rg.uniform(3.2, 5.2)))
                    gz += 11
                }
                gx += 11
            }
        }
        val left = ArrayList<V2>(); val right = ArrayList<V2>()
        var zq = PyMath.max(rz0, -5000.0)
        while (zq <= PyMath.min(rz1, 2000.0)) {
            left.add(V2(shoreE(zq), zq)); right.add(V2(shoreE(zq) + 34, zq)); zq += 40
        }
        val esp = left + right.asReversed()
        run {
            var z = PyMath.max(rz0, -5000.0).toInt()
            val zEnd = PyMath.min(rz1, 2000.0).toInt()
            while (z < zEnd) {
                if (Math.abs(z - BRIDGE_Z) < 30) {
                    z += 13; continue
                }
                if (rg.random() < 0.75) {
                    val zd = z.toDouble()
                    val tx = shoreE(zd) + rg.uniform(10.0, 26.0); val tz = zd + rg.uniform(-3.0, 3.0); val tr = rg.uniform(3.0, 4.6)
                    trees.add(Tree(tx, tz, tr))
                }
                z += 13
            }
        }
        // street trees near core
        for (i in -1 until 9) {
            for (j in -18 until 3) {
                val x0 = i * AV_SP + AV_W / 2.0; val x1 = (i + 1) * AV_SP - AV_W / 2.0
                val z0 = j * ST_SP + ST_W / 2.0; val z1 = (j + 1) * ST_SP - ST_W / 2.0
                val xl = PyMath.max(shoreE(z0), shoreE(z1)) + 38
                if (x0 < xl + 2) continue
                if (x0 >= PARK[0] - 1 && x1 <= PARK[1] + 1 && z0 >= PARK[2] - 1 && z1 <= PARK[3] + 1) continue
                val rg2 = rngFor("t", i, j)
                var x = x0.toInt() + 6
                while (x < x1.toInt() - 4) {
                    for (zz in doubleArrayOf(z0 + 2, z1 - 2)) {
                        if (rg2.random() < 0.55) {
                            val tx = x + rg2.uniform(-1.0, 1.0); val tr = rg2.uniform(2.6, 3.6)
                            trees.add(Tree(tx, zz, tr))
                        }
                    }
                    x += 12
                }
                var z = z0.toInt() + 6
                while (z < z1.toInt() - 4) {
                    for (xx in doubleArrayOf(x0 + 2, x1 - 2)) {
                        if (rg2.random() < 0.4) {
                            val tz = z + rg2.uniform(-1.0, 1.0); val tr = rg2.uniform(2.6, 3.6)
                            trees.add(Tree(xx, tz, tr))
                        }
                    }
                    z += 12
                }
            }
        }
        // water polygon
        val wl = ArrayList<V2>(); val wr = ArrayList<V2>()
        var z = -9000
        while (z <= 4000) {
            val zd = z.toDouble()
            wl.add(V2(shoreW(zd), zd)); wr.add(V2(shoreE(zd), zd)); z += 50
        }
        val water = wr + wl.asReversed()
        return CityData(pads, parks, bldgs, trees, water, esp)
    }

    private val BLOCK_WIDTHS = intArrayOf(14, 16, 18, 20, 22, 25, 28, 32)
    private val WIDE_WIDTHS = intArrayOf(36, 44, 52)
    private val LOW_HEIGHTS = intArrayOf(10, 12, 13, 15, 16, 18, 20, 22)
    private val FAR_WIDTHS = intArrayOf(26, 32, 38, 44)

    // ---------------- themes ----------------

    // render/city.py:322 L_
    fun L_(x: Double, y: Double, z: Double): V3 = vnorm(V3(x, y, z))

    val DAY: Dict = dictOf(
        "name" to "day",
        "L" to L_(-0.35, 0.6, 0.72),
        "sky_top" to "#A9C4E6", "sky_hor" to "#F4E7D9", "fogc" to "#EFE5DA",
        "fog0" to 900, "fog1" to 7000, "fog_max" to 0.78, "fog_pow" to 0.9,
        "street" to "#D3CEC4", "pad" to "#E8E4DC", "park" to "#B5D59A", "plaza" to "#E2DFD2", "esp" to "#C3DCAE",
        "water" to "#9DC2E2", "water_edge" to "#8DB5DA",
        "tree" to "#7DAE68", "tree_hi" to "#A2CC88", "tree_lo" to "#5C8C50",
        "shadow" to "#3A4570", "shadow_op" to 0.2,
        "amb" to 0.68, "dif" to 0.36, "shade_tint" to "#A7AECB", "shade_mix" to 0.36, "warm" to "#FFD8AE", "warm_mix" to 0.24,
        "top_tint" to "#FFF4E2", "top_mix" to 0.3, "top_k" to 1.03,
        "glass" to "#CAD8E8", "glass2" to "#BCCDE0", "lume" to "#F0A57A", "hall" to "#E9D6BE",
        "detail" to true, "night" to false,
    )

    val NIGHT: Dict = dictOf(
        "name" to "night",
        "L" to L_(0.3, 0.8, -0.5),
        "sky_top" to "#04070F", "sky_hor" to "#1D2541", "fogc" to "#1A223C",
        "fog0" to 500, "fog1" to 4200, "fog_max" to 0.85, "fog_pow" to 0.9,
        "street" to "#0C1120", "pad" to "#10172A", "park" to "#0E1A20", "plaza" to "#131A2C", "esp" to "#0F1C22",
        "water" to "#070B16", "water_edge" to "#0A1020",
        "tree" to "#11232A", "tree_hi" to "#183139", "tree_lo" to "#0C1B20",
        "shadow" to "#000000", "shadow_op" to 0.0,
        "amb" to 0.92, "dif" to 0.16, "shade_tint" to "#0B0F1C", "shade_mix" to 0.25, "warm" to "#2B3558", "warm_mix" to 0.3,
        "top_tint" to "#2A3452", "top_mix" to 0.35, "top_k" to 1.0,
        "glass" to "#1A2442", "glass2" to "#152039", "lume" to "#6A4A3A", "hall" to "#2A2A38",
        "detail" to false, "night" to true,
    )

    // render/city.py:355 fogmix
    /** The colour [c] fogged at depth [d], and the fog amount. */
    fun fogmix(th: Map<String, Any?>, c: V3, d: Double): Pair<V3, Double> {
        var t = clamp((d - th.num("fog0")) / (th.num("fog1") - th.num("fog0")))
        t = PyMath.pow(t, th.num("fog_pow")) * th.num("fog_max")
        return mix(c, hx(th.str("fogc")), t) to t
    }

    // render/city.py:361 face_color
    fun faceColor(th: Map<String, Any?>, base: V3, n: V3, d: Double, top: Boolean = false): Pair<V3, Double> {
        val L = v3(th["L"])
        var c: V3
        if (top) {
            c = mix(base, hx(th.str("top_tint")), th.num("top_mix")); c = mulc(c, th.num("top_k"))
        } else {
            val lam = PyMath.max(0.0, vdot(n, L))
            c = mulc(base, th.num("amb") + th.num("dif") * lam)
            c = mix(c, hx(th.str("shade_tint")), th.num("shade_mix") * (1 - lam))
            c = mix(c, hx(th.str("warm")), th.num("warm_mix") * lam)
        }
        return fogmix(th, c, d)
    }

    // render/city.py:373 base_color
    fun baseColor(th: Map<String, Any?>, b: Bldg, highlight: String? = null): V3 {
        if (b.named == "lume" && highlight == "lume") return hx(th.str("lume"))
        if (b.named == "marlow" && highlight == "marlow") return if (!th.flag("night")) hx("#F2B48E") else hx("#6A4A3A")
        if (b.named == "hall") return hx(th.str("hall"))
        if (b.kind == "car") return hx(CAR_COLS[PyMath.mod(b.col, CAR_COLS.size)])
        if (b.kind == "garage") return hx(th.str("garage", "#9FB0CC"))
        if (b.kind == "glass") {
            if (truthy(th["pal"]) && truthy(th["glass_from_pal"])) {
                val pal = th.list("pal")
                return hx(pal[PyMath.mod(b.col, pal.size)] as String)
            }
            return hx(if (b.col % 2 != 0) th.str("glass") else th.str("glass2"))
        }
        @Suppress("UNCHECKED_CAST")
        val pal = (if (truthy(th["pal"])) th["pal"] as List<String> else null) ?: (if (!th.flag("night")) DAY_PAL else NIGHT_PAL)
        return hx(pal[PyMath.mod(b.col, pal.size)])
    }

    // ---------------- scene rendering ----------------

    /** What [render] returns: the SVG, the anchors' screen positions, and the camera. */
    class Rendered(val svg: String, val anc: LinkedHashMap<String, List<Double>>, val cam: Camera)

    /** A thing to paint, with its depth: a building, tree, lamp, bridge part or extra. */
    private class Item(val d: Double, val kind: Char, val obj: Any)

    // render/city.py:387 render
    fun render(cfg: Map<String, Any?>, city: CityData, name: String): Rendered {
        val W = cfg.int("W"); val H = cfg.int("H")
        @Suppress("UNCHECKED_CAST")
        val th = cfg["theme"] as Map<String, Any?>
        val cam = Camera(
            v3(cfg["target"]), cfg.num("yaw"), cfg.num("pitch"), cfg.num("dist"), cfg.num("fov"), W.toDouble(), H.toDouble(),
            cx = cfg.numOrNull("cx"), cy = cfg.numOrNull("cy"),
        )
        val hl = cfg.strOrNull("highlight")
        val defs = ArrayList<String>(); val ground = ArrayList<String>(); val shadows = ArrayList<String>()
        val groundtop = ArrayList<String>(); val items = ArrayList<Item>()
        val hy = cam.horizonY()
        // base
        ground.add(PyFmt.pf("<rect width=\"%d\" height=\"%d\" fill=\"%s\"/>", W, H, th["street"]))
        // water
        var sp = cam.poly(city.water.map { V3(it.x, 0.0, it.y) }, margin = 4000.0)
        if (sp != null) ground.add(PyFmt.pf("<path d=\"%s\" fill=\"%s\"/>", P(sp), th["water"]))
        if (th.flag("chart")) {
            val zs = (-9000..4000 step 50).map { it.toDouble() }
            val bands: List<Triple<(Double) -> Double, (Double) -> Double, Any?>> = listOf(
                Triple({ z: Double -> shoreE(z) }, { z: Double -> shoreE(z) - 120 }, th["shallow2"]),
                Triple({ z: Double -> shoreW(z) }, { z: Double -> shoreW(z) + 120 }, th["shallow2"]),
                Triple({ z: Double -> shoreE(z) }, { z: Double -> shoreE(z) - 55 }, th["shallow"]),
                Triple({ z: Double -> shoreW(z) }, { z: Double -> shoreW(z) + 55 }, th["shallow"]),
            )
            for ((fa, fb, col) in bands) {
                val poly = zs.map { V2(fa(it), it) } + zs.asReversed().map { V2(fb(it), it) }
                val sp2 = cam.poly(poly.map { V3(it.x, 0.01, it.y) }, margin = 4000.0)
                if (sp2 != null) ground.add(PyFmt.pf("<path d=\"%s\" fill=\"%s\"/>", P(sp2), col))
            }
            for (off in intArrayOf(-120, 120)) {
                val f: (Double) -> Double = if (off < 0) { z -> shoreE(z) - 120 } else { z -> shoreW(z) + 120 }
                val pts = zs.map { cam.proj(V3(f(it), 0.02, it)) }
                    .filter { p -> p != null && -400 < p.x && p.x < W + 400 && -400 < p.y && p.y < H + 400 }
                if (pts.size > 1) {
                    ground.add(PyFmt.pf(
                        "<path d=\"M%s\" fill=\"none\" stroke=\"%s\" stroke-width=\".8\" stroke-dasharray=\"4 3\"/>",
                        pts.joinToString("L") { PyFmt.fixed(it!!.x, 1) + " " + PyFmt.fixed(it.y, 1) }, th["contour"],
                    ))
                }
            }
        }
        // pads
        val padp = StringBuilder()
        for (pd in city.pads) {
            val s = cam.poly(pd.map { V3(it.x, 0.0, it.y) })
            if (s != null && area(s) > 0.3) padp.append(P(s))
        }
        ground.add(PyFmt.pf("<path d=\"%s\" fill=\"%s\"/>", padp.toString(), th["pad"]))
        sp = cam.poly(city.esp.map { V3(it.x, 0.0, it.y) }, margin = 4000.0)
        if (sp != null) ground.add(PyFmt.pf("<path d=\"%s\" fill=\"%s\"/>", P(sp), th["esp"]))
        for ((kind, pk) in city.parks) {
            val s = cam.poly(pk.map { V3(it.x, 0.0, it.y) })
            if (s != null) ground.add(PyFmt.pf("<path d=\"%s\" fill=\"%s\"/>", P(s), if (kind == "park") th["park"] else th["plaza"]))
        }
        // park paths + lawn
        for (seg in listOf(listOf(V2(550.0, PARK[2]), V2(550.0, PARK[3])), listOf(V2(PARK[0], -455.0), V2(PARK[1], -455.0)))) {
            val rb = ribbon(seg, 4.0)
            val s = cam.poly(rb.map { V3(it.x, 0.05, it.y) })
            if (s != null) ground.add(PyFmt.pf("<path d=\"%s\" fill=\"%s\"/>", P(s), th["pad"]))
        }
        sp = cam.poly(rect(522.0, 598.0, -498.0, -422.0).map { V3(it.x, 0.04, it.y) })
        if (sp != null) {
            val lawn = tohex(mix(hx(th.str("park")), V3(255.0, 255.0, 255.0), if (!th.flag("night")) 0.14 else 0.03))
            ground.add(PyFmt.pf("<path d=\"%s\" fill=\"%s\"/>", P(sp), lawn))
        }

        val L = v3(th["L"])
        fun shoff(y: Double): V2 = V2(-L[0] / L[1] * y, -L[2] / L[1] * y)

        // night light pools & lamps
        if (th.flag("night") && !th.flag("no_lamps")) {
            defs.add("<radialGradient id=\"pool\"><stop offset=\"0\" stop-color=\"#FFB45E\" stop-opacity=\".55\"/><stop offset=\"1\" stop-color=\"#FFB45E\" stop-opacity=\"0\"/></radialGradient>")
            defs.add("<radialGradient id=\"halo\"><stop offset=\"0\" stop-color=\"#FFE6B8\" stop-opacity=\".9\"/><stop offset=\".25\" stop-color=\"#FFD38A\" stop-opacity=\".35\"/><stop offset=\"1\" stop-color=\"#FFC56E\" stop-opacity=\"0\"/></radialGradient>")
            val lamps = ArrayList<V2>()
            for (i in -2 until 10) {
                val x = i * AV_SP
                var z = -2000
                while (z < 300) {
                    for (s in intArrayOf(-1, 1)) lamps.add(V2(x + s * (AV_W / 2.0 + 1.5), (z + (if (s > 0) 8 else 24)).toDouble()))
                    z += 32
                }
            }
            for (j in -30 until 4) {
                val z = j * ST_SP
                var x = -40
                while (x < 900) {
                    lamps.add(V2((x + (if (PyMath.mod(j, 2) != 0) 8 else 20)).toDouble(), z + ST_W / 2.0 + 1.2))
                    x += 34
                }
            }
            for ((x, z) in lamps) {
                if (x < shoreE(z) + 30) continue
                val c = cam.cam(V3(x, 0.0, z))
                if (c[2] < 2 || c[2] > 1500) continue
                val p = cam.pc(c)
                if (!(-60 < p[0] && p[0] < W + 60 && -60 < p[1] && p[1] < H + 60)) continue
                val rx = 8 * cam.focal / c[2]
                val q = cam.proj(V3(x, 0.0, z + 8)) ?: p
                val ry = PyMath.max(0.4, Math.abs(q[1] - p[1]))
                val a = 0.8 * clamp(1 - (c[2] - 200) / 1300)
                ground.add(PyFmt.pf("<ellipse cx=\"%.1f\" cy=\"%.1f\" rx=\"%.1f\" ry=\"%.1f\" fill=\"url(#pool)\" opacity=\"%.2f\"/>", p[0], p[1], rx, ry, a))
                items.add(Item(c[2] - 0.5, 'l', V2(x, z)))
            }
        }

        // shadows
        if (th.num("shadow_op") > 0) {
            for (b in city.bldgs) {
                if (b.id[0] == "m") continue
                val o0 = shoff(b.y0); val o1 = shoff(b.y0 + b.h)
                val pts = b.foot.map { V2(it.x + o0[0], it.y + o0[1]) } + b.foot.map { V2(it.x + o1[0], it.y + o1[1]) }
                val hl2 = hull2(pts)
                val s = cam.poly(hl2.map { V3(it.x, 0.02, it.y) })
                if (s != null && area(s) > cfg.num("min_area", 0.5)) shadows.add(P(s))
            }
            for ((x, z, r) in city.trees) {
                val o = shoff(6.0)
                val pts = (0 until 8).map { k ->
                    val a = k * PyMath.PI / 4
                    V3(x + o[0] + r * PyMath.cos(a), 0.02, z + o[1] + r * PyMath.sin(a))
                }
                val s = cam.poly(pts)
                if (s != null && area(s) > cfg.num("min_area", 0.3)) shadows.add(P(s))
            }
        }

        // routes (ground)
        val routeSvgs = ArrayList<Triple<List<V2>, Any?, Any?>>()
        for (rt0 in cfg.list("routes")) {
            @Suppress("UNCHECKED_CAST")
            val rt = rt0 as Map<String, Any?>
            val pts = densify(smooth(v2s(rt["pts"]), rt.num("r", 14.0)), 10.0)
            for (layer in rt.list("layers")) {
                val ly = layer as List<*>
                val w = pyNum(ly[0]); val col = ly[1]; val op = pyNum(ly[2])
                val rb = ribbon(pts, w)
                val s = cam.poly(rb.map { V3(it.x, 0.25, it.y) }, margin = 2000.0)
                if (s != null) {
                    val str = PyFmt.pf("<path d=\"%s\" fill=\"%s\"%s/>", P(s), col, if (op >= 1) "" else PyFmt.pf(" opacity=\"%.2f\"", op))
                    groundtop.add(str)
                    if (truthy(rt["xray"])) routeSvgs.add(Triple(s, col, rt["xray"]))
                }
            }
        }

        val ringtop = ArrayList<String>()
        for (rg0 in cfg.list("rings")) {
            val rg = rg0 as List<*>
            val x = pyNum(rg[0]); val z = pyNum(rg[1]); val r = rg[2]; val fill = rg[3]; val fop = pyNum(rg[4])
            val stroke = rg[5]; val dash = rg[6]
            val pts = if (r is Number) {
                val rr = r.toDouble()
                (0 until 72).map { k -> val a = k * 2 * PyMath.PI / 72; V3(x + rr * PyMath.cos(a), 0.3, z + rr * PyMath.sin(a)) }
            } else {
                v2s(r).map { V3(it.x, 0.3, it.y) }
            }
            val s = cam.poly(pts, margin = 3000.0)
            if (s != null) {
                (if (cfg.flag("rings_top")) ringtop else groundtop).add(PyFmt.pf(
                    "<path d=\"%s\" fill=\"%s\" fill-opacity=\"%.2f\" stroke=\"%s\" stroke-width=\"1.4\"%s/>",
                    P(s), fill, fop, stroke, if (truthy(dash)) PyFmt.pf(" stroke-dasharray=\"%s\"", dash) else "",
                ))
            }
        }
        // markings (night nav)
        for (mk0 in cfg.list("markings")) {
            val mk = mk0 as List<*>
            val (x0, z0) = v2(mk[0]); val (x1, z1) = v2(mk[1])
            val dash = pyNum(mk[2]); val gap = pyNum(mk[3]); val w = pyNum(mk[4]); val col = mk[5]; val op = pyNum(mk[6])
            val l = PyMath.hypot(x1 - x0, z1 - z0); val ux = (x1 - x0) / l; val uz = (z1 - z0) / l
            var t = 0.0
            while (t < l) {
                val a = V2(x0 + ux * t, z0 + uz * t); val b = V2(x0 + ux * PyMath.min(l, t + dash), z0 + uz * PyMath.min(l, t + dash))
                val rb = ribbon(listOf(a, b), w)
                val s = cam.poly(rb.map { V3(it.x, 0.1, it.y) })
                if (s != null) groundtop.add(PyFmt.pf("<path d=\"%s\" fill=\"%s\" opacity=\"%.2f\"/>", P(s), col, op))
                t += dash + gap
            }
        }
        for (cw0 in cfg.list("crosswalks")) {
            val cw = cw0 as List<*>
            val (cx0, cz0) = v2(cw[0]); val along = cw[1] as String; val n = (cw[2] as Number).toInt()
            for (k in 0 until n) {
                val r = if (along == "x") rect(cx0 + k * 1.6, cx0 + k * 1.6 + 0.8, cz0, cz0 + 4)
                else rect(cx0, cx0 + 4, cz0 + k * 1.6, cz0 + k * 1.6 + 0.8)
                val s = cam.poly(r.map { V3(it.x, 0.1, it.y) })
                if (s != null) groundtop.add(PyFmt.pf("<path d=\"%s\" fill=\"#ffffff\" opacity=\"%.2f\"/>", P(s), if (th.flag("night")) 0.16 else 0.7))
            }
        }

        // buildings -> items
        for (b in city.bldgs) {
            val cxz = V2(PyMath.sumOf(b.foot) { it.x } / b.foot.size, PyMath.sumOf(b.foot) { it.y } / b.foot.size)
            val c = cam.cam(V3(cxz[0], b.y0 + b.h * 0.5, cxz[1]))
            if (c[2] < -60) continue
            if (c[2] > th.num("fog1") * 1.05) continue
            items.add(Item(c[2], 'b', b))
        }
        for (tr in city.trees) {
            val c = cam.cam(V3(tr.x, 6.0, tr.z))
            if (c[2] < 1 || c[2] > 1800) continue
            items.add(Item(c[2], 't', tr))
        }
        for (extra0 in cfg.list("extras")) {
            @Suppress("UNCHECKED_CAST")
            val extra = extra0 as Map<String, Any?>
            items.add(Item(cam.depth(v3(extra["at"])), 'x', extra))
        }
        // bridge
        for (seg in bridgeParts()) {
            val c = cam.cam(seg.c)
            if (c[2] < 0 || c[2] > th.num("fog1")) continue
            items.add(Item(c[2], 'g', seg))
        }

        items.pySortBy { -it.d }
        val outItems = ArrayList<String>()
        @Suppress("UNUSED_VARIABLE")
        val rngw = PyRandom(3)
        for (it in items) {
            val d = it.d
            when (it.kind) {
                'b' -> outItems.addAll(emitBldg(cam, th, it.obj as Bldg, d, hl, cfg))
                't' -> {
                    val (x, z, r) = it.obj as Tree
                    val c = cam.cam(V3(x, 6.0, z))
                    val p = cam.pc(c); val rr = r * cam.focal / c[2]
                    if (rr < cfg.num("min_tree", 0.35) || !(-20 < p[0] && p[0] < W + 20 && -20 < p[1] && p[1] < H + 20)) continue
                    val col = fogmix(th, hx(th.str("tree")), d).first
                    val hi = fogmix(th, hx(th.str("tree_hi")), d).first
                    val lo = fogmix(th, hx(th.str("tree_lo")), d).first
                    outItems.add(PyFmt.pf("<circle cx=\"%.1f\" cy=\"%.1f\" r=\"%.1f\" fill=\"%s\"/>", p[0], p[1] + rr * 0.12, rr, tohex(lo)))
                    outItems.add(PyFmt.pf("<circle cx=\"%.1f\" cy=\"%.1f\" r=\"%.1f\" fill=\"%s\"/>", p[0] - rr * 0.08, p[1] - rr * 0.05, rr * 0.86, tohex(col)))
                    if (rr > 1.2) {
                        outItems.add(PyFmt.pf("<circle cx=\"%.1f\" cy=\"%.1f\" r=\"%.1f\" fill=\"%s\"/>", p[0] - rr * 0.3, p[1] - rr * 0.32, rr * 0.42, tohex(hi)))
                    }
                }
                'l' -> {
                    val (x, z) = it.obj as V2
                    val c = cam.cam(V3(x, 7.0, z))
                    if (c[2] < 1) continue
                    val p = cam.pc(c); val rr = PyMath.max(0.7, 0.35 * cam.focal / c[2])
                    outItems.add(PyFmt.pf("<circle cx=\"%.1f\" cy=\"%.1f\" r=\"%.1f\" fill=\"url(#halo)\"/>", p[0], p[1], rr * 9))
                    outItems.add(PyFmt.pf("<circle cx=\"%.1f\" cy=\"%.1f\" r=\"%.1f\" fill=\"#FFF1D6\"/>", p[0], p[1], rr))
                }
                'g' -> outItems.addAll(emitBridge(cam, th, it.obj as BridgePart, d))
                'x' -> {
                    @Suppress("UNCHECKED_CAST")
                    val emit = (it.obj as Map<String, Any?>)["emit"] as (Camera, Map<String, Any?>) -> List<String>
                    outItems.addAll(emit(cam, th))
                }
            }
        }

        // x-ray route over buildings
        val xray = ArrayList<String>()
        for ((s, col, op) in routeSvgs) xray.add(PyFmt.pf("<path d=\"%s\" fill=\"%s\" opacity=\"%.2f\"/>", P(s), col, op))

        val sound = ArrayList<String>()
        for (snd0 in cfg.list("soundings")) {
            val snd = snd0 as List<*>
            val x = pyNum(snd[0]); val z = pyNum(snd[1]); val t = snd[2]
            val c = cam.cam(V3(x, 0.0, z))
            if (c[2] < 5) continue
            val p = cam.pc(c)
            if (!(0 < p[0] && p[0] < W && 0 < p[1] && p[1] < H)) continue
            val fs = clamp(9.5 * 480 / c[2], 5.5, 13.0)
            sound.add(PyFmt.pf(
                "<text x=\"%.1f\" y=\"%.1f\" font-size=\"%.1f\" fill=\"#4E6B85\" fill-opacity=\"%.2f\" font-style=\"italic\" text-anchor=\"middle\" font-family=\"Georgia,serif\">%s</text>",
                p[0], p[1], fs, clamp(1.1 - c[2] / 2600, 0.25, 0.9), t,
            ))
        }
        // fog overlay + sky
        val fogStops = StringBuilder()
        val top = PyMath.max(0.0, hy)
        val n = 28
        for (k in 0..n) {
            val sy = top + (H - top) * k / n
            val gd = cam.groundDepth(sy + 0.01)
            val a = if (gd == null) th.num("fog_max") else fogmix(th, V3(0.0, 0.0, 0.0), gd).second
            fogStops.append(PyFmt.pf("<stop offset=\"%.3f\" stop-color=\"%s\" stop-opacity=\"%.3f\"/>", k.toDouble() / n, th["fogc"], a))
        }
        defs.add(PyFmt.pf("<linearGradient id=\"fog\" gradientUnits=\"userSpaceOnUse\" x1=\"0\" y1=\"%.1f\" x2=\"0\" y2=\"%d\">%s</linearGradient>", top, H, fogStops.toString()))
        val fogRect = PyFmt.pf("<rect y=\"%.1f\" width=\"%d\" height=\"%.1f\" fill=\"url(#fog)\"/>", top, W, H - top)
        var sky = ""
        if (hy > 0) {
            defs.add(PyFmt.pf(
                "<linearGradient id=\"sky\" gradientUnits=\"userSpaceOnUse\" x1=\"0\" y1=\"%.1f\" x2=\"0\" y2=\"%.1f\"><stop offset=\"0\" stop-color=\"%s\"/><stop offset=\".72\" stop-color=\"%s\"/><stop offset=\"1\" stop-color=\"%s\"/></linearGradient>",
                hy - cam.focal * 0.9, hy, th["sky_top"], tohex(mix(hx(th.str("sky_top")), hx(th.str("sky_hor")), 0.7)), th["sky_hor"],
            ))
            sky = PyFmt.pf("<rect width=\"%d\" height=\"%.1f\" fill=\"url(#sky) %s\"/>", W, hy + 1, th["sky_hor"])
        }

        var shadowG = ""
        if (shadows.isNotEmpty()) {
            shadowG = PyFmt.pf("<g fill=\"%s\" opacity=\"%.2f\"><path d=\"%s\"/></g>", th["shadow"], th["shadow_op"], shadows.joinToString(""))
        }
        val svg = StringBuilder()
        svg.append(PyFmt.pf("<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 %d %d\" width=\"%d\" height=\"%d\">", W, H, W, H))
        svg.append(PyFmt.pf("<defs><clipPath id=\"vb\"><rect width=\"%d\" height=\"%d\"/></clipPath>%s</defs><g clip-path=\"url(#vb)\">", W, H, defs.joinToString("")))
        for (g in ground) svg.append(g)
        svg.append(shadowG)
        for (g in groundtop) svg.append(g)
        svg.append(fogRect).append(sky)
        svg.append("<g stroke-width=\".5\" stroke-linejoin=\"round\">")
        for (o in outItems) svg.append(o)
        svg.append("</g>")
        for (o in xray) svg.append(o)
        for (o in ringtop) svg.append(o)
        for (o in sound) svg.append(o)
        for (o in cfg.list("overlay_svg")) svg.append(o as String)
        svg.append("</g></svg>")
        // anchors
        val anc = LinkedHashMap<String, List<Double>>()
        val all = ArrayList<Pair<String, V3>>()
        for ((k, p) in ANCHORS) all.add(k to p)
        for ((k, p) in cfg.dictOrEmpty("anchors")) all.add(k to v3(p))
        for ((k, p) in all) {
            val q = cam.proj(p)
            if (q != null) anc[k] = listOf(PyMath.round(q[0], 1), PyMath.round(q[1], 1))
        }
        return Rendered(svg.toString(), anc, cam)
    }

    @Suppress("UNCHECKED_CAST")
    private fun Map<String, Any?>.dictOrEmpty(k: String): Map<String, Any?> = this[k] as? Map<String, Any?> ?: emptyMap()

    // render/city.py:621 face_svg
    fun faceSvg(th: Map<String, Any?>, sp: List<V2>, hc: String, top: Boolean): String {
        val e = th[if (top) "edge_top" else "edge_side"]
        if (truthy(e)) {
            return PyFmt.pf(
                "<path d=\"%s\" fill=\"%s\" stroke=\"%s\" stroke-opacity=\"%s\" stroke-width=\"%s\"/>",
                P(sp), hc, e, th["edge_op"] ?: ".8", th["edge_w"] ?: ".8",
            )
        }
        return PyFmt.pf("<path d=\"%s\" fill=\"%s\" stroke=\"%s\"/>", P(sp), hc, hc)
    }

    // render/city.py:628 emit_bldg
    fun emitBldg(cam: Camera, th: Map<String, Any?>, b: Bldg, d: Double, hl: String?, cfg: Map<String, Any?>): List<String> {
        val out = ArrayList<String>()
        var base = baseColor(th, b, hl)
        val rgb = rngFor("c", b.id)
        base = mulc(base, 1 + rgb.uniform(-0.025, 0.02))
        val foot = b.foot; val n = foot.size
        val cxz = V2(PyMath.sumOf(foot) { it.x } / n, PyMath.sumOf(foot) { it.y } / n)
        val y0 = b.y0; val y1 = b.y0 + b.h
        for (i in 0 until n) {
            val a = foot[i]; val bb = foot[(i + 1) % n]
            val ex = bb[0] - a[0]; val ez = bb[1] - a[1]
            var nrm = vnorm(V3(ez, 0.0, -ex))
            val mid = V2((a[0] + bb[0]) / 2, (a[1] + bb[1]) / 2)
            if ((mid[0] - cxz[0]) * nrm[0] + (mid[1] - cxz[1]) * nrm[2] < 0) nrm = V3(-nrm[0], 0.0, -nrm[2])
            val fp = V3(mid[0], (y0 + y1) / 2, mid[1])
            if (vdot(nrm, vsub(cam.C, fp)) <= 0) continue
            val quad = listOf(V3(a[0], y0, a[1]), V3(bb[0], y0, bb[1]), V3(bb[0], y1, bb[1]), V3(a[0], y1, a[1]))
            val sp = cam.poly(quad) ?: continue
            val ar = area(sp)
            if (ar < cfg.num("min_area", 0.5)) continue
            val (col, fg) = faceColor(th, base, nrm, d)
            val hc = tohex(col)
            out.add(faceSvg(th, sp, hc, false))
            // contact shade at base
            if (!th.flag("night") && ar > 30 && y0 == 0.0) {
                val sh = PyMath.min(3.5, b.h * 0.3)
                val q2 = listOf(V3(a[0], 0.0, a[1]), V3(bb[0], 0.0, bb[1]), V3(bb[0], sh, bb[1]), V3(a[0], sh, a[1]))
                val sp2 = cam.poly(q2)
                if (sp2 != null) out.add(PyFmt.pf("<path d=\"%s\" fill=\"#2B3350\" opacity=\"%.2f\"/>", P(sp2), 0.07 * (1 - fg)))
            }
            // details
            val elen = PyMath.hypot(ex, ez)
            if (th.flag("night")) {
                if (ar > 50) out.addAll(windows(cam, th, b, a, bb, y0, y1, elen, d, fg, ar))
            } else if (th.flag("detail") && ar > 160 && d < 1100) {
                out.addAll(dayLines(cam, th, b, a, bb, y0, y1, elen, col, fg))
            }
        }
        if (cam.C[1] > y1) {
            val sp = cam.poly(foot.map { V3(it.x, y1, it.y) })
            if (sp != null) {
                val (col, _) = faceColor(th, base, V3(0.0, 1.0, 0.0), d, top = true)
                val hc = tohex(col)
                out.add(faceSvg(th, sp, hc, true))
                // roof details for close ups
                val rd = cfg["roof_details"]
                if (truthy(rd) && d < pyNum(rd) && b.kind == "normal" && b.h < 40) out.addAll(roofBits(cam, th, b, base, d))
            }
        }
        return out
    }

    // render/city.py:675 roof_bits
    fun roofBits(cam: Camera, th: Map<String, Any?>, b: Bldg, base: V3, d: Double): List<String> {
        val out = ArrayList<String>()
        val rg = rngFor("r", b.id)
        val xs = b.foot.map { it.x }; val zs = b.foot.map { it.y }
        val x0 = pyMinOf(xs); val x1 = pyMaxOf(xs); val z0 = pyMinOf(zs); val z1 = pyMaxOf(zs)
        val y = b.y0 + b.h
        // parapet inner shade (inset roof)
        val inset = rect(x0 + 0.7, x1 - 0.7, z0 + 0.7, z1 - 0.7)
        val sp = cam.poly(inset.map { V3(it.x, y, it.y) })
        if (sp != null) {
            val col = fogmix(th, mulc(mix(base, hx(th.str("top_tint")), 0.2), 0.95), d).first
            out.add(PyFmt.pf("<path d=\"%s\" fill=\"%s\"/>", P(sp), tohex(col)))
        }
        repeat(rg.randint(1, 3)) {
            val w = rg.uniform(2.0, 4.0); val dd = rg.uniform(1.5, 3.0); val hh = rg.uniform(1.0, 2.0)
            val bx = rg.uniform(x0 + 1.5, PyMath.max(x0 + 1.6, x1 - 1.5 - w))
            val bz = rg.uniform(z0 + 1.5, PyMath.max(z0 + 1.6, z1 - 1.5 - dd))
            val bb = Bldg(rect(bx, bx + w, bz, bz + dd), hh, "normal", 0, listOf("rb", b.id, bx), y0 = y)
            out.addAll(emitSimple(cam, th, bb, mulc(base, 0.97), d))
        }
        if (rg.random() < 0.45 && (x1 - x0) > 12) {
            // water tower
            val cx = rg.uniform(x0 + 4, x1 - 4); val cz = rg.uniform(z0 + 4, z1 - 4)
            val yb = y + 2.6
            for (lxz in LEGS) {
                val lx = lxz.x; val lz = lxz.y
                val p0 = cam.proj(V3(cx + lx, y, cz + lz)); val p1 = cam.proj(V3(cx + lx, yb, cz + lz))
                if (p0 != null && p1 != null) {
                    out.add(PyFmt.pf(
                        "<path d=\"M%.1f %.1fL%.1f %.1f\" stroke=\"#6B5A4E\" stroke-width=\"%.1f\" fill=\"none\"/>",
                        p0[0], p0[1], p1[0], p1[1], PyMath.max(0.4, 0.25 * cam.focal / d),
                    ))
                }
            }
            val foot = (0 until 10).map { k -> val a = k * 2 * PyMath.PI / 10; V2(cx + 2.2 * PyMath.cos(a), cz + 2.2 * PyMath.sin(a)) }
            val wt = Bldg(foot, 4.2, "normal", 0, listOf("wt", b.id), y0 = yb)
            out.addAll(emitSimple(cam, th, wt, hx("#B7967C"), d, apex = V3(cx, yb + 4.2 + 1.8, cz)))
        }
        return out
    }

    private val LEGS = listOf(V2(-1.4, -1.4), V2(1.4, -1.4), V2(1.4, 1.4), V2(-1.4, 1.4))

    /** Python `min(list)`: the first of the smallest. */
    internal fun pyMinOf(xs: List<Double>): Double {
        var m = xs[0]
        for (i in 1 until xs.size) if (xs[i] < m) m = xs[i]
        return m
    }

    /** Python `max(list)`: the first of the largest. */
    internal fun pyMaxOf(xs: List<Double>): Double {
        var m = xs[0]
        for (i in 1 until xs.size) if (xs[i] > m) m = xs[i]
        return m
    }

    // render/city.py:705 emit_simple
    fun emitSimple(cam: Camera, th: Map<String, Any?>, b: Bldg, base: V3, d: Double, apex: V3? = null): List<String> {
        val out = ArrayList<String>()
        val foot = b.foot; val n = foot.size
        val cxz = V2(PyMath.sumOf(foot) { it.x } / n, PyMath.sumOf(foot) { it.y } / n)
        val y0 = b.y0; val y1 = b.y0 + b.h
        for (i in 0 until n) {
            val a = foot[i]; val bb = foot[(i + 1) % n]
            val ex = bb[0] - a[0]; val ez = bb[1] - a[1]
            var nrm = vnorm(V3(ez, 0.0, -ex))
            val mid = V2((a[0] + bb[0]) / 2, (a[1] + bb[1]) / 2)
            if ((mid[0] - cxz[0]) * nrm[0] + (mid[1] - cxz[1]) * nrm[2] < 0) nrm = V3(-nrm[0], 0.0, -nrm[2])
            if (vdot(nrm, vsub(cam.C, V3(mid[0], (y0 + y1) / 2, mid[1]))) <= 0) continue
            val sp = cam.poly(listOf(V3(a[0], y0, a[1]), V3(bb[0], y0, bb[1]), V3(bb[0], y1, bb[1]), V3(a[0], y1, a[1]))) ?: continue
            val (col, _) = faceColor(th, base, nrm, d)
            val hc = tohex(col)
            out.add(faceSvg(th, sp, hc, false))
        }
        if (apex != null) {
            val tris = ArrayList<String>()
            for (i in 0 until n) {
                val a = foot[i]; val bb = foot[(i + 1) % n]
                val p = listOf(V3(a[0], y1, a[1]), V3(bb[0], y1, bb[1]), apex)
                var nr = vnorm(vcross(vsub(p[1], p[0]), vsub(p[2], p[0])))
                if (nr[1] < 0) nr = V3(-nr[0], -nr[1], -nr[2])
                val cen = V3((a[0] + bb[0] + apex[0]) / 3, (y1 * 2 + apex[1]) / 3, (a[1] + bb[1] + apex[2]) / 3)
                if (vdot(nr, vsub(cam.C, cen)) <= 0) continue
                val sp = cam.poly(p) ?: continue
                val (col, _) = faceColor(th, mulc(base, 0.9), nr, d)
                val hc = tohex(col)
                tris.add(faceSvg(th, sp, hc, false))
            }
            out.addAll(tris)
        } else if (cam.C[1] > y1) {
            val sp = cam.poly(foot.map { V3(it.x, y1, it.y) })
            if (sp != null) {
                val (col, _) = faceColor(th, base, V3(0.0, 1.0, 0.0), d, top = true)
                val hc = tohex(col)
                out.add(faceSvg(th, sp, hc, true))
            }
        }
        return out
    }

    // render/city.py:746 day_lines
    fun dayLines(cam: Camera, th: Map<String, Any?>, b: Bldg, a: V2, bb: V2, y0: Double, y1: Double, elen: Double, col: V3, fg: Double): List<String> {
        val segs = StringBuilder()
        val step = if (b.kind == "glass") 4.0 else 3.6
        var y = y0 + step
        while (y < y1 - 0.8) {
            val p0 = cam.proj(V3(a[0], y, a[1])); val p1 = cam.proj(V3(bb[0], y, bb[1]))
            if (p0 != null && p1 != null) segs.append(PyFmt.pf("M%.1f %.1fL%.1f %.1f", p0[0], p0[1], p1[0], p1[1]))
            y += step
        }
        if (b.kind == "glass") {
            val k = (elen / 3.2).toInt()
            for (i in 1 until k) {
                val t = i.toDouble() / k
                val x = a[0] + (bb[0] - a[0]) * t; val z = a[1] + (bb[1] - a[1]) * t
                val p0 = cam.proj(V3(x, y0, z)); val p1 = cam.proj(V3(x, y1, z))
                if (p0 != null && p1 != null) segs.append(PyFmt.pf("M%.1f %.1fL%.1f %.1f", p0[0], p0[1], p1[0], p1[1]))
            }
        }
        if (segs.isEmpty()) return emptyList()
        val dc = tohex(mulc(col, if (b.kind == "glass") 0.9 else 0.93))
        return listOf(PyFmt.pf("<path d=\"%s\" stroke=\"%s\" stroke-width=\".45\" fill=\"none\" opacity=\"%.2f\"/>", segs.toString(), dc, 0.9 * (1 - fg)))
    }

    val WIN_COLS = listOf("#FFD08A", "#FFC56F", "#FFE2AE", "#FFB85C", "#CFE0FF", "#FFD9A0")
    private val WIN_OPS = listOf(0.45, 0.6, 0.75, 0.9)

    // render/city.py:768 windows
    fun windows(cam: Camera, th: Map<String, Any?>, b: Bldg, a: V2, bb: V2, y0: Double, y1: Double, elen: Double, d: Double, fg: Double, ar: Double): List<String> {
        val out = ArrayList<String>()
        val rg = rngFor("w", b.id, PyMath.round(a[0]), PyMath.round(a[1]))
        val tall = b.h > 60
        val fh = if (tall) 3.8 else 3.5
        val cw = if (tall) 3.4 else 4.2
        val floors = ((y1 - y0 - 2) / fh).toInt(); val cols = ((elen - 1.5) / cw).toInt()
        if (floors <= 0 || cols <= 0) return out
        var pLit = if (!tall) 0.34 else 0.42
        var budget = if (d < 500) PyMath.min(160.0, ar / 45).toInt() else PyMath.min(40.0, ar / 80).toInt()
        budget = (budget * th.num("win_scale", 1.0)).toInt()
        pLit = if (d < 500) pLit else PyMath.min(0.95, pLit * 4)
        var count = 0
        val paths = LinkedHashMap<Pair<String, Double>, MutableList<String>>()
        val ww = if (!tall) 2.2 else 2.6
        @Suppress("UNCHECKED_CAST")
        val winCols = (th["win_cols"] as List<String>?) ?: WIN_COLS
        for (fl in 0 until floors) {
            val yb = y0 + 1.6 + fl * fh; val yt = yb + (if (!tall) 1.9 else 2.6)
            if (fl == 0 && !tall && d < 600) {
                // storefront band
                if (rg.random() < 0.55) {
                    val u0 = 0.08; val u1 = 0.92
                    val q = listOf(
                        V3(a[0] + (bb[0] - a[0]) * u0, 0.4, a[1] + (bb[1] - a[1]) * u0), V3(a[0] + (bb[0] - a[0]) * u1, 0.4, a[1] + (bb[1] - a[1]) * u1),
                        V3(a[0] + (bb[0] - a[0]) * u1, 3.0, a[1] + (bb[1] - a[1]) * u1), V3(a[0] + (bb[0] - a[0]) * u0, 3.0, a[1] + (bb[1] - a[1]) * u0),
                    )
                    val sp = cam.poly(q)
                    if (sp != null) paths.getOrPut("#FFCF8A" to 0.75) { ArrayList() }.add(P(sp))
                }
                continue
            }
            var cells: MutableList<Int> = (0 until cols).toMutableList(); rg.shuffle(cells)
            if (d >= 500) cells = cells.subList(0, Math.min(cells.size, Math.max(1, cols / 5))).toMutableList()
            for (c in cells) {
                if (rg.random() > pLit) continue
                count += 1
                if (count > budget) break
                val u0 = (0.9 + c * cw + (cw - ww) / 2) / elen; val u1 = u0 + ww / elen
                val q = listOf(
                    V3(a[0] + (bb[0] - a[0]) * u0, yb, a[1] + (bb[1] - a[1]) * u0), V3(a[0] + (bb[0] - a[0]) * u1, yb, a[1] + (bb[1] - a[1]) * u1),
                    V3(a[0] + (bb[0] - a[0]) * u1, yt, a[1] + (bb[1] - a[1]) * u1), V3(a[0] + (bb[0] - a[0]) * u0, yt, a[1] + (bb[1] - a[1]) * u0),
                )
                val sp = cam.poly(q) ?: continue
                if (area(sp) < 0.3) continue
                val col = rg.choice(winCols)
                val op = PyMath.round(rg.choice(WIN_OPS), 2)
                paths.getOrPut(col to op) { ArrayList() }.add(P(sp))
            }
        }
        for ((key, ps) in paths) {
            val (col, op) = key
            out.add(PyFmt.pf("<path d=\"%s\" fill=\"%s\" opacity=\"%.2f\"/>", ps.joinToString(""), col, op * (1 - fg * 0.85)))
        }
        return out
    }

    /** A piece of the bridge: a deck or tower box (foot, y0, h), or a cable segment (a to b); [c] orders it. */
    class BridgePart(
        val kind: String, val c: V3,
        val foot: List<V2> = emptyList(), val y0: Double = 0.0, val h: Double = 0.0,
        val a: V3? = null, val b: V3? = null,
    )

    // render/city.py:814 bridge_parts
    fun bridgeParts(): List<BridgePart> {
        val parts = ArrayList<BridgePart>()
        val z = BRIDGE_Z.toDouble()
        val xa = shoreE(z) + 36; val xb = shoreW(z) - 34
        val n = 26
        for (k in 0 until n) {
            val x0 = xa + (xb - xa) * k / n; val x1 = xa + (xb - xa) * (k + 1) / n
            parts.add(BridgePart("deck", V3((x0 + x1) / 2, 38.0, z), rect(PyMath.min(x0, x1), PyMath.max(x0, x1), z - 13, z + 13), 36.0, 4.0))
        }
        // ramps into city
        for (k in 0 until 8) {
            val x0 = xa + k * 30; val x1 = x0 + 30
            val y = 36 * (1 - (k + 0.5) / 8)
            parts.add(BridgePart("deck", V3((x0 + x1) / 2, y, z), rect(x0, x1, z - 12, z + 12), PyMath.max(0.0, y - 2), if (y > 2) 4.0 else 1.5))
        }
        val t1 = xa - 95; val t2 = xb + 95
        for (tx in doubleArrayOf(t1, t2)) {
            for (dz in intArrayOf(-15, 11)) {
                parts.add(BridgePart("tower", V3(tx, 52.0, z + dz + 2), rect(tx - 5, tx + 5, z + dz, z + dz + 4), 0.0, 104.0))
            }
            parts.add(BridgePart("tower", V3(tx, 98.0, z), rect(tx - 5, tx + 5, z - 15, z + 15), 96.0, 5.0))
        }
        // cables
        for (dz in intArrayOf(-13, 13)) {
            val pts = ArrayList<V3>()
            for (k in 0 until 41) {
                val t = k.toDouble() / 40
                val x = t1 + (t2 - t1) * t
                val y = 102 - 60 * (1 - PyMath.pow(2 * t - 1, 2))
                pts.add(V3(x, y, z + dz))
            }
            for (k in 0 until 40) parts.add(BridgePart("cable", pts[k], a = pts[k], b = pts[k + 1]))
            for ((xs, xe) in listOf(t1 to xa - 10, t2 to xb + 10)) {
                for (k in 0 until 10) {
                    val t0 = k.toDouble() / 10; val t1_ = (k + 1).toDouble() / 10
                    fun cp(t: Double): V3 = V3(xs + (xe - xs) * t, 102 - (102 - 40) * PyMath.pow(t, 0.8), z + dz)
                    parts.add(BridgePart("cable", cp(t0), a = cp(t0), b = cp(t1_)))
                }
            }
        }
        return parts
    }

    // render/city.py:851 emit_bridge
    fun emitBridge(cam: Camera, th: Map<String, Any?>, seg: BridgePart, d: Double): List<String> {
        if (seg.kind == "cable") {
            val p0 = cam.proj(seg.a!!); val p1 = cam.proj(seg.b!!)
            if (!(p0 != null && p1 != null)) return emptyList()
            val col = fogmix(th, hx(if (!th.flag("night")) "#8E97AE" else "#6D7FB8"), d).first
            return listOf(PyFmt.pf(
                "<path d=\"M%.1f %.1fL%.1f %.1f\" stroke=\"%s\" stroke-width=\"%.2f\" fill=\"none\"/>",
                p0[0], p0[1], p1[0], p1[1], tohex(col), PyMath.max(0.5, 0.8 * cam.focal / d),
            ))
        }
        var base = if (!th.flag("night")) hx("#C9CED9") else hx("#2A3350")
        if (seg.kind == "tower") base = if (!th.flag("night")) hx("#B9BFCC") else hx("#2E3858")
        val bb = Bldg(seg.foot, seg.h, "normal", 0, listOf("br"), y0 = seg.y0)
        val out = ArrayList(emitSimple(cam, th, bb, base, d))
        if (th.flag("night") && seg.kind == "deck") {
            // deck lights
            val xs = seg.foot.map { it.x }; val zs = seg.foot.map { it.y }
            for (zz in doubleArrayOf(pyMinOf(zs), pyMaxOf(zs))) {
                val p = cam.proj(V3((pyMinOf(xs) + pyMaxOf(xs)) / 2, seg.y0 + seg.h + 0.5, zz))
                if (p != null) {
                    out.add(PyFmt.pf("<circle cx=\"%.1f\" cy=\"%.1f\" r=\"%.1f\" fill=\"#FFE2A8\" opacity=\".85\"/>", p[0], p[1], PyMath.max(0.5, 0.6 * cam.focal / d)))
                }
            }
        }
        return out
    }
}

// render/shade.py: shade-aware walking. Routes on real OSM footways and crossings, scores every metre for sun or shade from the
// real building heights and street-tree crowns at a given moment. Ported line by line.
package app.atlas.render

import app.atlas.render.py.GridMap
import app.atlas.render.py.PyJson
import app.atlas.render.py.PyMath
import app.atlas.render.py.truthy
import java.io.File
import java.time.LocalDateTime
import java.util.PriorityQueue

/** render/shade.py. */
@Suppress("UNCHECKED_CAST")
object Shade {
    /** Shade measured at shoulder height. */
    const val EYE = 1.3
    /** Metres between samples. */
    const val STEP = 3.0
    /** Sunlight through a leafy crown. */
    const val TREE_T = 0.25

    /** The walking graph: neighbours with edge lengths (insertion-ordered, as Python's dicts), node positions, edge kinds, named parks. */
    class Graph(
        val adj: LinkedHashMap<Long, LinkedHashMap<Long, Double>>,
        val coord: HashMap<Long, V2>,
        val kind: HashMap<Pair<Long, Long>, String>,
        val parks: LinkedHashMap<String, MutableList<V2>>,
    )

    // render/shade.py:12 load_graph
    /** The footway graph of an Overpass extract (shade.py reads `osm/raw.json`). */
    fun loadGraph(raw: File): Graph {
        val d = (PyJson.load(raw) as Map<String, Any?>)["elements"] as List<Map<String, Any?>>
        val W = d.filter { it["type"] == "way" && ((it["tags"] as Map<String, Any?>?) ?: emptyMap())["highway"] in setOf("footway", "path", "pedestrian", "steps", "living_street") }
        val adj = LinkedHashMap<Long, LinkedHashMap<Long, Double>>(); val coord = HashMap<Long, V2>(); val kind = HashMap<Pair<Long, Long>, String>()
        for (e in W) {
            val ns = (e["nodes"] as List<Any?>).map { (it as Number).toLong() }; val g = e["geometry"] as List<Map<String, Any?>>
            for ((i, n) in ns.withIndex()) coord[n] = Real.XZ(pyNum(g[i]["lat"]), pyNum(g[i]["lon"]))
            val t = e["tags"] as Map<String, Any?>
            val k = if (t["footway"] == "crossing") "crossing" else if (t["highway"] == "steps") "steps" else "walk"
            for (i in 0 until ns.size - 1) {
                val a = ns[i]; val b = ns[i + 1]
                val L = Amsterdam.dist(coord.getValue(a), coord.getValue(b))
                adj.getOrPut(a) { LinkedHashMap() }[b] = L; adj.getOrPut(b) { LinkedHashMap() }[a] = L
                kind[a to b] = k; kind[b to a] = k
            }
        }
        val parks = LinkedHashMap<String, MutableList<V2>>()
        for (e in d) {
            val t = (e["tags"] as Map<String, Any?>?) ?: emptyMap()
            if (t["leisure"] == "park" && truthy(t["name"])) {
                val ring = if (e["type"] == "relation") {
                    ((e["members"] as List<Map<String, Any?>>?) ?: emptyList()).filter { it["role"] == "outer" && truthy(it["geometry"]) }
                        .flatMap { m -> (m["geometry"] as List<Map<String, Any?>>).map { Real.XZ(pyNum(it["lat"]), pyNum(it["lon"])) } }
                } else ((e["geometry"] as List<Map<String, Any?>>?) ?: emptyList()).map { Real.XZ(pyNum(it["lat"]), pyNum(it["lon"])) }
                if (ring.isNotEmpty()) parks.getOrPut(t["name"] as String) { ArrayList() }.addAll(ring)
            }
        }
        return Graph(adj, coord, kind, parks)
    }

    private fun pyNum(v: Any?): Double = (v as Number).toDouble()

    // render/shade.py:33 TreeShade
    /** Street-tree crowns as spheres on a 20 m grid: how much sun reaches a point through them. */
    class TreeShade(trees: List<Real.Tree>) {
        val cell = 20.0
        private val grid = GridMap<MutableList<DoubleArray>>()

        init {
            for (t in trees) {
                val (h, r) = Real.treeSize(t)
                val c = doubleArrayOf(t.x, h * 0.66, t.z, r)
                grid.getOrPut(key(PyMath.floorDiv(t.x, cell).toLong(), PyMath.floorDiv(t.z, cell).toLong())) { ArrayList() }.add(c)
            }
        }

        private fun key(i: Long, j: Long): Long = GridMap.key(i, j)

        // render/shade.py:40 TreeShade.trans
        /** 1 in the open, [TREE_T] behind a crown, along the sun vector [L] from [p]. */
        fun trans(p: V3, L: V3): Double {
            if (L[1] <= 0) return 1.0
            val best = 1.0
            for (dt in 0 until 60 step 6) {
                val x = p[0] + L[0] * dt; val z = p[2] + L[2] * dt
                for (c in grid[key(PyMath.floorDiv(x, cell).toLong(), PyMath.floorDiv(z, cell).toLong())] ?: emptyList<DoubleArray>()) {
                    val vx = c[0] - p[0]; val vy = c[1] - p[1]; val vz = c[2] - p[2]
                    val tt = vx * L[0] + vy * L[1] + vz * L[2]
                    if (tt <= 0) continue
                    val dx = vx - L[0] * tt; val dy = vy - L[1] * tt; val dz = vz - L[2] * tt
                    if (dx * dx + dy * dy + dz * dz < c[3] * c[3]) return TREE_T
                }
            }
            return best
        }
    }

    /** The sun at a moment: azimuth, elevation and the vector toward it (straight down below the horizon). */
    class Sun(val az: Double, val el: Double, val L: V3)

    // render/shade.py:53 sun_at
    /** The sun at local time y-mo-dd hh:mm in New York (EDT). */
    fun sunAt(y: Int, mo: Int, dd: Int, hh: Int, mm: Int): Sun {
        val t = LocalDateTime.of(y, mo, dd, hh, mm).plusHours(4) // EDT
        val (az, el) = Living.sunPosition(t)
        return Sun(az, el, if (el > 0) Living.sunVector(az, el) else V3(0.0, -1.0, 0.0))
    }

    // render/shade.py:58 edge_sun
    /** The sunlight (0 to 1) of every 3 m sample along an edge, cached by edge. */
    fun edgeSun(a: Long, b: Long, coord: Map<Long, V2>, sh: Real.Shadows, ts: TreeShade, L: V3, cache: HashMap<Pair<Long, Long>, DoubleArray>): DoubleArray {
        val key = Math.min(a, b) to Math.max(a, b)
        cache[key]?.let { return it }
        val pa = coord.getValue(a); val pb = coord.getValue(b)
        val n = Math.max(1, PyMath.int(Amsterdam.dist(pa, pb) / STEP).toInt())
        val vals = DoubleArray(n)
        for (i in 0 until n) {
            val t = (i + 0.5) / n
            val p = V3(pa[0] + (pb[0] - pa[0]) * t, EYE, pa[1] + (pb[1] - pa[1]) * t)
            if (L[1] <= 0) { vals[i] = 0.0; continue }
            var lit = if (sh.lit(p)) 1.0 else 0.0
            if (lit != 0.0) lit *= ts.trans(p, L)
            vals[i] = lit
        }
        cache[key] = vals
        return vals
    }

    // render/shade.py:74 dijkstra
    /** The cheapest path from [src] to [dst] under [cost] (u, v, length), as heapq pops it. */
    fun dijkstra(adj: Map<Long, Map<Long, Double>>, src: Long, dst: Long, cost: (Long, Long, Double) -> Double): List<Long> {
        val dist = HashMap<Long, Double>(); dist[src] = 0.0; val prev = HashMap<Long, Long>()
        val pq = PriorityQueue<Pair<Double, Long>> { x, y -> if (x.first != y.first) java.lang.Double.compare(x.first, y.first) else x.second.compareTo(y.second) }
        pq.add(0.0 to src)
        while (pq.isNotEmpty()) {
            val (dd, u) = pq.poll()
            if (u == dst) break
            if (dd > (dist[u] ?: 1e18)) continue
            for ((v, L) in adj[u] ?: emptyMap()) {
                val nd = dd + cost(u, v, L)
                if (nd < (dist[v] ?: 1e18)) { dist[v] = nd; prev[v] = u; pq.add(nd to v) }
            }
        }
        val path = arrayListOf(dst)
        while (path[path.size - 1] != src) path.add(prev.getValue(path[path.size - 1]))
        return path.asReversed().toList()
    }

    /** A scored path: its length, metres in sun, share in shade, and every sample as a segment with its sunlight. */
    class Score(val length: Double, val sun: Double, val shade: Double, val segs: List<Triple<V2, V2, Double>>)

    // render/shade.py:87 score
    fun score(path: List<Long>, adj: Map<Long, Map<Long, Double>>, coord: Map<Long, V2>, sh: Real.Shadows, ts: TreeShade, L: V3, cache: HashMap<Pair<Long, Long>, DoubleArray>): Score {
        var length = 0.0; var sunM = 0.0; val segs = ArrayList<Triple<V2, V2, Double>>()
        for (k in 0 until path.size - 1) {
            val a = path[k]; val b = path[k + 1]
            val vals = edgeSun(a, b, coord, sh, ts, L, cache)
            val pa = coord.getValue(a); val pb = coord.getValue(b)
            val n = vals.size
            for ((i, v) in vals.withIndex()) {
                val p0 = V2(pa[0] + (pb[0] - pa[0]) * i / n, pa[1] + (pb[1] - pa[1]) * i / n)
                val p1 = V2(pa[0] + (pb[0] - pa[0]) * (i + 1) / n, pa[1] + (pb[1] - pa[1]) * (i + 1) / n)
                segs.add(Triple(p0, p1, v))
            }
            val Le = adj.getValue(a).getValue(b); length += Le; sunM += Le * (PyMath.sum(vals) / vals.size)
        }
        return Score(length, sunM, 1 - sunM / PyMath.max(1.0, length), segs)
    }

    /** What [plan] finds: the sun, the fastest path and the shadiest within the detour, the weight that found it, the ends. */
    class Plan(val az: Double, val el: Double, val fast: Score, val shady: Score, val weight: Int, val src: V2, val dst: V2, val fastPath: List<Long>, val shadyPath: List<Long>)

    // render/shade.py:100 plan
    /**
     * The fastest walk from the origin to the park [dest], and the shadiest within [detour] times its length, at [moment]
     * (y, mo, dd, hh, mm local), over the graph [g] and the buildings and trees of [S].
     */
    fun plan(moment: IntArray, S: Real.RealData, g: Graph, dest: String = "North 5th Street Pier and Park", detour: Double = 1.35, origin: V2 = V2(242.47, 178.59)): Plan {
        val adj = g.adj; val coord = g.coord
        val ring = g.parks.getValue(dest)
        val sun = sunAt(moment[0], moment[1], moment[2], moment[3], moment[4]); val L = sun.L
        val sh = Real.Shadows(S.buildings, L)
        val ts = TreeShade(S.trees)
        fun nearest(p: V2): Long {
            var best = 0L; var bd = 0.0; var first = true
            for (n in adj.keys) { val dd = Amsterdam.dist(coord.getValue(n), p); if (first || dd < bd) { best = n; bd = dd; first = false } }
            return best
        }
        val src = nearest(origin) // Bedford Av L entrance, wheelchair=yes (node 8105955603) in shade.py's Williamsburg
        var corner = ring[0]; var cd = Amsterdam.dist(ring[0], origin)
        for (p in ring) { val dd = Amsterdam.dist(p, origin); if (dd < cd) { cd = dd; corner = p } }
        val dst = nearest(corner)
        val cache = HashMap<Pair<Long, Long>, DoubleArray>()
        val fast = dijkstra(adj, src, dst) { _, _, Le -> Le }
        val F = score(fast, adj, coord, sh, ts, L, cache)
        var best: Triple<List<Long>, Score, Int>? = null
        for (w in intArrayOf(1, 2, 3, 5, 8, 12)) {
            val p = dijkstra(adj, src, dst) { u, v, Le ->
                val es = edgeSun(u, v, coord, sh, ts, L, cache)
                Le * (1 + w * (PyMath.sum(es) / Math.max(1, es.size)))
            }
            val R = score(p, adj, coord, sh, ts, L, cache)
            if (R.length <= F.length * detour && (best == null || R.shade > best.second.shade)) best = Triple(p, R, w)
        }
        val b = best ?: throw IllegalStateException("no path within the detour")
        return Plan(sun.az, sun.el, F, b.second, b.third, coord.getValue(src), coord.getValue(dst), fast, b.first)
    }
}

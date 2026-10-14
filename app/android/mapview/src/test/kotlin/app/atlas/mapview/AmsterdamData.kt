package app.atlas.mapview

import app.atlas.engine.Extras
import app.atlas.engine.TreeRow
import app.atlas.geo.Geo
import app.atlas.geo.LatLon
import app.atlas.model.OsmId
import app.atlas.model.OsmType
import app.atlas.model.Place
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import java.io.File
import java.util.PriorityQueue

/**
 * The Overpass extract around the Westerkerk (`data/ams/raw.json`): its real trees for the scenes, and its streets as
 * a graph, so test routes follow real streets (a plain Dijkstra; the real router lives in `:route`).
 */
object AmsterdamData {
    private val file = File(System.getProperty("atlas.repo") ?: "../../..", "data/ams/raw.json")

    val available: Boolean get() = file.isFile

    private class Graph(val lat: DoubleArray, val lon: DoubleArray, val edges: Array<IntArray>, val kinds: Array<IntArray>)

    /** Real trees and tree rows. */
    val trees: Extras by lazy { load().first }

    private val graph: Graph by lazy { load().second }

    private var loaded: Pair<Extras, Graph>? = null

    @Synchronized
    private fun load(): Pair<Extras, Graph> {
        loaded?.let { return it }
        val root = Json.parseToJsonElement(file.readText()).jsonObject
        val trees = ArrayList<Place>()
        val rows = ArrayList<TreeRow>()
        val index = HashMap<Long, Int>()
        val lat = ArrayList<Double>()
        val lon = ArrayList<Double>()
        val adj = ArrayList<MutableList<Int>>()
        val kind = ArrayList<MutableList<Int>>()
        for (e in root["elements"]!!.jsonArray) {
            val o = e.jsonObject
            val tags = (o["tags"] as? JsonObject)?.mapValues { it.value.jsonPrimitive.content } ?: continue
            val type = o["type"]!!.jsonPrimitive.content
            if (type == "node" && tags["natural"] == "tree") {
                trees += Place(OsmId(OsmType.NODE, o["id"]!!.jsonPrimitive.long), LatLon(o["lat"]!!.jsonPrimitive.double, o["lon"]!!.jsonPrimitive.double), tags)
                continue
            }
            if (type != "way") continue
            val geom = (o["geometry"] as? JsonArray)?.map { p -> p.jsonObject.let { LatLon(it["lat"]!!.jsonPrimitive.double, it["lon"]!!.jsonPrimitive.double) } } ?: continue
            if (tags["natural"] == "tree_row" && geom.size >= 2) rows += TreeRow(geom, tags)
            val k = when (tags["highway"]) {
                "primary", "secondary", "tertiary", "residential", "unclassified", "living_street", "service" -> 0
                "footway", "pedestrian", "path", "steps" -> 1
                else -> continue
            }
            val nodes = (o["nodes"] as? JsonArray)?.map { it.jsonPrimitive.long } ?: continue
            if (nodes.size != geom.size) continue
            var prev = -1
            for (i in nodes.indices) {
                val id = index.getOrPut(nodes[i]) {
                    lat.add(geom[i].lat); lon.add(geom[i].lon); adj.add(ArrayList()); kind.add(ArrayList())
                    lat.size - 1
                }
                if (prev >= 0) {
                    adj[prev] += id; kind[prev] += k
                    adj[id] += prev; kind[id] += k
                }
                prev = id
            }
        }
        val g = Graph(lat.toDoubleArray(), lon.toDoubleArray(), Array(adj.size) { adj[it].toIntArray() }, Array(kind.size) { kind[it].toIntArray() })
        return (Extras(trees, rows) to g).also { loaded = it }
    }

    private fun nearest(p: LatLon, walk: Boolean): Int {
        val g = graph
        var best = -1
        var bestD = Double.MAX_VALUE
        for (i in g.lat.indices) {
            if (g.edges[i].isEmpty()) continue
            if (!walk && g.kinds[i].none { it == 0 }) continue
            val d = Geo.distance(p.lat, p.lon, g.lat[i], g.lon[i])
            if (d < bestD) { bestD = d; best = i }
        }
        return best
    }

    /**
     * The shortest way from [from] to [to] along streets (and footways when [walk]); edges in [avoid] (node pairs) cost
     * [penalty] times more, for an alternative.
     */
    fun route(from: LatLon, to: LatLon, walk: Boolean = false, avoid: Set<Long> = emptySet(), penalty: Double = 3.0): List<LatLon> {
        val g = graph
        val s = nearest(from, walk)
        val t = nearest(to, walk)
        val dist = DoubleArray(g.lat.size) { Double.MAX_VALUE }
        val prev = IntArray(g.lat.size) { -1 }
        val queue = PriorityQueue<Pair<Double, Int>>(compareBy { it.first })
        dist[s] = 0.0
        queue += 0.0 to s
        while (queue.isNotEmpty()) {
            val (d, u) = queue.remove()
            if (d > dist[u]) continue
            if (u == t) break
            for ((k, v) in g.edges[u].withIndex()) {
                if (!walk && g.kinds[u][k] != 0) continue
                var w = Geo.distance(g.lat[u], g.lon[u], g.lat[v], g.lon[v])
                if (edge(u, v) in avoid) w *= penalty
                val nd = d + w
                if (nd < dist[v]) {
                    dist[v] = nd
                    prev[v] = u
                    queue += nd to v
                }
            }
        }
        val out = ArrayList<LatLon>()
        var u = t
        while (u >= 0) {
            out += LatLon(g.lat[u], g.lon[u])
            if (u == s) break
            u = prev[u]
        }
        return out.reversed()
    }

    /** The edges a route takes, for [route]'s avoid set. */
    fun edges(route: List<LatLon>): Set<Long> {
        val g = graph
        val ids = route.map { p -> g.lat.indices.first { g.lat[it] == p.lat && g.lon[it] == p.lon } }
        return ids.zipWithNext { a, b -> edge(a, b) }.toSet()
    }

    private fun edge(a: Int, b: Int): Long = if (a < b) (a.toLong() shl 32) or b.toLong() else (b.toLong() shl 32) or a.toLong()
}

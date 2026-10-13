package app.atlas.guide

import app.atlas.geo.Geo
import app.atlas.geo.LatLon
import app.atlas.geo.Polyline
import app.atlas.model.Fix
import app.atlas.model.Hazard
import app.atlas.model.Maneuver
import app.atlas.model.ManeuverType
import app.atlas.model.Mode
import app.atlas.model.OsmId
import app.atlas.model.OsmType
import app.atlas.model.Place
import app.atlas.model.Route
import app.atlas.model.Span
import app.atlas.model.Step
import app.atlas.model.Turn
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.BeforeClass
import org.junit.Test
import java.io.File
import java.util.PriorityQueue

/**
 * The guide on real OpenStreetMap data: the Overpass extract around the Westerkerk (data/ams/raw.json). A small test
 * router finds walking and driving routes over its ways, so the geometry, the street names, the crossings and the
 * landmarks are Amsterdam's own; the navigator, the landmarks and Ears-only mode then work on them.
 */
class RealDataTest {

    private class Way(val nodes: LongArray, val tags: Map<String, String>)

    companion object {
        private val coords = HashMap<Long, LatLon>()
        private val nodeTags = HashMap<Long, Map<String, String>>()
        private val ways = ArrayList<Way>()
        private val places = ArrayList<Place>()

        @BeforeClass @JvmStatic fun load() {
            val file = File(System.getProperty("atlas.repo") ?: "../../..", "data/ams/raw.json")
            if (!file.isFile) return
            val root = Json.parseToJsonElement(file.readText()).jsonObject
            for (e in root["elements"]!!.jsonArray) {
                val o = e.jsonObject
                val tags = (o["tags"] as? JsonObject)?.mapValues { it.value.jsonPrimitive.content } ?: emptyMap()
                when (o["type"]!!.jsonPrimitive.content) {
                    "node" -> {
                        val id = o["id"]!!.jsonPrimitive.long
                        val p = LatLon(o["lat"]!!.jsonPrimitive.double, o["lon"]!!.jsonPrimitive.double)
                        coords[id] = p
                        if (tags.isNotEmpty()) {
                            nodeTags[id] = tags
                            places += Place(OsmId(OsmType.NODE, id), p, tags)
                        }
                    }
                    "way" -> {
                        val ids = o["nodes"]!!.jsonArray.map { it.jsonPrimitive.long }
                        val geom = o["geometry"]!!.jsonArray
                        for ((i, g) in geom.withIndex()) {
                            val go = g.jsonObject
                            coords.putIfAbsent(ids[i], LatLon(go["lat"]!!.jsonPrimitive.double, go["lon"]!!.jsonPrimitive.double))
                        }
                        ways += Way(ids.toLongArray(), tags)
                    }
                }
            }
        }

        private val FOOT = setOf("footway", "pedestrian", "residential", "tertiary", "secondary", "unclassified", "living_street", "path", "steps", "service")
        private val CAR = setOf("residential", "tertiary", "secondary", "primary", "unclassified", "living_street")
    }

    private class Edge(val to: Long, val metres: Double, val way: Way)

    /** A shortest route over the extract's ways, turned into a [Route] the way the router does: steps where you turn. */
    private fun route(mode: Mode, from: LatLon, to: LatLon): Route {
        val car = mode == Mode.CAR
        val graph = HashMap<Long, MutableList<Edge>>()
        for (w in ways) {
            val hw = w.tags["highway"] ?: continue
            if (car && hw !in CAR || !car && (hw !in FOOT || w.tags["foot"] == "no")) continue
            val oneway = car && (w.tags["oneway"] == "yes" || w.tags["junction"] == "roundabout")
            for (i in 0 until w.nodes.size - 1) {
                val a = w.nodes[i]
                val b = w.nodes[i + 1]
                val d = Geo.distance(coords[a]!!, coords[b]!!)
                graph.getOrPut(a) { ArrayList() } += Edge(b, d, w)
                if (!oneway) graph.getOrPut(b) { ArrayList() } += Edge(a, d, w)
            }
        }
        val start = graph.keys.minBy { Geo.distance(coords[it]!!, from) }
        val goal = graph.keys.minBy { Geo.distance(coords[it]!!, to) }
        val dist = HashMap<Long, Double>()
        val prev = HashMap<Long, Pair<Long, Way>>()
        val queue = PriorityQueue<Pair<Double, Long>>(compareBy { it.first })
        dist[start] = 0.0
        queue += 0.0 to start
        while (queue.isNotEmpty()) {
            val (d, n) = queue.poll()
            if (n == goal) break
            if (d > dist[n]!!) continue
            for (e in graph[n].orEmpty()) {
                val nd = d + e.metres
                if (nd < (dist[e.to] ?: Double.MAX_VALUE)) {
                    dist[e.to] = nd
                    prev[e.to] = n to e.way
                    queue += nd to e.to
                }
            }
        }
        val path = ArrayList<Long>()
        val pathWays = ArrayList<Way>()
        var n = goal
        while (n != start) {
            val (p, w) = prev[n] ?: error("no route")
            path += n
            pathWays += w
            n = p
        }
        path += start
        path.reverse()
        pathWays.reverse()
        val points = path.map { coords[it]!! }
        val line = Polyline(points)
        // way tags by link: link i runs from point i to i + 1
        fun bearingBack(i: Int): Double = Geo.bearing(line.pointAt((line.cumulative[i] - 12).coerceAtLeast(0.0)), points[i])
        fun bearingOn(i: Int): Double = Geo.bearing(points[i], line.pointAt(line.cumulative[i] + 12))
        val turns = ArrayList<Int>()
        for (i in 1 until points.size - 1) {
            val angle = Geo.turnAngle(bearingBack(i), bearingOn(i))
            val renamed = pathWays[i].tags["name"] != null && pathWays[i].tags["name"] != pathWays[i - 1].tags["name"]
            if (kotlin.math.abs(angle) > 40 || renamed && kotlin.math.abs(angle) > 20) {
                if (turns.isNotEmpty() && line.cumulative[i] - line.cumulative[turns.last()] < 15) continue
                turns += i
            }
        }
        val stepPoints = listOf(0) + turns
        val steps = ArrayList<Step>()
        for ((k, i) in stepPoints.withIndex()) {
            val next = stepPoints.getOrNull(k + 1) ?: points.size - 1
            val d = line.cumulative[next] - line.cumulative[i]
            val street = pathWays[i].tags["name"]
            val m = if (k == 0) Maneuver(ManeuverType.DEPART, bearingAfter = bearingOn(0)) else {
                val angle = Geo.turnAngle(bearingBack(i), bearingOn(i))
                val turn = Turn.of(angle)
                Maneuver(if (turn == Turn.STRAIGHT) ManeuverType.CONTINUE else ManeuverType.TURN, turn, null, bearingBack(i), bearingOn(i))
            }
            steps += Step(m, i, points[i], d, d / if (car) 8.0 else 1.4, street)
        }
        steps += Step(Maneuver(ManeuverType.ARRIVE), points.size - 1, points.last(), 0.0, 0.0)
        val spans = ArrayList<Span>()
        for (key in listOf("highway", "surface", "maxspeed", "lit")) {
            var s = 0
            for (i in 1..pathWays.size) {
                if (i == pathWays.size || pathWays[i].tags[key] != pathWays[s].tags[key]) {
                    pathWays[s].tags[key]?.let { spans += Span(s, i, key, it) }
                    s = i
                }
            }
        }
        val hazards = ArrayList<Hazard>()
        for ((i, id) in path.withIndex()) {
            val t = nodeTags[id] ?: continue
            if (!car && (t["highway"] == "crossing" || t["highway"] == "traffic_signals")) {
                val signals = t["crossing"] == "traffic_signals" || t["highway"] == "traffic_signals"
                hazards += Hazard("crossing", i, points[i], tags = mapOf("crossing" to (t["crossing"] ?: "yes"), "signals" to if (signals) "yes" else "no"))
            }
        }
        return Route(mode, points, DoubleArray(0), line.length, line.length / if (car) 8.0 else 1.4, steps, spans.sortedBy { it.from }, hazards, source = "test:dijkstra")
    }

    private val lookup = FeatureLookup { p, r -> places.filter { Geo.distance(it.location, p) <= r } }

    private fun requireData() = assumeTrue("data/ams/raw.json not found", coords.isNotEmpty())

    @Test fun walkingFromTheWesterkerkToTheHerengracht() {
        requireData()
        val r = route(Mode.WALK, WESTERKERK, LatLon(52.3768, 4.8895))
        assertTrue("route ${r.distance} m", r.distance in 400.0..1500.0)
        val nav = Navigator(r, GuideSettings(mode = Mode.WALK), lookup)
        val d = drive(nav, Simulator(r, speed = 1.4, noise = 5.0, seed = 11).fixes())
        println("== walk ${r.distance.toInt()} m, ${r.steps.size} steps\n$d")
        assertEquals(0, d.reroutes.size)
        assertEquals(1, d.arrivals)
        assertEquals(d.said.size, d.said.map { it.id }.toSet().size)
        // every turn is told, with the real street names
        for (s in r.steps.drop(1).dropLast(1)) {
            val name = s.street ?: continue
            assertTrue("no instruction onto $name:\n$d", d.texts.any { name in it })
        }
        // the crossings on the way are told, each once
        val crossings = r.hazards.count { it.kind == "crossing" }
        println("crossings on the route: $crossings")
        assertEquals(crossings, d.texts.count { it.startsWith("Crossing ahead") || it.startsWith("Zebra crossing") })
    }

    @Test fun drivingThroughTheCanalRing() {
        requireData()
        val r = route(Mode.CAR, LatLon(52.3740, 4.8800), LatLon(52.3760, 4.8895))
        val nav = Navigator(r, GuideSettings(mode = Mode.CAR), lookup)
        val d = drive(nav, Simulator(r, speed = 8.0, noise = 8.0, seed = 5).fixes())
        println("== car ${r.distance.toInt()} m, ${r.steps.size} steps\n$d")
        assertEquals(0, d.reroutes.size)
        assertEquals(1, d.arrivals)
        assertEquals(d.said.size, d.said.map { it.id }.toSet().size)
        assertTrue(d.texts.first().startsWith("Head "))
        assertTrue(d.states.last().arrived)
    }

    @Test fun landmarksComeFromTheRealMap() {
        requireData()
        val r = route(Mode.CAR, LatLon(52.3740, 4.8800), LatLon(52.3760, 4.8895))
        val idx = RouteIndex(r)
        val lm = Landmarks(lookup)
        val book = Phrasebook.of("en")
        val found = (1 until r.steps.size - 1).mapNotNull { i -> lm.forStep(idx, i, ModeGroup.CAR)?.let { book.withLandmark(book.instruction(r.steps[i]), lm.phrase(it, book)).text } }
        println("== landmarks\n" + found.joinToString("\n"))
        assertTrue(found.isNotEmpty())
    }

    @Test fun earsOnlyAtTheWesterkerk() {
        requireData()
        val ears = EarsOnly(GuideSettings(mode = Mode.WALK))
        val fix = Fix(WESTERKERK, java.time.Instant.parse("2026-10-13T15:00:00Z"), 5.0, 1.2, 0.0)
        val near = places.filter { Geo.distance(it.location, WESTERKERK) <= 150 }
        val around = ears.aroundMe(fix, 0.0, near, limit = 5).map { (it as Cue.Speak).text }
        val where = (ears.whereAmI(fix, "Prinsengracht 279", "Prinsengracht", near, 0.0).single() as Cue.Speak).text
        println("== around me\n" + around.joinToString("\n") + "\n== where am I\n$where")
        assertEquals(5, around.size)
        assertEquals(around.size, around.toSet().size)
        assertTrue(where.startsWith("You are on Prinsengracht, near Prinsengracht 279, facing north. Nearby: "))
        // a walk north along the Prinsengracht calls out what it passes
        val heard = ArrayList<String>()
        for (i in 0..200) {
            val p = Geo.destination(WESTERKERK, 20.0, i * 1.4)
            heard += ears.update(Fix(p, fix.time.plusSeconds(i.toLong()), 5.0, 1.4, 20.0), 20.0, near).map { (it as Cue.Speak).text }
        }
        println("== walking callouts\n" + heard.joinToString("\n"))
        assertTrue(heard.isNotEmpty())
        assertEquals(heard.size, heard.toSet().size)
    }
}

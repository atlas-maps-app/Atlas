package app.atlas.route

import app.atlas.geo.Geo
import app.atlas.geo.LatLon
import app.atlas.geo.LocalFrame
import app.atlas.model.Mode
import app.atlas.model.OsmId
import app.atlas.model.OsmType
import app.atlas.model.Vehicle
import btools.router.OsmNogoPolygon
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class RestrictionsTest {

    @Test
    fun readsLengthsAndWeightsWithUnits() {
        assertEquals(4.2, OsmUnits.metres("4.2")!!, 1e-9)
        assertEquals(4.2, OsmUnits.metres("4,2 m")!!, 1e-9)
        assertEquals(4.4196, OsmUnits.metres("14'6\"")!!, 1e-4)
        assertEquals(3.9624, OsmUnits.metres("13 ft")!!, 1e-4)
        assertEquals(3.5, OsmUnits.metres("350 cm")!!, 1e-9)
        assertNull(OsmUnits.metres("default"))
        assertNull(OsmUnits.metres("below_default"))
        assertNull(OsmUnits.metres("none"))
        assertNull(OsmUnits.metres("3.8;4.0"))
        assertEquals(7.5, OsmUnits.tonnes("7.5")!!, 1e-9)
        assertEquals(7.5, OsmUnits.tonnes("7,5 t")!!, 1e-9)
        assertEquals(3.5, OsmUnits.tonnes("3500 kg")!!, 1e-9)
        assertEquals(10.886, OsmUnits.tonnes("12 st")!!, 1e-3)
        assertEquals(9.072, OsmUnits.tonnes("20000 lbs")!!, 1e-3)
        assertNull(OsmUnits.tonnes("unsigned"))
    }

    @Test
    fun buildsOverpassQueriesForTheCorridor() {
        val line = List(50) { LatLon(52.37 + it * 0.001, 4.88) }
        val road = OverpassQl.restrictions(Corridor(line, 30.0), OverpassRestrictionSource.Scope.ROAD)
        assertTrue(road.startsWith("[out:json]"))
        // the corridor filter runs once; the limits are picked from its ways
        assertEquals(1, Regex("around").findAll(road).count())
        assertTrue(road.contains("""way["highway"](around:30,52.37000,4.88000,52.41900,4.88000)->.roads;"""))
        assertTrue(road.contains("""node(w.roads)["barrier"="height_restrictor"];"""))
        for (key in listOf("maxweight", "maxwidth", "maxlength", "maxaxleload", "maxweightrating:hgv", "hgv", "hazmat", "height_restrictor")) {
            assertTrue(key, road.contains(key))
        }
        assertFalse(road.contains("seamark"))
        assertTrue(road.trimEnd().endsWith("out tags geom;"))
        val water = OverpassQl.restrictions(Corridor(line, 30.0), OverpassRestrictionSource.Scope.WATER)
        assertTrue(water.contains("seamark:bridge:clearance_height"))
        assertTrue(water.contains("""way.water["lock"="yes"]"""))
        assertTrue(water.contains("lock_gate"))
        assertFalse(water.contains(".roads"))
        val both = OverpassQl.restrictions(Corridor(line, 30.0), OverpassRestrictionSource.Scope.BOTH)
        assertTrue(both.contains("seamark") && both.contains("maxaxleload"))
    }

    @Test
    fun simplifiesLongCorridors() {
        val straight = List(1000) { LatLon(52.0, 4.0 + it * 0.0001) }
        assertEquals(2, OverpassQl.simplify(straight, 10.0, 400).size)
        val zigzag = List(2000) { LatLon(52.0 + (it % 2) * 0.001, 4.0 + it * 0.0005) }
        val thin = OverpassQl.simplify(zigzag, 10.0, 400)
        assertTrue(thin.size in 2..400)
        assertEquals(zigzag.first(), thin.first())
        assertEquals(zigzag.last(), thin.last())
    }

    @Test
    fun parsesOverpassAnswers() {
        val json = """
        {"version":0.6,"elements":[
          {"type":"way","id":1,"tags":{"highway":"primary","maxheight":"3.8","bridge":"no"},
           "geometry":[{"lat":52.0,"lon":4.0},{"lat":52.0,"lon":4.001}]},
          {"type":"node","id":2,"lat":52.1,"lon":4.1,"tags":{"barrier":"height_restrictor","maxheight":"2.1"}},
          {"type":"way","id":3,"tags":{"highway":"residential","hgv":"no"},"geometry":[{"lat":52.2,"lon":4.2},{"lat":52.2,"lon":4.201}]},
          {"type":"way","id":4,"tags":{"waterway":"canal","maxheight":"2.5","maxwidth":"5"},"geometry":[{"lat":52.3,"lon":4.3},{"lat":52.301,"lon":4.3}]},
          {"type":"node","id":5,"lat":52.4,"lon":4.4,"tags":{"seamark:type":"bridge","seamark:bridge:clearance_height":"3.1"}},
          {"type":"way","id":6,"tags":{"highway":"service","maxheight":"default"},"geometry":[{"lat":52.5,"lon":4.5},{"lat":52.5,"lon":4.501}]},
          {"type":"way","id":7,"tags":{"man_made":"bridge","bridge":"yes","seamark:bridge:clearance_height":"4"},
           "geometry":[{"lat":52.6,"lon":4.6},{"lat":52.6,"lon":4.601}]},
          {"type":"way","id":8,"tags":{"highway":"primary","tunnel":"yes","hazmat:E":"no","maxweightrating:hgv":"7.5 t"},
           "geometry":[{"lat":52.7,"lon":4.7},{"lat":52.7,"lon":4.701}]},
          {"type":"node","id":9,"lat":52.8,"lon":4.8,"tags":{"waterway":"lock_gate"}}
        ]}
        """.trimIndent()
        val rs = OverpassQl.parse(json).associateBy { it.id!!.id }
        assertEquals(setOf(1L, 2L, 3L, 4L, 5L, 7L, 8L, 9L), rs.keys)
        assertEquals(3.8, rs.getValue(1).maxHeight!!, 1e-9)
        assertEquals(OsmId(OsmType.WAY, 1), rs.getValue(1).id)
        assertTrue(rs.getValue(2).isPoint)
        assertEquals(2.1, rs.getValue(2).maxHeight!!, 1e-9)
        assertEquals("no", rs.getValue(3).hgv)
        assertEquals(2.5, rs.getValue(4).maxHeight!!, 1e-9)
        assertEquals(5.0, rs.getValue(4).maxWidth!!, 1e-9)
        assertEquals(3.1, rs.getValue(5).bridgeClearance!!, 1e-9)
        assertTrue(rs.getValue(7).crossing)
        assertEquals(4.0, rs.getValue(7).bridgeClearance!!, 1e-9)
        assertFalse(rs.getValue(8).hazmatAllowed)
        assertEquals(7.5, rs.getValue(8).maxWeight!!, 1e-9)
        assertEquals(HazardKind.LOCK, RestrictionRules.kindOf(rs.getValue(9)))
        // the source wires the query function to the parser
        assertTrue(rs.values.filter { it.id!!.id in setOf(4L, 5L, 7L, 9L) }.all { it.water })
        assertTrue(rs.values.filter { it.id!!.id in setOf(1L, 2L, 3L, 8L) }.none { it.water })
        assertEquals(setOf(1L, 2L, 3L, 8L), OverpassQl.parse(json, OverpassRestrictionSource.Scope.ROAD).map { it.id!!.id }.toSet())
        // the source wires the query function to the parser, and keeps to its scope
        val corridor = Corridor(listOf(LatLon(52.0, 4.0), LatLon(52.1, 4.1)))
        val both = OverpassRestrictionSource({ q -> assertTrue(q.contains("around")); json }, OverpassRestrictionSource.Scope.BOTH)
        assertEquals(8, runBlocking { both.restrictionsAlong(corridor) }.size)
        assertEquals(4, runBlocking { OverpassRestrictionSource({ json }).restrictionsAlong(corridor) }.size)
    }

    @Test
    fun decidesWhatAVehicleBreaks() {
        val p = listOf(LatLon(52.0, 4.0), LatLon(52.0, 4.001))
        val low = Restriction(null, p, maxHeight = 3.8)
        val high = Restriction(null, p, maxHeight = 4.5)
        val light = Restriction(null, p, maxWeight = 7.5)
        val local = Restriction(null, p, hgv = "destination")
        val banned = Restriction(null, p, hgv = "no")
        val noHazmat = Restriction(null, p, hazmatAllowed = false)
        val truck = Vehicle(heightM = 4.2)
        assertEquals(HazardKind.MAXHEIGHT, RestrictionRules.conflict(low, Mode.TRUCK, truck)!!.kind)
        assertEquals(4.2, RestrictionRules.conflict(low, Mode.TRUCK, truck)!!.actual!!, 0.0)
        assertNull(RestrictionRules.conflict(high, Mode.TRUCK, truck))
        // a 40 t truck by default
        assertEquals(HazardKind.MAXWEIGHT, RestrictionRules.conflict(light, Mode.TRUCK, truck)!!.kind)
        assertNull(RestrictionRules.conflict(light, Mode.TRUCK, truck.copy(weightT = 7.0)))
        assertNull(RestrictionRules.conflict(local, Mode.TRUCK, truck, nearEnds = true))
        assertEquals(HazardKind.HGV, RestrictionRules.conflict(local, Mode.TRUCK, truck, nearEnds = false)!!.kind)
        assertEquals(HazardKind.HGV, RestrictionRules.conflict(banned, Mode.TRUCK, truck, nearEnds = true)!!.kind)
        assertNull(RestrictionRules.conflict(noHazmat, Mode.TRUCK, truck))
        assertEquals(HazardKind.HAZMAT, RestrictionRules.conflict(noHazmat, Mode.TRUCK, truck.copy(hazmat = true))!!.kind)
        // campers: hgv rules only when heavier than 3.5 t
        assertNull(RestrictionRules.conflict(banned, Mode.CAMPER, Vehicle()))
        assertNotNull(RestrictionRules.conflict(banned, Mode.CAMPER, Vehicle(weightT = 5.0)))
        assertNotNull(RestrictionRules.conflict(Restriction(null, p, maxHeight = 2.9), Mode.CAMPER, Vehicle()))
        // cars only when the user gave dimensions; walkers never
        assertFalse(RestrictionRules.applies(Mode.CAR, Vehicle()))
        assertTrue(RestrictionRules.applies(Mode.CAR, Vehicle(heightM = 2.4)))
        assertNotNull(RestrictionRules.conflict(Restriction(null, p, maxHeight = 2.2), Mode.CAR, Vehicle(heightM = 2.4)))
        assertNull(RestrictionRules.conflict(low, Mode.WALK, truck))
        // boats: bridges, depth and width
        val boat = Vehicle(airDraftM = 3.0, draftM = 1.5, beamM = 4.0)
        assertTrue(RestrictionRules.applies(Mode.BOAT, boat))
        assertFalse(RestrictionRules.applies(Mode.BOAT, Vehicle()))
        assertEquals(HazardKind.BRIDGE, RestrictionRules.conflict(Restriction(null, p, bridgeClearance = 2.5, water = true), Mode.BOAT, boat)!!.kind)
        assertEquals(HazardKind.BRIDGE, RestrictionRules.conflict(Restriction(null, p, maxHeight = 2.5, water = true), Mode.SAIL, boat)!!.kind)
        assertEquals(HazardKind.MAXDRAUGHT, RestrictionRules.conflict(Restriction(null, p, maxDraught = 1.2, water = true), Mode.BOAT, boat)!!.kind)
        assertEquals(HazardKind.MAXWIDTH, RestrictionRules.conflict(Restriction(null, p, maxWidth = 3.5, water = true), Mode.BOAT, boat)!!.kind)
        assertNull(RestrictionRules.conflict(Restriction(null, p, bridgeClearance = 3.5, water = true), Mode.BOAT, boat))
        // road limits do not stop boats, nor water limits trucks
        assertNull(RestrictionRules.conflict(Restriction(null, p, maxHeight = 2.5), Mode.BOAT, boat))
        assertNull(RestrictionRules.conflict(Restriction(null, p, maxHeight = 2.5, water = true), Mode.TRUCK, truck))
    }

    @Test
    fun matchesRestrictionsAgainstARoute() {
        val f = LocalFrame(LatLon(52.37, 4.88))
        fun at(x: Double, y: Double) = f.toLatLon(x, y)
        val route = List(21) { at(it * 50.0, 0.0) } // 1 km east
        val m = RouteMatcher(route)
        // a way along the route from 100 m to 250 m
        val along = m.touch(Restriction(null, listOf(at(100.0, 0.5), at(250.0, 0.5)), maxHeight = 3.0))!!
        assertEquals(150.0, along.along, 1.0)
        assertEquals(90.0, along.bearing, 1.0)
        // the same in the opposite direction still counts
        assertNotNull(m.touch(Restriction(null, listOf(at(250.0, 0.0), at(100.0, 0.0)), maxHeight = 3.0)))
        // a parallel street 15 m away does not
        assertNull(m.touch(Restriction(null, listOf(at(100.0, 15.0), at(250.0, 15.0)), maxHeight = 3.0)))
        // a road crossing the route does not run along it…
        val crossingRoad = listOf(at(500.0, -40.0), at(500.0, 40.0))
        assertNull(m.touch(Restriction(null, crossingRoad, maxHeight = 3.0)))
        // …but a bridge over it is passed under
        assertEquals(500.0, m.touch(Restriction(null, crossingRoad, bridgeClearance = 3.0, crossing = true))!!.along, 1.0)
        // points: within a few metres
        assertEquals(700.0, m.touch(Restriction(null, listOf(at(700.0, 3.0)), maxHeight = 3.0))!!.along, 1.0)
        assertNull(m.touch(Restriction(null, listOf(at(700.0, 20.0)), maxHeight = 3.0)))
        assertEquals(14, m.pointIndex(700.0))
        assertEquals(1000.0, m.length, 1.0)
    }

    @Test
    fun nogosBecomeBRouterShapes() {
        val at = LatLon(52.37, 4.88)
        val line = Nogo.across(at, 90.0)
        assertEquals(5.0, Geo.distance(line.points[0], line.points[1]), 0.01)
        assertEquals(0.0, Geo.bearing(line.points[1], line.points[0]).let { if (it > 180) it - 360 else it }, 0.5)
        val poly = BRouterEngine.toBRouter(line) as OsmNogoPolygon
        assertFalse(poly.isClosed)
        assertEquals(2, poly.points.size)
        assertTrue(poly.radius >= 2.5)
        assertTrue(poly.nogoWeight.isNaN())
        assertTrue(poly.isNogo)
        val circle = BRouterEngine.toBRouter(Nogo.Circle(at, 30.0, weight = 500.0))
        assertEquals(30.0, circle.radius, 0.0)
        assertEquals(500.0, circle.nogoWeight, 0.0)
        assertEquals(((4.88 + 180) * 1e6 + 0.5).toInt(), circle.ilon)
        val area = BRouterEngine.toBRouter(Nogo.Area(listOf(at, Geo.destination(at, 0.0, 100.0), Geo.destination(at, 90.0, 100.0)))) as OsmNogoPolygon
        assertTrue(area.isClosed)
    }

    @Test
    fun aCanalBridgeLimitsBothTrucksAndBoats() {
        val bridge = listOf(LatLon(52.37, 4.88), LatLon(52.3702, 4.8803))
        val tags = mapOf("highway" to "residential", "bridge" to "yes", "maxweight" to "7.5", "seamark:bridge:clearance_height" to "2.4")
        val road = OverpassQl.roadRestriction(null, bridge, tags)!!
        val water = OverpassQl.waterRestriction(null, bridge, tags)!!
        assertEquals(7.5, road.maxWeight!!, 0.0)
        assertFalse(road.water || road.crossing)
        assertEquals(2.4, water.bridgeClearance!!, 0.0)
        assertTrue(water.water && water.crossing)
        // a plain road bridge says nothing to boats, a footbridge outline with a height does
        assertNull(OverpassQl.waterRestriction(null, bridge, mapOf("highway" to "primary", "bridge" to "yes", "maxheight" to "4")))
        assertEquals(3.1, OverpassQl.waterRestriction(null, bridge, mapOf("man_made" to "bridge", "bridge" to "yes", "maxheight" to "3.1"))!!.bridgeClearance!!, 0.0)
        assertNull(OverpassQl.roadRestriction(null, bridge, mapOf("waterway" to "canal", "maxheight" to "2.5")))
    }

    @Test
    fun aCanalBridgeIsKeptOnceForTrucksAndOnceForBoats() = runBlocking {
        val json = """
        {"elements":[{"type":"way","id":11,
          "tags":{"highway":"residential","bridge":"yes","maxweight":"7.5","seamark:bridge:clearance_height":"2.4"},
          "geometry":[{"lat":52.37,"lon":4.88},{"lat":52.3702,"lon":4.8803}]}]}
        """.trimIndent()
        val corridor = Corridor(listOf(LatLon(52.37, 4.88), LatLon(52.371, 4.881)))
        val found = OverpassRestrictionSource({ json }, OverpassRestrictionSource.Scope.BOTH).restrictionsAlong(corridor)
        // one OSM element, two limits: the weight on the bridge and the clearance under it
        assertEquals(2, found.size)
        assertTrue(found.all { it.id == OsmId(OsmType.WAY, 11) })
        assertEquals(7.5, found.single { !it.water }.maxWeight!!, 0.0)
        assertEquals(2.4, found.single { it.water }.bridgeClearance!!, 0.0)
    }

    @Test
    fun overpassRuntimeErrorsAreFailuresNotEmptyAnswers() = runBlocking {
        // a query that times out still answers HTTP 200, with no elements and a remark
        val timedOut = """{"version":0.6,"elements":[],"remark":"runtime error: Query timed out in \"query\" at line 3 after 91 seconds."}"""
        try {
            OverpassQl.parse(timedOut); fail("a timed-out query read as 'no restrictions'")
        } catch (e: java.io.IOException) {
            assertTrue(e.message!!.contains("timed out"))
        }
        try {
            OverpassQl.parse("<html><body>Too many requests</body></html>"); fail("an HTML page read as an answer")
        } catch (_: java.io.IOException) {
        }
        assertEquals(emptyList<Restriction>(), OverpassQl.parse("""{"elements":[],"remark":"a plain note"}"""))
        // the source asks again and takes the good answer
        val good = """{"elements":[{"type":"node","id":2,"lat":52.0,"lon":4.0,"tags":{"barrier":"height_restrictor","maxheight":"2.1"}}]}"""
        var calls = 0
        val source = OverpassRestrictionSource({ if (++calls == 1) timedOut else good }, retryDelayMillis = 1)
        assertEquals(1, source.restrictionsAlong(Corridor(listOf(LatLon(52.0, 4.0), LatLon(52.01, 4.0)))).size)
        assertEquals(2, calls)
    }

    /** A failure asking again cannot cure, as the app's sources say (Overpass switched off). */
    private class Refused : java.io.IOException("overpass is switched off: nothing was sent")

    @Test
    fun offlineOrRefusedTheLimitsComeAtOnceAsAFailureAndAStuckCheckEndsAtItsDeadline() = runBlocking {
        val corridor = Corridor(listOf(LatLon(52.0, 4.0), LatLon(52.01, 4.0)))
        var calls = 0
        fun failsWith(source: OverpassRestrictionSource): java.io.IOException? {
            val t0 = System.nanoTime()
            val e = runCatching { runBlocking { source.restrictionsAlong(corridor) } }.exceptionOrNull()
            assertTrue("took ${(System.nanoTime() - t0) / 1_000_000} ms", System.nanoTime() - t0 < 1_500_000_000L)
            return e as? java.io.IOException
        }
        // offline: nothing asked, no waits between tries; the router marks the route unchecked
        val offline = OverpassRestrictionSource({ calls++; "{}" }, retryDelayMillis = 2_000, mayAsk = { false })
        assertTrue(failsWith(offline)!!.message!!.contains("offline"))
        assertEquals(0, calls)
        // refused (switched off): asked once, not again two and four seconds later
        val refused = OverpassRestrictionSource({ calls++; throw Refused() }, retryDelayMillis = 2_000, retryable = { it !is Refused })
        assertTrue(failsWith(refused) is Refused)
        assertEquals(1, calls)
        // the connection lost between tries: not asked again
        var online = true
        val dropped = OverpassRestrictionSource({ calls++; online = false; throw java.io.IOException("unreachable") }, retryDelayMillis = 2_000, mayAsk = { online })
        assertEquals("unreachable", failsWith(dropped)!!.message)
        assertEquals(2, calls)
        // every server stuck: the whole check ends at its deadline, a failure, not the route's cancellation
        val stuck = OverpassRestrictionSource({ calls++; kotlinx.coroutines.delay(60_000); "{}" }, deadlineMillis = 300)
        assertTrue(failsWith(stuck)!!.message!!.contains("did not come"))
        assertEquals(3, calls)
    }

    @Test
    fun theCorridorCoversWhatSimplificationLeavesOut() {
        val f = LocalFrame(LatLon(52.0, 4.0))
        // out 3 km east and 1 km back: the far end lies on the line through the ends, yet 1 km beyond the last point
        val outAndBack = List(31) { f.toLatLon(it * 100.0, 0.0) } + List(10) { f.toLatLon(2900.0 - it * 100.0, 0.0) }
        val s = OverpassQl.simplified(outAndBack, 10.0, 400)
        assertEquals(3000.0, s.points.maxOf { f.x(it.lon) }, 1.0)
        // a line that wiggles 30 m every 50 m, thinned hard to fit the point limit: the query's corridor grows by the offset
        val wiggly = List(2000) { f.toLatLon(it * 50.0, if (it % 2 == 0) 0.0 else 30.0) }
        val z = OverpassQl.simplified(wiggly, 10.0, 400)
        assertTrue(z.points.size <= 400)
        val simple = app.atlas.geo.Polyline(z.points)
        for (p in wiggly) assertTrue(simple.snap(p)!!.offset <= z.maxOffset + 0.5)
        assertTrue(z.maxOffset >= 25.0)
        val q = OverpassQl.restrictions(Corridor(wiggly, 25.0), OverpassRestrictionSource.Scope.ROAD)
        val radius = Regex("""around:(\d+),""").find(q)!!.groupValues[1].toDouble()
        assertTrue("around:$radius for an offset of ${z.maxOffset}", radius >= 25.0 + z.maxOffset)
    }

    @Test
    fun longCorridorsAreAskedForInPieces() {
        val f = LocalFrame(LatLon(52.0, 4.0))
        val line = List(101) { f.toLatLon(it * 1000.0, 0.0) } // 100 km east
        val pieces = OverpassQl.pieces(line, 30_000.0, 50.0)
        assertEquals(4, pieces.size)
        for (p in pieces) assertTrue(app.atlas.geo.Polyline(p).length <= 25_000.0 + 101.0)
        assertEquals(line.first(), pieces.first().first())
        assertEquals(0.0, Geo.distance(line.last(), pieces.last().last()), 0.01)
        // consecutive pieces overlap
        assertTrue(f.x(pieces[0].last().lon) > f.x(pieces[1].first().lon))
        assertEquals(listOf(line.take(5)), OverpassQl.pieces(line.take(5), 30_000.0))
        // and the source merges what the pieces return, once per element
        val asked = java.util.concurrent.atomic.AtomicInteger()
        val json = """{"elements":[{"type":"node","id":2,"lat":52.0,"lon":4.0,"tags":{"barrier":"height_restrictor","maxheight":"2.1"}}]}"""
        val found = runBlocking { OverpassRestrictionSource({ asked.incrementAndGet(); json }, pieceMetres = 30_000.0).restrictionsAlong(Corridor(line)) }
        assertEquals(4, asked.get())
        assertEquals(1, found.size)
    }

    @Test
    fun busyServersAreAskedAgain() = runBlocking {
        val json = """{"elements":[{"type":"node","id":2,"lat":52.0,"lon":4.0,"tags":{"barrier":"height_restrictor","maxheight":"2.1"}}]}"""
        val corridor = Corridor(listOf(LatLon(52.0, 4.0), LatLon(52.01, 4.0)))
        var calls = 0
        val flaky = OverpassRestrictionSource({ if (++calls < 3) throw java.io.IOException("504") else json }, retryDelayMillis = 1)
        assertEquals(1, flaky.restrictionsAlong(corridor).size)
        assertEquals(3, calls)
        calls = 0
        val down = OverpassRestrictionSource({ calls++; throw java.io.IOException("504") }, attempts = 2, retryDelayMillis = 1)
        try {
            down.restrictionsAlong(corridor); fail("a dead server went unnoticed")
        } catch (e: java.io.IOException) {
            assertEquals("504", e.message)
        }
        assertEquals(2, calls)
    }

    @Test
    fun alternativesOverlap() {
        val a = List(50) { LatLon(52.0, 4.0 + it * 0.001) }
        assertEquals(1.0, Alternatives.overlap(a, a), 0.0)
        val far = a.map { LatLon(it.lat + 0.01, it.lon) }
        assertEquals(0.0, Alternatives.overlap(a, far), 0.0)
        val half = a.take(25)
        assertEquals(1.0, Alternatives.overlap(half, a), 0.0)
        assertEquals(0.5, Alternatives.overlap(a, half), 0.05)
        assertEquals(1.0, Alternatives.similarity(a, half), 0.0)
        // distinct keeps the order and drops near-duplicates of a route already kept
        fun route(points: List<LatLon>, name: String) =
            app.atlas.model.Route(Mode.CAR, points, distance = 1.0, duration = 1.0, steps = emptyList(), source = name)
        val kept = Alternatives.distinct(listOf(route(a, "best"), route(half, "part"), route(far, "far"), route(a, "same")))
        assertEquals(listOf("best", "far"), kept.map { it.source })
    }
}

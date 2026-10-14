package app.atlas.route

import app.atlas.geo.Geo
import app.atlas.geo.LatLon
import app.atlas.model.ManeuverType
import app.atlas.model.Mode
import app.atlas.model.OsmId
import app.atlas.model.OsmType
import app.atlas.model.Route
import app.atlas.model.RoutePrefs
import app.atlas.model.RouteRequest
import app.atlas.model.Turn
import app.atlas.model.Vehicle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

/** Routing real Amsterdam with BRouter and E0_N50.rd5 (skipped when the file is missing). */
class BRouterRealTest {

    companion object {
        private val BRUSSELS = LatLon(50.8467, 4.3525)

        @BeforeClass
        @JvmStatic
        fun data() = RealData.assumeAmsterdam()

        private fun timed(label: String, block: suspend () -> RouteResult): RouteResult = runBlocking {
            val t0 = System.nanoTime()
            val r = block()
            println("TIMING $label: ${(System.nanoTime() - t0) / 1_000_000} ms -> ${(r as? RouteResult.Found)?.best?.let { "%.0f m, %.0f s".format(it.distance, it.duration) } ?: r}")
            r
        }
    }

    private val router get() = RealData.router()

    private fun found(r: RouteResult): Route {
        assertTrue("expected a route, got $r", r is RouteResult.Found)
        return (r as RouteResult.Found).best
    }

    /** What every route must satisfy. */
    private fun checkShape(route: Route, from: LatLon, to: LatLon, endSlack: Double = 150.0) {
        assertTrue(route.points.size >= 2)
        assertTrue("starts ${Geo.distance(route.points.first(), from)} m away", Geo.distance(route.points.first(), from) < endSlack)
        assertTrue("ends ${Geo.distance(route.points.last(), to)} m away", Geo.distance(route.points.last(), to) < endSlack)
        assertEquals(ManeuverType.DEPART, route.steps.first().maneuver.type)
        assertEquals(ManeuverType.ARRIVE, route.steps.last().maneuver.type)
        assertEquals(route.distance, route.steps.sumOf { it.distance }, 1e-6)
        assertEquals(route.duration, route.steps.sumOf { it.duration }, 1e-6)
        assertTrue(route.steps.zipWithNext().all { (a, b) -> a.pointIndex < b.pointIndex })
        assertTrue(route.hazards.all { it.pointIndex in route.points.indices })
        assertTrue(route.spans.all { it.from < it.to && it.to < route.points.size })
        assertEquals(route.points.size, route.elevations.size)
    }

    @Test
    fun bikeFromTheWesterkerkToCentraal() {
        val route = found(timed("bike Westerkerk-Centraal") { router.route(RouteRequest(Mode.BIKE, listOf(RealData.WESTERKERK, RealData.CENTRAAL))) })
        checkShape(route, RealData.WESTERKERK, RealData.CENTRAAL)
        assertTrue("${route.distance} m", route.distance in 1500.0..2500.0)
        val kmh = route.distance / route.duration * 3.6
        assertTrue("$kmh km/h", kmh in 12.0..25.0)
        assertEquals("brouter:atlas-bike", route.source)
        val turns = route.steps.filter { it.maneuver.type == ManeuverType.TURN || it.maneuver.type == ManeuverType.ROUNDABOUT }
        assertTrue(turns.any { it.maneuver.turn.left } && turns.any { it.maneuver.turn.right })
        assertTrue(route.spans.any { it.key == "highway" && it.value == "cycleway" })
        assertTrue(route.spans.any { it.key == "surface" })
        assertTrue(route.climb < 20)
    }

    @Test
    fun walkTheSameWay() {
        val route = found(timed("walk Westerkerk-Centraal") { router.route(RouteRequest(Mode.WALK, listOf(RealData.WESTERKERK, RealData.CENTRAAL))) })
        checkShape(route, RealData.WESTERKERK, RealData.CENTRAAL)
        assertTrue("${route.distance} m", route.distance in 1300.0..2500.0)
        val kmh = route.distance / route.duration * 3.6
        assertTrue("$kmh km/h", kmh in 4.0..6.0)
        assertTrue(route.steps.count { it.maneuver.type == ManeuverType.TURN } >= 3)
        assertTrue(route.spans.none { it.key == "highway" && it.value in setOf("motorway", "trunk") })
        assertTrue(route.hazards.any { it.kind == HazardKind.CROSSING })
    }

    @Test
    fun wheelchairAvoidsSteps() {
        val route = found(timed("wheelchair Westerkerk-Centraal") { router.route(RouteRequest(Mode.WHEELCHAIR, listOf(RealData.WESTERKERK, RealData.CENTRAAL))) })
        checkShape(route, RealData.WESTERKERK, RealData.CENTRAAL, endSlack = 300.0)
        assertTrue(route.spans.none { it.key == "highway" && it.value == "steps" })
        assertTrue(route.hazards.none { it.kind == HazardKind.STEPS })
    }

    @Test
    fun carToHaarlem() {
        val route = found(timed("car Westerkerk-Haarlem") { router.route(RouteRequest(Mode.CAR, listOf(RealData.WESTERKERK, RealData.HAARLEM))) })
        checkShape(route, RealData.WESTERKERK, RealData.HAARLEM)
        assertTrue("${route.distance} m", route.distance in 18_000.0..26_000.0)
        assertTrue("${route.duration} s", route.duration in 900.0..3000.0)
        assertTrue(route.spans.any { it.key == "highway" && it.value in setOf("motorway", "trunk", "primary") })
        assertTrue(route.hazards.any { it.kind == HazardKind.SPEED_CHANGE })
        assertTrue(route.steps.any { it.maneuver.turn == Turn.RIGHT || it.maneuver.turn == Turn.SLIGHT_RIGHT })
    }

    @Test
    fun carAlternativesAreDistinct() {
        val r = timed("car Westerkerk-Haarlem, 3 alternatives") {
            router.route(RouteRequest(Mode.CAR, listOf(RealData.WESTERKERK, RealData.HAARLEM), alternatives = 3))
        } as RouteResult.Found
        println("alternatives: " + r.routes.map { "%.0f m".format(it.distance) })
        assertTrue(r.routes.size >= 2)
        for (i in r.routes.indices) for (j in i + 1 until r.routes.size) {
            assertTrue(Alternatives.similarity(r.routes[i].points, r.routes[j].points) <= BRouterRouter.MAX_OVERLAP)
        }
        assertTrue(r.routes.drop(1).all { it.distance in 15_000.0..40_000.0 })
    }

    @Test
    fun truckReroutesAroundALowBridge() = runBlocking {
        val req = RouteRequest(Mode.TRUCK, listOf(RealData.WESTERKERK, RealData.HAARLEM), vehicle = Vehicle(heightM = 4.2))
        val base = found(router.route(req))
        val car = found(router.route(req.copy(mode = Mode.CAR)))
        // a 4.0 m limit on the longest main-road piece in the middle of the car's route that the truck takes too
        val line = app.atlas.geo.Polyline(car.points)
        val main = car.spans.filter { it.key == "highway" && it.value in setOf("motorway", "trunk", "primary", "secondary") }
            .flatMap { it.from until it.to }.toSet()
        val truckLine = RouteMatcher(base.points)
        val seg = (car.points.indices.drop(1)).map { it - 1 }
            .filter { it in main && line.cumulative[it] in line.length * 0.3..line.length * 0.7 }
            .filter { truckLine.touch(Restriction(null, listOf(car.points[it], car.points[it + 1]), maxHeight = 4.0)) != null }
            .maxBy { line.cumulative[it + 1] - line.cumulative[it] }
        val low = Restriction(OsmId(OsmType.WAY, 4242), listOf(car.points[seg], car.points[seg + 1]), maxHeight = 4.0)
        println("truck test: a 4.0 m limit on %.0f m of %s at %s".format(line.cumulative[seg + 1] - line.cumulative[seg],
            car.spans.first { it.key == "highway" && seg in it.from until it.to }.value, car.points[seg]))
        val calls = AtomicInteger()
        val source = RestrictionSource { corridor ->
            calls.incrementAndGet()
            if (corridor.bbox.buffer(500.0).intersects(app.atlas.geo.BBox.of(low.geometry)!!)) listOf(low) else emptyList()
        }
        val checked = BRouterRouter(RealData.segments, RealData.profileDir(), restrictions = source)
        val t0 = System.nanoTime()
        val route = found(checked.route(req))
        println("TIMING truck with one re-route: ${(System.nanoTime() - t0) / 1_000_000} ms, ${calls.get()} restriction queries, %.0f m -> %.0f m".format(base.distance, route.distance))
        assertNotNull("the base route used the way", RouteMatcher(base.points).touch(low))
        assertNull("the re-route avoids it", RouteMatcher(route.points).touch(low))
        val avoided = route.hazards.single { it.kind == HazardKind.MAXHEIGHT }
        assertEquals("yes", avoided.tags["avoided"])
        assertEquals(4.0, avoided.value!!, 0.0)
        assertEquals("w4242", avoided.tags["osm"])
        assertTrue(calls.get() in 2..1 + BRouterRouter.MAX_VALIDATION_PASSES)
        checkShape(route, RealData.WESTERKERK, RealData.HAARLEM)

        // a lower truck passes under it, and is told about it
        val small = found(checked.route(req.copy(vehicle = Vehicle(heightM = 3.8))))
        assertNotNull(RouteMatcher(small.points).touch(low))
        val info = small.hazards.single { it.kind == HazardKind.MAXHEIGHT }
        assertNull(info.tags["avoided"])
        assertNull(info.tags["violated"])
    }

    @Test
    fun truckKeepsARouteWhenTheSourceFails() = runBlocking {
        val failing = BRouterRouter(RealData.segments, RealData.profileDir(), restrictions = { throw java.io.IOException("offline") })
        val route = found(failing.route(RouteRequest(Mode.TRUCK, listOf(RealData.WESTERKERK, RealData.HAARLEM))))
        assertEquals("offline", route.hazards.single { it.kind == HazardKind.UNCHECKED }.text)
    }

    @Test
    fun boatAlongTheCanalsToTheIJ() {
        val water = WaterRouter(router)
        val route = found(timed("boat Prinsengracht-IJ") { water.route(RouteRequest(Mode.BOAT, listOf(RealData.PRINSENGRACHT, RealData.IJ))) })
        checkShape(route, RealData.PRINSENGRACHT, RealData.IJ, endSlack = 300.0)
        assertTrue("${route.distance} m", route.distance in 1000.0..5000.0)
        assertTrue(route.spans.any { it.key == "waterway" && it.value == "canal" })
        assertTrue(route.spans.none { it.key == "highway" })
        assertEquals("brouter:atlas-boat", route.source)
        val kmh = (route.distance / (route.duration - route.steps.count { it.maneuver.type == ManeuverType.LOCK } * TrackConverter.LOCK_SECONDS)) * 3.6
        assertTrue("$kmh km/h", kmh in 5.0..13.0)
    }

    @Test
    fun aTallBoatGoesAroundALowBridge() = runBlocking {
        val req = RouteRequest(Mode.BOAT, listOf(RealData.PRINSENGRACHT, RealData.IJ), vehicle = Vehicle(airDraftM = 3.0))
        val base = found(WaterRouter(router).route(req))
        // a 2.4 m bridge across the canal halfway along
        val line = app.atlas.geo.Polyline(base.points)
        val mid = line.pointAt(line.length / 2)
        val across = line.bearingAt(line.length / 2) + 90
        val bridge = Restriction(
            OsmId(OsmType.WAY, 777), listOf(Geo.destination(mid, across, 20.0), Geo.destination(mid, across + 180, 20.0)),
            bridgeClearance = 2.4, crossing = true, water = true, tags = mapOf("name" to "Testbrug"),
        )
        val source = RestrictionSource { listOf(bridge) }
        val checked = WaterRouter(BRouterRouter(RealData.segments, RealData.profileDir(), restrictions = source))
        val t0 = System.nanoTime()
        val route = found(checked.route(req))
        println("TIMING boat around a low bridge: ${(System.nanoTime() - t0) / 1_000_000} ms, %.0f m -> %.0f m".format(base.distance, route.distance))
        assertNotNull(RouteMatcher(base.points).touch(bridge))
        assertNull("the re-route passes no longer under it", RouteMatcher(route.points).touch(bridge))
        val avoided = route.hazards.single { it.kind == HazardKind.BRIDGE }
        assertEquals("yes", avoided.tags["avoided"])
        assertEquals(2.4, avoided.value!!, 0.0)
        // a low motorboat passes under it and is told the clearance
        val low = found(checked.route(req.copy(vehicle = Vehicle(airDraftM = 1.8))))
        val info = low.hazards.single { it.kind == HazardKind.BRIDGE }
        assertNull(info.tags["avoided"])
        assertEquals("Testbrug", info.tags["name"])
        // road limits mean nothing to a boat
        val roadOnly = WaterRouter(BRouterRouter(RealData.segments, RealData.profileDir(), restrictions = { listOf(bridge.copy(water = false, bridgeClearance = null, maxHeight = 2.0)) }))
        assertTrue(found(roadOnly.route(req)).hazards.none { it.tags.containsKey("osm") })
    }

    @Test
    fun boatOutToTheNorthSea() {
        // along the IJ and the North Sea Canal, through the IJmuiden locks, then open water to a point 7 km off the coast
        val sea = LatLon(52.4800, 4.4500)
        val route = found(timed("boat IJ-North Sea") { WaterRouter(router).route(RouteRequest(Mode.BOAT, listOf(RealData.IJ, sea))) })
        println("boat to the sea: %.0f m, %.0f min; hazards %s".format(route.distance, route.duration / 60, route.hazards.groupingBy { it.kind }.eachCount()))
        checkShape(route, RealData.IJ, sea, endSlack = 150.0)
        assertTrue(Geo.distance(route.points.last(), sea) < 1)
        assertTrue("${route.distance} m", route.distance in 25_000.0..45_000.0)
        assertTrue(route.spans.any { it.key == "waterway" && it.value == "canal" })
        assertTrue(route.spans.any { it.key == "waterway" && it.value == "fairway" })
        // the open water is the last stretch, out from the end of the fairway
        val open = route.hazards.single { it.kind == HazardKind.OPEN_WATER }
        assertEquals(route.points.size - 1, open.tags["end"]!!.toInt())
        assertTrue(open.value!! in 3_000.0..10_000.0)
        // the IJmuiden locks are tagged lock=yes, which BRouter's lookups do not carry: only a restriction source sees them
        assertTrue(route.steps.none { it.maneuver.type == ManeuverType.LOCK })
    }

    @Test
    fun boatBetweenTwoPointsAtSea() {
        // both ends far out in the North Sea: no waterway helps, so a straight line
        val a = LatLon(52.60, 4.20)
        val b = LatLon(52.90, 4.30)
        val route = found(timed("boat at sea") { WaterRouter(router).route(RouteRequest(Mode.SAIL, listOf(a, b))) })
        assertTrue(Geo.distance(route.points.first(), a) < 1 && Geo.distance(route.points.last(), b) < 1)
        assertEquals(Geo.distance(a, b), route.distance, 50.0)
        val open = route.hazards.single { it.kind == HazardKind.OPEN_WATER }
        assertEquals(0, open.pointIndex)
        assertEquals(route.distance / (WaterRouter.SAIL_KMH / 3.6), route.duration, 1.0)
        assertEquals("atlas:open_water", route.source)
        assertTrue(route.hazards.none { it.kind == HazardKind.FERRY })
    }

    @Test
    fun missingDataIsReported() {
        val r = timed("car Amsterdam-Paris") { router.route(RouteRequest(Mode.CAR, listOf(RealData.WESTERKERK, LatLon(48.8566, 2.3522)))) }
        assertTrue("$r", r is RouteResult.NoData)
        assertEquals(listOf("E0_N45.rd5"), (r as RouteResult.NoData).missingTiles)
    }

    @Test
    fun invalidAndUnsupportedRequests() = runBlocking {
        assertTrue(router.route(RouteRequest(Mode.CAR, listOf(RealData.WESTERKERK))) is RouteResult.Invalid)
        assertTrue(router.route(RouteRequest(Mode.TRANSIT, listOf(RealData.WESTERKERK, RealData.CENTRAAL))) is RouteResult.Unsupported)
    }

    @Test
    fun aLongDriveAndItsTiming() {
        val route = found(timed("car Amsterdam-Brussels") { router.route(RouteRequest(Mode.CAR, listOf(RealData.WESTERKERK, BRUSSELS))) })
        assertTrue("${route.distance} m", route.distance in 190_000.0..240_000.0)
        assertTrue(route.spans.any { it.key == "highway" && it.value == "motorway" })
    }

    @Test
    fun cancellingTheCallerStopsTheEngine() = runBlocking {
        val job = async { router.route(RouteRequest(Mode.BIKE, listOf(RealData.WESTERKERK, BRUSSELS))) }
        delay(150)
        val t0 = System.nanoTime()
        job.cancel()
        try {
            job.await()
            assertTrue("finished before it could be cancelled", false)
        } catch (_: CancellationException) {
        }
        val ms = (System.nanoTime() - t0) / 1_000_000
        println("TIMING cancel: engine stopped $ms ms after cancel")
        assertTrue("$ms ms", ms < 3000)
    }

    @Test
    fun stoppingTheRouterReportsCancelled() = runBlocking {
        val own = BRouterRouter(RealData.segments, RealData.profileDir())
        own.profiles.install()
        val result = async { own.route(RouteRequest(Mode.BIKE, listOf(RealData.WESTERKERK, BRUSSELS))) }
        delay(500)
        own.cancelAll()
        assertEquals(RouteResult.Cancelled, result.await())
    }

    @Test
    fun stoppingWhileRestrictionsAreFetchedReportsCancelled() = runBlocking {
        // no engine runs while the source answers, yet the search is in progress and must end as cancelled
        lateinit var checked: BRouterRouter
        var cancel = true
        val source = RestrictionSource { corridor ->
            if (cancel) checked.cancelAll()
            listOf(Restriction(OsmId(OsmType.WAY, 1), corridor.line.subList(0, 2), maxHeight = 3.0))
        }
        checked = BRouterRouter(RealData.segments, RealData.profileDir(), restrictions = source)
        val req = RouteRequest(Mode.TRUCK, listOf(RealData.WESTERKERK, RealData.HAARLEM), vehicle = Vehicle(heightM = 4.2))
        assertEquals(RouteResult.Cancelled, checked.route(req))
        // a later search is not affected
        cancel = false
        assertTrue(checked.route(req) is RouteResult.Found)
    }

    @Test
    fun swappedPreferencesDoNotShareACachedProfile() = runBlocking {
        // BRouter caches a parsed profile under the sum of its key values' hash codes: these two requests have the same sum
        val own = BRouterRouter(RealData.segments, RealData.profileDir())
        val req = RouteRequest(Mode.CAR, listOf(RealData.WESTERKERK, RealData.HAARLEM))
        val noMotorways = found(own.route(req.copy(prefs = RoutePrefs(avoidMotorways = true))))
        val noTolls = found(own.route(req.copy(prefs = RoutePrefs(avoidTolls = true))))
        val plain = found(own.route(req))
        println("no motorways %.0f m, no tolls %.0f m, plain %.0f m".format(noMotorways.distance, noTolls.distance, plain.distance))
        assertTrue(noMotorways.spans.none { it.key == "highway" && it.value == "motorway" })
        // there is no toll on the way, so avoiding tolls is the plain route, motorway and all
        assertEquals(plain.distance, noTolls.distance, 1.0)
        assertTrue(noTolls.spans.any { it.key == "highway" && it.value == "motorway" })
    }

    @Test
    fun timeoutsAreTyped() {
        val hasty = BRouterRouter(RealData.segments, RealData.profileDir(), maxRunningMillis = 200)
        val r = timed("bike Amsterdam-Brussels with 200 ms") { hasty.route(RouteRequest(Mode.BIKE, listOf(RealData.WESTERKERK, BRUSSELS))) }
        assertTrue("$r", r is RouteResult.Timeout)
    }

    @Test
    fun streetNamesComeFromTheNamer() = runBlocking {
        val asked = AtomicInteger()
        val named = BRouterRouter(RealData.segments, RealData.profileDir(), namer = { at, _ -> asked.incrementAndGet(); "street near %.3f".format(at.lat) })
        val route = found(named.route(RouteRequest(Mode.BIKE, listOf(RealData.WESTERKERK, RealData.CENTRAAL))))
        assertEquals(route.steps.size, asked.get())
        assertTrue(route.steps.all { it.street!!.startsWith("street near 52.3") })
    }

    @Test
    fun startHeadingAndViaPoints() = runBlocking {
        val via = LatLon(52.3702, 4.8952) // Dam square
        val route = found(router.route(RouteRequest(Mode.BIKE, listOf(RealData.WESTERKERK, via, RealData.CENTRAAL), startHeading = 180.0)))
        val wp = route.steps.filter { it.maneuver.type == ManeuverType.WAYPOINT }
        assertEquals(1, wp.size)
        assertTrue(Geo.distance(wp[0].location, via) < 100)
        // the request is honoured from the very first metres: the start leaves southwards
        val first = route.steps.first().maneuver.bearingAfter!!
        assertTrue("leaves at $first°", kotlin.math.abs(Geo.turnAngle(first, 180.0)) < 100)
    }

    @Test
    fun everyModeRoutes() = runBlocking {
        for (mode in Mode.entries.filter { it != Mode.TRANSIT }) {
            val (from, to) = when {
                mode.water -> RealData.PRINSENGRACHT to RealData.IJ
                mode.motor -> RealData.WESTERKERK to RealData.HAARLEM
                else -> RealData.WESTERKERK to RealData.CENTRAAL
            }
            val t0 = System.nanoTime()
            val route = found(router.route(RouteRequest(mode, listOf(from, to))))
            println("TIMING $mode: ${(System.nanoTime() - t0) / 1_000_000} ms, %.0f m, %.0f s".format(route.distance, route.duration))
            assertTrue(route.source.endsWith(Profiles.forMode(mode)!!.name))
            assertFalse(route.steps.isEmpty())
        }
    }
}

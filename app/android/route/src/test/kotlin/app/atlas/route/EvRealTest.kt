package app.atlas.route

import app.atlas.geo.Geo
import app.atlas.geo.LatLon
import app.atlas.geo.Polyline
import app.atlas.model.EvSpec
import app.atlas.model.Mode
import app.atlas.model.Place
import app.atlas.model.Route
import app.atlas.model.RouteRequest
import app.atlas.model.Vehicle
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

/**
 * Charging stops on real roads: Amsterdam to Rotterdam along the A4 with a nearly empty battery, chargers placed beside
 * the motorway (skipped when E0_N50.rd5 is missing).
 */
class EvRealTest {

    companion object {
        private val ROTTERDAM = LatLon(51.9244, 4.4690)

        private lateinit var base: Route

        @BeforeClass
        @JvmStatic
        fun route() {
            RealData.assumeAmsterdam()
            val r = runBlocking { RealData.router().route(RouteRequest(Mode.CAR, listOf(RealData.WESTERKERK, ROTTERDAM))) }
            base = (r as RouteResult.Found).best
        }
    }

    private val car = EvSpec(batteryKwh = 50.0, consumption = 18.0, maxDcKw = 120.0, socNow = 0.2, socMin = 0.08)

    private fun charger(name: String, at: LatLon, vararg tags: Pair<String, String>) =
        Place(null, at, mapOf("amenity" to "charging_station", "name" to name) + tags, source = "test")

    /** A point [metres] along the base route, [side] metres to its right. */
    private fun beside(metres: Double, side: Double): LatLon {
        val line = Polyline(base.points)
        return Geo.destination(line.pointAt(metres), line.bearingAt(metres) + 90, side)
    }

    /** Fast chargers every 12 km beside the motorway, and some that do not fit. */
    private fun chargers(): List<Place> {
        val out = ArrayList<Place>()
        var k = 0
        var at = 12_000.0
        while (at < base.distance - 8_000) {
            out += charger("fast $k", beside(at, 120.0), "socket:type2_combo" to "4", "socket:type2_combo:output" to if (k % 2 == 0) "150 kW" else "50 kW")
            at += 12_000; k++
        }
        out += charger("chademo only", beside(20_000.0, 80.0), "socket:chademo" to "2", "socket:chademo:output" to "50 kW")
        out += charger("slow", beside(26_000.0, 80.0), "socket:type2" to "2", "socket:type2:output" to "11 kW")
        out += charger("far away", beside(30_000.0, 15_000.0), "socket:type2_combo" to "4", "socket:type2_combo:output" to "300 kW")
        return out
    }

    /** The chargers inside the corridor box, as a real source would answer. */
    private fun source(all: List<Place>, calls: AtomicInteger = AtomicInteger()) = ChargerSource { corridor, minKw, connectors ->
        calls.incrementAndGet()
        assertTrue(minKw > 0)
        assertTrue("type2_combo" in connectors)
        all.filter { it.location in corridor.bbox }
    }

    @Test
    fun theA4NeedsAStop() = runBlocking {
        println("A4 base route: %.0f m, %.0f s, %d points".format(base.distance, base.duration, base.points.size))
        assertTrue("${base.distance} m", base.distance in 65_000.0..90_000.0)
        assertTrue(base.spans.any { it.key == "highway" && it.value == "motorway" })
        val calls = AtomicInteger()
        val planner = EvPlanner(RealData.router(), source(chargers(), calls))
        val req = RouteRequest(Mode.CAR, listOf(RealData.WESTERKERK, ROTTERDAM), vehicle = Vehicle(ev = car))
        val t0 = System.nanoTime()
        val result = planner.plan(req)
        println("TIMING EV plan Amsterdam-Rotterdam: ${(System.nanoTime() - t0) / 1_000_000} ms")
        val plan = (result as EvPlanner.PlanResult.Ok).plan
        println("stops: " + plan.stops.joinToString { "%s %.0f kW %.2f→%.2f %.0f min".format(it.place.name(), it.powerKw, it.arriveSoc, it.departSoc, it.chargeSeconds / 60) } +
            ", arrive with %.2f".format(plan.arriveSoc))
        assertTrue(plan.feasible)
        assertEquals(1, calls.get())
        assertTrue(plan.stops.isNotEmpty())
        // only chargers that fit the car were chosen, and the trip never drops below the reserve
        assertTrue(plan.stops.all { it.place.name()!!.startsWith("fast") })
        assertTrue(plan.stops.all { it.arriveSoc >= car.socMin - 0.02 && it.departSoc > it.arriveSoc && it.departSoc <= 1.0 })
        assertTrue("arrives with ${plan.arriveSoc}", plan.arriveSoc >= car.socMin - 0.02)
        // the route goes through the chargers and its time includes the charging
        val route = plan.route
        for (s in plan.stops) {
            val d = route.points.minOf { Geo.distance(it, s.place.location) }
            assertTrue("${s.place.name()} is $d m off the route", d < 150)
        }
        assertEquals(plan.stops.map { it.place.location }, route.stops.map { it.location })
        assertTrue(route.stops.all { it.extra.containsKey("atlas:charge_minutes") && it.extra.containsKey("atlas:arrive_soc") })
        val charging = plan.stops.sumOf { it.chargeSeconds }
        assertEquals(route.steps.sumOf { it.duration } + charging, route.duration, 1.0)
        assertTrue(route.duration > base.duration + charging * 0.9)
        assertTrue(Geo.distance(route.points.last(), ROTTERDAM) < 200)
        assertEquals(plan.stops.size, route.steps.count { it.maneuver.type == app.atlas.model.ManeuverType.WAYPOINT })
    }

    @Test
    fun aFullBatteryNeedsNoStop() = runBlocking {
        val full = car.copy(socNow = 1.0)
        val calls = AtomicInteger()
        val plan = (EvPlanner(RealData.router(), source(chargers(), calls)).plan(
            RouteRequest(Mode.CAR, listOf(RealData.WESTERKERK, ROTTERDAM), vehicle = Vehicle(ev = full)),
        ) as EvPlanner.PlanResult.Ok).plan
        assertTrue(plan.stops.isEmpty())
        assertEquals(0, calls.get())
        // 50 kWh for about 75 km leaves most of it
        assertTrue("${plan.arriveSoc}", plan.arriveSoc in 0.6..0.9)
        // the same trip (the electric car's weight and regeneration may shift it a little)
        assertEquals(base.distance, plan.route.distance, base.distance * 0.1)
    }

    @Test
    fun noUsableChargerIsSaid() = runBlocking {
        val unusable = chargers().filter { !it.name()!!.startsWith("fast") }
        val plan = (EvPlanner(RealData.router(), source(unusable)).plan(
            RouteRequest(Mode.CAR, listOf(RealData.WESTERKERK, ROTTERDAM), vehicle = Vehicle(ev = car)),
        ) as EvPlanner.PlanResult.Ok).plan
        assertFalse(plan.feasible)
        val empty = plan.route.hazards.single { it.kind == HazardKind.BATTERY }
        assertEquals("no_chargers", empty.tags["reason"])
        // the battery runs low somewhere in the first 40 km
        assertTrue(Polyline(plan.route.points).cumulative[empty.pointIndex] < 40_000)
    }

    @Test
    fun theMultiRouterSendsElectricCarsToThePlanner() = runBlocking {
        val router = RealData.router()
        val multi = MultiRouter.standard(router, WaterRouter(router), EvPlanner(router, source(chargers())))
        val r = multi.route(RouteRequest(Mode.CAR, listOf(RealData.WESTERKERK, ROTTERDAM), vehicle = Vehicle(ev = car))) as RouteResult.Found
        assertTrue(r.best.stops.isNotEmpty())
        val plain = multi.route(RouteRequest(Mode.CAR, listOf(RealData.WESTERKERK, ROTTERDAM))) as RouteResult.Found
        assertTrue(plain.best.stops.isEmpty())
    }
}

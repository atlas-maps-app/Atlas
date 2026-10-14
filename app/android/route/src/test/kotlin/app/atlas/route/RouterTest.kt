package app.atlas.route

import app.atlas.geo.LatLon
import app.atlas.model.EvSpec
import app.atlas.model.Mode
import app.atlas.model.Route
import app.atlas.model.RouteRequest
import app.atlas.model.Vehicle
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** Dispatch by mode and the shape of results, without an engine. */
class RouterTest {

    private val a = LatLon(52.37, 4.88)
    private val b = LatLon(52.38, 4.90)

    /** A router that answers with a one-segment route named after itself and remembers what it was asked. */
    private class Named(val name: String) : Router {
        val asked = ArrayList<RouteRequest>()
        override suspend fun route(req: RouteRequest): RouteResult {
            asked += req
            return RouteResult.Found(listOf(Route(req.mode, req.waypoints, distance = 1.0, duration = 1.0, steps = emptyList(), source = name)))
        }
    }

    @Test
    fun standardSendsEachModeToItsRouter() = runBlocking {
        val land = Named("land")
        val water = Named("water")
        val multi = MultiRouter.standard(land, water)
        assertEquals(Mode.entries.toSet() - Mode.TRANSIT, multi.modes)
        for (mode in Mode.entries) {
            val r = multi.route(RouteRequest(mode, listOf(a, b)))
            when {
                mode == Mode.TRANSIT -> assertEquals(RouteResult.Unsupported(Mode.TRANSIT), r)
                mode.water -> assertEquals("water", r.routes!!.single().source)
                else -> assertEquals("land", r.routes!!.single().source)
            }
        }
        assertEquals(2, water.asked.size)
        assertEquals(Mode.entries.size - 3, land.asked.size)
    }

    @Test
    fun electricMotorVehiclesGoToThePlanner() = runBlocking {
        val land = Named("land")
        val ev = Named("ev")
        val multi = MultiRouter.standard(land, Named("water"), ev)
        val car = Vehicle(ev = EvSpec(batteryKwh = 60.0, consumption = 17.0))
        assertEquals("ev", multi.route(RouteRequest(Mode.CAR, listOf(a, b), vehicle = car)).routes!!.single().source)
        assertEquals("ev", multi.route(RouteRequest(Mode.TRUCK, listOf(a, b), vehicle = car)).routes!!.single().source)
        // an e-bike's battery is not planned for: the vehicle is only consulted for motor modes
        assertEquals("land", multi.route(RouteRequest(Mode.EBIKE, listOf(a, b), vehicle = car)).routes!!.single().source)
        assertEquals("land", multi.route(RouteRequest(Mode.CAR, listOf(a, b))).routes!!.single().source)
        // without a planner, electric cars route as any car
        assertEquals("land", MultiRouter.standard(land, Named("water")).route(RouteRequest(Mode.CAR, listOf(a, b), vehicle = car)).routes!!.single().source)
    }

    @Test
    fun aRouterCanBeALambda() = runBlocking {
        val refusing = Router { RouteResult.NoRoute("nothing here") }
        val multi = MultiRouter(mapOf(Mode.WALK to refusing))
        assertEquals(RouteResult.NoRoute("nothing here"), multi.route(RouteRequest(Mode.WALK, listOf(a, b))))
        assertTrue(multi.route(RouteRequest(Mode.CAR, listOf(a, b))) is RouteResult.Unsupported)
    }

    @Test
    fun resultsSayWhatHappened() {
        val one = Route(Mode.WALK, listOf(a, b), distance = 1.0, duration = 1.0, steps = emptyList())
        val two = one.copy(source = "other")
        val found = RouteResult.Found(listOf(one, two), millis = 12)
        assertSame(one, found.best)
        assertEquals(listOf(two), found.alternatives)
        assertEquals(listOf(one, two), found.routes)
        try {
            RouteResult.Found(emptyList()); fail("an empty result was accepted")
        } catch (_: IllegalArgumentException) {
        }
        assertNull((RouteResult.Cancelled as RouteResult).routes)
        assertEquals("no routing data for E0_N50.rd5, E5_N50.rd5", RouteResult.NoData(listOf("E0_N50.rd5", "E5_N50.rd5")).message)
        assertEquals("routing timed out after 60 s", RouteResult.Timeout(60_000).message)
        assertEquals("no router for TRANSIT", RouteResult.Unsupported(Mode.TRANSIT).message)
        assertEquals("routing cancelled", RouteResult.Cancelled.message)
    }
}

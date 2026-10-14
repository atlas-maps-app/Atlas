package app.atlas.ui.go

import app.atlas.geo.LatLon
import app.atlas.model.Mode
import app.atlas.model.RouteRequest
import app.atlas.route.RouteResult
import app.atlas.testing.FixtureTiles
import app.atlas.tiles.TileStack
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Street names from the real fixture tiles around the Westerkerk. */
class TileWayNamerTest {
    private val namer = TileWayNamer({ TileStack(listOf(FixtureTiles())) })

    @Test
    fun namesTheStreetAlongTheWayOfTravel() {
        // On the Prinsengracht by the Anne Frank House, heading north-north-east along the canal.
        assertEquals("Prinsengracht", namer.name(LatLon(52.37530, 4.88405), 15.0))
        // On the Raadhuisstraat between the Keizersgracht and the Herengracht, heading east.
        assertEquals("Raadhuisstraat", namer.name(LatLon(52.3738, 4.8860), 105.0))
    }

    @Test
    fun knowsNothingOutsideTheTiles() {
        assertNull(namer.name(LatLon(48.8584, 2.2945), 0.0))
        assertNull(TileWayNamer({ null }).name(LatLon(52.3744, 4.8842), 0.0))
    }

    @Test
    fun aRealRouteHasNamedSteps() {
        val router = GoFixtures.router
        assumeTrue("needs cache/brouter/segments4/E0_N50.rd5", router != null)
        val r = runBlocking { router!!.route(RouteRequest(Mode.BIKE, listOf(GoFixtures.WESTERKERK, GoFixtures.CENTRAAL))) }
        assertTrue("$r", r is RouteResult.Found)
        val steps = (r as RouteResult.Found).best.steps
        val named = steps.mapNotNull { it.street }
        // The fixture tiles cover the whole trip: every step carries its street.
        assertEquals(steps.size, named.size)
        assertEquals("Prinsengracht", steps.first().street)
    }

    @Test
    fun aBoatFollowsTheCanalsNames() {
        val router = GoFixtures.router
        assumeTrue("needs cache/brouter/segments4/E0_N50.rd5", router != null)
        val r = runBlocking { app.atlas.route.WaterRouter(router!!).route(RouteRequest(Mode.BOAT, listOf(GoFixtures.WESTERKERK, GoFixtures.IJ))) }
        val streets = (r as RouteResult.Found).best.steps.mapNotNull { it.street }
        // Canals, not the quays' or the bridges' names.
        assertTrue("$streets", streets.containsAll(listOf("Prinsengracht", "Singel", "IJ")))
        assertTrue("$streets", streets.none { it.endsWith("brug") || it.endsWith("veer") })
    }
}

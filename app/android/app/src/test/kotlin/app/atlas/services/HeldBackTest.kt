package app.atlas.services

import app.atlas.Services
import app.atlas.geo.LatLon
import app.atlas.model.Place
import app.atlas.providers.Mangrove
import app.atlas.providers.OsmChargers
import app.atlas.providers.Overpass
import app.atlas.providers.ProviderState
import app.atlas.providers.SwitchedOffException
import app.atlas.route.Corridor
import app.atlas.testing.testServices
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.net.InetAddress
import java.time.Clock

/**
 * A caller that reads a source's switch and so never builds its request is counted as held back, once, under that
 * source ([Services.heldBack]), as the gate counts a request it refuses: the Privacy screen says so. The map's windows
 * with Overpass off; the chargers a plan leaves out by their own switch; the area ratings with Mangrove off, and not
 * where the ratings kept from within the hour would have answered without a request.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class HeldBackTest {
    private suspend fun Services.switch(off: Set<String>) {
        settings.set(SettingsKeys.PROVIDERS_OFF, off.takeIf { it.isNotEmpty() })
        withTimeout(5_000) { while (sourcesOff() != off) delay(10) }
    }

    private val amsterdam = doubleArrayOf(4.88, 52.37, 4.89, 52.38)

    @Test
    fun aWindowAskedForWithOverpassOffIsHeldBackOnceAsOverpasss() = runBlocking {
        val services = testServices()
        try {
            services.switch(setOf(Overpass.INFO.id))
            val data = MapData(services)
            val file = File.createTempFile("window", ".json").apply { deleteOnExit() }
            val window = runCatching { data.windowData(amsterdam, file) }.exceptionOrNull()
            assertTrue("$window", window is SwitchedOffException && window.source == Overpass.INFO.id)
            assertEquals(1L, services.asked.of(Overpass.INFO.id).refused)
            val diff = runCatching { data.windowDiff(amsterdam, "2026-10-12T00:00:00Z", "2026-10-12T00:00:00Z") }.exceptionOrNull()
            assertTrue("$diff", diff is SwitchedOffException)
            assertEquals("a window's diff once more", 2L, services.asked.of(Overpass.INFO.id).refused)
            assertEquals(0L, services.asked.of(Overpass.INFO.id).sent)
        } finally {
            services.close()
        }
    }

    @Test
    fun theChargersOwnSwitchHoldsThemBackInAPlanAsTheChargers() = runBlocking {
        val services = testServices()
        try {
            val ready = services.chargers.filter { it.state() == ProviderState.READY }.map { it.id }
            assertTrue(ready.toString(), OsmChargers.INFO.id in ready)
            services.switch(ready.toSet())
            val found = services.chargerSource.chargersNear(Corridor(listOf(LatLon(52.37, 4.88), LatLon(52.38, 4.90)), 2_000.0), 50.0, emptySet())
            assertEquals(emptyList<Place>(), found)
            for (id in ready) assertEquals(id, 1L, services.asked.of(id).refused)
            assertEquals("Overpass was not what held them", 0L, services.asked.of(Overpass.INFO.id).refused)
            assertTrue("nothing was asked: ${services.asked.all()}", services.asked.all().values.all { it.sent == 0L })
        } finally {
            services.close()
        }
    }

    @Test
    fun theAreaRatingsWithMangroveOffAreHeldBackWhereTheyWouldHaveAsked() = runBlocking {
        MockWebServer().use { server ->
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest) = MockResponse.Builder().code(200).body("""{"reviews":[]}""").build()
            }
            server.start(InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1)), 0)
            val services = testServices()
            try {
                val mangrove = Mangrove(services.http(Mangrove.ID), base = "http://127.0.0.1:${server.port}")
                var online = true
                val ratings = AreaRatings(
                    mangrove, Clock.systemUTC(), online = { online },
                    off = { Mangrove.ID in services.sourcesOff() }, heldBack = { services.asked.refused(Mangrove.ID) },
                )
                val here = listOf(Place(null, LatLon(52.3744, 4.8839), mapOf("name" to "Here")))
                val there = listOf(Place(null, LatLon(51.9244, 4.4777), mapOf("name" to "There")))
                ratings.rate(here)
                assertEquals("asked once while on", 1, server.requestCount)
                services.switch(setOf(Mangrove.ID))
                ratings.rate(here)
                assertEquals("the hour's kept reviews would have answered: nothing held back", 0L, services.asked.of(Mangrove.ID).refused)
                ratings.rate(there)
                assertEquals("a stretch it would have asked: held back once", 1L, services.asked.of(Mangrove.ID).refused)
                online = false
                ratings.rate(there)
                assertEquals("offline it asks nothing, switch or no switch", 1L, services.asked.of(Mangrove.ID).refused)
                assertEquals(1, server.requestCount)
            } finally {
                services.close()
            }
        }
    }
}

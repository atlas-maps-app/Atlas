package app.atlas.services

import app.atlas.Services
import app.atlas.geo.LatLon
import app.atlas.providers.OsmChargers
import app.atlas.providers.SwitchedOffException
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
import okhttp3.HttpUrl.Companion.toHttpUrl
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ProxySelector
import java.net.SocketAddress
import java.net.URI

/**
 * The chargers asked of Overpass's servers through `Services` ([Services.chargers]): their queries are the chargers', so
 * the Privacy screen's "OpenStreetMap chargers" row counts them, and Overpass's own count is unchanged; the servers, their
 * turns and the gate are Overpass's, so with Overpass off they are held back as Overpass's, as before.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ChargersAskedTest {
    private suspend fun Services.switch(off: Set<String>) {
        settings.set(SettingsKeys.PROVIDERS_OFF, off.takeIf { it.isNotEmpty() })
        withTimeout(5_000) { while (sourcesOff() != off) delay(10) }
    }

    private fun Services.osmChargers() = chargers.first { it is OsmChargers }

    private val westerkerk = LatLon(52.3744, 4.8839)

    /**
     * Overpass's first server by its own name, as the app lists it, so the source that lists the host decides the count
     * (both Overpass and the chargers list it): its plain-http address, sent through the system's proxy, which this
     * test makes the mock. The proxy is an address written as numbers, so no name is looked up.
     */
    @Test
    fun theChargersQueriesAreCountedAsTheChargers() = runBlocking {
        MockWebServer().use { server ->
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest) = MockResponse.Builder().code(200).body("""{"version":0.6,"elements":[]}""").build()
            }
            val loopback = InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1))
            server.start(loopback, 0)
            val proxy = Proxy(Proxy.Type.HTTP, InetSocketAddress(loopback, server.port))
            val system = ProxySelector.getDefault()
            ProxySelector.setDefault(object : ProxySelector() {
                override fun select(uri: URI): List<Proxy> = listOf(proxy)
                override fun connectFailed(uri: URI, sa: SocketAddress, ioe: IOException) = Unit
            })
            val host = app.atlas.providers.Overpass.ENDPOINTS.first().toHttpUrl().host
            val services = testServices(overpass = listOf("http://$host/api/interpreter"))
            try {
                services.osmChargers().near(westerkerk, 500.0)
                assertEquals(1, server.requestCount)
                assertEquals(host, server.takeRequest().headers["Host"])
                val chargers = services.asked.of(OsmChargers.INFO.id)
                assertEquals(1L, chargers.sent)
                assertEquals("a host the chargers list is none beyond", emptySet<String>(), chargers.hosts)
                assertEquals(0L, services.asked.of(app.atlas.providers.Overpass.INFO.id).sent)
                // Overpass's own queries stay Overpass's
                services.overpass.raw("[out:json];node(1);out;")
                assertEquals(1L, services.asked.of(app.atlas.providers.Overpass.INFO.id).sent)
                assertEquals(1L, services.asked.of(OsmChargers.INFO.id).sent)
            } finally {
                ProxySelector.setDefault(system)
                services.close()
            }
        }
    }

    @Test
    fun withOverpassOffTheChargersAreHeldBackAsOverpasss() = runBlocking {
        // the public servers, as the app asks them: refused before any name is looked up
        val services = testServices()
        try {
            services.switch(setOf(app.atlas.providers.Overpass.INFO.id))
            val refused = runCatching { services.osmChargers().near(westerkerk, 500.0) }.exceptionOrNull()
            assertTrue("$refused", refused is SwitchedOffException && refused.source == app.atlas.providers.Overpass.INFO.id)
            assertEquals(1L, services.asked.of(app.atlas.providers.Overpass.INFO.id).refused)
            assertEquals(0L, services.asked.of(OsmChargers.INFO.id).refused)
        } finally {
            services.close()
        }
    }
}

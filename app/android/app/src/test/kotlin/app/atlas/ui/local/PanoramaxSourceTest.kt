package app.atlas.ui.local

import app.atlas.Services
import app.atlas.geo.LatLon
import app.atlas.services.SettingsKeys
import app.atlas.testing.testServices
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.net.InetAddress
import java.util.Collections

/**
 * Panoramax's street photo, as [PanoramaxSource] asks it through `Services`' own client: the search, then the picture
 * from the instance that keeps it, a host no list names, which Panoramax's view alone holds to Panoramax's switch.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PanoramaxSourceTest {
    private suspend fun Services.switch(off: Set<String>) {
        settings.set(SettingsKeys.PROVIDERS_OFF, off.takeIf { it.isNotEmpty() })
        withTimeout(5_000) { while (sourcesOff() != off) delay(10) }
    }

    @Test
    fun thePictureComesFromAnInstanceNoListNamesAndPanoramaxsSwitchAloneHoldsIt() = runBlocking {
        val services = testServices()
        try {
            MockWebServer().use { server ->
                val asked = Collections.synchronizedList(ArrayList<String>())
                server.dispatcher = object : Dispatcher() {
                    override fun dispatch(request: RecordedRequest): MockResponse {
                        asked += request.url.encodedPath
                        return if (request.url.encodedPath == "/api/search") MockResponse.Builder().code(200).body(
                            """{"type":"FeatureCollection","features":[{"id":"p1","geometry":{"type":"Point","coordinates":[4.8839,52.3744]},""" +
                                """"properties":{"datetime":"2023-02-06T10:00:00Z"},"assets":{"sd":{"href":"http://127.0.0.1:${server.port}/pic.jpg"}}}]}""",
                        ).build()
                        else MockResponse.Builder().code(200).body("not a picture").build()
                    }
                }
                server.start(InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1)), 0)
                services.local.onlineOverride = true
                val source = PanoramaxSource(services) { "http://127.0.0.1:${server.port}/api/search" }
                val at = LatLon(52.3744, 4.8839)
                // Panoramax on, Open Prices off: the search and the picture both go (the picture's bytes are no image here)
                services.switch(setOf("open-prices"))
                source.near(at)
                assertEquals(listOf("/api/search", "/pic.jpg"), asked.toList())
                // Panoramax off: nothing is asked
                services.switch(setOf("panoramax"))
                assertNull(source.near(at))
                assertEquals(2, server.requestCount)
            }
        } finally {
            services.close()
        }
    }
}

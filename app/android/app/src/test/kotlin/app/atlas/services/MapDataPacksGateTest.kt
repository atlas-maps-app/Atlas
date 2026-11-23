package app.atlas.services

import app.atlas.Services
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
import java.io.File
import java.net.InetAddress

/**
 * The map's packs ([MapData.packs]) are asked through Atlas packs' view, so Atlas packs' switch holds them and the Privacy
 * screen counts them as Atlas packs': a pack's download from a host no list names (a mirror; here a mock on this machine,
 * named in the packs' list kept on the phone) goes with Overpass off and is held back with Atlas packs off.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MapDataPacksGateTest {
    private suspend fun Services.switch(off: Set<String>) {
        settings.set(SettingsKeys.PROVIDERS_OFF, off.takeIf { it.isNotEmpty() })
        withTimeout(5_000) { while (sourcesOff() != off) delay(10) }
    }

    @Test
    fun aPacksDownloadIsAtlasPacksNotOverpasss() = runBlocking {
        val services = testServices()
        try {
            MockWebServer().use { server ->
                server.dispatcher = object : Dispatcher() {
                    override fun dispatch(request: RecordedRequest) = MockResponse.Builder().code(200).body("not a pack").build()
                }
                server.start(InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1)), 0)
                // the packs' list as the phone keeps it, with one pack at the mock
                File(services.dirs.files, "maps").apply { mkdirs() }.resolve("packs.json").writeText(
                    """{"format":1,"packs":[{"id":"mirror-test","name":"Mirror","bbox":[4.8,52.3,4.9,52.4],""" +
                        """"files":[{"kind":"atlas","url":"http://127.0.0.1:${server.port}/mirror-test.atlas"}]}]}""",
                )
                val packs = MapData(services).packs
                val entry = packs.entry("mirror-test")!!
                // Overpass off, Atlas packs on: the download is sent, and counted as Atlas packs'
                services.switch(setOf("overpass"))
                val went = runCatching { packs.download(entry) }.exceptionOrNull()
                assertTrue("not held back: $went", went !is SwitchedOffException)
                assertEquals(1, server.requestCount)
                assertEquals(1L, services.asked.of("atlas-packs").sent)
                assertEquals(setOf("127.0.0.1"), services.asked.of("atlas-packs").hosts)
                assertEquals(0L, services.asked.of("overpass").sent)
                // Atlas packs off: held back as Atlas packs', and nothing sent
                services.switch(setOf("atlas-packs"))
                val held = runCatching { packs.download(entry) }.exceptionOrNull()
                assertTrue("$held", held is SwitchedOffException && held.source == "atlas-packs")
                assertEquals(1, server.requestCount)
                assertEquals(1L, services.asked.of("atlas-packs").refused)
            }
        } finally {
            services.close()
        }
    }
}

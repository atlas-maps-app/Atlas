package app.atlas.services

import app.atlas.Services
import app.atlas.providers.Http
import app.atlas.providers.Overpass
import app.atlas.render.data.PackBoxes
import app.atlas.render.data.PackFeatures
import app.atlas.render.data.PackReader
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The bundled Westerkerk pack first (every tag); where the circle reaches past it, Overpass for the whole of it; a failing
 * Overpass keeps the pack's answer and says why. Where the pack holds the whole circle, nothing is asked online.
 */
class PlaceFinderOnlineTest {
    private val server = MockWebServer()
    private val repo = java.io.File(System.getProperty("atlas.repo") ?: "../..")
    private val pack by lazy { PackFeatures(listOf(PackBoxes(PackReader.open(java.io.File(repo, "app/android/app/src/main/assets/packs/amsterdam.atlas"))))) }

    @Before
    fun start() = server.start()

    @After
    fun stop() = server.close()

    private fun finder() = LayeredPlaceFinder(
        local = { LocalData(stores = { null }, packs = { pack }) },
        overpass = { Overpass(Http(), endpoints = listOf(server.url("/api/interpreter").toString())) },
        online = { true },
    )

    @Test
    fun theOnlineAnswerFollowsTheOfflineOne() = runBlocking {
        server.enqueue(
            MockResponse.Builder().code(200).addHeader("Content-Type", "application/json").body(
                """{"version":0.6,"elements":[
                  {"type":"node","id":42,"lat":52.3746,"lon":4.8840,"tags":{"amenity":"cafe","name":"Test Café","opening_hours":"Mo-Su 08:00-18:00"}},
                  {"type":"node","id":43,"lat":52.3750,"lon":4.8850,"tags":{"amenity":"bench"}}
                ]}""",
            ).build(),
        )
        // 500 m reaches past the pack (390 m south of the church): the pack answers first, then Overpass for the whole circle
        val answers = finder().nearby("eat", Services.WESTERKERK, 500.0, 20).toList()
        assertEquals(listOf(PlaceOrigin.PACK, PlaceOrigin.ONLINE), answers.map { it.source })
        assertTrue(answers[0].places.any { "opening_hours" in it.tags })
        val online = answers[1].places
        assertEquals(listOf("Test Café"), online.map { it.name() })
        assertEquals("Mo-Su 08:00-18:00", online[0].tags["opening_hours"])
        val sent = server.takeRequest().body?.utf8() ?: ""
        assertTrue(sent, sent.contains("around") && sent.contains("restaurant"))
    }

    @Test
    fun aFailingServerIsReportedNotThrown() = runBlocking {
        repeat(3) { server.enqueue(MockResponse.Builder().code(400).body("bad query").build()) }
        val answers = finder().nearby("eat", Services.WESTERKERK, 500.0, 20).toList()
        assertEquals(PlaceOrigin.PACK, answers[0].source)
        assertTrue(answers[0].places.isNotEmpty())
        assertTrue(answers.last().error != null)
    }

    @Test
    fun aCaptivePortalPageIsReportedNotThrown() = runBlocking {
        // Hotel Wi-Fi answers every request with its login page, status 200.
        repeat(3) { server.enqueue(MockResponse.Builder().code(200).addHeader("Content-Type", "text/html").body("<html><body>Log in to continue</body></html>").build()) }
        val answers = finder().nearby("eat", Services.WESTERKERK, 500.0, 20).toList()
        assertEquals(PlaceOrigin.PACK, answers[0].source)
        assertTrue(answers[0].places.isNotEmpty())
        assertTrue(answers.last().error != null)
    }

    @Test
    fun aPackAroundTheWholeCircleAnswersAloneWithEveryTag() = runBlocking {
        // 300 m around the church is inside the pack: no request at all
        val answers = finder().nearby("eat", Services.WESTERKERK, 300.0, 20).toList()
        assertEquals(listOf(PlaceOrigin.PACK), answers.map { it.source })
        assertEquals(20, answers[0].places.size)
        assertTrue(answers[0].places.count { "opening_hours" in it.tags } >= 5)
        assertEquals(0, server.requestCount)
    }
}

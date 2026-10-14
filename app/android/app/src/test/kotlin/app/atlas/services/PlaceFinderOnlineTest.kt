package app.atlas.services

import app.atlas.Services
import app.atlas.providers.Http
import app.atlas.providers.Overpass
import app.atlas.testing.FixtureTiles
import app.atlas.tiles.TileStack
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** Tiles first, then Overpass with every tag; a failing Overpass keeps the tiles' answer and says why. */
class PlaceFinderOnlineTest {
    private val server = MockWebServer()

    @Before
    fun start() = server.start()

    @After
    fun stop() = server.close()

    private fun finder() = LayeredPlaceFinder(
        tiles = { TileStack(listOf(FixtureTiles())) },
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
        val answers = finder().nearby("eat", Services.WESTERKERK, 300.0, 20).toList()
        assertEquals(listOf(PlaceOrigin.TILES, PlaceOrigin.ONLINE), answers.map { it.source })
        val online = answers[1].places
        assertEquals(listOf("Test Café"), online.map { it.name() })
        assertEquals("Mo-Su 08:00-18:00", online[0].tags["opening_hours"])
        val sent = server.takeRequest().body?.utf8() ?: ""
        assertTrue(sent, sent.contains("around") && sent.contains("restaurant"))
    }

    @Test
    fun aFailingServerIsReportedNotThrown() = runBlocking {
        repeat(3) { server.enqueue(MockResponse.Builder().code(400).body("bad query").build()) }
        val answers = finder().nearby("eat", Services.WESTERKERK, 300.0, 20).toList()
        assertEquals(PlaceOrigin.TILES, answers[0].source)
        assertTrue(answers[0].places.isNotEmpty())
        assertTrue(answers.last().error != null)
    }

    @Test
    fun aCaptivePortalPageIsReportedNotThrown() = runBlocking {
        // Hotel Wi-Fi answers every request with its login page, status 200.
        repeat(3) { server.enqueue(MockResponse.Builder().code(200).addHeader("Content-Type", "text/html").body("<html><body>Log in to continue</body></html>").build()) }
        val answers = finder().nearby("eat", Services.WESTERKERK, 300.0, 20).toList()
        assertEquals(PlaceOrigin.TILES, answers[0].source)
        assertTrue(answers[0].places.isNotEmpty())
        assertTrue(answers.last().error != null)
    }
}

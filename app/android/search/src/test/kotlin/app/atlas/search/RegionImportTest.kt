package app.atlas.search

import app.atlas.geo.BBox
import app.atlas.providers.Http
import app.atlas.providers.Overpass
import kotlinx.coroutines.runBlocking
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.URLDecoder
import java.util.concurrent.atomic.AtomicInteger

class RegionImportTest {
    @Test
    fun theQueryAsksForPlacesAddressesAndNamedFeatures() {
        val ql = RegionImport.ql(BBox(4.87, 52.37, 4.90, 52.38))
        assertTrue(ql.startsWith("[out:json][timeout:180][bbox:52.370000,4.870000,52.380000,4.900000];"))
        for (part in listOf("nwr[\"amenity\"];", "nwr[\"shop\"];", "nwr[\"healthcare\"];", "nwr[\"public_transport\"];", "node[\"highway\"=\"bus_stop\"];", "nwr[\"addr:housenumber\"];", "nwr[\"name\"];")) {
            assertTrue(part, ql.contains(part))
        }
        assertTrue(ql.contains("nwr[\"natural\"~\"^(peak|spring|tree|"))
        assertTrue(ql.contains("nwr[\"railway\"~\"^(station|halt|tram_stop|"))
        assertTrue(ql.endsWith("out tags bb qt;"))
        assertTrue(RegionImport.ql(BBox(4.87, 52.37, 4.90, 52.38), withEditTimes = true).endsWith("out meta bb qt;"))
    }

    @Test
    fun bigRegionsAreCutIntoTiles() {
        assertEquals(1, RegionImport.tiles(BBox(4.87, 52.37, 4.90, 52.38)).size)
        val tiles = RegionImport.tiles(BBox(4.70, 52.28, 5.07, 52.43))
        assertEquals(8 * 3, tiles.size)
        val area = tiles.sumOf { it.width * it.height }
        assertEquals(0.37 * 0.15, area, 1e-9)
        assertTrue(tiles.all { it.width <= 0.05 + 1e-9 && it.height <= 0.05 + 1e-9 })
    }

    @Test
    fun importsTileByTileRetriesAFailureAndReportsProgress() = runBlocking {
        val body = Fixtures.eat.readText()
        val calls = AtomicInteger()
        val queries = ArrayList<String>()
        MockWebServer().use { server ->
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    synchronized(queries) { queries += URLDecoder.decode(request.body?.utf8()?.removePrefix("data=") ?: "", "UTF-8") }
                    // the very first answer is a busy server
                    return if (calls.getAndIncrement() == 0) MockResponse.Builder().code(504).body("busy").build()
                    else MockResponse.Builder().code(200).body(body).build()
                }
            }
            server.start()
            val overpass = Overpass(Http(policies = emptyMap()), listOf(server.url("/api/interpreter").toString()))
            val store = PlaceStore.inMemory()
            val progress = ArrayList<RegionImport.Progress>()
            val stats = RegionImport(overpass, tileDegrees = 0.05).import(BBox(4.85, 52.36, 4.95, 52.39), store, "test") { progress += it }
            assertEquals(2, progress.last().tiles)
            assertEquals(listOf(0, 1, 2), progress.map { it.tilesDone })
            assertEquals(3, calls.get()) // one failure, retried, then the second tile
            // the same answer twice leaves one copy of every place
            assertEquals(1925, store.size)
            assertEquals(1925 * 2L, stats.elements)
            assertEquals("test", store.name)
            assertTrue(queries.all { it.contains("[bbox:") })
            assertTrue(store.text("pancakes").isNotEmpty())
            store.close()
        }
    }
}

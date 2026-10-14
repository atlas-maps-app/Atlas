package app.atlas.search

import app.atlas.providers.Http
import app.atlas.providers.Overpass
import app.atlas.providers.OverpassArea
import app.atlas.providers.Photon
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Assume
import org.junit.Before
import org.junit.Test

/** Calls the real Overpass and Photon: run with -Datlas.online=true. */
class OnlineTest {
    @Before
    fun online() = Assume.assumeTrue("online tests run with -Datlas.online=true", System.getProperty("atlas.online") == "true")

    @Test
    fun aCompiledNearQueryRunsOnOverpass() = runBlocking {
        val query = QueryParser.parse("amenity=cafe near amenity=bicycle_rental 200m").query
        val area = OverpassArea.Around(Fixtures.westerkerk, 700.0)
        val answer = OnlineSearch(Overpass(Http())).run(query, area, Fixtures.context())
        println(answer.ql)
        println("online: ${answer.places.size} places, ${answer.result.total} hits: " + answer.result.hits.take(5).map { "${it.record.name()} ${it.near.map { n -> n.distance.toInt() }}" })
        assertTrue(answer.places.any { it.tags["amenity"] == "bicycle_rental" })
        assertTrue(answer.result.hits.isNotEmpty())
        for (h in answer.result.hits) {
            assertTrue(h.tags["amenity"] == "cafe")
            assertTrue(h.near.single().distance <= 200)
        }
    }

    @Test
    fun theEngineFallsBackToOverpassAndPhoton() = runBlocking {
        val empty = PlaceStore.inMemory()
        val engine = SearchEngine(empty, geocoder = Photon(Http()), overpass = Overpass(Http()))
        val ctx = SearchContext(location = Fixtures.westerkerk, online = true, now = Fixtures.saturday)
        val kerk = engine.search("Westerkerk Amsterdam", ctx)
        println("photon: " + kerk.items.take(3))
        assertTrue(kerk.usedOnline)
        assertTrue(kerk.items.any { it is SearchItem.Online && it.place.tags["name"]?.contains("Westerkerk") == true })
        val toilets = engine.search("toilets wheelchair=yes around me", ctx)
        println("overpass: " + toilets.items.take(3))
        assertTrue(toilets.usedOnline)
        assertTrue(toilets.places.isNotEmpty())
        empty.close()
    }
}

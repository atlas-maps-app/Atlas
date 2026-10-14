package app.atlas.search

import app.atlas.geo.BBox
import app.atlas.model.OsmId
import app.atlas.model.OsmType
import app.atlas.places.TagFilter
import app.atlas.providers.Http
import app.atlas.providers.Overpass
import app.atlas.providers.OverpassArea
import app.atlas.providers.OverpassParser
import app.atlas.providers.Photon
import kotlinx.coroutines.runBlocking
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.StringReader
import java.net.URLDecoder

/** The online paths against local servers: Overpass answering a compiled query, Photon answering the search box. */
class OnlineMockTest {
    private val eat by lazy { Fixtures.eat.readText() }

    private fun server(answer: (RecordedRequest) -> MockResponse) = MockWebServer().apply {
        dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest) = answer(request)
        }
        start()
    }

    @Test
    fun memoryPlacesAnswerLikeAStore() {
        val places = OverpassParser.parse(StringReader(eat)).places()
        val mem = MemoryPlaces(places)
        assertEquals(1925, mem.size)
        val store = PlaceStore.inMemory().also { it.importOverpass(Fixtures.eat) }
        val ctx = Fixtures.context().copy(limit = 2000)
        for (q in listOf("vegan restaurant open now", "cafe with terrace", "pizza", "amenity=bar or amenity=pub")) {
            val query = QueryParser.parse(q).query
            val a = OfflineEvaluator(mem).run(query, ctx).hits.map { it.place.key }.toSet()
            val b = OfflineEvaluator(store).run(query, ctx).hits.map { it.place.key }.toSet()
            assertEquals(q, b, a)
            assertTrue(q, a.isNotEmpty())
        }
        assertEquals(mem.byFilter(TagFilter.parse("amenity=cafe")).size, store.byFilter(TagFilter.parse("amenity=cafe")).size)
        assertTrue(mem.get(OsmId(OsmType.WAY, 78090886)) != null)
        store.close()
    }

    @Test
    fun onlineSearchChecksTheAnswerAgain() = runBlocking {
        val sent = ArrayList<String>()
        server { r ->
            sent += URLDecoder.decode(r.body?.utf8()?.removePrefix("data=") ?: "", "UTF-8")
            MockResponse.Builder().body(eat).build()
        }.use { s ->
            val overpass = Overpass(Http(policies = emptyMap()), listOf(s.url("/api/interpreter").toString()))
            val area = OverpassArea.Around(Fixtures.westerkerk, 500.0)
            val answer = OnlineSearch(overpass).run(QueryParser.parse("vegan restaurant open now").query, area, Fixtures.context())
            assertTrue(sent.single().contains("[\"diet:vegan\"~\"^(yes|only)$\",i]"))
            // the server sent every eatery in 1.4 km; the client keeps the vegan ones, open, within the circle
            assertEquals(1925, answer.places.size)
            assertTrue(answer.result.hits.isNotEmpty())
            assertTrue(answer.result.hits.all { it.distance!! <= 500 && it.open!!.isOpen && it.tags["amenity"] == "restaurant" })
        }
    }

    @Test
    fun theSearchBoxAsksPhotonWhenTheRegionsHaveLittle() = runBlocking {
        val photon = """{"type":"FeatureCollection","features":[{"type":"Feature","geometry":{"type":"Point","coordinates":[4.8839863,52.3745466]},
            "properties":{"osm_type":"W","osm_id":99205257,"osm_key":"amenity","osm_value":"place_of_worship","name":"Westerkerk","city":"Amsterdam","countrycode":"NL","type":"house"}},
            {"type":"Feature","geometry":{"type":"Point","coordinates":[4.9,52.37]},"properties":{"osm_type":"R","osm_id":47811,"osm_key":"place","osm_value":"city","name":"Amsterdam","extent":[4.72,52.43,5.08,52.28],"type":"city"}}]}"""
        server { MockResponse.Builder().body(photon).build() }.use { s ->
            val empty = PlaceStore.inMemory()
            val engine = SearchEngine(empty, geocoder = Photon(Http(policies = emptyMap()), s.url("/").toString().removeSuffix("/")))
            val ctx = SearchContext(location = Fixtures.westerkerk, online = true, now = Fixtures.saturday)
            val r = engine.search("westerkerk", ctx)
            assertTrue(r.usedOnline)
            val first = r.items.filterIsInstance<SearchItem.Online>().first()
            assertEquals(OsmId(OsmType.WAY, 99205257), first.place.id)
            assertTrue(first.distance!! < 50)
            // offline only: nothing, and no call
            val count = s.requestCount
            val off = engine.search("westerkerk", ctx.copy(online = false))
            assertTrue(off.items.isEmpty())
            assertEquals(count, s.requestCount)
            // a named area the stores do not know is found online, and the query runs inside it
            Fixtures.store.let { store ->
                val e = SearchEngine(store, geocoder = Photon(Http(policies = emptyMap()), s.url("/").toString().removeSuffix("/")))
                val inAms = e.search("vegan restaurant in Amsterdam", ctx)
                assertEquals(null, inAms.unresolvedArea)
                assertTrue(inAms.places.isNotEmpty())
                assertTrue(inAms.places.all { it.hit.record.location in BBox(4.72, 52.28, 5.08, 52.43) })
            }
            empty.close()
        }
    }
}

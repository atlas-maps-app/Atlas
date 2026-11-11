package app.atlas.search

import app.atlas.geo.LatLon
import app.atlas.model.OsmId
import app.atlas.model.OsmType
import app.atlas.model.Place
import app.atlas.providers.Http
import app.atlas.providers.Overpass
import kotlinx.coroutines.runBlocking
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.URLDecoder

/**
 * A region keeps its country's national parks (the user, 9 November: "Why only online?"): asked of Overpass with the
 * download, kept in the region's store, found offline after, and the store still holds only its own ground.
 */
class NationalParksTest {
    private val amsterdam = LatLon(52.37, 4.88)

    @Test
    fun theQueryAsksForTheCountrysNationalParksWithTheirBoxes() {
        val ql = NationalParks.ql(amsterdam)
        assertTrue(ql.contains("is_in(52.370000,4.880000)->.a;"))
        assertTrue(ql.contains("area.a[\"ISO3166-1\"][\"admin_level\"=\"2\"]->.c;"))
        assertTrue(ql.contains("wr[\"boundary\"=\"national_park\"](area.c);"))
        assertTrue(ql.endsWith("out tags bb qt;"))
    }

    @Test
    fun aRegionKeepsItsCountrysNationalParksAndHoldsOnlyItsOwnGround() = runBlocking {
        val body = """{"version":0.6,"elements":[
            {"type":"relation","id":1,"bounds":{"minlat":52.04,"minlon":5.76,"maxlat":52.16,"maxlon":5.90},"tags":{"boundary":"national_park","name":"De Hoge Veluwe"}},
            {"type":"way","id":2,"bounds":{"minlat":53.45,"minlon":6.10,"maxlat":53.51,"maxlon":6.32},"tags":{"boundary":"national_park","name":"Schiermonnikoog"}},
            {"type":"node","id":3,"lat":51.5,"lon":5.5,"tags":{"boundary":"national_park","name":"Een punt"}}
        ]}"""
        val queries = ArrayList<String>()
        MockWebServer().use { server ->
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    synchronized(queries) { queries += URLDecoder.decode(request.body?.utf8()?.removePrefix("data=") ?: "", "UTF-8") }
                    return MockResponse.Builder().code(200).body(body).build()
                }
            }
            server.start()
            val overpass = Overpass(Http(policies = emptyMap()), listOf(server.url("/api/interpreter").toString()))
            PlaceStore.inMemory().use { store ->
                // a city's own places
                store.importPlaces(
                    listOf(
                        Place(OsmId(OsmType.NODE, 10), amsterdam, mapOf("amenity" to "cafe", "name" to "Café")),
                        Place(OsmId(OsmType.WAY, 11), LatLon(52.36, 4.87), mapOf("leisure" to "park", "name" to "Vondelpark"), app.atlas.geo.BBox(4.86, 52.355, 4.88, 52.36)),
                    ),
                    listOf("en", "nl"), "Amsterdam",
                )
                assertNull(store.meta(NationalParks.KEPT))
                val kept = NationalParks(overpass).keep(amsterdam, store)
                assertEquals(2, kept)
                assertTrue(queries.single().contains("is_in(52.370000,4.880000)"))
                assertNotNull(store.meta(NationalParks.KEPT))
                // found offline by their kind and their name, and among the region's largest greens
                assertEquals(listOf("De Hoge Veluwe", "Schiermonnikoog"), store.byTag("boundary", "national_park", null).map { it.name("en") }.sortedBy { it })
                assertTrue(store.text("hoge veluwe").isNotEmpty())
                // a park drawn as a point has no size to be listed by
                assertTrue(store.text("een punt").isEmpty())
                val greens = TagFilter("leisure=park or boundary=national_park")
                assertEquals(listOf("De Hoge Veluwe", "Schiermonnikoog", "Vondelpark"), Largest.of(listOf(store), greens, 5).map { it.name("en") })
                // the region still holds Amsterdam, and not the ground around a park it was given
                assertTrue(store.covers(amsterdam))
                assertFalse(store.covers(LatLon(52.10, 5.83)))
                assertEquals("Amsterdam", store.name)
            }
        }
    }

    private fun TagFilter(text: String) = app.atlas.places.TagFilter.parse(text)
}

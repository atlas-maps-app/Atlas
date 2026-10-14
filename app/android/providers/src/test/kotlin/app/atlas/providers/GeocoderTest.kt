package app.atlas.providers

import app.atlas.geo.LatLon
import app.atlas.model.OsmId
import app.atlas.model.OsmType
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GeocoderTest {
    private val westerkerk = LatLon(52.3745, 4.8838)

    @Test
    fun photonSearchIsBiasedAndMapsToPlaces() = runBlocking {
        Mock().use { m ->
            m.fixture("photon_search.json")
            val photon = Photon(testHttp(), m.base)
            assertTrue(photon.typeahead)
            val got = photon.search("Westerkerk", westerkerk, lang = "nl", limit = 5)
            assertEquals(5, got.size)
            val first = got.first()
            assertEquals(OsmId(OsmType.WAY, 99205257), first.id)
            assertEquals("place_of_worship", first["amenity"])
            assertEquals("Westermarkt", first["addr:street"])
            assertEquals("1016 DK", first["addr:postcode"])
            assertEquals("NL", first["addr:country"])
            assertEquals("Westerkerk, Westermarkt, Amsterdam, Netherlands", first.extra["label"])
            assertTrue(first.bbox!!.contains(first.location))
            assertEquals("Utrecht", got[1]["addr:city"])
            val req = m.take()
            assertEquals("/api/", req.url.encodedPath)
            assertEquals("52.374500", req.url.queryParameter("lat"))
            // Photon's public instance answers 400 to Dutch, so no language is sent.
            assertNull(req.url.queryParameter("lang"))
        }
    }

    @Test
    fun photonReverse() = runBlocking {
        Mock().use { m ->
            m.fixture("photon_reverse.json")
            val p = Photon(testHttp(), m.base).reverse(LatLon(52.374436, 4.883981), "en")!!
            assertEquals("postcode", p["place"])
            assertEquals("1016 DL", p.name())
            assertNull(p.id)
            assertEquals("en", m.take().url.queryParameter("lang"))
        }
    }

    @Test
    fun nominatimSearchReverseAndLookup() = runBlocking {
        Mock().use { m ->
            m.fixture("nominatim_search.json")
            m.fixture("nominatim_reverse.json")
            m.fixture("nominatim_lookup.json")
            m.fixture("nominatim_reverse_sea.json")
            val n = Nominatim(testHttp(), m.base)
            val kerk = n.search("Westerkerk", westerkerk).single()
            assertEquals(OsmId(OsmType.WAY, 99205257), kerk.id)
            assertEquals("place_of_worship", kerk["amenity"])
            assertEquals("Q1130722", kerk["wikidata"])
            assertEquals("Церковь Вестеркерк", kerk["name:ru"])
            assertEquals("Westermarkt", kerk["addr:street"])
            assertTrue(kerk.extra["label"]!!.startsWith("Westerkerk, Westermarkt"))
            val search = m.take()
            assertEquals("jsonv2", search.url.queryParameter("format"))
            assertTrue(search.url.queryParameter("viewbox") != null)
            val rev = n.reverse(LatLon(52.374547, 4.883986), null)!!
            assertNull(rev["place"])
            assertEquals("Westermarkt", rev["addr:street"])
            m.take()
            val looked = n.lookup(listOf(OsmId(OsmType.WAY, 99205257), OsmId(OsmType.NODE, 2468154212)))
            assertEquals(listOf("Westerkerk", "De Hoek"), looked.map { it.name() })
            assertEquals("W99205257,N2468154212", m.take().url.queryParameter("osm_ids"))
            assertNull(n.reverse(LatLon(54.0, 3.0), null))
            // The same search again comes from the cache: Nominatim asks for that.
            n.search("Westerkerk", westerkerk)
            assertEquals(4, m.count)
        }
    }

    @Test
    fun nominatimIsHeldToOneRequestASecond() = runBlocking {
        Mock().use { m ->
            repeat(2) { m.fixture("nominatim_search.json") }
            val http = Http(policies = mapOf("localhost" to HostPolicy(minIntervalMs = 1_000, maxConcurrent = 1)))
            val n = Nominatim(http, m.base)
            val start = System.nanoTime()
            n.search("Westerkerk", null)
            n.search("Noorderkerk", null)
            assertTrue((System.nanoTime() - start) / 1_000_000 >= 1_000)
            assertTrue(!n.typeahead)
        }
    }
}

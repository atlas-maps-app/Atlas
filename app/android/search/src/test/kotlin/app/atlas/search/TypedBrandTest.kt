package app.atlas.search

import app.atlas.geo.LatLon
import app.atlas.model.OsmId
import app.atlas.model.Place
import app.atlas.places.OpeningHoursContext
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * A typed brand's name is the brand of the country searched in (the reviewer, 17 November, finding 9): the search
 * held a country of its own that no caller gave, so of the Jumbos the widest spread, the toy shops of Greece and its
 * neighbours, was the one asked for in Amsterdam or Santiago, and the supermarkets' branches went unnamed. The country
 * is now the search's hours', where the search is.
 */
class TypedBrandTest {
    private val amsterdam = LatLon(52.3731, 4.8926)
    private val saturday = ZonedDateTime.of(2026, 10, 17, 12, 0, 0, 0, ZoneId.of("Europe/Amsterdam"))
    private val jumbo = Place(OsmId.parse("n1")!!, LatLon(52.3735, 4.8930), mapOf("shop" to "supermarket", "brand" to "Jumbo", "name" to "Jumbo"))
    private val engine by lazy { SearchEngine(MemoryPlaces(listOf(jumbo), "NL")) }
    private fun ctx(country: String?) = SearchContext(location = amsterdam, now = saturday, hours = OpeningHoursContext(country = country))

    @Test
    fun aTypedNameIsTheBrandOfTheCountrySearchedIn() {
        val r = runBlocking { engine.search("jumbo", ctx("NL")) }
        val brand = r.items.filterIsInstance<SearchItem.BrandResult>().single()
        assertEquals("jumbo-1cf55d", brand.brand.id)
        assertTrue(brand.named)
        assertEquals(1, brand.count)
    }

    @Test
    fun anAnswerAsksForThatBrandToo() {
        val r = runBlocking { engine.answer("jumbo", ctx("NL")) }!!
        val answer = r.items.filterIsInstance<SearchItem.Structured>().single()
        // as asked, not the nearest thing to it with the brand let go of
        assertTrue(answer.loosened.toString(), answer.loosened.isEmpty())
        assertEquals(listOf(jumbo.id), answer.results.single().hits.map { it.record.place.id })
    }

    @Test
    fun inChileTheChileanOne() {
        val santiago = LatLon(-33.4489, -70.6693)
        val chilean = Place(OsmId.parse("n2")!!, LatLon(-33.4485, -70.6690), mapOf("shop" to "supermarket", "brand" to "Jumbo", "name" to "Jumbo"))
        val r = runBlocking {
            SearchEngine(MemoryPlaces(listOf(chilean), "CL")).search("jumbo", ctx("CL").copy(location = santiago, now = saturday.withZoneSameInstant(ZoneId.of("America/Santiago"))))
        }
        assertEquals("jumbo-99a0d7", r.items.filterIsInstance<SearchItem.BrandResult>().single().brand.id)
    }
}

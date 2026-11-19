package app.atlas.search

import app.atlas.geo.LatLon
import app.atlas.model.OsmId
import app.atlas.model.OsmType
import app.atlas.model.Place
import app.atlas.places.OpeningHoursContext
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * A result's address as the country at the place writes it, where the place names none of its own (the reviewer, 17
 * November, finding 11: the search wrote it street first, "Rue de Rivoli 12, 75004 Paris"; the app maintainer, 18
 * November 2026, batch 4 A7: then as the country searched in writes it, the searcher's, not the place's).
 */
class SearchAddressTest {
    private val paris = LatLon(48.8556, 2.3601)
    private val zone = ZoneId.of("Europe/Paris")

    @Test
    fun aResultsAddressIsWrittenAsTheCountrySearchedInWritesIt() {
        val bakery = Place(OsmId(OsmType.NODE, 7001L), paris, mapOf("name" to "Chez Lucien", "shop" to "bakery",
            "addr:street" to "Rue de Rivoli", "addr:housenumber" to "12", "addr:postcode" to "75004", "addr:city" to "Paris"))
        // searched from Germany: the bakery's address as France writes it, the country of the map at it
        val ctx = SearchContext(location = paris, lang = "fr", now = ZonedDateTime.of(2026, 10, 13, 18, 0, 0, 0, zone), hours = OpeningHoursContext.forPlace("DE", zone),
            countryAt = { at -> if (at.lon < 5.0) "FR" else "DE" })
        val r = runBlocking { SearchEngine(MemoryPlaces(listOf(bakery), country = null)).search("chez lucien", ctx) }
        val hit = r.items.filterIsInstance<SearchItem.PlaceResult>().first { it.hit.record.name() == "Chez Lucien" }
        assertTrue(hit.subtitle.orEmpty(), hit.subtitle.orEmpty().contains("12 Rue de Rivoli, 75004 Paris"))
    }
}

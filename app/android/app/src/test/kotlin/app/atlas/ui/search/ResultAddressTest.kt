package app.atlas.ui.search

import android.os.Looper
import app.atlas.geo.LatLon
import app.atlas.model.OsmId
import app.atlas.model.Place
import app.atlas.search.MemoryPlaces
import app.atlas.search.SearchItem
import app.atlas.testing.testServices
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Duration

/**
 * A result's address is written as the country at the place writes it, where the place names none of its own (the app
 * maintainer, 18 November 2026, batch 4 A7): the typed search wrote it as the country searched in writes it, so a café
 * in Berlin searched from Paris read "1 Pariser Platz, 10117 Berlin", and a bakery in Paris searched from Berlin "Rue de
 * Rivoli 12, 75004 Paris".
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ResultAddressTest {
    private val paris = LatLon(48.8556, 2.3601)
    private val berlin = LatLon(52.5163, 13.3777)

    /** Two places, neither naming its country, as most of OpenStreetMap's don't. */
    private val places = listOf(
        Place(OsmId.parse("n7101")!!, LatLon(berlin.lat + 0.0003, berlin.lon), mapOf("name" to "Café Adlon", "amenity" to "cafe",
            "addr:street" to "Pariser Platz", "addr:housenumber" to "1", "addr:postcode" to "10117", "addr:city" to "Berlin")),
        Place(OsmId.parse("n7102")!!, LatLon(paris.lat + 0.0003, paris.lon), mapOf("name" to "Chez Lucien", "shop" to "bakery",
            "addr:street" to "Rue de Rivoli", "addr:housenumber" to "12", "addr:postcode" to "75004", "addr:city" to "Paris")),
    )

    private fun until(done: () -> Boolean) {
        val end = System.currentTimeMillis() + 20_000
        while (!done() && System.currentTimeMillis() < end) {
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(50))
            Thread.sleep(20)
        }
        assertTrue("timed out", done())
    }

    /** The address line of the result named [name], typed as [text] by a searcher at [here]. */
    private fun addressOf(here: LatLon, text: String, name: String): String {
        val s = testServices(stores = listOf(MemoryPlaces(places, country = null)))
        val vm = SearchViewModel(s, text, null, { here }, "en", online = { false })
        until { vm.state.value.results != null && !vm.state.value.searching }
        val hit = vm.state.value.results!!.items.filterIsInstance<SearchItem.PlaceResult>().first { it.hit.record.name() == name }
        s.close()
        return hit.subtitle.orEmpty()
    }

    @Test
    fun aResultsAddressIsWrittenAsItsOwnCountryWritesIt() {
        val cafe = addressOf(paris, "cafe adlon", "Café Adlon")
        assertTrue(cafe, cafe.contains("Pariser Platz 1, 10117 Berlin"))
        val bakery = addressOf(berlin, "chez lucien", "Chez Lucien")
        assertTrue(bakery, bakery.contains("12 Rue de Rivoli, 75004 Paris"))
    }
}

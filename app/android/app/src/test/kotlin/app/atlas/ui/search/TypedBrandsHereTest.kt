package app.atlas.ui.search

import android.os.Looper
import app.atlas.Services
import app.atlas.geo.LatLon
import app.atlas.model.OsmId
import app.atlas.model.Place
import app.atlas.search.MemoryPlaces
import app.atlas.search.SearchItem
import app.atlas.testing.testServices
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Duration

/**
 * Where a name is typed, it is the brand of the country here (the reviewer, 17 November, finding 9): the search, the
 * query screen and the sheet's rewording gave the parser and the engine no country, so "jumbo" by the Westerkerk was
 * the Jumbo of Greece's toy shops, the widest spread, and the Dutch one's branch was no chain's. The store is a Dutch region's, which matched its places'
 * brands in the Netherlands when it was written.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TypedBrandsHereTest {
    private val here = Services.WESTERKERK
    private val dutchJumbo = "jumbo-1cf55d"
    private val jumbo = Place(OsmId.parse("n1")!!, LatLon(here.lat + 0.001, here.lon), mapOf("shop" to "supermarket", "brand" to "Jumbo", "name" to "Jumbo"))
    private val bench = Place(OsmId.parse("n99")!!, LatLon(here.lat - 0.001, here.lon), mapOf("amenity" to "bench"))

    private fun until(timeoutMs: Long = 20_000, done: () -> Boolean) {
        val end = System.currentTimeMillis() + timeoutMs
        while (!done() && System.currentTimeMillis() < end) {
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(50))
            Thread.sleep(20)
        }
        assertTrue("timed out", done())
    }

    @Test
    fun theSearchNamesTheDutchJumbo() {
        val s = testServices(stores = listOf(MemoryPlaces(listOf(jumbo, bench), country = "NL")))
        val vm = SearchViewModel(s, "jumbo", null, { here }, "en")
        until { vm.state.value.results != null && !vm.state.value.searching }
        val brands = vm.state.value.results!!.items.filterIsInstance<SearchItem.BrandResult>()
        assertEquals(listOf(dutchJumbo), brands.map { it.brand.id })
        s.close()
    }

    @Test
    fun theQueryScreenReadsTheDutchJumbo() {
        val s = testServices(stores = listOf(MemoryPlaces(listOf(jumbo, bench), country = "NL")))
        val vm = QueryViewModel(s, "jumbo", { here }, "en")
        until { vm.state.value.parsed != null && !vm.state.value.running }
        val steps = vm.state.value.parsed!!.steps.toString()
        assertTrue(steps, "Brand(id=$dutchJumbo)" in steps)
        s.close()
    }

    @Test
    fun theSheetsRewordingReadsTheDutchJumbo() {
        val s = testServices(stores = listOf(MemoryPlaces(listOf(jumbo, bench), country = "NL")))
        val m = SearchSheetModel(s, here, "en")
        m.reword("jumbo")
        val asked = m.state.value.asked as Asked.Words
        assertTrue(asked.parsed.steps.toString(), "Brand(id=$dutchJumbo)" in asked.parsed.steps.toString())
        s.close()
    }
}

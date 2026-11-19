package app.atlas.ui.search

import android.os.Looper
import app.atlas.Services
import app.atlas.geo.BBox
import app.atlas.geo.LatLon
import app.atlas.model.OsmId
import app.atlas.model.Place
import app.atlas.search.MemoryPlaces
import app.atlas.testing.testServices
import app.atlas.ui.home.Highlight
import app.atlas.ui.home.HighlightKind
import app.atlas.ui.home.HomeHighlights
import app.atlas.ui.shop.ShopArea
import app.atlas.ui.shop.ShopRepository
import app.atlas.ui.shop.Shelf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Duration

/**
 * Every screen matches a place's brand in the country of the map where it is (the reviewer, 17 November, finding 9):
 * a supermarket by the Westerkerk tagged only `brand=Jumbo` is the Dutch Jumbo on the chains around, the Shop area,
 * the home's branch pin, the chains the phone holds and the place's page, not the Chilean Jumbo the widest spread chose;
 * a pharmacy named "Farmacia" in Rome is an independent, not a Ukrainian chain's branch.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ChainsInTheirCountryTest {
    private val here = Services.WESTERKERK
    private val rome = LatLon(41.9028, 12.4964)
    private val dutchJumbo = "jumbo-1cf55d"

    private fun place(id: Long, at: LatLon, tags: Map<String, String>) = Place(OsmId.parse("n$id")!!, at, tags)

    private val jumbo = place(1, LatLon(here.lat + 0.001, here.lon), mapOf("shop" to "supermarket", "brand" to "Jumbo", "name" to "Jumbo"))

    /** [p] with a bench on the other side, so that the places on the phone cover the point between. */
    private fun around(p: Place) = listOf(p, place(99, LatLon(p.location.lat - 0.002, p.location.lon), mapOf("amenity" to "bench")))

    private fun until(timeoutMs: Long = 15_000, done: () -> Boolean) {
        val end = System.currentTimeMillis() + timeoutMs
        while (!done() && System.currentTimeMillis() < end) {
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(50))
            Thread.sleep(20)
        }
        assertTrue("timed out", done())
    }

    @Test
    fun theChainsAroundAreTheDutchJumbo() {
        val s = testServices(stores = listOf(MemoryPlaces(around(jumbo), country = null)))
        val near = runBlocking { BrandsAround.near(s, here, 2_000.0) }
        assertEquals(listOf(dutchJumbo), near.chains.map { it.brand.id })
        val held = runBlocking { s.localData.brands(BBox.around(here, 2_000.0)) }
        assertEquals(listOf(dutchJumbo), held.value.map { it.brand.id })
        s.close()
    }

    @Test
    fun theShopAreaKnowsTheDutchJumbo() {
        val s = testServices(stores = listOf(MemoryPlaces(around(jumbo), country = null)))
        val area = runBlocking {
            var last: ShopArea? = null
            withTimeout(30_000) { ShopRepository.of(s).area("t", Shelf.SUPER.filter, here).collect { last = it } }
            last!!
        }
        assertEquals(dutchJumbo, area.places.single().brand?.id)
        s.close()
    }

    @Test
    fun theHomesBranchPinWearsTheDutchJumbo() {
        val pin = HomeHighlights.markers(listOf(Highlight(HighlightKind.BRANCH, jumbo)), "NL").single()
        assertEquals(dutchJumbo, pin.logo)
    }

    @Test
    fun thePlacesPageShowsTheDutchJumbo() {
        val s = testServices()
        val vm = PlaceViewModel(s, s.remember(jumbo), null, null, null, "en")
        until { !vm.state.value.looking && vm.state.value.place != null }
        assertEquals(dutchJumbo, vm.state.value.brand?.id)
        s.close()
    }

    @Test
    fun aFarmaciaInRomeIsNoChains() {
        val farmacia = place(2, rome, mapOf("amenity" to "pharmacy", "name" to "Farmacia"))
        val s = testServices(stores = listOf(MemoryPlaces(around(farmacia), country = null)))
        val near = runBlocking { BrandsAround.near(s, rome, 2_000.0) }
        assertTrue(near.chains.toString(), near.chains.isEmpty())
        assertEquals(listOf("Farmacia"), near.independents.map { it.tags["name"] })
        s.close()
    }
}

package app.atlas.ui.search

import android.os.Looper
import app.atlas.Services
import app.atlas.geo.BBox
import app.atlas.geo.Geo
import app.atlas.model.OsmId
import app.atlas.model.OsmType
import app.atlas.model.Place
import app.atlas.search.Intents
import app.atlas.search.MemoryPlaces
import app.atlas.search.Order
import app.atlas.search.TellKey
import app.atlas.testing.testServices
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Duration

/**
 * Largest chosen on a kind: the region's largest join those around, however far (the user, 9 November: "Is it
 * somewhere possible to see the largest green areas in your country or around you?"); a walk's stay within its reach.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LargestModelTest {
    private val w = Services.WESTERKERK
    private val walk = Intents.all.first { it.id == Intents.WALK }

    private fun idle() = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(10))

    private fun until(timeoutMs: Long = 20_000, done: () -> Boolean) {
        val end = System.currentTimeMillis() + timeoutMs
        while (!done() && System.currentTimeMillis() < end) {
            idle()
            Thread.sleep(20)
        }
        assertTrue("timed out", done())
    }

    private fun green(id: Long, north: Double, across: Double, vararg tags: Pair<String, String>): Place {
        val at = Geo.destination(w, 0.0, north)
        return Place(OsmId(OsmType.WAY, id), at, tags.toMap(), BBox.around(at, across / 2))
    }

    // ten parks within a kilometre, enough for the first circle, and a far larger one 40 km off
    private val places = (1..10).map { i -> green(i.toLong(), 100.0 * i, 120.0 + 10 * i, "leisure" to "park", "name" to "Park $i") } +
        green(20, 40_000.0, 7_000.0, "leisure" to "park", "name" to "Het Verre Park") +
        Place(OsmId(OsmType.NODE, 30), w, mapOf("amenity" to "cafe", "name" to "Kalkhoven"))

    private fun model(): SearchSheetModel {
        val m = SearchSheetModel(testServices(stores = listOf(MemoryPlaces(places))), w, "en")
        m.moveTo(w)
        return m
    }

    private fun names(m: SearchSheetModel) = m.state.value.results!!.hits.map { it.record.name("en") }

    @Test
    fun largestOnTheParksListsTheRegionsLargestHoweverFar() {
        val m = model()
        m.enter("parks")
        until { m.state.value.results != null && !m.state.value.loading }
        assertFalse("Het Verre Park" in names(m))
        assertTrue(Order.LARGEST in m.state.value.results!!.purpose.orders)
        m.setOrder(Order.LARGEST)
        until { m.state.value.results?.order == Order.LARGEST && names(m).firstOrNull() == "Het Verre Park" }
        assertEquals(listOf("Het Verre Park", "Park 10", "Park 9"), names(m).take(3))
        // its way round in kilometres, where walking it would take most of a day
        assertTrue(m.state.value.results!!.hits[0].tells.first { it.key == TellKey.LAP }.text!!.endsWith("km lap"))
        // back to the kind's own order: those around
        m.setOrder(Order.FIT)
        until { m.state.value.results?.order == Order.FIT }
        assertFalse("Het Verre Park" in names(m))
    }

    @Test
    fun aWalksLargestStayWithinReach() {
        val m = model()
        m.enter(walk)
        until { m.state.value.results != null && !m.state.value.loading }
        m.setOrder(Order.LARGEST)
        until { m.state.value.results?.order == Order.LARGEST && !m.state.value.loading }
        assertEquals("Park 10", names(m).first())
        assertFalse("Het Verre Park" in names(m))
    }
}

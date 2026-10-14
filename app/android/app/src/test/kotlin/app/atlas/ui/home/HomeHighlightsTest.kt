package app.atlas.ui.home

import app.atlas.geo.Geo
import app.atlas.mapview.MarkerStyle
import app.atlas.model.Place
import app.atlas.search.Regions
import app.atlas.testing.Fixtures
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalDateTime

/** The home's highlights at the Westermarkt, from the real Amsterdam place store. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class HomeHighlightsTest {
    private val westermarkt = app.atlas.Services.WESTERKERK

    /** Thursday 15 October 2026, 16:00. */
    private val thursday = LocalDateTime.of(2026, 10, 15, 16, 0)

    private fun names(list: List<Highlight>, kind: HighlightKind) = list.filter { it.kind == kind }.map { it.place.tags["name"] }

    @Test
    fun theWestermarktsHighlightsAreRealPlaces() {
        val h = HomeHighlights.pick(Fixtures.amsStore, westermarkt, thursday)
        assertEquals(listOf("Westerkerk"), names(h, HighlightKind.SIGHT))
        // The nearest museum open now (until 22:00), next door to the Westerkerk.
        assertEquals(listOf("Anne Frank Huis"), names(h, HighlightKind.MUSEUM))
        assertEquals(listOf("Westermarkt"), names(h, HighlightKind.TRANSIT))
        assertEquals(listOf("Bagels & Beans"), names(h, HighlightKind.BRANCH))
        val chargers = h.filter { it.kind == HighlightKind.CHARGER }
        assertEquals(2, chargers.size)
        // The sockets as mapped (capacity=2), nearest first.
        assertTrue(chargers.all { it.value == "2" })
        assertTrue(Geo.distance(chargers[0].place.location, westermarkt) <= Geo.distance(chargers[1].place.location, westermarkt))
        // Each place once.
        assertEquals(h.size, h.map { it.place.key }.toSet().size)
    }

    @Test
    fun closedPlacesStepAside() {
        // At 7:00 the museums and Bagels & Beans are shut: the landmark, the stop and the chargers stay.
        val h = HomeHighlights.pick(Fixtures.amsStore, westermarkt, thursday.withHour(7))
        assertTrue(names(h, HighlightKind.MUSEUM).isEmpty())
        assertTrue(names(h, HighlightKind.BRANCH).isEmpty())
        assertEquals(listOf("Westerkerk"), names(h, HighlightKind.SIGHT))
        assertEquals(listOf("Westermarkt"), names(h, HighlightKind.TRANSIT))
    }

    @Test
    fun savedPlacesComeFirstWithTheWalkOnTheNearest() {
        val near = Place(null, app.atlas.geo.LatLon(52.37480, 4.88420), mapOf("name" to "Near", "amenity" to "restaurant"))
        val far = Place(null, app.atlas.geo.LatLon(52.37600, 4.88700), mapOf("name" to "Far", "amenity" to "restaurant"))
        val away = Place(null, app.atlas.geo.LatLon(52.40000, 4.90000), mapOf("name" to "Away", "amenity" to "restaurant"))
        val h = HomeHighlights.pick(Regions(), westermarkt, thursday, saved = listOf(far, away, near))
        assertEquals(listOf("Near", "Far"), names(h, HighlightKind.SAVED))
        assertTrue(h[0].value!!.endsWith("′"))
        assertNull(h[1].value)
        val m = HomeHighlights.markers(h)
        assertEquals(MarkerStyle.PIN, m[0].style)
        assertEquals("Near", m[0].label)
        assertEquals("ui-check", m[0].badge)
    }

    @Test
    fun anEmptyPhoneHasNoHighlights() {
        assertTrue(HomeHighlights.pick(Regions(), westermarkt, thursday).isEmpty())
    }
}

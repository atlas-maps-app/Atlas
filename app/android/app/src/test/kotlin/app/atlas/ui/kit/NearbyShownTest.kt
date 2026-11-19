package app.atlas.ui.kit

import app.atlas.geo.LatLon
import app.atlas.model.OsmId
import app.atlas.model.Place
import app.atlas.services.NearbyPlaces
import app.atlas.services.PlaceOrigin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * What the nearby list shows never shrinks but for an answer given whole (the app maintainer, 18 November 2026, batch 5
 * B13): a failed answer that held a place was shown as it was, and the packs' part read again after other lookups had
 * let windows go, or read without the windows, could hold fewer than what was shown.
 */
class NearbyShownTest {
    private val centre = LatLon(52.0830, 4.3900)

    /** A café [m] metres north of the centre, named for its [id]. */
    private fun cafe(id: Long, m: Double) = Place(OsmId.parse("n$id")!!, LatLon(centre.lat + m / 111_195.0, centre.lon), mapOf("amenity" to "cafe", "name" to "$id"))

    private val a = cafe(1, 100.0)
    private val b = cafe(2, 200.0)
    private val c = cafe(3, 300.0)
    private val d = cafe(4, 50.0)

    private fun names(p: NearbyPlaces) = p.places.map { it.tags["name"] }

    @Test
    fun aFailureThatHoldsFewerKeepsWhatWasShown() {
        val r = shownAfter(NearbyPlaces(listOf(a, b, c), PlaceOrigin.PACK), NearbyPlaces(listOf(a), PlaceOrigin.PACK, "HTTP 504"), centre, 40)
        assertEquals(listOf("1", "2", "3"), names(r))
        assertEquals("HTTP 504", r.error)
    }

    @Test
    fun thePacksPartWithoutTheWindowsKeepsWhatWasShown() {
        val shown = NearbyPlaces(listOf(a, b, c), PlaceOrigin.PACK)
        assertEquals(listOf("1", "2", "3"), names(shownAfter(shown, NearbyPlaces(listOf(b), PlaceOrigin.PACK), centre, 40)))
        assertEquals(listOf("1", "2", "3"), names(shownAfter(shown, NearbyPlaces(emptyList(), PlaceOrigin.PACK), centre, 40)))
    }

    @Test
    fun whatCameMeanwhileIsShownWithWhatWas() {
        val r = shownAfter(NearbyPlaces(listOf(a, b), PlaceOrigin.PACK), NearbyPlaces(listOf(a, b, c), PlaceOrigin.PACK, "HTTP 504"), centre, 40)
        assertEquals(listOf("1", "2", "3"), names(r))
        assertEquals("HTTP 504", r.error)
        // nearest first, at most the limit: a place leaves only for a nearer one
        val nearer = shownAfter(NearbyPlaces(listOf(a, b, c), PlaceOrigin.PACK), NearbyPlaces(listOf(d), PlaceOrigin.PACK, "HTTP 504"), centre, 3)
        assertEquals(listOf("4", "1", "2"), names(nearer))
    }

    @Test
    fun aFailureThatHoldsNothingKeepsWhatWasShownAsBefore() {
        val r = shownAfter(NearbyPlaces(listOf(a), PlaceOrigin.PACK), NearbyPlaces(emptyList(), PlaceOrigin.ONLINE, "offline"), centre, 40)
        assertEquals(listOf("1"), names(r))
        assertEquals("offline", r.error)
        // nothing shown: the failure as it is
        val none = NearbyPlaces(emptyList(), PlaceOrigin.ONLINE, "offline")
        assertSame(none, shownAfter(null, none, centre, 40))
        assertSame(none, shownAfter(NearbyPlaces(emptyList(), PlaceOrigin.PACK), none, centre, 40))
    }

    @Test
    fun anAnswerGivenWholeIsShownAsItIs() {
        // Overpass's answer for the whole circle, or a region's store's: as it is, as on 38ddafa1
        val shown = NearbyPlaces(listOf(a, b, c), PlaceOrigin.PACK)
        val online = NearbyPlaces(listOf(a), PlaceOrigin.ONLINE)
        assertSame(online, shownAfter(shown, online, centre, 40))
        val store = NearbyPlaces(listOf(b), PlaceOrigin.STORE)
        assertSame(store, shownAfter(shown, store, centre, 40))
    }
}

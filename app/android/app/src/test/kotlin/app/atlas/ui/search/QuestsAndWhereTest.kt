package app.atlas.ui.search

import app.atlas.Services
import app.atlas.geo.BBox
import app.atlas.geo.Geo
import app.atlas.geo.LatLon
import app.atlas.model.OsmId
import app.atlas.model.Place
import app.atlas.places.OpeningHoursContext
import app.atlas.services.PlaceOrigin
import app.atlas.testing.Fixtures
import app.atlas.testing.testServices
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalDateTime
import kotlin.random.Random

/** Map quests around the Westerkerk from the Amsterdam extract, and the Where wheel's choices. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class QuestsAndWhereTest {
    private val evening = LocalDateTime.now(Fixtures.evening)

    @Test
    fun questsAroundAreNearestFirstAndNotAskedTwice() = runBlocking {
        val s = testServices(places = true)
        val (found, origin) = QuestFinder.near(s, Services.WESTERKERK, "en", online = false)
        assertEquals(PlaceOrigin.STORE, origin)
        assertTrue("quests: ${found.size}", found.size >= 10)
        // Near first, in bands of 50 m.
        assertTrue(found.zipWithNext().all { (a, b) -> (a.distanceM / 50).toInt() <= (b.distanceM / 50).toInt() })
        assertTrue(found.all { it.distanceM <= QuestFinder.RADIUS_M && it.place.id != null && it.place.name() != null })
        assertTrue(found.groupBy { it.place.key }.values.all { it.size <= 2 })
        // What the place has mapped is never asked.
        for (q in found) assertNull("${q.place.name()} ${q.quest.key}", q.place.tags[q.quest.key])

        val first = found.first()
        s.saved.recordAnswer(first.place.key, first.quest.key, true, "https://www.openstreetmap.org/note/1")
        val (again, _) = QuestFinder.near(s, Services.WESTERKERK, "en", online = false)
        assertFalse(again.any { it.id == first.id })
        assertTrue(s.saved.answered(first.place.key, first.quest.key))
    }

    @Test
    fun withoutARegionAndOfflineThereAreNoQuests() = runBlocking {
        val (found, origin) = QuestFinder.near(testServices(), Services.WESTERKERK, "en", online = false)
        assertTrue(found.isEmpty())
        assertNull(origin)
    }

    @Test
    fun theWheelsWedgesFillFromTheRegion() = runBlocking {
        val s = testServices(places = true)
        val hours = HereContext.hours(s, Services.WESTERKERK, Fixtures.evening.zone)
        val wheel = WherePicker.places(s, Services.WESTERKERK, WhereFilters(), evening, hours)
        assertTrue(WherePicker.WEDGES.count { wheel.of(it).isNotEmpty() } >= 5)
        val r = WhereFilters().radiusM
        for (w in WherePicker.WEDGES) for (p in wheel.of(w)) assertTrue(Geo.distance(Services.WESTERKERK, p.location) <= r)
        // A spin lands on a wedge with places, on one of them.
        val (i, p) = WherePicker.spin(wheel, Services.WESTERKERK, Random(7))!!
        assertTrue(p in wheel.of(WherePicker.WEDGES[i]))
        // Indoors only: the outdoor wedges are empty.
        val indoors = WherePicker.places(s, Services.WESTERKERK, WhereFilters(indoors = true), evening, hours)
        assertTrue(WherePicker.WEDGES.filter { !it.indoors }.all { indoors.of(it).isEmpty() })
    }

    @Test
    fun theChipsAreHonest() {
        val at = LatLon(52.3745, 4.8838)
        val museum = WherePicker.WEDGES.first { it.id == "culture" }
        val park = WherePicker.WEDGES.first { it.id == "nature" }
        fun place(vararg t: Pair<String, String>) = Place(OsmId.parse("n1"), LatLon(52.3750, 4.8838), mapOf(*t), source = "osm")
        val ctx = OpeningHoursContext.Default
        val open = WhereFilters(openNow = true)
        val evening = LocalDateTime.of(2026, 10, 13, 19, 30)
        assertFalse(WherePicker.fits(place("tourism" to "museum"), museum, at, WhereFilters(), evening, ctx))
        assertTrue(WherePicker.fits(place("tourism" to "museum", "name" to "M"), museum, at, WhereFilters(), evening, ctx))
        assertFalse(WherePicker.fits(place("tourism" to "museum", "name" to "M", "opening_hours" to "Mo-Su 10:00-17:00"), museum, at, open, evening, ctx))
        // Indoors with no hours mapped may be shut; a park with none is open.
        assertFalse(WherePicker.fits(place("tourism" to "museum", "name" to "M"), museum, at, open, evening, ctx))
        assertTrue(WherePicker.fits(place("leisure" to "park", "name" to "P"), park, at, open, evening, ctx))
        assertFalse(WherePicker.fits(place("tourism" to "museum", "name" to "M", "fee" to "yes"), museum, at, WhereFilters(free = true), evening, ctx))
        assertFalse(WherePicker.fits(place("leisure" to "park", "name" to "P"), park, at, WhereFilters(indoors = true), evening, ctx))
        val far = Place(OsmId.parse("n2"), LatLon(52.40, 4.8838), mapOf("leisure" to "park", "name" to "Far"), source = "osm")
        assertFalse(WherePicker.fits(far, park, at, WhereFilters(walkMinutes = 15), evening, ctx))
        assertNotNull(WhereFilters(walkMinutes = 30).radiusM.takeIf { it > WhereFilters().radiusM })
        assertNull(WherePicker.spin(WheelPlaces(emptyMap()), at, Random(1)))
        assertTrue(BBox.around(at, 10.0).contains(at))
    }
}

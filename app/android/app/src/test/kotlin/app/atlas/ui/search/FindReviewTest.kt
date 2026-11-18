package app.atlas.ui.search

import android.os.Looper
import app.atlas.geo.LatLon
import app.atlas.model.OsmId
import app.atlas.model.OsmType
import app.atlas.model.Place
import app.atlas.places.OpenSpan
import app.atlas.places.OpenState
import app.atlas.places.OpeningHours
import app.atlas.search.PlaceRecord
import app.atlas.search.ResultHit
import app.atlas.search.TravelMode
import app.atlas.testing.testServices
import app.atlas.ui.kit.walkMinutes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Duration
import java.time.LocalDateTime
import java.time.ZoneId

/** What the review of the search package fixed: far places keep their own clock, quests can be retried, safe links. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class FindReviewTest {
    private fun until(timeoutMs: Long = 15_000, done: () -> Boolean) {
        val end = System.currentTimeMillis() + timeoutMs
        while (!done() && System.currentTimeMillis() < end) {
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(50))
            Thread.sleep(20)
        }
        assertTrue("timed out", done())
    }

    @Test
    fun aFarPlaceIsReadOnItsOwnClock() {
        // The test clock is Tuesday 19:30 in Amsterdam: it is 02:30 on Wednesday in Tokyo, where this place is open 01:00-04:00.
        val s = testServices()
        val cafe = Place(
            OsmId.parse("n42")!!, LatLon(35.6595, 139.7005),
            mapOf("name" to "Shibuya Kissa", "amenity" to "cafe", "opening_hours" to "Mo-Su 01:00-04:00", "addr:country" to "JP"),
        )
        val vm = PlaceViewModel(s, s.remember(cafe), null, null, null, "en")
        until { !vm.state.value.looking && vm.state.value.place != null }
        val st = vm.state.value
        assertEquals(ZoneId.of("Asia/Tokyo"), HereContext.localeOf(s, cafe, HereContext.phoneAt(s), ZoneId.of("Europe/Amsterdam")).zone)
        assertEquals(2, st.now.hour)
        assertEquals(true, st.status?.isOpen)
        assertEquals("JP", st.country)
    }

    @Test
    fun aNearPlaceKeepsThePhonesClockAndCountry() {
        val s = testServices(places = true)
        val near = Place(OsmId.parse("n7")!!, LatLon(52.3745, 4.8838), mapOf("name" to "Near"))
        val l = HereContext.localeOf(s, near, LatLon(52.37, 4.89), ZoneId.of("Europe/Amsterdam"))
        assertEquals(ZoneId.of("Europe/Amsterdam"), l.zone)
        assertEquals("NL", l.country)
    }

    @Test
    fun aFarPlaceWithoutACountryKeepsTheMapsOwn() {
        // Sydney, tagged with no country and with no region on the phone: the map's own country there (finding 8).
        val s = testServices()
        val far = Place(OsmId.parse("n8")!!, LatLon(-33.87, 151.21), mapOf("name" to "Far"))
        val l = HereContext.localeOf(s, far, LatLon(52.37, 4.89), ZoneId.of("Europe/Amsterdam"))
        assertEquals("AU", l.country)
        // Australia's zone nearest the sun's hour there: +10 at 151 degrees east, before its summer time.
        assertEquals(10 * 3600, l.zone.rules.getStandardOffset(java.time.Instant.parse("2026-10-13T00:00:00Z")).totalSeconds)
    }

    @Test
    fun aPlaceFarFromEveryCountryHasNoHolidaysOfTheirs() {
        val s = testServices()
        val far = Place(OsmId.parse("n9")!!, LatLon(-25.07, -130.10), mapOf("name" to "Adamstown"))
        val l = HereContext.localeOf(s, far, LatLon(52.37, 4.89), ZoneId.of("Europe/Amsterdam"))
        assertNull(l.country)
        // The sun's own hour there: -9 at 130 degrees west.
        assertEquals(-9 * 3600, l.zone.rules.getOffset(java.time.Instant.parse("2026-10-13T00:00:00Z")).totalSeconds)
    }

    @Test
    fun theZoneOfACountryNearestTheSun() {
        // Zones that keep one clock (Detroit, New York) are as good as each other: compare what they read.
        val noon = java.time.Instant.parse("2026-10-13T12:00:00Z")
        fun offset(z: ZoneId) = z.rules.getOffset(noon)
        assertEquals(offset(ZoneId.of("America/Los_Angeles")), offset(PlaceZone.guess("US", -122.4, noon)))
        assertEquals(offset(ZoneId.of("America/New_York")), offset(PlaceZone.guess("US", -74.0, noon)))
        assertEquals(offset(ZoneId.of("America/Chicago")), offset(PlaceZone.guess("US", -87.6, noon)))
        assertEquals(ZoneId.of("Asia/Tokyo"), PlaceZone.guess("JP", 139.7, noon))
    }

    @Test
    fun aFailedNoteLeavesItsQuestionOpenToAskAgain() {
        val place = Place(OsmId.parse("n9")!!, LatLon(52.37, 4.88), mapOf("name" to "X", "amenity" to "cafe"))
        val quests = Quests.of(app.atlas.places.Facts.of(place, "en", java.time.LocalDateTime.now(), app.atlas.places.OpeningHoursContext.Default))
        assertTrue(quests.size >= 2)
        val st = PlaceState(place = place, quests = quests)
        assertEquals(quests[0], st.openQuest)
        assertEquals(quests[1], st.copy(answered = mapOf(quests[0].key to Sending.Busy)).openQuest)
        assertEquals(quests[1], st.copy(answered = mapOf(quests[0].key to Sending.Done("u"))).openQuest)
        assertEquals(quests[0], st.copy(answered = mapOf(quests[0].key to Sending.Failed("offline"))).openQuest)
        assertNull(st.copy(answered = quests.associate { it.key to Sending.Done("u") }).openQuest)
    }

    @Test
    fun aFailedAnswerKeepsItsQuestOnTheQuestsScreen() {
        val place = Place(OsmId.parse("n9")!!, LatLon(52.37, 4.88), mapOf("name" to "X", "amenity" to "cafe"))
        val facts = app.atlas.places.Facts.of(place, "en", java.time.LocalDateTime.now(), app.atlas.places.OpeningHoursContext.Default)
        val quest = Quests.of(facts).first()
        val q = PlaceQuest(place, quest, 30.0)
        val base = QuestsState(quests = listOf(q))
        assertEquals(q, base.current)
        assertNull(base.copy(sending = mapOf(q.id to Sending.Busy)).current)
        assertNull(base.copy(sending = mapOf(q.id to Sending.Done(null))).current)
        assertEquals(q, base.copy(sending = mapOf(q.id to Sending.Failed("offline"))).current)
        assertNull(base.copy(skipped = setOf(q.id), sending = mapOf(q.id to Sending.Failed("offline"))).current)
    }

    @Test
    fun onlyWebAddressesAreOpened() {
        assertEquals("https://example.nl", webUrl("example.nl"))
        assertEquals("http://example.nl/menu", webUrl(" http://example.nl/menu ; other"))
        assertEquals("https://example.nl:8080/x", webUrl("example.nl:8080/x"))
        assertNull(webUrl("javascript:alert(1)"))
        assertNull(webUrl("intent://scan/#Intent;scheme=zxing;end"))
        assertNull(webUrl("file:///sdcard/x"))
        assertNull(webUrl("ftp://example.nl"))
        assertNull(webUrl("two words"))
        assertNull(webUrl(""))
        assertNull(webUrl(null))
    }

    @Test
    fun aFarWalkIsADistance() {
        assertEquals(walkMinutes(app.atlas.ui.kit.WalkTime.seconds(500.0)), walkOrDistance(500.0))
        assertEquals("12 km", walkOrDistance(12_000.0))
        assertEquals("480 km", walkOrDistance(480_000.0))
    }

    @Test
    fun anEveningEndingAtMidnightSaysSo() {
        assertEquals("18:00–24:00", spanWords(listOf(OpenSpan(18 * 60, 1440, OpenState.OPEN))))
        assertEquals("24 h", spanWords(listOf(OpenSpan(0, 1440, OpenState.OPEN))))
        assertEquals("18:00–1:00", spanWords(listOf(OpenSpan(18 * 60, 25 * 60, OpenState.OPEN))))
    }

    @Test
    fun anOpenEndNamesNoClosingTime() {
        // a day's words: the stated end and a "+", or the start and a "+", never the guess of how long it lasts
        assertEquals("16:00–2:00+", spanWords(OpeningHours.parse("Tu-Su 16:00-02:00+").weekTable()[6]))
        assertEquals("17:00+", spanWords(OpeningHours.parse("Mo-Su 17:00+").weekTable()[0]))
        assertEquals("10:00–14:00, 17:00+", spanWords(OpeningHours.parse("Mo 10:00-14:00,17:00+").weekTable()[0]))
        // a row at 21:00: neither "closed when you get there" nor minutes to spare from the guess
        val evening = LocalDateTime.of(2026, 11, 17, 21, 0)
        val bar = OpeningHours.parse("Mo-Su 17:00+").status(evening)
        assertNull(arrivalOf(bar, 90, evening))
        val record = PlaceRecord(Place(OsmId(OsmType.NODE, 1), LatLon(52.37, 4.89), mapOf("amenity" to "bar", "opening_hours" to "Mo-Su 17:00+")), null, null, null, "maki-bar")
        val hit = ResultHit(record, 400.0, 5, TravelMode.WALK, bar, hoursConfirmed = true, hoursStale = false, node = null, score = 1.0)
        assertNull(spareMinutes(hit, evening))
        // as before where the hours state the closing
        val pub = OpeningHours.parse("Mo-Su 17:00-22:00").status(evening)
        assertEquals(Arrival.CLOSED to -30, arrivalOf(pub, 90, evening))
        assertEquals(55, spareMinutes(hit.copy(open = pub), evening))
    }
}

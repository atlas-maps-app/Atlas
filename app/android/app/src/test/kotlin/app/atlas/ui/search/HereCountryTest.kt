package app.atlas.ui.search

import app.atlas.Services
import app.atlas.geo.LatLon
import app.atlas.model.OsmId
import app.atlas.model.Place
import app.atlas.places.FactKind
import app.atlas.places.FactState
import app.atlas.places.OpeningHours
import app.atlas.search.MemoryPlaces
import app.atlas.testing.testServices
import app.atlas.ui.eat.EatRepository
import app.atlas.ui.eat.Openness
import app.atlas.ui.shop.Open
import app.atlas.ui.shop.ShopArea
import app.atlas.ui.shop.ShopRepository
import app.atlas.ui.shop.Shelf
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Clock
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.Locale

/**
 * A place's country and clock, the one way for every screen (the reviewer, 17 November, finding 8): the place's own
 * `addr:country`, else the regions on the phone, else the map's own country at its point; never the phone's language.
 * Each on a phone set to English (United States) in Amsterdam with no region downloaded: King's Day is kept, Thanksgiving
 * is not; "What's here" reads a place on its own clock, as the place's page does; Shop's and Eat's areas keep the
 * holidays of where they are, though none of their places says its country.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class HereCountryTest {
    private val amsterdam = ZoneId.of("Europe/Amsterdam")

    /** King's Day 2026, a Monday, at noon in Amsterdam. */
    private val kingsDay: Clock = Clock.fixed(Instant.parse("2026-04-27T10:00:00Z"), amsterdam)
    private val noon = LocalDateTime.of(2026, 4, 27, 12, 0)

    /** Every day but Sunday, and never on a public holiday. */
    private val shutOnHolidays = "Mo-Sa 09:00-22:00; PH off"

    private val here = Services.WESTERKERK
    private var phone: Locale = Locale.getDefault()

    @Before
    fun anAmericanPhone() {
        phone = Locale.getDefault()
        Locale.setDefault(Locale.US)
    }

    @After
    fun back() = Locale.setDefault(phone)

    private fun place(id: Long, dLat: Double, tags: Map<String, String>) = Place(OsmId.parse("n$id")!!, LatLon(here.lat + dLat, here.lon), tags)

    /** [p] with a bench on the other side of the Westerkerk, so that the places on the phone cover the point. */
    private fun around(p: Place) = listOf(p, place(99, -0.001, mapOf("amenity" to "bench")))

    @Test
    fun anAmericanPhoneInAmsterdamKeepsKingsDayAndNotThanksgiving() {
        val s = testServices(clock = kingsDay)
        assertEquals("NL", HereContext.country(s, here))
        val hours = HereContext.hours(s, here, amsterdam)
        val h = OpeningHours.parse(shutOnHolidays)
        assertEquals("King's Day", false, h.isOpen(noon, hours))
        assertEquals("Thanksgiving", true, h.isOpen(LocalDateTime.of(2026, 11, 26, 12, 0), hours))
        s.close()
    }

    @Test
    fun whatsHereReadsAPlaceOnItsOwnClock() {
        // The test clock is Tuesday 19:30 in Amsterdam: 02:30 on Wednesday in Tokyo, where this café, which names no
        // country, is open 01:00 to 04:00. Its page says open (FindReviewTest); so does its card now.
        val s = testServices()
        val cafe = Place(OsmId.parse("n42")!!, LatLon(35.6595, 139.7005), mapOf("name" to "Shibuya Kissa", "amenity" to "cafe", "opening_hours" to "Mo-Su 01:00-04:00"))
        val hours = spotFacts(s, cafe, "en").single { it.kind == FactKind.OPENING_HOURS }
        assertEquals(FactState.YES, hours.state)
        assertEquals("04:00", hours.value)
        s.close()
    }

    @Test
    fun whatsHereKeepsTheHolidaysOfWhereThePlaceIs() {
        val s = testServices(clock = kingsDay)
        val shop = place(1, 0.001, mapOf("name" to "Kaashuis", "shop" to "cheese", "opening_hours" to shutOnHolidays))
        assertEquals(FactState.NO, spotFacts(s, shop, "en").single { it.kind == FactKind.OPENING_HOURS }.state)
        s.close()
    }

    @Test
    fun shopsAreaKeepsTheHolidaysOfWhereItIs() {
        val market = place(2, 0.001, mapOf("name" to "Buurtsuper", "shop" to "supermarket", "opening_hours" to shutOnHolidays))
        val s = testServices(clock = kingsDay, stores = listOf(MemoryPlaces(around(market))))
        val area = runBlocking {
            var last: ShopArea? = null
            withTimeout(30_000) { ShopRepository.of(s).area("t", Shelf.SUPER.filter, here).collect { last = it } }
            last!!
        }
        assertEquals("NL", area.country)
        assertEquals("Buurtsuper", area.places.single().name)
        assertEquals(Open.CLOSED, area.places.single().openness(noon, area.hours))
        s.close()
    }

    @Test
    fun eatsAreaKeepsTheHolidaysOfWhereItIs() {
        val diner = place(3, 0.001, mapOf("name" to "Eetcafé", "amenity" to "restaurant", "opening_hours" to shutOnHolidays))
        val s = testServices(clock = kingsDay, stores = listOf(MemoryPlaces(around(diner))))
        val area = runBlocking { withTimeout(30_000) { EatRepository.of(s).area(here).first { !it.loading } } }
        assertEquals("NL", area.country)
        assertEquals("Eetcafé", area.places.single().name)
        assertEquals(Openness.CLOSED, area.places.single().openness(noon, area.hours))
        s.close()
    }
}

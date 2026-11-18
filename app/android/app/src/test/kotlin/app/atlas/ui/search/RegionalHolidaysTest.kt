package app.atlas.ui.search

import android.os.Looper
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import app.atlas.Services
import app.atlas.geo.LatLon
import app.atlas.model.OsmId
import app.atlas.model.Place
import app.atlas.places.FactKind
import app.atlas.places.FactState
import app.atlas.places.OpeningHours
import app.atlas.places.OpeningHoursContext
import app.atlas.search.MemoryPlaces
import app.atlas.testing.testServices
import app.atlas.ui.shop.Open
import app.atlas.ui.shop.ShopArea
import app.atlas.ui.shop.ShopRepository
import app.atlas.ui.shop.Shelf
import app.atlas.ui.kit.AtlasTheme
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * A state's or a nation's own holidays, where the place's tags or the places around say which it is in (the reviewer,
 * 17 November, finding 10: `forPlace` took a subdivision no caller gave). Epiphany in Bavaria, 2 January and St
 * Andrew's Day but not Easter Monday in Scotland, St Patrick's Day in Northern Ireland, Good Friday in Alsace: on a
 * place's card and page, in the rows and Shop's area around a point, in the sheet's "today is a holiday" and in the
 * week's dialog. Munich, Strasbourg and Belfast name their country, which the map's own boxes there do not give.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RegionalHolidaysTest {
    @get:Rule
    val compose = createComposeRule()

    private val berlin = ZoneId.of("Europe/Berlin")
    private val london = ZoneId.of("Europe/London")
    private val munich = LatLon(48.1374, 11.5755)

    /** Epiphany 2026, a Tuesday, at 10:00 in Munich. */
    private val epiphany: Clock = Clock.fixed(Instant.parse("2026-01-06T09:00:00Z"), berlin)

    private fun place(id: Long, at: LatLon, vararg tags: Pair<String, String>) = Place(OsmId.parse("n$id")!!, at, tags.toMap())

    private fun hoursOf(s: Services, p: Place) = spotFacts(s, p, "en").single { it.kind == FactKind.OPENING_HOURS }.state

    /** Bakeries and shops of a quarter whose places name their country and [state], and a bench across the point. */
    private fun quarter(at: LatLon, state: String) = listOf(
        place(71, LatLon(at.lat + 0.001, at.lon), "shop" to "bakery", "addr:country" to "DE", "addr:state" to state),
        place(72, LatLon(at.lat + 0.001, at.lon + 0.001), "shop" to "supermarket", "name" to "Markt", "addr:country" to "DE",
            "addr:state" to state, "opening_hours" to "Mo-Sa 07:00-20:00; PH off"),
        place(73, LatLon(at.lat - 0.001, at.lon + 0.001), "amenity" to "pharmacy", "addr:country" to "DE", "addr:state" to state),
        place(74, LatLon(at.lat - 0.001, at.lon - 0.001), "amenity" to "bench"),
    )

    @Test
    fun aBakeryInMunichIsShutOnEpiphany() {
        val s = testServices(clock = epiphany)
        val bakery = place(1, munich, "name" to "Bäckerei", "shop" to "bakery", "opening_hours" to "Mo-Sa 07:00-18:00; PH off",
            "addr:country" to "DE", "addr:state" to "Bayern")
        assertEquals("Munich", FactState.NO, hoursOf(s, bakery))
        // Berlin keeps no Epiphany
        val inBerlin = place(2, LatLon(52.52, 13.405), "name" to "Bäckerei", "shop" to "bakery",
            "opening_hours" to "Mo-Sa 07:00-18:00; PH off", "addr:country" to "DE", "addr:state" to "Berlin")
        assertEquals("Berlin", FactState.YES, hoursOf(s, inBerlin))
        s.close()
    }

    @Test
    fun scotlandKeepsItsOwnDays() {
        val cafe = place(3, LatLon(55.9533, -3.1883), "name" to "Tearoom", "amenity" to "cafe",
            "opening_hours" to "Mo-Su 09:00-17:00; PH off", "addr:postcode" to "EH1 1BB")
        fun at(instant: String) = testServices(clock = Clock.fixed(Instant.parse(instant), london))
        at("2026-01-02T12:00:00Z").let { s -> assertEquals("2 January", FactState.NO, hoursOf(s, cafe)); s.close() }
        at("2026-11-30T12:00:00Z").let { s -> assertEquals("St Andrew's Day", FactState.NO, hoursOf(s, cafe)); s.close() }
        at("2026-04-06T11:00:00Z").let { s -> assertEquals("Easter Monday, England's", FactState.YES, hoursOf(s, cafe)); s.close() }
    }

    @Test
    fun belfastAndStrasbourgKeepTheirs() {
        val pub = place(4, LatLon(54.5973, -5.9301), "name" to "Bar", "amenity" to "pub", "opening_hours" to "Mo-Su 12:00-23:00; PH off",
            "addr:postcode" to "BT1 5GS", "addr:country" to "GB")
        testServices(clock = Clock.fixed(Instant.parse("2026-03-17T14:00:00Z"), london)).let { s ->
            assertEquals("St Patrick's Day", FactState.NO, hoursOf(s, pub))
            s.close()
        }
        val shop = place(5, LatLon(48.5734, 7.7521), "name" to "Boutique", "shop" to "clothes", "opening_hours" to "Mo-Sa 10:00-19:00; PH off",
            "addr:postcode" to "67000", "addr:country" to "FR")
        testServices(clock = Clock.fixed(Instant.parse("2026-04-03T10:00:00Z"), ZoneId.of("Europe/Paris"))).let { s ->
            assertEquals("Good Friday in Alsace", FactState.NO, hoursOf(s, shop))
            s.close()
        }
    }

    @Test
    fun aPlacesPageReadsItInItsState() {
        val s = testServices(clock = epiphany)
        val bakery = place(6, munich, "shop" to "bakery", "addr:country" to "DE", "addr:state" to "BY")
        val local = HereContext.localeOf(s, bakery, HereContext.phoneAt(s), berlin)
        val noon = LocalDateTime.of(2026, 1, 6, 12, 0)
        assertEquals(false, OpeningHours.parse("Mo-Sa 07:00-18:00; PH off").isOpen(noon, local.hours(munich)))
        s.close()
    }

    @Test
    fun theRowsAroundAPointReadTheStateItsPlacesName() {
        // nothing at the point names a state; most of the places around do
        val s = testServices(clock = epiphany, stores = listOf(MemoryPlaces(quarter(munich, "Bayern"), country = null)))
        val hours = HereContext.hours(s, munich, berlin)
        assertEquals("Heilige Drei Könige", hours.publicHolidays?.holiday(LocalDate.of(2026, 1, 6))?.localName)
        s.close()
        val inBerlin = LatLon(52.52, 13.405)
        val b = testServices(clock = epiphany, stores = listOf(MemoryPlaces(quarter(inBerlin, "Berlin"), country = null)))
        assertEquals(null, HereContext.hours(b, inBerlin, berlin).publicHolidays?.holiday(LocalDate.of(2026, 1, 6)))
        b.close()
    }

    @Test
    fun shopsAreaKeepsItsStatesDays() {
        val s = testServices(clock = epiphany, stores = listOf(MemoryPlaces(quarter(munich, "Bayern"), country = null)))
        val area = runBlocking {
            var last: ShopArea? = null
            withTimeout(30_000) { ShopRepository.of(s).area("t", Shelf.SUPER.filter, munich).collect { last = it } }
            last!!
        }
        assertEquals("Markt", area.places.single().name)
        assertEquals(Open.CLOSED, area.places.single().openness(LocalDateTime.of(2026, 1, 6, 10, 0), area.hours))
        s.close()
    }

    @Test
    fun theSheetKnowsTodayIsAHolidayThere() {
        val s = testServices(clock = epiphany, stores = listOf(MemoryPlaces(quarter(munich, "Bayern"), country = null)))
        val m = SearchSheetModel(s, munich, "en")
        m.moveTo(munich)
        val end = System.currentTimeMillis() + 20_000
        while (m.state.value.gridOrigin == null && System.currentTimeMillis() < end) {
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(50))
            Thread.sleep(20)
        }
        assertTrue("the grid came", m.state.value.gridOrigin != null)
        assertEquals("Epiphany in Bavaria", true, m.state.value.moment.holiday)
        s.close()
    }

    @Test
    fun theWeekNamesTheStatesHoliday() {
        val ctx = OpeningHoursContext.forPlace("DE", berlin, munich.lat, munich.lon, "DE-BY")
        val raw = "Mo-Sa 07:00-18:00; PH off"
        compose.setContent { AtlasTheme(dark = false) { WeekTableDialog(OpeningHours.parse(raw), raw, LocalDate.of(2026, 1, 5), ctx) {} } }
        compose.waitForIdle()
        compose.onNodeWithText("Heilige Drei Könige").assertExists()
    }
}

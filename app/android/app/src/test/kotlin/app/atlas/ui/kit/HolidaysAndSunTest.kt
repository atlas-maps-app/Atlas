package app.atlas.ui.kit

import app.atlas.Services
import app.atlas.geo.LatLon
import app.atlas.model.OsmId
import app.atlas.model.Place
import app.atlas.places.OpeningHoursContext
import app.atlas.search.MemoryPlaces
import app.atlas.testing.testServices
import app.atlas.ui.go.EarsSession
import app.atlas.ui.home.HighlightKind
import app.atlas.ui.home.HomeHighlights
import app.atlas.ui.local.LookScene
import app.atlas.ui.local.Moment
import app.atlas.ui.local.MomentInputs
import app.atlas.ui.local.Moments
import app.atlas.ui.local.Parkland
import app.atlas.ui.local.isShut
import app.atlas.ui.local.marketLine
import app.atlas.ui.search.HereContext
import app.atlas.ui.search.PlaceLocale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId

/**
 * Every row, card and line reads a place's hours in the context of where it is, its country's holidays and its real
 * sun (the reviewer, 17 November, finding 7: no default context, so the compiler finds every caller). The reviewer's
 * shop, `Mo-Sa 10:00-18:00; Su 12:00-17:00; PH off`, is shut on Christmas Day in Amsterdam on the rows, the Picnic and
 * Swim cards, the ears, Look's labels, the moments and the home's highlights, as on its own page; `sunrise-sunset` lasts
 * till the June sunset, not till 18:00.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class HolidaysAndSunTest {
    private val amsterdam = ZoneId.of("Europe/Amsterdam")
    private val here = Services.WESTERKERK
    private val shop = "Mo-Sa 10:00-18:00; Su 12:00-17:00; PH off"

    /** Christmas Day 2026, a Friday, at noon in Amsterdam. */
    private val christmas = LocalDateTime.of(2026, 12, 25, 12, 0)

    /** Midsummer 2026, a Sunday, at 20:00 in Amsterdam: two hours before sunset. */
    private val midsummer = LocalDateTime.of(2026, 6, 21, 20, 0)

    private fun place(id: Long, tags: Map<String, String>) = Place(OsmId.parse("n$id")!!, LatLon(here.lat + 0.0005, here.lon), tags)

    /** The hours where the Westerkerk is, as the app gives them. */
    private fun hours(): OpeningHoursContext = testServices().let { s -> HereContext.hours(s, here, amsterdam).also { s.close() } }

    @Test
    fun aRowKeepsChristmasAsTheShopsPageDoes() {
        val row = PlaceSummary.of(place(1, mapOf("shop" to "gift", "name" to "Cadeau", "opening_hours" to shop)), here, "en", christmas, hours())
        assertEquals(false, row.open?.isOpen)
    }

    @Test
    fun aPicnicLawnIsOpenTillTheJuneSunset() {
        val lawn = place(2, mapOf("leisure" to "park", "name" to "Weide", "opening_hours" to "sunrise-sunset"))
        assertFalse("open at 20:00 in June", isShut(lawn, here, midsummer, hours()))
        val span = Parkland.todaysSpan("sunrise-sunset", midsummer.toLocalDate(), hours())!!
        assertTrue(span, Regex("5:\\d\\d–22:\\d\\d").matches(span))
    }

    @Test
    fun theEarsLookAndTheMarketsKeepChristmas() {
        val h = hours()
        val bar = place(3, mapOf("amenity" to "bar", "name" to "Kroeg", "opening_hours" to shop))
        val noon = christmas.atZone(amsterdam).toInstant()
        assertEquals(LocalTime.of(18, 0), EarsSession.closesAt(bar, noon.minusSeconds(86_400), amsterdam, h))
        assertNull(EarsSession.closesAt(bar, noon, amsterdam, h))
        assertEquals(false, LookScene.status(shop, christmas, h)?.first)
        // the market's next stretch is on Monday: the 26th is a holiday too
        val market = marketLine("Mo-Sa 09:00-17:00; PH off", noon, amsterdam, h, "open") { "open now until $it" }
        assertEquals("Mon 9–17", market)
    }

    @Test
    fun noMomentSaysASavedShopClosesOnChristmas() {
        val saved = place(4, mapOf("shop" to "gift", "name" to "Cadeau", "opening_hours" to shop))
        val late = LocalDateTime.of(2026, 12, 25, 17, 0).atZone(amsterdam).toInstant()
        val moments = Moments.build(MomentInputs(late, amsterdam, here, null, listOf(saved), emptyList(), null, emptyList()) { PlaceLocale("NL", amsterdam) })
        assertTrue(moments.toString(), moments.none { it is Moment.Closing || it is Moment.Opening })
    }

    @Test
    fun theHomeShowsNoCafeOpenOnChristmas() {
        val cafe = place(5, mapOf("amenity" to "cafe", "brand" to "Koffieketen", "name" to "Koffieketen", "opening_hours" to shop))
        val picked = HomeHighlights.pick(MemoryPlaces(listOf(cafe), country = null), here, christmas, hours())
        assertTrue(picked.toString(), picked.none { it.kind == HighlightKind.BRANCH })
        // the day before, it is
        assertTrue(HomeHighlights.pick(MemoryPlaces(listOf(cafe), country = null), here, christmas.minusDays(1), hours()).any { it.kind == HighlightKind.BRANCH })
    }
}

package app.atlas.ui.local

import app.atlas.geo.LatLon
import app.atlas.ui.local.astro.OrbitSource
import app.atlas.ui.local.astro.Planet
import app.atlas.ui.local.astro.TleStore
import app.atlas.ui.local.astro.Tonight
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.File
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset

/** Bugs found in review, each held by a test: wrong words for bins, feet for metres, polar nights, a CelesTrak that is asked too often, a street named for a hotel. */
class LocalReviewTest {
    @get:Rule
    val folder = TemporaryFolder()

    private fun resource(name: String) = LocalReviewTest::class.java.getResourceAsStream("/local/$name")!!.bufferedReader().readText()

    @Test
    fun aShortWordOfAWasteCalendarMustStartAWord() {
        // "tri" is French for sorting, but it is also the end of "district"; "rest" is the grey bin, not "forest".
        assertEquals(Stream.OTHER, WasteCalendar.classify("District clean-up day"))
        assertEquals(Stream.OTHER, WasteCalendar.classify("Forest walk"))
        assertEquals(Stream.PACKAGING, WasteCalendar.classify("Tri sélectif"))
        assertEquals(Stream.REST, WasteCalendar.classify("Restafval"))
        assertEquals(Stream.ORGANIC, WasteCalendar.classify("Bio-Tonne"))
        assertEquals(Stream.GLASS, WasteCalendar.classify("Glasbak"))
    }

    @Test
    fun aDailyCalendarThatBeganYearsAgoStillGivesItsDays() {
        val ics = "BEGIN:VCALENDAR\nBEGIN:VEVENT\nSUMMARY:Restafval\nDTSTART;VALUE=DATE:20000101\nRRULE:FREQ=DAILY;INTERVAL=3\nEND:VEVENT\nEND:VCALENDAR\n"
        val cal = WasteCalendar.parse(ics)!!
        val from = java.time.LocalDate.of(2026, 10, 12)
        val since = java.time.temporal.ChronoUnit.DAYS.between(java.time.LocalDate.of(2000, 1, 1), from)
        val expected = from.plusDays((3 - since % 3) % 3)
        assertEquals(listOf(expected, expected.plusDays(3)), cal.pickups(from, from.plusDays(5)).map { it.date })
    }

    @Test
    fun aKeptForecastThatDoesNotReachNowSaysNothingAboutRain() {
        val now = Instant.parse("2026-10-16T10:00:00Z")
        val here = LatLon(52.3731, 4.8922)
        val old = app.atlas.providers.Forecast(
            here, 0.0, "Europe/Amsterdam", 7200, null, listOf(app.atlas.providers.Precipitation15(now.plusSeconds(1800), 2.0)), emptyList(), emptyList(),
            fetchedAt = now.minus(Duration.ofDays(3)), kept = true,
        )
        val moments = Moments.build(MomentInputs(now, ZoneId.of("Europe/Amsterdam"), here, old, emptyList(), emptyList(), null, emptyList()))
        assertTrue(moments.none { it is Moment.RainStarts || it is Moment.RainStops })
    }

    @Test
    fun aHeightInFeetIsConvertedToMetres() {
        assertEquals(12.192, Footprints.height(mapOf("height" to "40 ft"))!!, 1e-6)
        assertEquals(3.048, Footprints.height(mapOf("height" to "10'"))!!, 1e-6)
        assertEquals(40.0, Footprints.height(mapOf("height" to "40 m"))!!, 1e-9)
        assertEquals(9.0, Footprints.height(mapOf("height" to "about nine", "building:levels" to "3"))!!, 1e-9)
    }

    @Test
    fun aCalendarFileThatIsTooBigIsNotRead() {
        assertEquals("BEGIN:VCALENDAR", readCapped(ByteArrayInputStream("BEGIN:VCALENDAR".toByteArray())))
        assertNull(readCapped(ByteArrayInputStream(ByteArray(5_000)), limit = 4_000))
        assertEquals("", readCapped(ByteArrayInputStream(ByteArray(0))))
    }

    @Test
    fun theNightIsWorkedOutAtEveryLatitudeAndDayWithoutFailing() {
        // Near and inside the polar circles the sun does not always rise or set; a missing sunrise must not be a crash.
        val zone = ZoneId.of("Europe/Oslo")
        for ((lat, lon) in listOf(69.65 to 18.96, 66.5 to 25.7, 78.2 to 15.6, -77.85 to 166.7, 64.1 to -21.9)) {
            var day = Instant.parse("2026-01-01T12:00:00Z")
            repeat(73) {
                for (hour in listOf(0L, 6L, 12L, 18L)) {
                    val night = Tonight.planets(day.plus(Duration.ofHours(hour)), lat, lon, zone, Planet.visibleFromEarth)
                    assertEquals(Planet.visibleFromEarth.size, night.size)
                }
                day = day.plus(Duration.ofDays(5))
            }
        }
    }

    private class MovableClock(var at: Instant) : Clock() {
        override fun getZone(): ZoneId = ZoneOffset.UTC
        override fun withZone(zone: ZoneId?): Clock = this
        override fun instant(): Instant = at
    }

    @Test
    fun aFailedFetchOfTheOrbitDataIsNotRepeatedWithinTwoHours() = runBlocking {
        val clock = MovableClock(Instant.parse("2026-10-16T10:00:00Z"))
        var asked = 0
        val store = TleStore(
            file = folder.newFile("iss.tle").also { it.delete() },
            bundled = { resource("iss_2026-10-15.tle") },
            fetch = { asked++; throw IllegalStateException("the server answered a login page") },
            clock = clock,
        )
        val first = store.orbit(online = true)
        assertNotNull("the bundled set stands in", first)
        store.orbit(online = true)
        store.orbit(online = true)
        assertEquals("asked once, not on every screen", 1, asked)
        clock.at = clock.at.plus(Duration.ofHours(3))
        store.orbit(online = true)
        assertEquals("and again when two hours are up", 2, asked)
    }

    @Test
    fun whereCelesTrakGivesNothingThatReadsArissGivesTheOrbitData() = runBlocking {
        val clock = MovableClock(Instant.parse("2026-10-16T10:00:00Z"))
        val set = resource("iss_2026-10-15.tle")
        var celestrak = 0
        var ariss = 0
        val file = folder.newFile("iss.tle").also { it.delete() }
        val store = TleStore(
            file = file,
            bundled = { null },
            fetch = { celestrak++; "<html>403: GP data has not updated since your last successful download</html>" },
            clock = clock,
            second = { ariss++; set },
        )
        val got = store.orbit(online = true)!!
        assertEquals(OrbitSource.FRESH, got.source)
        assertEquals(set, file.readText())
        assertEquals(1 to 1, celestrak to ariss)
        file.setLastModified(clock.at.toEpochMilli())
        // neither is asked again within two hours; where CelesTrak answers, ARISS is not asked
        store.orbit(online = true)
        assertEquals(1 to 1, celestrak to ariss)
        clock.at = clock.at.plus(Duration.ofHours(3))
        TleStore(file, { null }, { celestrak++; set }, clock, second = { ariss++; set }).orbit(online = true)
        assertEquals(2 to 1, celestrak to ariss)
    }

    @Test
    fun aFreshOrbitSetThatCannotBeKeptIsStillUsed() = runBlocking {
        // the kept copy's folder is a file (as good as a full disk): the write fails, and the set that came still answers
        val blocker = folder.newFile("not-a-folder")
        val set = resource("iss_2026-10-15.tle")
        val store = TleStore(File(blocker, "iss.tle"), { null }, { set }, MovableClock(Instant.parse("2026-10-16T10:00:00Z")))
        assertEquals(OrbitSource.FRESH, store.orbit(online = true)!!.source)
    }

    @Test
    fun aStreetIsNotNamedAfterTheNamesakeOfAHotelWithTheSameName() = runBlocking {
        val hotel = """{"entities":{"Q1":{"labels":{"en":{"value":"Rembrandtplein Hotel"}},"claims":{
            "P31":[{"mainsnak":{"datavalue":{"value":{"id":"Q27686"}}}}],
            "P625":[{"mainsnak":{"datavalue":{"value":{"latitude":52.3661,"longitude":4.8966}}}}],
            "P138":[{"mainsnak":{"datavalue":{"value":{"id":"Q5598"}}}}]}}}}"""
        val etymology = Etymology { url -> if ("wbsearchentities" in url) """{"search":[{"id":"Q1"}]}""" else hotel }
        val found = etymology.lookup(LatLon(52.36605, 4.89664), listOf(LocalFixtures.rembrandtplein), "Rembrandtplein", listOf("en"), online = true)
        assertEquals(NamedAfter.State.NOT_RECORDED, found.state)
        assertTrue(found.namesakes.isEmpty())
    }
}

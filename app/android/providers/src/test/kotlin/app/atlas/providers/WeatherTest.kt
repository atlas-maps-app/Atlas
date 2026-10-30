package app.atlas.providers

import app.atlas.geo.LatLon
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.time.Instant
import java.time.LocalDate

/** MET Norway nowhere: these tests are Open-Meteo's, and no test may reach the real MET. */
internal const val NO_MET = "http://127.0.0.1:9"

class WeatherTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val amsterdam = LatLon(52.3745, 4.8838)
    private val fetched = Instant.parse("2026-10-13T17:35:00Z")

    private fun forecast() = Weather.parseForecast(ProviderJson.parseToJsonElement(Fixtures.text("meteo_forecast.json")).asObject()!!, fetched)

    @Test
    fun parsesNowHoursDaysAndQuarters() {
        val f = forecast()
        assertEquals("Europe/Amsterdam", f.timezone)
        assertEquals(7200, f.utcOffsetSeconds)
        val now = f.now!!
        assertEquals(17.0, now.temperature, 0.0)
        assertEquals(Sky.CLEAR, now.sky)
        assertFalse(now.isDay)
        assertEquals(283.0, now.windDirection, 0.0)
        assertEquals(45.4, now.windGusts, 0.0)
        assertEquals(1019.8, now.pressureMsl, 0.0)
        assertEquals(48, f.hours.size)
        assertEquals(7, f.days.size)
        assertEquals(24, f.quarters.size)
        assertEquals(LocalDate.of(2026, 10, 13), f.days.first().date)
        val d = f.days.first()
        assertTrue(d.sunrise!!.isBefore(d.sunset))
        assertTrue(d.temperatureMax >= d.temperatureMin)
        // Hours run on the hour, one apart.
        assertTrue(f.hours.zipWithNext().all { (a, b) -> b.time.epochSecond - a.time.epochSecond == 3600L })
        assertEquals(f.hours[3], f.hourAt(f.hours[3].time.plusSeconds(1800)))
    }

    @Test
    fun rainStartsAndStops() {
        val t0 = Instant.parse("2026-10-14T16:00:00Z")
        fun q(min: Long, mm: Double) = Precipitation15(t0.plusSeconds(min * 60), mm)
        val f = forecast().copy(quarters = listOf(q(0, 0.0), q(15, 0.0), q(30, 0.3), q(45, 0.6), q(60, 0.0)), hours = emptyList())
        assertEquals(t0.plusSeconds(30 * 60), f.nextRain(t0))
        assertNull(f.rainEnds(t0))
        assertEquals(t0.plusSeconds(60 * 60), f.rainEnds(t0.plusSeconds(35 * 60)))
        assertNull(f.copy(quarters = listOf(q(0, 0.0))).nextRain(t0))
    }

    @Test
    fun skyCodesAndSymbols() {
        assertEquals(Sky.CLEAR, Sky.of(0))
        assertEquals(Sky.FOG, Sky.of(48))
        assertEquals(Sky.FREEZING_RAIN, Sky.of(67))
        assertEquals(Sky.THUNDERSTORM_HAIL, Sky.of(99))
        assertEquals(Sky.UNKNOWN, Sky.of(null))
        assertTrue(Sky.SHOWERS.wet)
        assertFalse(Sky.OVERCAST.wet)
    }

    @Test
    fun keepsTheForecastForNoSignal() = runBlocking {
        val cache = tmp.newFolder("cache")
        Mock().use { m ->
            m.fixture("meteo_forecast.json")
            val w = Weather(testHttp(), cache, base = m.base, metBase = NO_MET) { fetched }
            val f = w.forecast(amsterdam)
            assertFalse(f.kept)
            val req = m.take()
            assertEquals("/v1/forecast", req.url.encodedPath)
            assertEquals("52.3745", req.url.queryParameter("latitude"))
            assertEquals("unixtime", req.url.queryParameter("timeformat"))
            assertTrue(req.url.queryParameter("hourly")!!.contains("precipitation_probability"))
            assertTrue(req.url.queryParameter("daily")!!.contains("sunrise"))
            assertNull(req.url.queryParameter("apikey"))
        }
        // No signal: a place 2 km away gets the kept forecast, marked as kept, with its original time.
        var now = fetched.plusSeconds(6 * 3600)
        val offline = Weather(testHttp(), cache, base = "http://127.0.0.1:9", metBase = NO_MET) { now }
        val kept = offline.forecast(LatLon(52.36, 4.90))
        assertTrue(kept.kept)
        assertEquals(fetched, kept.fetchedAt)
        assertEquals(48, kept.hours.size)
        // Too far from anything kept: the failure shows.
        try {
            offline.forecast(LatLon(48.85, 2.35))
            fail()
        } catch (_: java.io.IOException) {
        }
        // A forecast kept for over a week has run out: it is not passed off as the weather.
        now = fetched.plusSeconds(8 * 86_400)
        assertNull(offline.kept(amsterdam))
        try {
            offline.forecast(amsterdam)
            fail()
        } catch (_: java.io.IOException) {
        }
    }

    @Test
    fun aForecastThatCameIsShownEvenWhenItCannotBeKept() = runBlocking {
        // The cache folder cannot be written (here it is a file): keeping fails, but the forecast that arrived stands.
        val notAFolder = tmp.newFile("full")
        Mock().use { m ->
            m.fixture("meteo_forecast.json")
            val f = Weather(testHttp(), notAFolder, base = m.base, metBase = NO_MET) { fetched }.forecast(amsterdam)
            assertEquals(48, f.hours.size)
            assertFalse(f.kept)
        }
    }

    @Test
    fun dailyDatesSurviveAClockChange() {
        // Asked on 27 March 2026 in Amsterdam (UTC+1); summer time starts on the 29th, so later days begin at 22:00 UTC.
        val midnights = listOf("2026-03-27T23:00:00Z", "2026-03-28T23:00:00Z", "2026-03-29T22:00:00Z", "2026-03-30T22:00:00Z").map { Instant.parse(it).epochSecond }
        val json = ProviderJson.parseToJsonElement(
            """{"latitude":52.37,"longitude":4.89,"timezone":"Europe/Amsterdam","utc_offset_seconds":3600,"daily":{"time":$midnights,"weather_code":[3,3,3,3]}}""",
        ).asObject()!!
        val days = Weather.parseForecast(json, fetched).days
        assertEquals((28..31).map { LocalDate.of(2026, 3, it) }, days.map { it.date })
        // And back again in October, when days begin an hour later than the offset of the day it was asked.
        val autumn = listOf("2026-10-23T22:00:00Z", "2026-10-24T22:00:00Z", "2026-10-25T23:00:00Z").map { Instant.parse(it).epochSecond }
        val json2 = ProviderJson.parseToJsonElement(
            """{"timezone":"Europe/Amsterdam","utc_offset_seconds":7200,"daily":{"time":$autumn,"weather_code":[1,1,1]}}""",
        ).asObject()!!
        assertEquals((24..26).map { LocalDate.of(2026, 10, it) }, Weather.parseForecast(json2, fetched).days.map { it.date })
    }

    @Test
    fun aBadRequestIsReportedNotHidden() = runBlocking {
        Mock().use { m ->
            m.fixture("meteo_error.json", 400)
            try {
                Weather(testHttp(), base = m.base, metBase = NO_MET).forecast(amsterdam)
                fail()
            } catch (e: ProviderException) {
                assertTrue(e.message!!.contains("Latitude must be in range"))
            }
        }
    }

    @Test
    fun aCommercialKeyUsesTheCustomerHost() = runBlocking {
        Mock().use { m ->
            m.fixture("meteo_forecast.json")
            Weather(testHttp(), keys = Keys.of("open-meteo" to "k123"), customerBase = m.base, metBase = NO_MET).forecast(amsterdam)
            assertEquals("k123", m.take().url.queryParameter("apikey"))
        }
    }

    @Test
    fun marineForBoatsAndNothingOnLand() = runBlocking {
        Mock().use { m ->
            m.fixture("meteo_marine.json")
            m.fixture("meteo_marine_land.json")
            val w = Weather(testHttp(), marineBase = m.base, metBase = NO_MET)
            val sea = w.marine(LatLon(52.47, 4.45))!!
            assertEquals(1.52, sea.now!!.waveHeight, 0.0)
            assertEquals(261.0, sea.now.waveDirection, 0.0)
            assertEquals(16.9, sea.now.waterTemperature, 0.0)
            assertEquals(48, sea.hours.size)
            assertTrue(sea.hours.all { !it.waveHeight.isNaN() })
            assertNotNull(sea.hours.first().swellPeriod)
            assertEquals("/v1/marine", m.take().url.encodedPath)
            assertNull(w.marine(LatLon(50.0, 10.0)))
        }
    }

    @Test
    fun theSeasForecastIsKeptForNoSignalAndRunsOutWithItsHours() = runBlocking {
        val cache = tmp.newFolder("sea")
        var now = fetched
        Mock().use { m ->
            m.fixture("meteo_marine.json")
            val sea = Weather(testHttp(), cache, marineBase = m.base, metBase = NO_MET) { now }.marine(LatLon(52.47, 4.45))!!
            assertFalse(sea.kept)
        }
        // no signal five hours on, a kilometre away: the kept forecast, marked kept, the sea now its hour of now
        now = fetched.plusSeconds(5 * 3600)
        val offline = Weather(testHttp(), cache, marineBase = NO_MET, metBase = NO_MET) { now }
        val kept = offline.marine(LatLon(52.46, 4.44))!!
        assertTrue(kept.kept)
        assertEquals(fetched, kept.fetchedAt)
        assertEquals(Instant.parse("2026-10-13T22:00:00Z"), kept.now!!.time)
        assertEquals(kept.hours.last { !it.time.isAfter(now) }, kept.now)
        // the app's offline path asks the kept one alone
        assertTrue(offline.keptMarine(LatLon(52.47, 4.45))!!.kept)
        // too far from anything kept: the failure shows
        assertTrue(runCatching { offline.marine(LatLon(54.0, 3.0)) }.exceptionOrNull() is java.io.IOException)
        // two days on its hours have run out: not passed off as the sea
        now = fetched.plusSeconds(2 * 86_400 + 60)
        assertNull(offline.keptMarine(LatLon(52.47, 4.45)))
    }
}

package app.atlas.providers

import app.atlas.geo.LatLon
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

class MetNorwayTest {
    private val amsterdam = LatLon(52.3745, 4.8838)
    private val fetched = Instant.parse("2026-10-13T17:35:00Z")
    private val zone = ZoneId.of("Europe/Amsterdam")

    private fun forecast() = MetNorway.parse(
        ProviderJson.parseToJsonElement(Fixtures.text("met_forecast.json")).asObject()!!, amsterdam, fetched, zone,
    )

    @Test
    fun readsNowAndTheHoursInOpenMeteosTerms() {
        val f = forecast()
        assertEquals("Europe/Amsterdam", f.timezone)
        assertEquals(7200, f.utcOffsetSeconds)
        assertEquals(2.0, f.elevation, 0.0)
        assertTrue(f.quarters.isEmpty())
        // the hour that began at 17:00 is now; the one before has passed
        val now = f.now!!
        assertEquals(Instant.parse("2026-10-13T17:00:00Z"), now.time)
        assertEquals(17.0, now.temperature, 0.0)
        assertEquals(1, now.code)
        assertEquals(Sky.MAINLY_CLEAR, now.sky)
        assertTrue(now.isDay)
        // MET's m/s become the km/h Open-Meteo is asked for
        assertEquals(18.0, now.windSpeed, 1e-9)
        assertEquals(45.36, now.windGusts, 1e-9)
        assertEquals(1019.8, now.pressureMsl, 0.0)
        assertFalse(now.feelsLike.isNaN())
        // only the hourly steps, from the current hour on
        assertEquals(listOf(1, 3, 45, 61, 95, 85), f.hours.map { it.code })
        assertEquals(Instant.parse("2026-10-13T17:00:00Z"), f.hours.first().time)
    }

    @Test
    fun tellsDayFromNightAndFogFromClearAir() {
        val h = forecast().hours
        // no suffix on "cloudy": the clear-sky UV index (0 after sunset) says it is night
        assertFalse(h[1].isDay)
        assertEquals(1_000.0, h[2].visibility, 0.0)
        assertTrue(h[3].visibility.isNaN())
        assertEquals(0.4, h[3].precipitation, 0.0)
        assertEquals(60.0, h[3].precipitationProbability, 0.0)
        assertTrue(h[2].precipitationProbability.isNaN())
        assertEquals(Sky.THUNDERSTORM, h[4].sky)
        assertFalse(h[4].isDay)
        assertEquals(Sky.SNOW_SHOWERS, h[5].sky)
    }

    @Test
    fun groupsTheDaysInTheZoneWithTheirWorstWeather() {
        val d = forecast().days
        assertEquals(listOf(LocalDate.of(2026, 10, 13), LocalDate.of(2026, 10, 14)), d.map { it.date })
        assertEquals(Sky.THUNDERSTORM, d[0].sky)
        assertEquals(17.6, d[0].temperatureMax, 0.0)
        assertEquals(3.6, d[0].precipitationSum, 1e-9)
        // 22:00 UTC is midnight in Amsterdam: the snow shower opens the 14th, before the six-hour blocks
        assertEquals(Sky.SNOW_SHOWERS, d[1].sky)
        assertEquals(15.5, d[1].temperatureMax, 0.0)
        assertEquals(2.1, d[1].temperatureMin, 0.0)
        assertEquals(6.2, d[1].precipitationSum, 1e-9)
        assertEquals(7.0, d[1].precipitationHours, 0.0)
        assertNull(d[1].sunrise)
    }

    @Test
    fun mapsTheSymbolsToWmoCodes() {
        assertEquals(0, MetNorway.code("clearsky_night"))
        assertEquals(2, MetNorway.code("partlycloudy_polartwilight"))
        assertEquals(63, MetNorway.code("sleet"))
        assertEquals(82, MetNorway.code("heavyrainshowers_day"))
        assertEquals(75, MetNorway.code("heavysnow"))
        assertEquals(95, MetNorway.code("lightssnowshowersandthunder_day"))
        assertNull(MetNorway.code(null))
        assertNull(MetNorway.code("somethingnew"))
    }

    @Test
    fun answersWhenOpenMeteoCannot() = runBlocking {
        Mock().use { meteo ->
            Mock().use { met ->
                meteo.enqueue("down", 503)
                met.fixture("met_forecast.json")
                val f = Weather(testHttp(), base = meteo.base, metBase = met.base, zone = { zone }) { fetched }.forecast(amsterdam)
                assertFalse(f.kept)
                assertEquals(6, f.hours.size)
                val req = met.take()
                assertEquals("/weatherapi/locationforecast/2.0/complete", req.url.encodedPath)
                assertEquals("52.3745", req.url.queryParameter("lat"))
                assertEquals("4.8838", req.url.queryParameter("lon"))
            }
        }
    }

    @Test
    fun answersWhenOpenMeteoRefusesTheRequestOrAnswersSomethingElse() = runBlocking {
        // a 400 (a key it no longer takes, say), then a Wi-Fi's sign-in page answering 200: MET Norway answers both times
        Mock().use { meteo ->
            Mock().use { met ->
                meteo.fixture("meteo_error.json", 400)
                meteo.enqueue("<html><body>Sign in to the hotel's Wi-Fi</body></html>", 200)
                met.fixture("met_forecast.json")
                met.fixture("met_forecast.json")
                val w = Weather(testHttp(), base = meteo.base, metBase = met.base, zone = { zone }) { fetched }
                assertEquals(6, w.forecast(amsterdam).hours.size)
                assertEquals(6, w.forecast(amsterdam).hours.size)
                assertEquals(2, met.count)
            }
        }
    }

    @Test
    fun failsWhenNeitherAnswersAndNothingIsKept() = runBlocking {
        Mock().use { meteo ->
            Mock().use { met ->
                meteo.enqueue("down", 503)
                met.enqueue("down too", 503)
                try {
                    Weather(testHttp(), base = meteo.base, metBase = met.base) { fetched }.forecast(amsterdam)
                    org.junit.Assert.fail("nothing kept: the failure shows")
                } catch (_: HttpException) {
                }
            }
        }
    }
}

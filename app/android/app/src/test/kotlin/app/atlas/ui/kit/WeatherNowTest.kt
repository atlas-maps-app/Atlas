package app.atlas.ui.kit

import app.atlas.Services
import app.atlas.providers.Sky
import app.atlas.testing.keepFixtureForecast
import app.atlas.testing.testServices
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Instant

/** The sky now, from a real Open-Meteo forecast kept on the phone: fresh conditions, then the hour, then nothing. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class WeatherNowTest {
    private val forecast by lazy {
        val s = testServices()
        assertTrue(s.keepFixtureForecast())
        runBlocking { s.weather.kept(Services.WESTERKERK) }!!
    }

    @Test
    fun freshConditionsAreShownAsTheyAre() {
        val w = CurrentWeather.of(forecast, Instant.parse("2026-10-13T17:45:00Z"))!!
        assertEquals(forecast.now!!.temperature, w.temperature, 0.0)
        assertEquals(forecast.now!!.isDay, w.isDay)
        assertTrue(w.kept)
        assertEquals(skySymbol(w.sky, w.isDay), w.symbol)
    }

    @Test
    fun laterTheForecastHourStandsIn() {
        val at = Instant.parse("2026-10-14T10:20:00Z")
        val w = CurrentWeather.of(forecast, at)
        val hour = forecast.hourAt(at)
        assertNotNull(hour)
        assertEquals(hour!!.temperature, w!!.temperature, 0.0)
        assertEquals(hour.isDay, w.isDay)
    }

    @Test
    fun beyondTheForecastThereIsNothingToShow() {
        assertNull(CurrentWeather.of(forecast, Instant.parse("2026-10-20T12:00:00Z")))
        assertNull(CurrentWeather.of(null, Instant.parse("2026-10-13T17:30:00Z")))
    }

    @Test
    fun skiesHaveDayAndNightSymbols() {
        assertEquals("ui-sun", skySymbol(Sky.CLEAR, true))
        assertEquals("ui-moon", skySymbol(Sky.CLEAR, false))
        assertEquals("ui-cloud-rain", skySymbol(Sky.SHOWERS, true))
        for (sky in Sky.entries) for (day in listOf(true, false)) assertTrue(sky.name, hasSymbol(skySymbol(sky, day)))
    }

    @Test
    fun aMissingTemperatureIsNotShownAsANumber() {
        // A model that has no value for now (null in the JSON, NaN here) leaves the chip without a sky rather than
        // crashing it on "NaN°": the hour stands in, else nothing.
        val at = Instant.parse("2026-10-13T17:45:00Z")
        val blankNow = forecast.copy(now = forecast.now!!.copy(temperature = Double.NaN))
        val w = CurrentWeather.of(blankNow, at)
        assertEquals(forecast.hourAt(at)!!.temperature, w!!.temperature, 0.0)
        val blank = blankNow.copy(hours = blankNow.hours.map { it.copy(temperature = Double.NaN) })
        assertNull(CurrentWeather.of(blank, at))
    }
}

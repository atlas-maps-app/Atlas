package app.atlas.ui.home

import app.atlas.Services
import app.atlas.providers.Forecast
import app.atlas.providers.Precipitation15
import app.atlas.providers.Sky
import app.atlas.providers.WeatherHour
import app.atlas.providers.WeatherNow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.ZoneId

class MomentsTest {
    private val ams = ZoneId.of("Europe/Amsterdam")
    private val at = Services.WESTERKERK

    private fun now(t: Instant, uv: Double = 1.0, day: Boolean = true) =
        WeatherNow(t, 14.0, 13.0, 70.0, Sky.PARTLY_CLOUDY, 2, day, 40.0, 1015.0, 1014.0, 0.0, 12.0, 220.0, 25.0, uv)

    private fun hour(t: Instant, mm: Double, gusts: Double = 20.0) =
        WeatherHour(t, 14.0, 13.0, 50.0, mm, Sky.RAIN, 61, 80.0, 12.0, 220.0, gusts, 1.0, true, 1015.0, 20_000.0)

    private fun forecast(t: Instant, quarters: List<Precipitation15>, hours: List<WeatherHour>, uv: Double = 1.0) =
        Forecast(at, 2.0, "Europe/Amsterdam", 7200, now(t, uv), quarters, hours, emptyList(), t)

    @Test
    fun rainComingInFortyMinutes() {
        val t = Instant.parse("2026-10-13T08:00:00Z")
        val q = (0 until 16).map { Precipitation15(t.plusSeconds(it * 900L), if (it >= 3) 0.4 else 0.0) }
        val m = Moments.of(forecast(t, q, emptyList()), at, t, ams)
        assertEquals(MomentKind.RAIN, m.first().kind)
        assertEquals(t.plusSeconds(45 * 60), m.first().at)
    }

    @Test
    fun rainStoppingSoon() {
        val t = Instant.parse("2026-10-13T08:00:00Z")
        val q = (0 until 16).map { Precipitation15(t.plusSeconds(it * 900L), if (it < 4) 1.2 else 0.0) }
        val m = Moments.of(forecast(t, q, emptyList()), at, t, ams)
        assertEquals(MomentKind.DRY, m.first().kind)
        assertEquals(t.plusSeconds(60 * 60), m.first().at)
    }

    @Test
    fun sunsetAndGoldenHourInTheLateAfternoon() {
        // Sunset in Amsterdam on 13 October 2026 is about 18:45 local, 16:45 UTC.
        val t = Instant.parse("2026-10-13T15:00:00Z")
        val m = Moments.of(null, at, t, ams)
        val kinds = m.map { it.kind }
        assertTrue(kinds.toString(), MomentKind.SUNSET in kinds && MomentKind.GOLDEN_HOUR in kinds)
        assertTrue(m.zipWithNext().all { (a, b) -> !a.at!!.isAfter(b.at!!) })
    }

    @Test
    fun nothingAtNightAndNothingFromAnOldForecast() {
        val night = Instant.parse("2026-10-13T23:00:00Z")
        assertTrue(Moments.of(null, at, night, ams).isEmpty())
        val t = Instant.parse("2026-10-13T08:00:00Z")
        val stale = forecast(t.minusSeconds(24 * 3600), listOf(Precipitation15(t.plusSeconds(600), 3.0)), emptyList(), uv = 9.0)
        assertTrue(Moments.of(stale, at, t, ams).isEmpty())
    }

    @Test
    fun highUvAndStrongGusts() {
        val t = Instant.parse("2026-10-13T10:00:00Z")
        val f = forecast(t, emptyList(), listOf(hour(t.plusSeconds(3600), 0.0, gusts = 62.0)), uv = 6.4)
        val m = Moments.of(f, at, t, ams)
        assertEquals(6, m.first { it.kind == MomentKind.UV }.value)
        assertEquals(62, m.first { it.kind == MomentKind.WIND }.value)
    }

    @Test
    fun searchRadiusFollowsTheZoom() {
        assertEquals(800.0, HomeViewModel.radiusFor(16.0), 1e-9)
        assertEquals(300.0, HomeViewModel.radiusFor(19.0), 1e-9)
        assertEquals(5_000.0, HomeViewModel.radiusFor(10.0), 1e-9)
    }

    @Test
    fun theUvIndexIsTheOneForNowNotTheOneAtFetchTime() {
        // Fetched at noon with UV 6.4; five hours later the sun is low. A forecast without hours says nothing for now.
        val noon = Instant.parse("2026-06-13T10:00:00Z")
        val later = noon.plusSeconds(5 * 3600)
        val f = forecast(noon, emptyList(), emptyList(), uv = 6.4)
        assertTrue(Moments.of(f, at, later, ams).none { it.kind == MomentKind.UV })
        // With hours, the hour that holds now decides: 7 at 15:00 is high, whatever "now" said at noon.
        val hours = (0 until 8).map { h ->
            hour(noon.plusSeconds(h * 3600L), 0.0).copy(uvIndex = if (h == 5) 7.2 else 2.0)
        }
        val m = Moments.of(f.copy(hours = hours), at, later.plusSeconds(600), ams)
        assertEquals(7, m.single { it.kind == MomentKind.UV }.value)
        // Not after dark, even when the model's hour says otherwise.
        val night = (0 until 8).map { h -> hour(noon.plusSeconds(h * 3600L), 0.0).copy(uvIndex = 7.0, isDay = false) }
        assertTrue(Moments.of(f.copy(hours = night), at, later, ams).none { it.kind == MomentKind.UV })
    }
}

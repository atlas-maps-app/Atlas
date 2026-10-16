package app.atlas.render.live

import app.atlas.render.Amsterdam
import app.atlas.render.Regions
import app.atlas.render.py.PyJson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId

/** The tolerance bands, and the live moment from a recorded forecast. */
class LodAndMomentTest {
    @Test
    fun `bands follow the generalisation table, and named views keep their own`() {
        assertEquals(0.0, Lod.band(120.0), 0.0)
        assertEquals(0.15, Lod.band(330.0), 0.0)
        assertEquals(0.3, Lod.band(470.0), 0.0)
        assertEquals(0.6, Lod.band(1600.0), 0.0)
        assertEquals(0.8, Lod.band(2700.0), 0.0)
        assertEquals(0.8, Lod.band(40_000.0), 0.0)
        var last = -1.0
        for (d in 50..5000 step 10) {
            val b = Lod.band(d.toDouble())
            assertTrue(b >= last)
            assertTrue(Lod.BANDS.any { it == b })
            last = b
        }
        // a region's views: their own tolerance, as regions.load looks it up
        assertEquals(0.7, Lod.tolerance("grachten", "grachten_home", 2300.0), 0.0)
        assertEquals(0.6, Lod.tolerance("grachten", "grachten_eat", 1600.0), 0.0)
        assertEquals(0.8, Lod.tolerance("grachten", "grachten", 2700.0), 0.0)
        assertEquals(0.2, Lod.tolerance("bergen", "bergen_close", 430.0), 0.0)
        assertEquals(0.0, Lod.tolerance("newyork", "newyork", 520.0), 0.0)
        // between views: the band of the distance
        assertEquals(0.6, Lod.tolerance("grachten", null, 1900.0), 0.0)
        assertEquals("grachten_lod600", Lod.bandView("grachten", 0.6))
    }

    private val repo = File(System.getProperty("atlas.repo") ?: "../../..")
    private val zone = ZoneId.of("Europe/Amsterdam")

    /** data/wx/ams.json (Open-Meteo's answer for the Westerkerk, 30 September and 1 October 2026) as the map's forecast hours. */
    @Suppress("UNCHECKED_CAST")
    private fun recorded(): List<WxHour> {
        val h = (PyJson.loads(File(repo, "data/wx/ams.json").readText()) as Map<String, Any?>)["hourly"] as Map<String, List<Any?>>
        fun d(k: String, i: Int) = (h.getValue(k)[i] as Number).toDouble()
        return h.getValue("time").indices.map { i ->
            val t = LocalDateTime.parse(h.getValue("time")[i] as String).atZone(zone).toInstant()
            WxHour(t, d("cloud_cover", i), d("precipitation", i), d("wind_speed_10m", i), d("wind_direction_10m", i), d("wind_gusts_10m", i),
                d("temperature_2m", i), d("pressure_msl", i), d("visibility", i), (h.getValue("weather_code")[i] as Number).toInt(),
                d("cloud_cover_low", i), d("relative_humidity_2m", i))
        }
    }

    @Test
    fun `the recorded forecast gives the engine's own moments`() {
        val hours = recorded()
        Regions.resetPlace()
        // the board's sunny moment: 1 October 2026 16:00 in Amsterdam
        val sunnyAt = LocalDateTime.of(2026, 10, 1, 16, 0).atZone(zone).toInstant()
        assertEquals("sunny", LiveMoment.install(sunnyAt, zone, hours, false))
        val live = Amsterdam.THEMES.getValue(LiveMoment.KEY)
        Regions.resetPlace()
        val want = Amsterdam.THEMES.getValue("sunny")
        assertEquals(want.toString(), live.toString())
        // the grey one (13:30 that day) and the wet evening (30 September 21:30, raining after dark)
        assertEquals("grey", LiveMoment.install(LocalDateTime.of(2026, 10, 1, 13, 30).atZone(zone).toInstant(), zone, hours, false))
        Regions.resetPlace()
        assertEquals(Amsterdam.THEMES.getValue("grey").toString(), themeOf(LocalDateTime.of(2026, 10, 1, 13, 30), hours))
        assertEquals("night", LiveMoment.install(LocalDateTime.of(2026, 9, 30, 21, 30).atZone(zone).toInstant(), zone, hours, false))
        Regions.resetPlace()
    }

    private fun themeOf(t: LocalDateTime, hours: List<WxHour>): String {
        LiveMoment.install(t.atZone(zone).toInstant(), zone, hours, false)
        return Amsterdam.THEMES.getValue(LiveMoment.KEY).toString()
    }

    @Test
    fun `without a forecast it is grey by day and night after sunset`() {
        Regions.resetPlace()
        val noon = LocalDateTime.of(2026, 12, 3, 12, 0).atZone(zone).toInstant()
        assertEquals("grey", LiveMoment.install(noon, zone, null, false))
        val evening = LocalDateTime.of(2026, 12, 3, 19, 0).atZone(zone).toInstant()
        assertEquals("night", LiveMoment.install(evening, zone, null, false))
        // a forecast that does not reach now is no forecast
        val old = recorded()
        assertEquals("grey", LiveMoment.install(noon, zone, old, false))
        Regions.resetPlace()
    }

    @Test
    fun `hours become wx_json's hourly record in local time`() {
        val t = Instant.parse("2026-10-01T14:00:00Z")
        val h = LiveMoment.hourly(listOf(WxHour(t, 40.0, 0.0, 12.0, 200.0, 20.0, 15.0, 1010.0, 30000.0, 2)), zone)
        assertEquals(listOf("2026-10-01T16:00"), h["time"])
        assertEquals(listOf(40.0), h["cloud_cover"])
        assertEquals(listOf(40.0), h["cloud_cover_low"])
    }
}

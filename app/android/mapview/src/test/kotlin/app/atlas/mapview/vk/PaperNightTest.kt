package app.atlas.mapview.vk

import app.atlas.mapcore.state.ThemeBlock
import app.atlas.render.Amsterdam
import app.atlas.render.Regions
import app.atlas.render.live.LiveMoment
import app.atlas.render.live.Moment
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.time.Duration
import java.time.Instant
import java.time.ZoneId

/**
 * The start's paper takes the night's colour by [PaperNight], on the main thread with no core: it must say what the
 * core's theme block will say ([CoreMoment.write]'s ThemeBlock.NIGHT), for every named moment and for the live one
 * around dusk and dawn, in a zone of whole hours, one half an hour off, and one far south, at the place the moment
 * lights. And what it costs there.
 */
class PaperNightTest {
    @After
    fun reset() = Regions.resetPlace()

    private fun flagged(moment: Moment, zone: ZoneId, lat: Double, lon: Double): Boolean {
        val out = ByteBuffer.allocate(ThemeBlock.SIZE).order(ByteOrder.LITTLE_ENDIAN)
        CoreMoment.write(out, moment, zone, daylight = true, placeLat = lat, placeLon = lon)
        return out.getInt(ThemeBlock.FLAGS_AT) and ThemeBlock.NIGHT != 0
    }

    @Test
    fun everyNamedMomentIsTheNightsExactlyWhereTheEnginesThemeSaysSo() {
        val names = Amsterdam.THEMES.keys.filter { it != LiveMoment.KEY }
        assertTrue("the engine's moments: $names", "night" in names && "sunny" in names)
        for (name in names) {
            val m = Moment.Named(name)
            assertEquals(name, flagged(m, ZoneId.of("Europe/Amsterdam"), ThemeBlock.PLACE_LAT, ThemeBlock.PLACE_LON), PaperNight.of(m, ThemeBlock.PLACE_LAT, ThemeBlock.PLACE_LON))
        }
    }

    @Test
    fun theLiveMomentIsTheNightsExactlyWhereTheCoresThemeWillFlagIt() {
        // each place over a day in steps of seven minutes (the minute the sun crosses -2 degrees falls among them), and
        // the minutes either side of the crossings at the first place
        val places = listOf(
            Triple(ZoneId.of("Europe/Amsterdam"), 52.374, 4.884),
            Triple(ZoneId.of("Asia/Kolkata"), 22.6, 88.4),
            Triple(ZoneId.of("Australia/Hobart"), -42.9, 147.3),
        )
        var nights = 0
        var days = 0
        for ((zone, lat, lon) in places) {
            var at = Instant.parse("2026-11-23T00:00:30Z")
            val end = at.plus(Duration.ofDays(1))
            while (at < end) {
                val m = Moment.Live(at, null)
                val night = PaperNight.of(m, lat, lon)
                assertEquals("$zone at $at", flagged(m, zone, lat, lon), night)
                if (night) nights++ else days++
                at = at.plusSeconds(7 * 60)
            }
        }
        assertTrue("both kinds met: $nights nights, $days days", nights > 0 && days > 0)
    }

    @Test
    fun itsCostOnTheMainThread() {
        val m = Moment.Live(Instant.parse("2026-11-23T16:20:00Z"), null)
        var sink = 0
        repeat(20_000) { if (PaperNight.of(m, 52.374, 4.884)) sink++ }
        val n = 200_000
        val start = System.nanoTime()
        repeat(n) { if (PaperNight.of(m, 52.374, 4.884)) sink++ }
        val each = (System.nanoTime() - start).toDouble() / n
        println("PaperNight.of: ${"%.2f".format(each / 1000.0)} µs a call on this JVM ($sink)")
        assertTrue("one call is far under a frame: ${each / 1000.0} µs", each < 1_000_000.0)
    }
}

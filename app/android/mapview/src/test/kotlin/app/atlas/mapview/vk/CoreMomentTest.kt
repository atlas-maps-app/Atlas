package app.atlas.mapview.vk

import app.atlas.mapcore.state.ThemeBlock
import app.atlas.render.Amsterdam
import app.atlas.render.Regions
import app.atlas.render.live.LiveMoment
import app.atlas.render.live.Moment
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * The light the core draws the map in (plan-vector D14, K11): the theme block of the moment MapStyle names, made from
 * the port's own theme and sun at the bundled packs' place, byte for byte what K5's writer makes of them, and the
 * port's module state (the place today's map last drew, its moments) put back after.
 */
class CoreMomentTest {
    private val zone = ZoneId.of("Europe/Amsterdam")

    @After
    fun reset() = Regions.resetPlace()

    private fun bytes(write: (java.nio.ByteBuffer) -> Unit): ByteArray {
        val b = ThemeBlock.allocate()
        write(b)
        return ByteArray(ThemeBlock.SIZE).also { b.get(0, it) }
    }

    /** What K5's writer makes of the port's theme [key] at the Westerkerk, the profile's TONE off. */
    private fun expected(key: String): ByteArray = bytes { out ->
        val th = Amsterdam.THEMES.getValue(key)
        val (az, el, sun) = Amsterdam.sunOf(th)
        ThemeBlock.write(out, th, doubleArrayOf(sun.x, sun.y, sun.z), az, el, golden = key == "golden", tone = false)
    }

    @Test
    fun theNamedMomentsAreThePortsAtTheBundledPacksPlace() {
        Regions.resetPlace()
        for (name in listOf("sunny", "grey", "wet", "night", "golden", "noon")) {
            val want = expected(name)
            // Today's map left another place in the port's modules: the block is still the Westerkerk's.
            Amsterdam.LAT0 = 48.8566
            Amsterdam.LON0 = 2.3522
            val got = bytes { CoreMoment.write(it, Moment.Named(name), zone, daylight = false) }
            assertArrayEquals(name, want, got)
            assertEquals("the port's place is put back", 48.8566, Amsterdam.LAT0, 0.0)
            assertEquals(2.3522, Amsterdam.LON0, 0.0)
            Regions.resetPlace()
        }
        val night = bytes { CoreMoment.write(it, Moment.Named("night"), zone, daylight = false) }
        val flags = java.nio.ByteBuffer.wrap(night).order(java.nio.ByteOrder.LITTLE_ENDIAN).getInt(ThemeBlock.FLAGS_AT)
        assertTrue(flags and ThemeBlock.NIGHT != 0)
        assertEquals(0, flags and ThemeBlock.TONE)
    }

    @Test
    fun theLiveMomentIsTheEnginesWeatherThemeAtTheClock() {
        Regions.resetPlace()
        val at = LocalDateTime.of(2026, 9, 30, 15, 20).atZone(zone).toInstant()
        LiveMoment.install(at, zone, null, false)
        val want = expected(LiveMoment.KEY)
        Regions.resetPlace()
        val themesBefore = Amsterdam.THEMES.keys.toList()
        val got = bytes { CoreMoment.write(it, Moment.Live(at, null), zone, daylight = false) }
        assertArrayEquals(want, got)
        assertEquals("the live moment leaves the port's moments as they were", themesBefore, Amsterdam.THEMES.keys.toList())
        // Another hour is another light.
        val later = bytes { CoreMoment.write(it, Moment.Live(at.plusSeconds(6 * 3600), null), zone, daylight = false) }
        assertNotEquals(want.toList(), later.toList())
    }

    private fun block(write: (java.nio.ByteBuffer) -> Unit): java.nio.ByteBuffer = ThemeBlock.allocate().also(write)

    @Test
    fun theLiveMomentsSunIsThePlacesOwn() {
        Regions.resetPlace()
        // 14:00 in Tokyo on 11 November 2026: 05:00 UTC, the Westerkerk in night, Tokyo's sun 25 degrees up in the south-west
        val tokyo = ZoneId.of("Asia/Tokyo")
        val at = LocalDateTime.of(2026, 11, 11, 14, 0).atZone(tokyo).toInstant()
        val here = block { CoreMoment.write(it, Moment.Live(at, null), tokyo, daylight = false, placeLat = 35.6812, placeLon = 139.7671) }
        val (az, el) = Amsterdam.sunPosition(LocalDateTime.of(2026, 11, 11, 5, 0), 35.6812, 139.7671)
        assertEquals(az, here.getDouble(ThemeBlock.AZIMUTH_AT), 0.0)
        assertEquals(el, here.getDouble(ThemeBlock.ELEVATION_AT), 0.0)
        assertTrue("Tokyo's afternoon sun is up ($el)", el > 20.0 && el < 30.0)
        assertEquals(0, here.getInt(ThemeBlock.FLAGS_AT) and ThemeBlock.NIGHT)
        // the block says where that sun is seen from, and its clock is the place's
        assertEquals(35.6812f, here.getFloat(ThemeBlock.PLACE_LAT_AT))
        assertEquals(139.7671f, here.getFloat(ThemeBlock.PLACE_LON_AT))
        assertEquals(14, here.get(ThemeBlock.HOUR_AT).toInt())
        // the same instant from the bundled packs' place: night, as the map drew every place until now
        val west = block { CoreMoment.write(it, Moment.Live(at, null), tokyo, daylight = false) }
        assertTrue(west.getInt(ThemeBlock.FLAGS_AT) and ThemeBlock.NIGHT != 0)
        assertTrue(west.getDouble(ThemeBlock.ELEVATION_AT) < -10.0)
        assertEquals(ThemeBlock.PLACE_LAT.toFloat(), west.getFloat(ThemeBlock.PLACE_LAT_AT))
        assertEquals("the port's place is put back", 52.3746, Amsterdam.LAT0, 0.0)
        assertEquals(0, Amsterdam.TZ_M)
    }

    @Test
    fun aZoneHalfAnHourOffHasItsOwnInstant() {
        Regions.resetPlace()
        // 17:00 in Delhi (UTC+5:30) on 11 November 2026 is 11:30 UTC: the sun a few degrees up, where the whole hours
        // alone took the instant half an hour late and drew it set
        val delhi = ZoneId.of("Asia/Kolkata")
        val at = LocalDateTime.of(2026, 11, 11, 17, 0).atZone(delhi).toInstant()
        val b = block { CoreMoment.write(it, Moment.Live(at, null), delhi, daylight = false, placeLat = 28.6139, placeLon = 77.2090) }
        val (az, el) = Amsterdam.sunPosition(LocalDateTime.of(2026, 11, 11, 11, 30), 28.6139, 77.2090)
        assertEquals(az, b.getDouble(ThemeBlock.AZIMUTH_AT), 0.0)
        assertEquals(el, b.getDouble(ThemeBlock.ELEVATION_AT), 0.0)
        assertTrue("Delhi's sun at 17:00 is still up ($el)", el > 0.0)
        assertEquals(17, b.get(ThemeBlock.HOUR_AT).toInt())
        assertEquals(0, b.get(ThemeBlock.MINUTE_AT).toInt())
        // west of Greenwich the minutes still count forward from the floored hour: Newfoundland's UTC-3:30 is -4 h + 30 min
        val stJohns = ZoneId.of("America/St_Johns")
        val there = LocalDateTime.of(2026, 11, 11, 12, 0).atZone(stJohns).toInstant()
        val n = block { CoreMoment.write(it, Moment.Live(there, null), stJohns, daylight = false, placeLat = 47.5615, placeLon = -52.7126) }
        val utc = LocalDateTime.ofInstant(there, java.time.ZoneOffset.UTC)
        assertEquals(Amsterdam.sunPosition(utc, 47.5615, -52.7126).second, n.getDouble(ThemeBlock.ELEVATION_AT), 0.0)
        // the forecast's hours, on the half hour of the zone's clock, are read at its whole hours: each between two
        val start = LocalDateTime.of(2026, 11, 11, 0, 0).toInstant(java.time.ZoneOffset.UTC)
        val hours = List(48) { i ->
            app.atlas.render.live.WxHour(start.plusSeconds(3600L * i), 10.0 * (i % 2), 0.0, 10.0 + i, 225.0, 18.0, 12.0 + i, 1013.0, 20000.0, 3)
        }
        val whole = LiveMoment.wholeHours(hours, delhi)
        assertEquals(47, whole.size)
        // 00:00 UTC is 05:30 in Delhi, 01:00 UTC 06:30: 06:00 lies half way
        assertEquals(LocalDateTime.of(2026, 11, 11, 6, 0).atZone(delhi).toInstant(), whole[0].time)
        assertEquals(12.5, whole[0].temperature, 1e-12)
        assertEquals(10.5, whole[0].windSpeed, 1e-12)
        assertEquals(5.0, whole[0].cloudCover, 1e-12)
        // in a zone of whole hours the hours are themselves
        assertEquals(hours, LiveMoment.wholeHours(hours, zone))
    }

    @Test
    fun aNamedMomentIsALookWrittenForTheCamerasPlace() {
        Regions.resetPlace()
        // the same light wherever the camera is: the bundled packs' sun; only the place the block names differs, so the
        // world's look puts the terminator where it leaves that place lit as the moment is
        val home = block { CoreMoment.write(it, Moment.Named("sunny"), zone, daylight = false) }
        val tokyo = block { CoreMoment.write(it, Moment.Named("sunny"), zone, daylight = false, placeLat = 35.6812, placeLon = 139.7671) }
        for (i in 0 until ThemeBlock.PLACE_LAT_AT) assertEquals("byte $i", home.get(i), tokyo.get(i))
        assertEquals(35.6812f, tokyo.getFloat(ThemeBlock.PLACE_LAT_AT))
        assertEquals(139.7671f, tokyo.getFloat(ThemeBlock.PLACE_LON_AT))
    }

    @Test
    fun theNightFlagFlipsWhereTheBlockSaysItDoes() {
        Regions.resetPlace()
        // minute by minute through the Westerkerk's dusk on 11 November 2026: night exactly where the sun is under
        // ThemeBlock.NIGHT_BELOW, which the core's world look ties its far day colours to
        val start = LocalDateTime.of(2026, 11, 11, 15, 30).toInstant(java.time.ZoneOffset.UTC)
        var flips = 0
        var before: Boolean? = null
        for (minute in 0 until 60) {
            val b = block { CoreMoment.write(it, Moment.Live(start.plusSeconds(60L * minute), null), zone, daylight = false) }
            val night = b.getInt(ThemeBlock.FLAGS_AT) and ThemeBlock.NIGHT != 0
            assertEquals("minute $minute", b.getDouble(ThemeBlock.ELEVATION_AT) < ThemeBlock.NIGHT_BELOW, night)
            if (before != null && before != night) flips++
            before = night
        }
        assertEquals("the flip lies within the hour (16:03 UTC)", 1, flips)
    }
}

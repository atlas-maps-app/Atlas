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
}

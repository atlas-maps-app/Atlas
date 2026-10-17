package app.atlas.mapcorecheck.style

import app.atlas.mapcore.state.ThemeBlock
import app.atlas.render.Amsterdam
import app.atlas.render.Regions
import app.atlas.render.live.LiveMoment
import app.atlas.render.live.WxHour
import org.junit.AfterClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * The theme block (plan-vector D14, K5): the port's theme for each of 48 recorded hours (the Westerkerk's forecast of
 * 30 September and 1 October 2026, as the phone's live moment installs it, with and without the daylight's dusk), the
 * fixed moments and the golden wash, written by [ThemeBlock] and read by the core's own reader, equal to the port's
 * dict field by field: every key of the dict, colours as hx reads them, doubles bit for bit, the sun vector the render
 * takes and the sun's position.
 */
class ThemeBlockTest {
    companion object {
        private val zone: ZoneId = ZoneId.of("Europe/Amsterdam")

        @BeforeClass
        @JvmStatic
        fun place() = Regions.resetPlace()

        @AfterClass
        @JvmStatic
        fun restore() = Regions.resetPlace()

        /** The Westerkerk's recorded forecast (data/wx/ams.json, the engine's resource) as the app's forecast hours. */
        @Suppress("UNCHECKED_CAST")
        fun recordedHours(): List<WxHour> {
            val w = Amsterdam.WXD
            fun col(k: String) = (w[k] as List<Any?>).map { (it as Number).toDouble() }
            val time = w["time"] as List<String>
            return time.indices.map { i ->
                val at = LocalDateTime.parse(time[i]).atZone(zone).toInstant()
                WxHour(
                    at, col("cloud_cover")[i], col("precipitation")[i], col("wind_speed_10m")[i], col("wind_direction_10m")[i],
                    col("wind_gusts_10m")[i], col("temperature_2m")[i], col("pressure_msl")[i], col("visibility")[i],
                    (w["weather_code"] as List<Any?>)[i].let { (it as Number).toInt() }, col("cloud_cover_low")[i], col("relative_humidity_2m")[i],
                )
            }
        }

        private fun bits(d: Double) = "%016x".format(java.lang.Double.doubleToRawLongBits(d))
        private fun num(v: Any?) = (v as Number).toDouble()
        private fun hex(v: Any?): String = "#%06x".format(ThemeBlock.rgbOf(v as String))

        /** Writes [th] as the app does and returns the block as the core reads it. */
        fun throughTheCore(th: Map<String, Any?>, key: String, tone: Boolean): Map<String, String> {
            val (az, el, L) = Amsterdam.sunOf(th)
            val b = ThemeBlock.allocate()
            ThemeBlock.write(b, th, doubleArrayOf(L.x, L.y, L.z), az, el, key == "golden", tone)
            return parse(StyleHost.themeDump(b))
        }

        /** Holds the core's reading of [th] to the port's dict, key by key; every key of the dict must be carried. */
        fun assertSame(th: Map<String, Any?>, key: String, tone: Boolean, got: Map<String, String>, what: String) {
            val (az, el, L) = Amsterdam.sunOf(th)
            assertTrue("$what: the core refused the block: $got", "error" !in got)
            val want = LinkedHashMap<String, String>()
            want["version"] = "1"
            var flags = 0
            val carried = HashSet<String>()
            for ((k, v) in th) {
                carried += k
                when (k) {
                    "name" -> want["moment"] = ThemeBlock.MOMENTS.indexOf(v as String).coerceAtLeast(0).toString()
                    "date" -> (v as List<*>).let { want["year"] = "${it[0]}"; want["month"] = "${it[1]}"; want["day"] = "${it[2]}" }
                    "hhmm" -> (v as List<*>).let { want["hour"] = "${it[0]}"; want["minute"] = "${it[1]}" }
                    "night" -> if (v == true) flags = flags or ThemeBlock.NIGHT
                    "wet" -> flags = flags or ThemeBlock.WET_SET or (if (v == true) ThemeBlock.WET else 0)
                    "wx" -> {
                        flags = flags or ThemeBlock.WEATHER
                        val wx = v as Map<*, *>
                        for (wk in wx.keys) {
                            if (wk == "code") want["code"] = "${(wx[wk] as Number).toInt()}"
                            else {
                                assertTrue("$what: the block carries no wx.$wk", wk in ThemeBlock.WEATHER_KEYS)
                                want[wk as String] = bits(num(wx[wk]))
                            }
                        }
                    }
                    in ThemeBlock.COLOURS -> want[k] = hex(v)
                    in ThemeBlock.DOUBLES -> want[k] = bits(num(v))
                    else -> throw AssertionError("$what: the block carries no '$k'")
                }
            }
            if ("sky_k" in th) flags = flags or ThemeBlock.OVERCAST
            if ("shadow_blur" in th) flags = flags or ThemeBlock.SHADOW_BLUR
            if ("cloud_shadows" in th) flags = flags or ThemeBlock.CLOUD_SHADOWS
            if ("water_top" in th) flags = flags or ThemeBlock.WATER_GRADIENT
            if ("_dusk" in th) flags = flags or ThemeBlock.DUSK
            if (key == "golden") flags = flags or ThemeBlock.GOLDEN
            if (tone) flags = flags or ThemeBlock.TONE
            want["flags"] = "$flags"
            for (c in ThemeBlock.COLOURS) if (c !in carried) want[c] = "-"
            for (d in ThemeBlock.DOUBLES) if (d !in carried) want[d] = bits(if (d == "sky_k") 1.0 else 0.0)
            if ("wx" !in carried) {
                want["code"] = "255"
                for (wk in ThemeBlock.WEATHER_KEYS) want[wk] = bits(0.0)
            }
            want["L0"] = bits(L.x); want["L1"] = bits(L.y); want["L2"] = bits(L.z)
            want["az"] = bits(az); want["el"] = bits(el)
            assertEquals("$what: every field", want.toSortedMap(), got.toSortedMap())
        }
    }

    @Test
    fun fortyEightRecordedHoursAsTheCoreReadsThem() {
        val hours = recordedHours()
        assertEquals(72, hours.size)
        var n = 0
        val names = HashSet<String>()
        for (daylight in listOf(false, true)) {
            for (h in 0 until 48) {
                // twenty past each hour, so the weather is the hour's and the next one's mix, as wx_at makes it
                val at = LocalDateTime.of(2026, 9, 30, 0, 20).plusHours(h.toLong()).atZone(zone).toInstant()
                val name = LiveMoment.install(at, zone, hours, daylight)
                val th = Amsterdam.THEMES.getValue(LiveMoment.KEY)
                names += name
                for (tone in listOf(false, true)) {
                    assertSame(th, LiveMoment.KEY, tone, throughTheCore(th, LiveMoment.KEY, tone), "$at daylight=$daylight tone=$tone")
                    n++
                }
            }
        }
        println("ThemeBlockTest: $n blocks from 48 recorded hours, moments ${names.sorted()}")
        // the hours cover the weather's every kind: night, rain, overcast, sun between clouds
        assertTrue(names.containsAll(listOf("night", "wet", "grey", "sunny")))
    }

    @Test
    fun theFixedMomentsAndTheGoldenWash() {
        Regions.resetPlace()
        for (key in listOf("golden", "noon", "night", "grey", "sunny", "wet")) {
            val th = Amsterdam.THEMES.getValue(key)
            assertSame(th, key, false, throughTheCore(th, key, false), key)
        }
        // golden carries the wash's flag; the night's shadow, CSS's #000, reads as black
        assertEquals(((ThemeBlock.GOLDEN)).toString(), throughTheCore(Amsterdam.THEMES.getValue("golden"), "golden", false)["flags"])
        assertEquals("#000000", throughTheCore(Amsterdam.THEMES.getValue("night"), "night", false)["shadow"])
    }

    @Test
    fun theCoreRefusesABrokenBlock() {
        val th = Amsterdam.THEMES.getValue("sunny")
        val (az, el, L) = Amsterdam.sunOf(th)
        val b = ThemeBlock.allocate()
        fun fresh() = ThemeBlock.write(b, th, doubleArrayOf(L.x, L.y, L.z), az, el, false, false)
        fresh(); b.putInt(ThemeBlock.VERSION_AT, 2)
        assertEquals("version", parse(StyleHost.themeDump(b))["error"])
        fresh(); b.putInt(ThemeBlock.FLAGS_AT, 1 shl 12)
        assertEquals("flags", parse(StyleHost.themeDump(b))["error"])
        fresh(); b.putDouble(ThemeBlock.SUN_AT, Double.NaN)
        assertEquals("not_finite", parse(StyleHost.themeDump(b))["error"])
        fresh(); b.put(ThemeBlock.MONTH_AT, 13.toByte())
        assertEquals("date", parse(StyleHost.themeDump(b))["error"])
        // the ranges: a colour every theme has, a theme value, the sun vector, the hour's weather
        fresh(); b.putInt(ThemeBlock.COLOURS_AT + 4 * ThemeBlock.COLOURS.indexOf("amb"), 0)
        assertEquals("colour", parse(StyleHost.themeDump(b))["error"])
        fresh(); b.putDouble(ThemeBlock.DOUBLES_AT + 8 * ThemeBlock.DOUBLES.indexOf("haze_max"), 5.0)
        assertEquals("value", parse(StyleHost.themeDump(b))["error"])
        fresh(); b.putDouble(ThemeBlock.SUN_AT + 8, 2.0)
        assertEquals("sun", parse(StyleHost.themeDump(b))["error"])
        fresh(); b.putDouble(ThemeBlock.WEATHER_AT + 8 * ThemeBlock.WEATHER_KEYS.indexOf("pressure"), 0.0)
        assertEquals("weather", parse(StyleHost.themeDump(b))["error"])
        fresh()
        assertTrue("error" !in parse(StyleHost.themeDump(b)))
    }
}

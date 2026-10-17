package app.atlas.mapcore.state

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * The moment's theme for the core (plan-vector D14, Appendix D): the port's theme dict (a `THEMES` entry of
 * `R/Amsterdam.kt`, as `weather_theme` and `LiveMoment.install` make it) with its sun, written as the 256-byte block
 * the core reads (`CP/style/Theme.hpp`), about once a minute, through `MapCore.moment`. Every value is the port's own;
 * the doubles go bit for bit, the colours as `hx` reads them (CSS's `#rgb` as `#rrggbb`).
 *
 * The layout is defined once, in C++; [FIELDS], the flag bits and [MOMENTS] mirror its tables and `MT/style/LayoutTest.kt`
 * holds them equal. The core refuses a block the port could not have made (the ranges Theme.hpp lists), so [write]
 * takes the sun's position from the same moment as the sun vector. Plain Kotlin on `java.nio`, so the JVM tests compile
 * it too.
 */
object ThemeBlock {
    /** The layout's version (`theme_block_version`). */
    const val VERSION = 1

    /** The block's size in bytes. */
    const val SIZE = 256

    // The flag bits (theme.slang's THEME_ bits, the shaders read them unchanged).
    const val NIGHT = 1
    const val WET = 2
    const val WEATHER = 4
    const val OVERCAST = 8
    const val SHADOW_BLUR = 16
    const val CLOUD_SHADOWS = 32
    const val WATER_GRADIENT = 64
    const val DUSK = 128
    const val WET_SET = 256
    const val GOLDEN = 512
    const val TONE = 1024

    /** The moment names the block numbers (`th['name']`); any other name (a region's own moment) is 0. */
    val MOMENTS: List<String> = listOf("", "sunny", "grey", "wet", "night", "golden", "noon", "truth")

    /** The theme's colours, in the block's order (four bytes each from [COLOURS_AT]). */
    val COLOURS: List<String> = listOf("sky_top", "sky_hor", "haze", "sun", "amb", "ground", "road", "water", "water_top", "water_bot", "shadow")

    /** The theme's doubles, in the block's order (from [DOUBLES_AT]); sky_k is 1 and the others 0 when the theme has none. */
    val DOUBLES: List<String> = listOf("haze_max", "sun_k", "amb_k", "sky_k", "shadow_op", "shadow_blur", "cloud_shadows", "_dusk")

    /** The hour's weather (`th['wx']`), in the block's order (from [WEATHER_AT]); 0 without weather. */
    val WEATHER_KEYS: List<String> = listOf("cloud", "low", "rain", "wind", "gust", "wind_dir", "temp", "pressure", "vis", "hum")

    const val VERSION_AT = 0
    const val FLAGS_AT = 4
    const val YEAR_AT = 8
    const val MONTH_AT = 10
    const val DAY_AT = 11
    const val HOUR_AT = 12
    const val MINUTE_AT = 13
    const val NAME_AT = 14
    const val CODE_AT = 15
    const val COLOURS_AT = 16
    const val RESERVED0_AT = 60
    const val DOUBLES_AT = 64
    const val SUN_AT = 128
    const val AZIMUTH_AT = 152
    const val ELEVATION_AT = 160
    const val WEATHER_AT = 168
    const val RESERVED1_AT = 248

    /** The layout as (name, offset, size), named as the core's table names its fields (`CP/style/Layouts.cpp`). */
    val FIELDS: List<Triple<String, Int, Int>> = buildList {
        add(Triple("version", VERSION_AT, 4))
        add(Triple("flags", FLAGS_AT, 4))
        add(Triple("year", YEAR_AT, 2))
        add(Triple("month", MONTH_AT, 1))
        add(Triple("day", DAY_AT, 1))
        add(Triple("hour", HOUR_AT, 1))
        add(Triple("minute", MINUTE_AT, 1))
        add(Triple("name", NAME_AT, 1))
        add(Triple("wx.code", CODE_AT, 1))
        COLOURS.forEachIndexed { i, k -> add(Triple(k, COLOURS_AT + 4 * i, 4)) }
        add(Triple("reserved0", RESERVED0_AT, 4))
        DOUBLES.forEachIndexed { i, k -> add(Triple(k, DOUBLES_AT + 8 * i, 8)) }
        add(Triple("_L", SUN_AT, 24))
        add(Triple("az", AZIMUTH_AT, 8))
        add(Triple("el", ELEVATION_AT, 8))
        WEATHER_KEYS.forEachIndexed { i, k -> add(Triple("wx.$k", WEATHER_AT + 8 * i, 8)) }
        add(Triple("reserved1", RESERVED1_AT, 8))
    }

    /** A direct, little-endian buffer for one block. */
    fun allocate(): ByteBuffer = ByteBuffer.allocateDirect(SIZE).order(ByteOrder.LITTLE_ENDIAN)

    /**
     * Writes [theme] into [out] at [at] (the buffer must be little-endian, with [SIZE] bytes from there): [sun] is the
     * sun vector the render takes (`_L`: toward the sun, straight down at night), [azimuth] and [elevation] the sun's
     * position at the moment in degrees, [golden] whether the moment is `THEMES`' golden one (the golden wash), [tone]
     * the profile's TONE. Fails on a theme without a value the block needs.
     */
    fun write(out: ByteBuffer, theme: Map<String, Any?>, sun: DoubleArray, azimuth: Double, elevation: Double, golden: Boolean, tone: Boolean, at: Int = 0) {
        require(out.order() == ByteOrder.LITTLE_ENDIAN) { "the theme block is little-endian" }
        require(at >= 0 && out.limit() - at >= SIZE) { "no room for a theme block at $at" }
        require(sun.size == 3) { "the sun vector has three components" }
        for (i in 0 until SIZE step 8) out.putLong(at + i, 0L)
        val wx = theme["wx"] as Map<*, *>?
        var flags = 0
        if (theme["night"] == true) flags = flags or NIGHT
        if ("wet" in theme) flags = flags or WET_SET
        if (theme["wet"] == true) flags = flags or WET
        if (wx != null) flags = flags or WEATHER
        if ("sky_k" in theme) flags = flags or OVERCAST
        if ("shadow_blur" in theme) flags = flags or SHADOW_BLUR
        if ("cloud_shadows" in theme) flags = flags or CLOUD_SHADOWS
        if ("water_top" in theme && "water_bot" in theme) flags = flags or WATER_GRADIENT
        if ("_dusk" in theme) flags = flags or DUSK
        if (golden) flags = flags or GOLDEN
        if (tone) flags = flags or TONE
        out.putInt(at + VERSION_AT, VERSION)
        out.putInt(at + FLAGS_AT, flags)
        val date = ints(theme, "date", 3)
        val hhmm = ints(theme, "hhmm", 2)
        out.putShort(at + YEAR_AT, date[0].toShort())
        out.put(at + MONTH_AT, date[1].toByte())
        out.put(at + DAY_AT, date[2].toByte())
        out.put(at + HOUR_AT, hhmm[0].toByte())
        out.put(at + MINUTE_AT, hhmm[1].toByte())
        out.put(at + NAME_AT, MOMENTS.indexOf(theme["name"] as String?).coerceAtLeast(0).toByte())
        out.put(at + CODE_AT, (if (wx != null && wx["code"] != null) num(wx["code"], "wx.code").toInt() else 255).toByte())
        for ((i, k) in COLOURS.withIndex()) {
            val v = theme[k] ?: if (k == "water_top" || k == "water_bot") continue else throw IllegalArgumentException("the theme has no $k")
            val rgb = rgbOf(v as String)
            val o = at + COLOURS_AT + 4 * i
            out.put(o, (rgb shr 16).toByte())
            out.put(o + 1, (rgb shr 8).toByte())
            out.put(o + 2, rgb.toByte())
            out.put(o + 3, 255.toByte())
        }
        for ((i, k) in DOUBLES.withIndex()) {
            val v = theme[k]
            val d = when {
                v != null -> num(v, k)
                k == "sky_k" -> 1.0
                k == "haze_max" || k == "sun_k" || k == "amb_k" || k == "shadow_op" -> throw IllegalArgumentException("the theme has no $k")
                else -> 0.0
            }
            out.putDouble(at + DOUBLES_AT + 8 * i, d)
        }
        for (i in 0 until 3) out.putDouble(at + SUN_AT + 8 * i, sun[i])
        out.putDouble(at + AZIMUTH_AT, azimuth)
        out.putDouble(at + ELEVATION_AT, elevation)
        if (wx != null) for ((i, k) in WEATHER_KEYS.withIndex()) out.putDouble(at + WEATHER_AT + 8 * i, num(wx[k], "wx.$k"))
    }

    /** `#rrggbb` or CSS's `#rgb` as 0xRRGGBB. */
    fun rgbOf(hex: String): Int {
        val h = hex.removePrefix("#")
        val full = when (h.length) {
            6 -> h
            3 -> buildString { for (c in h) append(c).append(c) }
            else -> throw IllegalArgumentException("not a colour: $hex")
        }
        return full.toInt(16)
    }

    private fun num(v: Any?, key: String): Double = (v as? Number ?: throw IllegalArgumentException("the theme's $key is not a number: $v")).toDouble()

    private fun ints(theme: Map<String, Any?>, key: String, n: Int): IntArray {
        val v = theme[key] ?: throw IllegalArgumentException("the theme has no $key")
        val list: List<Any?> = when (v) {
            is IntArray -> v.toList()
            is List<*> -> v
            else -> throw IllegalArgumentException("the theme's $key is not a tuple: $v")
        }
        require(list.size == n) { "the theme's $key has ${list.size} values, not $n" }
        return IntArray(n) { (list[it] as Number).toInt() }
    }
}

package app.atlas.mapview.palette

import kotlin.math.atan2
import kotlin.math.cbrt
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin

/** The families of the Colour the map board's tabs, in its order. */
enum class Family { GROUND, WATER, ROADS, WALLS, ROOFS, TREES, NIGHT }

/**
 * One colour of the map the user can change (the Colour the map board's entries): [code] its number in a palette code,
 * never reused; its [family]; and [today], its colour in today's look (0xRRGGBB).
 */
enum class Slot(val code: Int, val family: Family, val today: Int) {
    PAPER(1, Family.GROUND, 0xD6CFC2),
    LAWN(2, Family.GROUND, 0xA9C38A),
    WOODS(3, Family.GROUND, 0x6F8661),
    SQUARES(4, Family.GROUND, 0xDCD3C4),
    PARKING(5, Family.GROUND, 0xC9C4BA),
    PLAYGROUND(6, Family.GROUND, 0xD9B48A),
    MEMORIAL(7, Family.GROUND, 0xE3B3C0),
    BRIDGE_DECK(8, Family.GROUND, 0xCFC6B6),
    WATER(16, Family.WATER, 0x4F7590),
    WATER_SKY(17, Family.WATER, 0xA6BFD4),
    WATER_DEEP(18, Family.WATER, 0x2D506C),
    QUAY_WALLS(19, Family.WATER, 0x6E5546),
    STREET(32, Family.ROADS, 0xC9C1B4),
    ASPHALT(33, Family.ROADS, 0x7B7B7E),
    PAVING_STONES(34, Family.ROADS, 0xC9B9A6),
    SETTS(35, Family.ROADS, 0xA89A88),
    CONCRETE_PAVING(36, Family.ROADS, 0xC4C2BC),
    BRICK_PAVING(37, Family.ROADS, 0xB98870),
    BOARDWALK(38, Family.ROADS, 0xA88A62),
    OLD_BRICK_1(48, Family.WALLS, 0x6E3B2E),
    OLD_BRICK_2(49, Family.WALLS, 0x7A4434),
    OLD_BRICK_3(50, Family.WALLS, 0x5C3A30),
    OLD_BRICK_4(51, Family.WALLS, 0x8A5040),
    OLD_BRICK_5(52, Family.WALLS, 0x6A4A3C),
    OLD_BRICK_6(53, Family.WALLS, 0x7E5242),
    PLASTER_1(54, Family.WALLS, 0xE8E2D4),
    PLASTER_2(55, Family.WALLS, 0xEFE9DC),
    PLASTER_3(56, Family.WALLS, 0xD9D2C2),
    NEW_BRICK_1(57, Family.WALLS, 0x9C6A52),
    NEW_BRICK_2(58, Family.WALLS, 0x8F7F74),
    NEW_BRICK_3(59, Family.WALLS, 0xB08A6A),
    NEW_BRICK_4(60, Family.WALLS, 0x8C8A86),
    NEW_BRICK_5(61, Family.WALLS, 0xA9876E),
    LIMESTONE(62, Family.WALLS, 0xD8CCB2),
    CONCRETE(63, Family.WALLS, 0xBEBAB1),
    RED_TILES(80, Family.ROOFS, 0xAE573F),
    DARK_TILES_1(81, Family.ROOFS, 0x4A3E3A),
    DARK_TILES_2(82, Family.ROOFS, 0x56504C),
    DARK_TILES_3(83, Family.ROOFS, 0x3E4248),
    FLAT_GREY_1(84, Family.ROOFS, 0x7E7A74),
    FLAT_GREY_2(85, Family.ROOFS, 0x8C8882),
    FLAT_GREY_3(86, Family.ROOFS, 0x6E6A66),
    BITUMEN(87, Family.ROOFS, 0x56585C),
    GRAVEL(88, Family.ROOFS, 0x9C988F),
    SLATE(89, Family.ROOFS, 0x535C66),
    ZINC(90, Family.ROOFS, 0x737E88),
    LEAD(91, Family.ROOFS, 0x7A8187),
    LEAVES(112, Family.TREES, 0x4E6B35),
    EVERGREEN(113, Family.TREES, 0x4E6B35),
    BARK(114, Family.TREES, 0x4B4038),
    BENCHES(115, Family.TREES, 0x8A6A4C),
    LAMP_POSTS(116, Family.TREES, 0x2A2E33),
    BOLLARDS(117, Family.TREES, 0x4A2620),
    TURBINES(118, Family.TREES, 0xE9ECEE),
    PYLONS(119, Family.TREES, 0x6E747C),
    NIGHT_GROUND(128, Family.NIGHT, 0x12151F),
    NIGHT_WATER(129, Family.NIGHT, 0x070C14),
    LIT_WINDOWS(130, Family.NIGHT, 0xFFD08A),
    SKY(131, Family.NIGHT, 0x7FA6DA),
    SUN(132, Family.NIGHT, 0xFFEBCB),
    SHADE(133, Family.NIGHT, 0x93A6CC),
    SHADOWS(134, Family.NIGHT, 0x27305A),
    HAZE(135, Family.NIGHT, 0xDFE6EE),
    ;

    companion object {
        private val byCode = entries.associateBy { it.code }

        /** The slot a palette code names by [code], or null for a number this Atlas does not know. */
        fun ofCode(code: Int): Slot? = byCode[code]
    }
}

/**
 * The Map look's switches a palette carries (the Map look board's card under Tone), as bits of a palette code:
 * [NO_GUESSES] shows only what OpenStreetMap maps (Atlas's guesses off), [GRASS] grows grass on bare ground instead of
 * paper, [MAPPED_FOLLOW] moves mapped colours with the palette's nearest colour, and [FLAT_GREYS] gives each guessed
 * flat roof one of the three mid greys, as alpha 22 drew them before the roofs' photo mix.
 */
object PaletteFlags {
    const val NO_GUESSES = 1
    const val GRASS = 2
    const val MAPPED_FOLLOW = 4
    const val FLAT_GREYS = 8
    const val KNOWN = NO_GUESSES or GRASS or MAPPED_FOLLOW or FLAT_GREYS
}

/**
 * A look to start from (the Map look board's presets): its name in a palette code, its anchors (the colours the
 * board's glyph shows, exact), the Tone it brings (0 to 2), its switches, and how it moves every other colour but the
 * light's ([rest], in OKLab; none for today's and alpha 22's).
 */
enum class Preset(val code: String, val anchors: Map<Slot, Int>, val tone: Float, val flags: Int, val rest: Rest?) {
    /** The review's look with TONE: every colour as the map draws it today. */
    TODAY("TODAY", emptyMap(), 1f, 0, null),

    /** The first New look: TONE off, the red tiles as tiles_red, each flat roof one of three mid greys. */
    ALPHA_22("A22", mapOf(Slot.RED_TILES to 0x9E5038), 0f, PaletteFlags.FLAT_GREYS, null),

    /** True materials, as streets.gl colours a city, with grass on bare ground. */
    REAL(
        "REAL",
        anchors(0x86A060, 0x3B6470, 0x55585C, 0x6E9448, 0xA4482F, 0x8A4B36, 0x3F4144, 0xEDE7DA, 0x45474A, 0xB5A48E, 0x46682F),
        1f, PaletteFlags.GRASS, null,
    ),

    /** Soft and light, so routes and places come forward. */
    QUIET(
        "QUIET",
        anchors(0xECE9E3, 0xA9C0D0, 0xFFFFFF, 0xCBD8BC, 0xC8A699, 0xD8D2C8, 0xB9B4AC, 0xEEEAE3, 0xC2BEB6, 0xDDD7CD, 0xAFC39C),
        1f, 0, Rest(lighten = 0.55f, chroma = 0.45f),
    ),

    /** The city as an old print. */
    SEPIA(
        "SEPIA",
        anchors(0xE9DEC6, 0xA3A68F, 0xD9CAA9, 0xC9BE97, 0xA47657, 0x9B7A5A, 0x7D654D, 0xE3D3B5, 0x8C7760, 0xB59877, 0x8F8661),
        0.6f, 0, Rest(lighten = 0.25f, chroma = 0.35f, hue = 75f, toHue = 0.85f, floor = 0.025f),
    ),

    /** Black and white, with faint water. */
    INK(
        "INK",
        anchors(0xF7F6F2, 0xCFDCE7, 0xFFFFFF, 0xDDE6D6, 0x8E8E8E, 0xCFCFCF, 0x6F6F6F, 0xE6E6E6, 0xA3A3A3, 0xBDBDBD, 0xB9C7AE),
        0f, 0, Rest(lighten = 0.2f, chroma = 0.08f),
    ),
    ;

    /** This look's colour for [slot]: its anchor, else today's moved by [rest] (the light's own colours stay). */
    fun colour(slot: Slot): Int {
        anchors[slot]?.let { return it }
        if (rest == null || slot.family == Family.NIGHT) return slot.today
        return rest.move(slot.today)
    }

    companion object {
        private val byCode = entries.associateBy { it.code }

        /** The preset a palette code names, or null. */
        fun ofCode(code: String): Preset? = byCode[code]
    }
}

/** The board's glyph colours in its order (g, w, r, l, roof1, wall1, roof2, wall2, roof3, wall3, tree) as anchors. */
private fun anchors(vararg c: Int): Map<Slot, Int> = listOf(
    Slot.PAPER, Slot.WATER, Slot.STREET, Slot.LAWN, Slot.RED_TILES, Slot.OLD_BRICK_2, Slot.DARK_TILES_1,
    Slot.PLASTER_1, Slot.BITUMEN, Slot.NEW_BRICK_1, Slot.LEAVES,
).zip(c.toList()).toMap()

/**
 * How a preset moves a colour it does not name, in OKLab: lighter by [lighten] of the way to white, its chroma times
 * [chroma] (at least [floor]), and its hue turned [toHue] of the way to [hue] (degrees) when one is given.
 */
data class Rest(val lighten: Float, val chroma: Float, val hue: Float? = null, val toHue: Float = 0f, val floor: Float = 0f) {
    fun move(rgb: Int): Int {
        val (l, a, b) = Oklab.of(rgb)
        val c = hypot(a, b)
        var h = atan2(b, a)
        if (hue != null) {
            val target = Math.toRadians(hue.toDouble()).toFloat()
            var d = target - h
            while (d > Math.PI) d -= (2 * Math.PI).toFloat()
            while (d < -Math.PI) d += (2 * Math.PI).toFloat()
            h += d * toHue
        }
        val c2 = maxOf(c * chroma, floor)
        val l2 = l + (1f - l) * lighten
        return Oklab.rgb(l2, c2 * cos(h), c2 * sin(h))
    }
}

/**
 * A palette: the [base] look, the colours the user changed on it ([colours], by slot, 0xRRGGBB), the Tone (0 to 2,
 * the base's when null) and the Map look's switches ([flags], PaletteFlags; the base's when null).
 */
data class Palette(
    val base: Preset = Preset.TODAY,
    val colours: Map<Slot, Int> = emptyMap(),
    val tone: Float? = null,
    val flags: Int? = null,
) {
    init {
        require(colours.values.all { it in 0..0xFFFFFF }) { "a colour is 0xRRGGBB" }
        require(tone == null || tone in 0f..2f) { "the Tone is 0 to 2" }
        require(flags == null || flags and PaletteFlags.KNOWN.inv() == 0) { "an unknown switch" }
    }

    /** The colour this palette draws [slot] in. */
    fun colour(slot: Slot): Int = colours[slot] ?: base.colour(slot)

    /** The Tone this palette draws with. */
    val toneStrength: Float get() = tone ?: base.tone

    /** The switches this palette draws with. */
    val switches: Int get() = flags ?: base.flags

    /** True when this palette draws every colour as today's map does (the core then takes no palette). */
    val isToday: Boolean get() = Slot.entries.all { colour(it) == it.today } && switches == 0

    /** How many colours differ from the base's (the Colour the map board's "6 colours changed"). */
    val changed: Int get() = colours.count { (slot, c) -> c != base.colour(slot) }
}

/** OKLab (Björn Ottosson's), for the presets' moves and the Colour the map board's sliders. */
object Oklab {
    private fun lin(c: Int): Float {
        val v = c / 255f
        return if (v <= 0.04045f) v / 12.92f else ((v + 0.055f) / 1.055f).pow(2.4f)
    }

    private fun gamma(v: Float): Int {
        val c = if (v <= 0.0031308f) 12.92f * v else 1.055f * v.pow(1f / 2.4f) - 0.055f
        return (c * 255f).roundToInt().coerceIn(0, 255)
    }

    /** L, a and b of a 0xRRGGBB colour. */
    fun of(rgb: Int): Triple<Float, Float, Float> {
        val r = lin((rgb shr 16) and 0xFF)
        val g = lin((rgb shr 8) and 0xFF)
        val b = lin(rgb and 0xFF)
        val l = cbrt(0.4122214708f * r + 0.5363325363f * g + 0.0514459929f * b)
        val m = cbrt(0.2119034982f * r + 0.6806995451f * g + 0.1073969566f * b)
        val s = cbrt(0.0883024619f * r + 0.2817188376f * g + 0.6299787005f * b)
        return Triple(
            0.2104542553f * l + 0.7936177850f * m - 0.0040720468f * s,
            1.9779984951f * l - 2.4285922050f * m + 0.4505937099f * s,
            0.0259040371f * l + 0.7827717662f * m - 0.8086757660f * s,
        )
    }

    /** The 0xRRGGBB colour of L, a and b, each channel clamped to 0-255. */
    fun rgb(lightness: Float, a: Float, b: Float): Int {
        val l = (lightness + 0.3963377774f * a + 0.2158037573f * b).let { it * it * it }
        val m = (lightness - 0.1055613458f * a - 0.0638541728f * b).let { it * it * it }
        val s = (lightness - 0.0894841775f * a - 1.2914855480f * b).let { it * it * it }
        val r = gamma(4.0767416621f * l - 3.3077115913f * m + 0.2309699292f * s)
        val g = gamma(-1.2684380046f * l + 2.6097574011f * m - 0.3413193965f * s)
        val bl = gamma(-0.0041960863f * l - 0.7034186147f * m + 1.7076147010f * s)
        return (r shl 16) or (g shl 8) or bl
    }

    /** The distance of two colours in OKLab: under 0.02 hard to tell apart. */
    fun distance(x: Int, y: Int): Float {
        val (l1, a1, b1) = of(x)
        val (l2, a2, b2) = of(y)
        return hypot(hypot(l1 - l2, a1 - a2), b1 - b2)
    }
}

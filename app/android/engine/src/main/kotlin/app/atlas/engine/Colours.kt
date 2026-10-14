package app.atlas.engine

import kotlin.math.roundToInt

/**
 * Colours as packed ARGB ints (0xAARRGGBB), as Android's Canvas takes them. Channel maths works in 0..255 floats and
 * clamps only when packing, so lighting can overshoot and settle.
 */
object Colours {
    const val WHITE: Int = -0x1
    const val BLACK: Int = -0x1000000
    const val TRANSPARENT: Int = 0

    fun argb(a: Int, r: Int, g: Int, b: Int): Int =
        (a.coerceIn(0, 255) shl 24) or (r.coerceIn(0, 255) shl 16) or (g.coerceIn(0, 255) shl 8) or b.coerceIn(0, 255)

    fun rgb(r: Int, g: Int, b: Int): Int = argb(255, r, g, b)

    /** Packs float channels (0..255, clamped and rounded); alpha opaque. */
    fun rgb(r: Float, g: Float, b: Float): Int = rgb(round(r), round(g), round(b))

    private fun round(v: Float): Int = if (v <= 0f) 0 else if (v >= 255f) 255 else (v + 0.5f).toInt()

    fun alpha(c: Int): Int = c ushr 24
    fun red(c: Int): Int = (c shr 16) and 0xFF
    fun green(c: Int): Int = (c shr 8) and 0xFF
    fun blue(c: Int): Int = c and 0xFF

    fun withAlpha(c: Int, a: Int): Int = (c and 0xFFFFFF) or (a.coerceIn(0, 255) shl 24)
    fun withAlpha(c: Int, a: Float): Int = withAlpha(c, (a * 255f).roundToInt())

    /** [a] moved toward [b] by [t] (0..1) in every channel, alpha included. */
    fun mix(a: Int, b: Int, t: Float): Int {
        if (t <= 0f) return a
        if (t >= 1f) return b
        val s = 1f - t
        val aa = (a ushr 24) * s + (b ushr 24) * t
        val r = ((a shr 16) and 0xFF) * s + ((b shr 16) and 0xFF) * t
        val g = ((a shr 8) and 0xFF) * s + ((b shr 8) and 0xFF) * t
        val bb = (a and 0xFF) * s + (b and 0xFF) * t
        return ((aa + 0.5f).toInt() shl 24) or ((r + 0.5f).toInt() shl 16) or ((g + 0.5f).toInt() shl 8) or (bb + 0.5f).toInt()
    }

    /** Every colour channel times [k], clamped; alpha kept. */
    fun scale(c: Int, k: Float): Int {
        val r = (((c shr 16) and 0xFF) * k + 0.5f).toInt().coerceIn(0, 255)
        val g = (((c shr 8) and 0xFF) * k + 0.5f).toInt().coerceIn(0, 255)
        val b = ((c and 0xFF) * k + 0.5f).toInt().coerceIn(0, 255)
        return (c and -0x1000000) or (r shl 16) or (g shl 8) or b
    }

    /** Perceived brightness 0..255 (Rec. 601 weights). */
    fun luma(c: Int): Float = 0.299f * red(c) + 0.587f * green(c) + 0.114f * blue(c)

    /** [c] moved toward its own grey by [t]. */
    fun desaturate(c: Int, t: Float): Int {
        if (t <= 0f) return c
        val y = luma(c)
        val s = 1f - t
        val r = red(c) * s + y * t
        val g = green(c) * s + y * t
        val b = blue(c) * s + y * t
        return (c and -0x1000000) or (round(r) shl 16) or (round(g) shl 8) or round(b)
    }

    /** "#RGB", "#RRGGBB" or "#AARRGGBB" (a leading '#' optional) as an opaque or translucent colour. */
    fun parse(hex: String): Int = parseOrNull(hex) ?: throw IllegalArgumentException("not a colour: $hex")

    fun parseOrNull(hex: String?): Int? {
        if (hex == null) return null
        val s = hex.trim().removePrefix("#")
        if (s.any { Character.digit(it, 16) < 0 }) return null
        return when (s.length) {
            3 -> {
                val r = Character.digit(s[0], 16) * 17
                val g = Character.digit(s[1], 16) * 17
                val b = Character.digit(s[2], 16) * 17
                rgb(r, g, b)
            }
            6 -> (0xFF shl 24) or s.toInt(16)
            8 -> s.toLong(16).toInt()
            else -> null
        }
    }

    fun toHex(c: Int): String = "#%02X%02X%02X".format(red(c), green(c), blue(c))

    /**
     * A mapped colour value (`building:colour`, the tiles' `colour`): hex exactly, or a CSS colour word the way mappers
     * write it, as the paint it means (pulled 16% toward a warm white, as house paint is; white and black stay).
     */
    fun paint(value: String?, toward: Float = 0.16f): Int? {
        val w = value?.trim()?.lowercase()?.replace(" ", "")?.replace("_", "") ?: return null
        if (w.startsWith("#")) return parseOrNull(w)
        val named = NAMED[w] ?: return null
        if (w == "white" || w == "black") return named
        return mix(named, PAINT_WHITE, toward)
    }

    /** A colour word or hex value exactly, without the paint shift (roof colours). */
    fun named(value: String?): Int? {
        val w = value?.trim()?.lowercase()?.replace(" ", "")?.replace("_", "") ?: return null
        return if (w.startsWith("#")) parseOrNull(w) else NAMED[w]
    }

    private val PAINT_WHITE = rgb(244, 240, 232)

    /** Colour words as OSM mappers write them (CSS names), to the colour they mean on a building. */
    val NAMED: Map<String, Int> = mapOf(
        "white" to "#FFFFFF", "black" to "#2A2B2E", "grey" to "#808080", "gray" to "#808080", "lightgrey" to "#D3D3D3",
        "lightgray" to "#D3D3D3", "darkgrey" to "#A9A9A9", "darkgray" to "#A9A9A9", "dimgray" to "#696969", "dimgrey" to "#696969",
        "silver" to "#C0C0C0", "gainsboro" to "#DCDCDC", "whitesmoke" to "#F5F5F5", "slategray" to "#708090", "slategrey" to "#708090",
        "red" to "#B03A2E", "darkred" to "#8B0000", "firebrick" to "#B22222", "indianred" to "#CD5C5C", "brown" to "#8B5A3C",
        "maroon" to "#800000", "saddlebrown" to "#8B4513", "sienna" to "#A0522D", "peru" to "#CD853F", "sandybrown" to "#F4A460",
        "burlywood" to "#DEB887", "tan" to "#D2B48C", "wheat" to "#F5DEB3", "beige" to "#E8E0C8", "ivory" to "#FFFFF0",
        "linen" to "#FAF0E6", "antiquewhite" to "#FAEBD7", "cream" to "#F3EBD3", "khaki" to "#C3B091", "yellow" to "#E6C840",
        "gold" to "#D4AF37", "orange" to "#E07B39", "coral" to "#FF7F50", "salmon" to "#FA8072", "tomato" to "#E5533C",
        "pink" to "#E8B4BE", "rosybrown" to "#BC8F8F", "green" to "#4F7A3A", "darkgreen" to "#2F5233", "olive" to "#808000",
        "blue" to "#3A5F9A", "navy" to "#1F2E5A", "lightblue" to "#ADD8E6", "skyblue" to "#87CEEB", "teal" to "#2F7F7F",
        "purple" to "#6A4A8A", "darkslategray" to "#2F4F4F", "darkslategrey" to "#2F4F4F", "mahogany" to "#7B3A2A",
        "burgundy" to "#7A2533", "ochre" to "#C8913F", "lightyellow" to "#F2E8B6", "lightgreen" to "#B5D3A8",
        "lightpink" to "#EBCBCB", "terracotta" to "#B5643F",
    ).mapValues { parse(it.value) }
}

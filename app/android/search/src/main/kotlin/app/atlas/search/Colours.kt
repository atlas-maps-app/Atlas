package app.atlas.search

import java.util.Locale
import kotlin.math.max
import kotlin.math.min

/**
 * The colours places are mapped in (`building:colour`, `roof:colour`, `colour`: "red", "#a0522d", "sienna"), read as
 * the families people name them by ("a red church"): red, orange, yellow, green, blue, purple, pink, brown, beige,
 * white, grey, black.
 */
object Colours {
    val FAMILIES = listOf("red", "orange", "yellow", "green", "blue", "purple", "pink", "brown", "beige", "white", "grey", "black")

    /** The keys a place's colours are read from, its own before its building's and its roof's. */
    val KEYS = listOf("colour", "building:colour", "roof:colour")

    /** The families of a place's colours, by [KEYS] (every `;` part), best known first. */
    fun of(tags: Map<String, String>): List<String> =
        KEYS.mapNotNull { tags[it] }.flatMap { v -> v.split(';').mapNotNull { family(it) } }.distinct()

    /** The family of a colour value (a name or #RGB / #RRGGBB), or null when it is neither. */
    fun family(value: String): String? {
        val v = value.trim().lowercase(Locale.ROOT).replace(" ", "").replace("_", "")
        NAMED_FAMILY[v]?.let { return it }
        val rgb = hex(v) ?: NAMED[v] ?: return null
        return ofRgb(rgb)
    }

    /** The family of an 0xRRGGBB colour, by its hue, saturation and lightness. */
    fun ofRgb(rgb: Int): String {
        val r = (rgb shr 16 and 0xFF) / 255.0
        val g = (rgb shr 8 and 0xFF) / 255.0
        val b = (rgb and 0xFF) / 255.0
        val hi = max(r, max(g, b))
        val lo = min(r, min(g, b))
        val l = (hi + lo) / 2
        val d = hi - lo
        val s = if (d == 0.0) 0.0 else d / (1 - kotlin.math.abs(2 * l - 1))
        if (l < 0.16) return "black"
        if (s < 0.14 || d < 0.06) return if (l > 0.86) "white" else if (l < 0.22) "black" else "grey"
        val h = when (hi) {
            r -> 60 * (((g - b) / d) % 6)
            g -> 60 * ((b - r) / d + 2)
            else -> 60 * ((r - g) / d + 4)
        }.let { if (it < 0) it + 360 else it }
        return when {
            h < 15 || h >= 345 -> if (l > 0.78) "pink" else if (l < 0.3 && s < 0.6) "brown" else "red"
            h < 45 -> when {
                l > 0.72 && s < 0.75 -> "beige"
                l < 0.48 || s < 0.45 -> "brown"
                else -> "orange"
            }
            h < 68 -> when {
                l > 0.7 && s < 0.6 -> "beige"
                l < 0.4 -> "brown"
                else -> "yellow"
            }
            h < 170 -> "green"
            h < 255 -> "blue"
            h < 290 -> "purple"
            else -> if (l < 0.35) "purple" else "pink"
        }
    }

    /** The family [word] names in English, Dutch, German or French ("rood", "rote", "rouge"), folded; null for none. */
    fun named(word: String): String? = WORDS[word]

    private fun hex(v: String): Int? {
        val h = v.removePrefix("#")
        if (h.isEmpty() || !h.all { it.isDigit() || it in 'a'..'f' }) return null
        return when (h.length) {
            3 -> h.map { "$it$it" }.joinToString("").toInt(16)
            6 -> h.toInt(16)
            else -> null
        }
    }

    /** Names whose family is plain, whatever their exact shade. */
    private val NAMED_FAMILY = buildMap {
        for (f in FAMILIES) put(f, f)
        put("gray", "grey")
        put("brick", "red")
        put("redbrick", "red")
        put("terracotta", "orange")
        put("cream", "beige")
        put("sand", "beige")
        put("sandstone", "beige")
        put("ochre", "yellow")
        put("anthracite", "grey")
        put("concrete", "grey")
    }

    /** The CSS names OpenStreetMap's colour values use most, as 0xRRGGBB. */
    private val NAMED: Map<String, Int> = mapOf(
        "maroon" to 0x800000, "darkred" to 0x8B0000, "firebrick" to 0xB22222, "crimson" to 0xDC143C, "indianred" to 0xCD5C5C,
        "brown" to 0xA52A2A, "sienna" to 0xA0522D, "saddlebrown" to 0x8B4513, "chocolate" to 0xD2691E, "peru" to 0xCD853F,
        "rosybrown" to 0xBC8F8F, "tan" to 0xD2B48C, "burlywood" to 0xDEB887, "wheat" to 0xF5DEB3, "khaki" to 0xF0E68C,
        "darkkhaki" to 0xBDB76B, "gold" to 0xFFD700, "goldenrod" to 0xDAA520, "darkgoldenrod" to 0xB8860B, "coral" to 0xFF7F50,
        "tomato" to 0xFF6347, "salmon" to 0xFA8072, "darkorange" to 0xFF8C00, "orangered" to 0xFF4500, "ivory" to 0xFFFFF0,
        "linen" to 0xFAF0E6, "beige" to 0xF5F5DC, "antiquewhite" to 0xFAEBD7, "bisque" to 0xFFE4C4, "navajowhite" to 0xFFDEAD,
        "silver" to 0xC0C0C0, "lightgrey" to 0xD3D3D3, "lightgray" to 0xD3D3D3, "darkgrey" to 0xA9A9A9, "darkgray" to 0xA9A9A9,
        "dimgrey" to 0x696969, "dimgray" to 0x696969, "slategrey" to 0x708090, "slategray" to 0x708090, "gainsboro" to 0xDCDCDC,
        "whitesmoke" to 0xF5F5F5, "snow" to 0xFFFAFA, "navy" to 0x000080, "darkblue" to 0x00008B, "royalblue" to 0x4169E1,
        "steelblue" to 0x4682B4, "lightblue" to 0xADD8E6, "skyblue" to 0x87CEEB, "teal" to 0x008080, "turquoise" to 0x40E0D0,
        "olive" to 0x808000, "darkgreen" to 0x006400, "forestgreen" to 0x228B22, "seagreen" to 0x2E8B57, "lightgreen" to 0x90EE90,
        "lime" to 0x00FF00, "darkolivegreen" to 0x556B2F, "violet" to 0xEE82EE, "magenta" to 0xFF00FF, "fuchsia" to 0xFF00FF,
        "indigo" to 0x4B0082, "lavender" to 0xE6E6FA, "plum" to 0xDDA0DD, "hotpink" to 0xFF69B4, "lightpink" to 0xFFB6C1,
    )

    /** The families by their words in the four languages, folded as queries are. */
    private val WORDS: Map<String, String> = buildMap {
        fun f(family: String, words: String) = words.split(' ').forEach { put(it, family) }
        f("red", "red rood rode rot rote roten roter rotes rouge rouges")
        f("orange", "orange oranje oranjes orangen")
        f("yellow", "yellow geel gele gelb gelbe gelben gelber jaune jaunes gold golden goud gouden goldene goldener goldenes dore doree")
        f("green", "green groen groene grun grune grunen gruner vert verte verts vertes")
        f("blue", "blue blauw blauwe blau blaue blauen blauer bleu bleue bleus bleues")
        f("purple", "purple paars paarse lila violett violette")
        f("pink", "pink roze rosa")
        f("brown", "brown bruin bruine braun braune braunen brauner brun brune bruns brunes marron")
        f("beige", "beige")
        f("white", "white wit witte weiss weisse weissen weisser blanc blanche blancs blanches")
        f("grey", "grey gray grijs grijze grau graue grauen grauer gris grise")
        f("black", "black zwart zwarte schwarz schwarze schwarzen schwarzer noir noire noirs noires")
    }
}

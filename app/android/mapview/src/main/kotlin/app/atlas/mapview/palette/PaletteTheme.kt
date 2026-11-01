package app.atlas.mapview.palette

/**
 * The palette's colours that the moment's theme carries to the core, every frame: the bare ground, the streets without
 * a mapped surface, the water and its gradient, and the light (sky, sun, the shade's light, shadows, haze). A changed
 * colour moves each moment's own by the same ratio per channel, so the live day's (whose colours are today's slots)
 * takes it exactly and dusk, night or rain keep their mood around it; a colour left as today's leaves the theme as it
 * is, byte for byte.
 */
object PaletteTheme {
    /** The theme's colour keys (ThemeBlock.COLOURS) and the slots that move them, by day and at night. */
    private fun slotOf(key: String, night: Boolean): Slot? = when (key) {
        "ground" -> if (night) Slot.NIGHT_GROUND else Slot.PAPER
        "road" -> Slot.STREET
        "water" -> if (night) Slot.NIGHT_WATER else Slot.WATER
        "water_top" -> Slot.WATER_SKY
        "water_bot" -> Slot.WATER_DEEP
        "sky_top", "sky_hor" -> Slot.SKY
        "sun" -> Slot.SUN
        "amb" -> Slot.SHADE
        "shadow" -> Slot.SHADOWS
        "haze" -> Slot.HAZE
        else -> null
    }

    /** [theme] with its colours moved by [palette]; [theme] itself where the palette changes none of them. */
    fun recolour(theme: Map<String, Any?>, palette: Palette): Map<String, Any?> {
        val night = theme["night"] == true
        var out: LinkedHashMap<String, Any?>? = null
        for ((key, value) in theme) {
            val slot = slotOf(key, night) ?: continue
            val to = palette.colour(slot)
            if (to == slot.today || value !is String) continue
            val moved = move(rgbOf(value), slot.today, to)
            (out ?: LinkedHashMap(theme).also { out = it })[key] = "#%06X".format(moved)
        }
        return out ?: theme
    }

    /** [c] moved as [from] becomes [to]: each channel times to / from, or shifted by the difference where from's is 0. */
    fun move(c: Int, from: Int, to: Int): Int {
        var out = 0
        for (sh in intArrayOf(16, 8, 0)) {
            val v = (c shr sh) and 0xFF
            val f = (from shr sh) and 0xFF
            val t = (to shr sh) and 0xFF
            val m = if (f == 0) v + t else (v * t + f / 2) / f
            out = out or (m.coerceIn(0, 255) shl sh)
        }
        return out
    }

    /** #RGB or #RRGGBB as 0xRRGGBB. */
    private fun rgbOf(hex: String): Int {
        val h = hex.removePrefix("#")
        return if (h.length == 3) h.map { "$it$it" }.joinToString("").toInt(16) else h.toInt(16)
    }
}

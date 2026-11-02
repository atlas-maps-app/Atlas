package app.atlas.mapview.palette

import kotlin.math.hypot

/**
 * The palette's colours that the moment's theme carries to the core, every frame: the bare ground, the streets without
 * a mapped surface, the water and its gradient, and the light (sky, sun, the shade's light, shadows, haze). A changed
 * colour moves each moment's own with it ([move]), so the live day's (whose colours are today's slots) takes it
 * exactly and dusk, night or rain keep their mood around it; a colour left as today's leaves the theme as it is, byte
 * for byte. With grass on bare ground (PaletteFlags.GRASS) the bare ground is the lawns' colour where it was the
 * paper's, so where nothing is mapped it is grass; the night's stays the night ground's.
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
        val grass = palette.switches and PaletteFlags.GRASS != 0
        var out: LinkedHashMap<String, Any?>? = null
        for ((key, value) in theme) {
            val slot = slotOf(key, night) ?: continue
            val to = if (grass && slot == Slot.PAPER) palette.colour(Slot.LAWN) else palette.colour(slot)
            if (to == slot.today || value !is String) continue
            val moved = move(rgbOf(value), slot.today, to)
            (out ?: LinkedHashMap(theme).also { out = it })[key] = "#%06X".format(moved)
        }
        return out ?: theme
    }

    /**
     * [c] carried along as the colour it goes with, [from], becomes [to]: the palette's rule for a moment's own colour
     * and, in the core, for a slot's night, its lit side and the parts drawn with it (style::palette_move, the same
     * rule). In Oklab, [c] keeps its lightness against [from]'s (a darker tone the same share of the way to black, a
     * lighter one of the way to white) and its tint against [from]'s, per unit of lightness, turned as [from]'s turns
     * into [to]'s and lessened as [to]'s is greyer, never strengthened. So [from] becomes [to], a darker tone of it the
     * same tone of [to], and a grey [to] greys whatever goes with it.
     */
    fun move(c: Int, from: Int, to: Int): Int {
        if (c == from) return to
        val (vl, va, vb) = Oklab.of(c)
        val (fl, fa, fb) = Oklab.of(from)
        val (tl, ta, tb) = Oklab.of(to)
        val l = when {
            vl < fl -> tl * (vl / fl)
            vl > fl && fl < 1f -> 1f - (1f - tl) * ((1f - vl) / (1f - fl))
            else -> tl
        }
        // the tints per unit of lightness, c's against from's turned as from's turns into to's, never strengthened (so a
        // grey's tones' small tints are not blown up; GREY the tint under which a colour has no hue to turn by)
        fun tint(l: Float, x: Float) = if (l > 1e-9f) x / l else 0f
        val vx = tint(vl, va)
        val vy = tint(vl, vb)
        val fx = tint(fl, fa)
        val fy = tint(fl, fb)
        val tx = tint(tl, ta)
        val ty = tint(tl, tb)
        val fm = hypot(fx, fy)
        var re = 0f
        var im = 0f
        if (fm > 1e-9f) {
            val d = fm * maxOf(fm, hypot(tx, ty), GREY)
            re = (tx * fx + ty * fy) / d
            im = (ty * fx - tx * fy) / d
        }
        val dx = vx - fx
        val dy = vy - fy
        return Oklab.rgb(l, (tx + dx * re - dy * im) * l, (ty + dx * im + dy * re) * l)
    }

    private const val GREY = 0.02f

    /** #RGB or #RRGGBB as 0xRRGGBB. */
    private fun rgbOf(hex: String): Int {
        val h = hex.removePrefix("#")
        return if (h.length == 3) h.map { "$it$it" }.joinToString("").toInt(16) else h.toInt(16)
    }
}

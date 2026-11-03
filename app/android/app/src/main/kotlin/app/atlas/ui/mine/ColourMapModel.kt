package app.atlas.ui.mine

import app.atlas.mapview.palette.Family
import app.atlas.mapview.palette.Oklab
import app.atlas.mapview.palette.Palette
import app.atlas.mapview.palette.PaletteTheme
import app.atlas.mapview.palette.Slot
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * The Colour the map board's logic: its groups and their colours, a colour as the sliders show it (OKLCH), the board's
 * light of a colour in sun, in shade and at night, and the palette as a colour changes, alone or with its group.
 */
internal object ColourMapModel {
    /** A colour as the board's sliders hold it: lightness (0 to 1), chroma and hue (degrees). */
    data class Lch(val l: Float, val c: Float, val h: Float)

    /** The sliders' reaches, as the board's: lightness 0.15 to 0.97, chroma 0 to 0.25, hue all round. */
    const val L_MIN = 0.15f
    const val L_MAX = 0.97f
    const val C_MAX = 0.25f

    /** The colours of [family] in the board's order. */
    fun slots(family: Family): List<Slot> = Slot.entries.filter { it.family == family }

    /** [rgb] (0xRRGGBB) in OKLCH. */
    fun lch(rgb: Int): Lch {
        val (l, a, b) = Oklab.of(rgb)
        val h = Math.toDegrees(atan2(b, a).toDouble()).toFloat()
        return Lch(l, hypot(a, b), (h + 360f) % 360f)
    }

    /** The colour of [x], each channel clamped. */
    fun rgb(x: Lch): Int {
        val r = Math.toRadians(x.h.toDouble())
        return Oklab.rgb(x.l, x.c * cos(r).toFloat(), x.c * sin(r).toFloat())
    }

    /** #RRGGBB of [rgb]. */
    fun hex(rgb: Int): String = "#%06X".format(rgb and 0xFFFFFF)

    /** The colour [text] names (#RGB, #RRGGBB, with or without the #), or null. */
    fun hexOf(text: String): Int? {
        val h = text.trim().removePrefix("#")
        if (!h.all { it.isDigit() || it.lowercaseChar() in 'a'..'f' }) return null
        return when (h.length) {
            3 -> h.map { "$it$it" }.joinToString("").toInt(16)
            6 -> h.toInt(16)
            else -> null
        }
    }

    /**
     * The board's light of [rgb] (render/amsterdam.py's light with TONE, as `Colour.dc.html` has it): a roof facing the
     * sun at [lam] of its light, the same in shade (0), or at [night]; the sun's and the shade's colours the palette's.
     */
    fun light(rgb: Int, lam: Double, night: Boolean, sun: Int = Slot.SUN.today, shade: Int = Slot.SHADE.today): Int {
        val amb = if (night) 0x3A4668 else shade
        var out = 0
        for (shift in intArrayOf(16, 8, 0)) {
            val v = ((rgb shr shift) and 0xFF).toDouble()
            val a = ((amb shr shift) and 0xFF).toDouble()
            val c = if (night) {
                (v + (a - v) * 0.55) * 0.48
            } else {
                val k = 0.6
                val lit = v * k + (a * k * 0.55 - v * k) * 0.26 + v * 0.3 + v * lam * 1.05 * ((sun shr shift) and 0xFF) / 255.0
                if (lit < 205.0) lit else 205.0 + 50.0 * (1.0 - exp(-(lit - 205.0) / 50.0))
            }
            out = (out shl 8) or c.roundToInt().coerceIn(0, 255)
        }
        return out
    }

    /** True when text on [rgb] reads best dark (the board: its OKLCH lightness over 0.62). */
    fun darkText(rgb: Int): Boolean = Oklab.of(rgb).first > 0.62f

    /** [p] with [slot] in [colour]: none of its own where that is its preset's. */
    fun withColour(p: Palette, slot: Slot, colour: Int): Palette {
        val colours = if (colour == p.base.colour(slot)) p.colours - slot else p.colours + (slot to colour)
        return p.copy(colours = colours)
    }

    /**
     * [p] with [slot] in [colour] and every other colour of its group moved with it, as the palette moves what goes with
     * a colour (PaletteTheme.move): each from its preset's colour as [slot]'s preset colour becomes [colour].
     */
    fun withGroup(p: Palette, slot: Slot, colour: Int): Palette {
        val from = p.base.colour(slot)
        return slots(slot.family).fold(p) { q, s ->
            withColour(q, s, if (s == slot) colour else PaletteTheme.move(p.base.colour(s), from, colour))
        }
    }

    /** [p] with [slot] back to its preset's colour. */
    fun reset(p: Palette, slot: Slot): Palette = p.copy(colours = p.colours - slot)

    /** True when [p] draws [slot] other than its preset does (the board's dot). */
    fun changed(p: Palette, slot: Slot): Boolean = p.colours[slot]?.let { it != p.base.colour(slot) } ?: false
}

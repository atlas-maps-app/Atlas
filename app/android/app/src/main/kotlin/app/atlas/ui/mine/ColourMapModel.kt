package app.atlas.ui.mine

import app.atlas.geo.LatLon
import app.atlas.mapview.CameraPosition
import app.atlas.mapview.palette.Family
import app.atlas.mapview.palette.Oklab
import app.atlas.mapview.palette.Palette
import app.atlas.mapview.palette.PaletteFace
import app.atlas.mapview.palette.PaletteTheme
import app.atlas.mapview.palette.Slot
import app.atlas.services.SavedCamera
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

    /** The sliders' reaches: lightness black to white, chroma 0 to 0.25, hue all round. */
    const val L_MIN = 0f
    const val L_MAX = 1f
    const val C_MAX = 0.25f

    /** [x] as the sliders say it, each from 0 to 100: its lightness, its saturation (of the sliders' reach) and its hue. */
    fun shown(x: Lch): Triple<Int, Int, Int> = Triple(
        ((x.l - L_MIN) / (L_MAX - L_MIN) * 100f).roundToInt().coerceIn(0, 100),
        (x.c / C_MAX * 100f).roundToInt().coerceIn(0, 100),
        (x.h / 360f * 100f).roundToInt().coerceIn(0, 100),
    )

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
     * a colour (PaletteTheme.move): each from where it is as [slot]'s colour goes from where it is to [colour], so an
     * earlier change of one of them is moved too, not undone.
     */
    fun withGroup(p: Palette, slot: Slot, colour: Int): Palette {
        val from = p.colour(slot)
        if (from == colour) return p
        return slots(slot.family).fold(p) { q, s ->
            withColour(q, s, if (s == slot) colour else PaletteTheme.move(p.colour(s), from, colour))
        }
    }

    /** [p] with [slot] back to its colour in [opened] (the palette the editor opened with). */
    fun reset(p: Palette, slot: Slot, opened: Palette): Palette = withColour(p, slot, opened.colour(slot))

    /** The first colour [a] draws other than [b] does (what an undo brings back into view), or null where none does. */
    fun differing(a: Palette, b: Palette): Slot? = Slot.entries.firstOrNull { a.colour(it) != b.colour(it) }

    /**
     * The colour a tap on a building's [face] picks: the slot that paints it, where one does and the map shows it so:
     * not a guessed face drawn plain while Atlas's guesses are off, a mapped colour kept as mapped, or one no slot paints.
     */
    fun picks(face: PaletteFace): Slot? = face.slot?.takeIf { face.colour == PaletteFace.How.GUESSED || face.colour == PaletteFace.How.MAPPED }

    /**
     * Where the editor's map opens: where the map last was ([saved]), close enough to see the buildings, else [here];
     * tilted, as the board draws it.
     */
    fun start(saved: SavedCamera?, here: LatLon): CameraPosition =
        if (saved == null) CameraPosition(here, 16.4, 0.0, 45.0) else CameraPosition(saved.target, saved.zoom.coerceIn(15.0, 18.5), saved.bearing, 45.0)

    /** True when [p] draws [slot] other than its preset does (the board's dot). */
    fun changed(p: Palette, slot: Slot): Boolean = p.colours[slot]?.let { it != p.base.colour(slot) } ?: false
}

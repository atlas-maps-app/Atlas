package app.atlas.ui.mine

import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import app.atlas.mapview.palette.Palette
import app.atlas.mapview.palette.PaletteCode
import app.atlas.mapview.palette.PaletteFlags
import app.atlas.mapview.palette.Preset
import app.atlas.mapview.palette.Slot
import app.atlas.services.SettingsKeys

/** A palette the user saved (the Map look board's Your palettes): its [name] and its [code] (PaletteCode, AT1-…). */
internal data class SavedPalette(val name: String, val code: String) {
    /** The palette the code holds, or null where this Atlas cannot read it. */
    val palette: Palette? get() = MapLookModel.read(code)
}

/**
 * The Map look board's logic: the presets' glyphs, the palette the map draws and its switches, the Tone a palette
 * brings, and the palettes the user saved. The map's palette is kept as its code without the Tone (MAP_PALETTE), the
 * Tone as MAP_TONE and MAP_TONE_ON (the Map look's and the Layers sheet's), so a saved or shared palette's code carries
 * the Tone it was saved with and taking it sets the Tone.
 */
internal object MapLookModel {
    /** The slots of the board's glyph in its order: bare ground, water, street, lawn, three roofs each over its wall, a tree. */
    val GLYPH = listOf(
        Slot.PAPER, Slot.WATER, Slot.STREET, Slot.LAWN, Slot.RED_TILES, Slot.OLD_BRICK_2, Slot.DARK_TILES_1,
        Slot.PLASTER_1, Slot.BITUMEN, Slot.NEW_BRICK_1, Slot.LEAVES,
    )

    /** The four colours of a saved palette's tile (the board's 2 × 2 grid): bare ground, red tiles, lawn, water. */
    val TILE = listOf(Slot.PAPER, Slot.RED_TILES, Slot.LAWN, Slot.WATER)

    /** The colours [p]'s glyph is drawn in, [GLYPH]'s order; the third roof a flat grey where the flat greys are on. */
    fun glyph(p: Palette): List<Int> = GLYPH.map { slot ->
        if (slot == Slot.BITUMEN && p.switches and PaletteFlags.FLAT_GREYS != 0) p.colour(Slot.FLAT_GREY_1) else p.colour(slot)
    }

    /** The palette [code] holds, or null for none or one this Atlas cannot read. */
    fun read(code: String?): Palette? = code?.let { (PaletteCode.read(it) as? PaletteCode.Read.Ok)?.palette }

    /** The palette the map draws for MAP_PALETTE's [code]: today's when unset or unreadable. */
    fun current(code: String?): Palette = read(code) ?: Palette()

    /** MAP_PALETTE for [p]: its code without the Tone, or null (unset) for today's look. */
    fun keep(p: Palette): String? {
        val bare = p.copy(tone = null)
        return if (bare == Palette()) null else PaletteCode.write(bare)
    }

    /** [p] with the switch [bit] (PaletteFlags) [on]: no switches of its own where they come out as its base's. */
    fun withSwitch(p: Palette, bit: Int, on: Boolean): Palette {
        val flags = if (on) p.switches or bit else p.switches and bit.inv()
        return p.copy(flags = flags.takeIf { it != p.base.flags })
    }

    /** True when [p]'s switch [bit] is on. */
    fun on(p: Palette, bit: Int): Boolean = p.switches and bit != 0

    /** The Tone taking [p] sets: on where its strength is above 0, and the strength (today's 1 where it is off). */
    fun toneOf(p: Palette): Pair<Boolean, Float> = p.toneStrength.let { s -> (s > 0f) to (if (s > 0f) s else 1f) }

    /** [p] with the Tone the Map look has ([on], [strength]): none of its own where that is its base's. */
    fun withTone(p: Palette, on: Boolean, strength: Float): Palette {
        val s = if (on) strength else 0f
        return p.copy(tone = s.takeIf { Math.round(it * 20f) != Math.round(p.base.tone * 20f) })
    }

    /** The index of the saved palette the map draws ([current], the Tone aside), or -1. */
    fun savedIndex(saved: List<SavedPalette>, current: Palette): Int {
        val bare = current.copy(tone = null)
        return saved.indexOfFirst { it.palette?.copy(tone = null) == bare }
    }

    /** The preset whose glyph is ringed: [current]'s base where it changes no colour, else none. */
    fun ringed(current: Palette): Preset? = current.base.takeIf { current.changed == 0 }

    /** The palettes as the settings keep them (MAP_PALETTES): a line each, the code, a tab and the name. */
    fun savedOf(text: String?): List<SavedPalette> = text.orEmpty().lineSequence().mapNotNull { line ->
        val tab = line.indexOf('\t')
        if (tab <= 0) null else SavedPalette(line.substring(tab + 1), line.substring(0, tab))
    }.toList()

    /** MAP_PALETTES for [saved]: null (unset) for none. */
    fun textOf(saved: List<SavedPalette>): String? =
        saved.takeIf { it.isNotEmpty() }?.joinToString("\n") { "${it.code}\t${clean(it.name)}" }

    /** A palette's name as it is kept: one line, no tabs, at most [NAME_MAX] characters. */
    fun clean(name: String): String = name.replace(Regex("[\\t\\r\\n]+"), " ").trim().take(NAME_MAX)

    /** The longest name a palette keeps. */
    const val NAME_MAX = 40

    /** The name of a new palette from [base] (its preset's name): "[base] 1", or the least number no other has. */
    fun newName(base: String, taken: Collection<String>): String =
        generateSequence(1) { it + 1 }.map { "$base $it" }.first { it !in taken }

    /** [saved] with the palette at [index] taking [p], as it keeps it (its code, the Tone with it). */
    fun replaced(saved: List<SavedPalette>, index: Int, p: Palette): List<SavedPalette> =
        saved.mapIndexed { i, s -> if (i == index) s.copy(code = PaletteCode.write(p)) else s }

    /** A palette's code found in [text] (a code alone, the QR's `app.atlas:` link, or a message holding one), or null. */
    fun codeIn(text: String?): String? = text?.let { CODE.find(it)?.value?.trimEnd('-') }

    /** The name a shared message gives before its code ([text]'s words before it on its first line), or null. */
    fun nameIn(text: String?): String? {
        val t = text ?: return null
        val at = CODE.find(t)?.range?.first ?: return null
        val before = t.substring(0, at).removeSuffix(LINK).lineSequence().map { it.trim().trimEnd(':', '·', '-').trim() }
            .firstOrNull { it.isNotEmpty() } ?: return null
        return clean(before).takeIf { it.isNotEmpty() }
    }

    /** The QR's text: the scheme Atlas opens (lower case, as Android matches it) and the code. */
    fun link(code: String): String = LINK + code

    /** The message a palette is shared as: its name, then its code on a line of its own. */
    fun message(name: String, code: String): String = "$name\n$code"

    const val LINK = "app.atlas:"

    private val CODE = Regex("""(?i)\bAT1-[0-9A-Z]+(?:-[0-9A-Z]+)+""")
}

/**
 * The Map look's writes, each inside one edit of the settings (SettingsStore.edit), so the map's palette (MAP_PALETTE),
 * its Tone (MAP_TONE, MAP_TONE_ON) and the saved palettes (MAP_PALETTES) never disagree: a switch or the Tone changed
 * while the map draws a saved palette changes that palette too, as a palette carries its switches and its Tone.
 */
internal object MapLookWrites {
    /** The map takes [palette] and the Tone it brings (a preset's, a saved or a shared palette's). */
    fun take(m: MutablePreferences, palette: Palette) {
        m.put(SettingsKeys.MAP_PALETTE, MapLookModel.keep(palette))
        val (on, strength) = MapLookModel.toneOf(palette)
        m[SettingsKeys.MAP_TONE_ON] = on
        m[SettingsKeys.MAP_TONE] = strength
    }

    /** The switch [bit] (PaletteFlags) of the map's palette [on]. */
    fun switch(m: MutablePreferences, bit: Int, on: Boolean) {
        change(m, MapLookModel.withSwitch(current(m), bit, on))
    }

    /** The map's Tone: [on], at [strength] (kept for when it is on again while off). */
    fun tone(m: MutablePreferences, on: Boolean, strength: Float) {
        val at = savedAt(m)
        m[SettingsKeys.MAP_TONE_ON] = on
        m[SettingsKeys.MAP_TONE] = strength
        if (at >= 0) keepSaved(m, at, current(m))
    }

    /**
     * Adds [palette] to the saved palettes as [name] (or as the next "[base] n" without one; one saved alike is taken
     * as it is) and the map takes it. Its name as saved.
     */
    fun add(m: MutablePreferences, palette: Palette, name: String?, base: String): String {
        val saved = MapLookModel.savedOf(m[SettingsKeys.MAP_PALETTES])
        val alike = MapLookModel.savedIndex(saved, palette)
        if (alike >= 0) {
            take(m, saved[alike].palette ?: palette)
            return saved[alike].name
        }
        val kept = name?.let(MapLookModel::clean)?.takeIf { it.isNotEmpty() } ?: MapLookModel.newName(base, saved.map { it.name })
        m.put(SettingsKeys.MAP_PALETTES, MapLookModel.textOf(saved + SavedPalette(kept, PaletteCode.write(palette))))
        take(m, palette)
        return kept
    }

    /**
     * Colour the map's Save: the map's palette becomes [palette] with the map's Tone, and so does the saved palette it
     * was opened on ([from], its code then) where that is still saved, else it is saved as the next "[base] n". Nothing
     * is saved for a palette that changes no colour of its preset; the map takes it all the same.
     */
    fun save(m: MutablePreferences, palette: Palette, from: String?, base: String) {
        val saved = MapLookModel.savedOf(m[SettingsKeys.MAP_PALETTES])
        val at = from?.let { code -> saved.indexOfFirst { it.code == code } } ?: -1
        m.put(SettingsKeys.MAP_PALETTE, MapLookModel.keep(palette))
        val (on, strength) = tone(m)
        val toned = MapLookModel.withTone(palette, on, strength)
        when {
            at >= 0 -> m.put(SettingsKeys.MAP_PALETTES, MapLookModel.textOf(MapLookModel.replaced(saved, at, toned)))
            palette.changed > 0 -> {
                val name = MapLookModel.newName(base, saved.map { it.name })
                m.put(SettingsKeys.MAP_PALETTES, MapLookModel.textOf(saved + SavedPalette(name, PaletteCode.write(toned))))
            }
        }
    }

    /** The saved palette at [index] is no longer kept; the map draws what it drew. */
    fun remove(m: MutablePreferences, index: Int) {
        val saved = MapLookModel.savedOf(m[SettingsKeys.MAP_PALETTES])
        if (index !in saved.indices) return
        m.put(SettingsKeys.MAP_PALETTES, MapLookModel.textOf(saved.filterIndexed { i, _ -> i != index }))
    }

    /** The saved palette at [index] is called [name] (left as it was for a blank one). */
    fun rename(m: MutablePreferences, index: Int, name: String) {
        val saved = MapLookModel.savedOf(m[SettingsKeys.MAP_PALETTES])
        val clean = MapLookModel.clean(name)
        if (index !in saved.indices || clean.isEmpty()) return
        m.put(SettingsKeys.MAP_PALETTES, MapLookModel.textOf(saved.mapIndexed { i, s -> if (i == index) s.copy(name = clean) else s }))
    }

    private fun current(p: Preferences): Palette = MapLookModel.current(p[SettingsKeys.MAP_PALETTE])

    private fun tone(p: Preferences): Pair<Boolean, Float> = (p[SettingsKeys.MAP_TONE_ON] ?: true) to (p[SettingsKeys.MAP_TONE] ?: 1f)

    /** The saved palette the map draws, or -1. */
    private fun savedAt(p: Preferences): Int = MapLookModel.savedIndex(MapLookModel.savedOf(p[SettingsKeys.MAP_PALETTES]), current(p))

    /** The map's palette becomes [next], and the saved palette it was with it. */
    private fun change(m: MutablePreferences, next: Palette) {
        val at = savedAt(m)
        m.put(SettingsKeys.MAP_PALETTE, MapLookModel.keep(next))
        if (at >= 0) keepSaved(m, at, next)
    }

    /** The saved palette at [index] keeps [p] with the map's Tone. */
    private fun keepSaved(m: MutablePreferences, index: Int, p: Palette) {
        val saved = MapLookModel.savedOf(m[SettingsKeys.MAP_PALETTES])
        val (on, strength) = tone(m)
        m.put(SettingsKeys.MAP_PALETTES, MapLookModel.textOf(MapLookModel.replaced(saved, index, MapLookModel.withTone(p, on, strength))))
    }

    private fun <T> MutablePreferences.put(key: Preferences.Key<T>, value: T?) {
        if (value == null) remove(key) else this[key] = value
    }
}

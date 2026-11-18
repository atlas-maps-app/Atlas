package app.atlas.ui.mine

import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import app.atlas.mapview.palette.Palette
import app.atlas.mapview.palette.PaletteCode
import app.atlas.mapview.palette.PaletteFlags
import app.atlas.mapview.palette.Preset
import app.atlas.mapview.palette.Slot
import app.atlas.services.DEFAULT_PALETTE
import app.atlas.services.DEFAULT_TONE_ON
import app.atlas.services.SettingsKeys
import app.atlas.services.mapPalette

/** A palette the user saved (the Map look board's Your palettes): its [name] and its [code] (PaletteCode, AT1-…). */
internal data class SavedPalette(val name: String, val code: String) {
    /** The palette the code holds, or null where this Atlas cannot read it. */
    val palette: Palette? get() = MapLookModel.read(code)
}

/**
 * What a look draws, Atlas's guesses aside (they are the user's, never a look's): its [base] preset, the colours it
 * changes from the preset's, its Tone in steps of 5 % (0 to 40, 0 off) and its switches. Two palettes that draw the same
 * are the same look, whatever they keep of their own: this decides the ring, the line, and that a tap on what the map
 * draws changes nothing.
 */
internal data class Look(val base: Preset, val colours: Map<Slot, Int>, val tone: Int, val switches: Int)

/**
 * The Tone as both its controls (the Map look's card, the Layers sheet's row) show and write it: [on], and the strength
 * the switch brings back ([kept], 0 to 2). The map draws [drawn]: the kept strength while on, else nothing.
 */
internal data class Tone(val on: Boolean, val kept: Float) {
    val drawn: Float get() = if (on) kept else 0f
}

/**
 * The Map look board's logic: the presets' glyphs, the look the map draws (its palette, MAP_PALETTE, with the Tone,
 * MAP_TONE and MAP_TONE_ON), the saved palettes and what the page says of them. A saved or shared palette's code
 * carries the Tone it was saved with, so taking it sets the Tone.
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

    /** The palette the map draws for MAP_PALETTE's [code]: the default look's when unset or unreadable (mapPalette). */
    fun current(code: String?): Palette = mapPalette(code)

    /** MAP_PALETTE for [p]: its code without the Tone, or null (unset) for the default look's (DEFAULT_PALETTE). */
    fun keep(p: Palette): String? {
        val bare = p.copy(tone = null)
        return if (bare == DEFAULT_PALETTE) null else PaletteCode.write(bare)
    }

    /** The look [p] draws, at the Tone [tone] (its own unless given). */
    fun look(p: Palette, tone: Float = p.toneStrength): Look = Look(
        p.base,
        p.colours.filter { (slot, c) -> c != p.base.colour(slot) },
        Math.round(tone * 20f).coerceIn(0, 40),
        p.switches and PaletteFlags.NO_GUESSES.inv(),
    )

    /** The look the map draws: its palette at its Tone. */
    fun drawn(p: Preferences): Look = look(current(p[SettingsKeys.MAP_PALETTE]), tone(p).drawn)

    /** The map's Tone. A strength of 0 kept while on (as the slider's off end once wrote it) is off. */
    fun tone(p: Preferences): Tone = tone(p[SettingsKeys.MAP_TONE_ON], p[SettingsKeys.MAP_TONE])

    /** The Tone of MAP_TONE_ON's [on] and MAP_TONE's [kept], as they are read (null for unset). */
    fun tone(on: Boolean?, kept: Float?): Tone {
        val k = kept ?: 1f
        return Tone((on ?: DEFAULT_TONE_ON) && k > 0f, k)
    }

    /** [p] with the switch [bit] (PaletteFlags) [on]: no switches of its own where they come out as its base's. */
    fun withSwitch(p: Palette, bit: Int, on: Boolean): Palette {
        val flags = if (on) p.switches or bit else p.switches and bit.inv()
        return p.copy(flags = flags.takeIf { it != p.base.flags })
    }

    /** True when [p]'s switch [bit] is on. */
    fun on(p: Palette, bit: Int): Boolean = p.switches and bit != 0

    /** [p] as a look keeps it, saved or shared: Atlas's guesses are none of it. */
    fun lookPalette(p: Palette): Palette = withSwitch(p, PaletteFlags.NO_GUESSES, false)

    /** [p] at the Tone [strength]: none of its own where that is its base's. */
    fun withTone(p: Palette, strength: Float): Palette =
        p.copy(tone = strength.takeIf { Math.round(it * 20f) != Math.round(p.base.tone * 20f) })

    /** The index of the saved palette that draws [target], or -1. */
    fun savedIndex(saved: List<SavedPalette>, target: Look): Int = saved.indexOfFirst { s -> s.palette?.let { look(it) } == target }

    /** The preset that draws exactly [target] (its glyph ringed), or none. */
    fun ringed(target: Look): Preset? = Preset.entries.firstOrNull { look(Palette(it)) == target }

    /** The default look, as the map draws it with nothing set. */
    val DEFAULT_LOOK: Look get() = look(DEFAULT_PALETTE, if (DEFAULT_TONE_ON) 1f else 0f)

    /** What the page says the map draws: a saved palette, a preset, or a look changed from one. */
    sealed interface Line {
        data class Saved(val name: String) : Line
        data class Exact(val preset: Preset, val isDefault: Boolean) : Line

        /** Changed from the saved palette [from] (its name, where still saved), else from [base] with [colours] changed. */
        data class Changed(val from: String?, val base: Preset, val colours: Int) : Line
    }

    /** The line for [drawn], with the user's [saved] palettes and the code of the one the look came from ([from]). */
    fun line(drawn: Look, saved: List<SavedPalette>, from: String?): Line {
        saved.getOrNull(savedIndex(saved, drawn))?.let { return Line.Saved(it.name) }
        ringed(drawn)?.let { return Line.Exact(it, drawn == DEFAULT_LOOK) }
        return Line.Changed(saved.firstOrNull { it.code == from }?.name, drawn.base, drawn.colours.size)
    }

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

    /** What a name typed for a palette says: nothing (it will do), that it is blank, or that another palette has it. */
    sealed interface NameSays {
        data object Fine : NameSays
        data object Blank : NameSays
        data class Taken(val name: String) : NameSays
    }

    /** What [name] says as a palette's, [taken] the names of the others (not the palette's own). */
    fun nameSays(name: String, taken: Collection<String>): NameSays {
        val clean = clean(name)
        return when {
            clean.isEmpty() -> NameSays.Blank
            taken.any { it.equals(clean, ignoreCase = true) } -> NameSays.Taken(clean)
            else -> NameSays.Fine
        }
    }

    /** [name] where no other palette has it, else "[name] 2", or the least number no other has. */
    fun unique(name: String, taken: Collection<String>): String {
        val clean = clean(name)
        if (taken.none { it.equals(clean, ignoreCase = true) }) return clean
        return generateSequence(2) { it + 1 }.map { n -> "${clean.take(NAME_MAX - 1 - n.toString().length)} $n" }
            .first { c -> taken.none { it.equals(c, ignoreCase = true) } }
    }

    /** The name of a new palette from [base] (its preset's name): "[base] 1", or the least number no other has. */
    fun newName(base: String, taken: Collection<String>): String =
        generateSequence(1) { it + 1 }.map { "$base $it" }.first { it !in taken }

    /** A palette's code found in [text] (a code alone, the QR's `app.atlas:` link, or a message holding one), or null. */
    fun codeIn(text: String?): String? = text?.let { CODE.find(it)?.value?.trimEnd('-') }

    /** The name a shared message gives before its code ([text]'s words before it on its first line), or null. */
    fun nameIn(text: String?): String? {
        val t = text ?: return null
        val at = ANY_CODE.find(t)?.range?.first ?: return null
        val before = t.substring(0, at).removeSuffix(LINK).lineSequence().map { it.trim().trimEnd(':', '·', '-').trim() }
            .firstOrNull { it.isNotEmpty() } ?: return null
        return clean(before).takeIf { it.isNotEmpty() }
    }

    /** What a text typed, pasted or handed to Atlas holds. */
    sealed interface Found {
        /** A palette this Atlas reads, and the name its message gave it. */
        data class Ok(val palette: Palette, val name: String?) : Found
        data object Empty : Found
        data object NotCode : Found
        data object Mistyped : Found
        data object Newer : Found
    }

    /**
     * What [text] holds: a palette; nothing; no code; a code with a letter wrong, missing or too many (or cut short);
     * or one from a newer Atlas (a later AT number, a preset, colour or switch this Atlas does not know).
     */
    fun find(text: String?): Found {
        val t = text?.trim().orEmpty()
        if (t.isEmpty()) return Found.Empty
        val m = ANY_CODE.find(t) ?: return if (STARTED.containsMatchIn(t)) Found.Mistyped else Found.NotCode
        if (m.groupValues[1].trimStart('0') != "1") return Found.Newer
        return when (val read = PaletteCode.read(m.value)) {
            is PaletteCode.Read.Ok -> Found.Ok(read.palette, nameIn(t))
            is PaletteCode.Read.Bad -> when (read.fault) {
                PaletteCode.Fault.CHECK, PaletteCode.Fault.LETTER, PaletteCode.Fault.FORM -> Found.Mistyped
                PaletteCode.Fault.PRESET, PaletteCode.Fault.SLOT, PaletteCode.Fault.SWITCH -> Found.Newer
            }
        }
    }

    /** The QR's text: the scheme Atlas opens (lower case, as Android matches it) and the code. */
    fun link(code: String): String = LINK + code

    /** The message a palette is shared as: its name, then its link (which opens it in Atlas) on a line of its own. */
    fun message(name: String, code: String): String = "$name\n${link(code)}"

    const val LINK = "app.atlas:"

    private val CODE = Regex("""(?i)\bAT1-[0-9A-Z]+(?:-[0-9A-Z]+)+""")

    /** A code of any AT number, its groups apart by dashes or spaces (as a person types them), the number its first group. */
    private val ANY_CODE = Regex("""(?i)\bAT(\d+)[- ]+[0-9A-Z]+(?:[- ]+[0-9A-Z]{1,5}\b)*""")

    /** The start of a code, typed without the rest. */
    private val STARTED = Regex("""(?i)\bAT\d+\b""")
}

/**
 * The Map look's writes, each inside one edit of the settings (SettingsStore.edit). The map draws its palette
 * (MAP_PALETTE) at its Tone (MAP_TONE_ON, MAP_TONE); MAP_LOOK_FROM names the saved palette that look came from. A
 * switch or the Tone changes the map's look and never a saved palette: that is Colour the map's Save.
 */
internal object MapLookWrites {
    /**
     * The map takes the whole look [palette] (a preset's, a saved or a shared palette's): its colours, switches and Tone;
     * Atlas's guesses stay as the user has them. [from] is the saved palette's code, null for a preset.
     */
    fun take(m: MutablePreferences, palette: Palette, from: String? = null) {
        val guessesOff = MapLookModel.on(current(m), PaletteFlags.NO_GUESSES)
        val next = MapLookModel.withSwitch(MapLookModel.lookPalette(palette), PaletteFlags.NO_GUESSES, guessesOff)
        m.put(SettingsKeys.MAP_PALETTE, MapLookModel.keep(next))
        toneTo(m, palette.toneStrength)
        m.put(SettingsKeys.MAP_LOOK_FROM, from)
    }

    /** A tap on [palette]'s look: the map takes it, unless it draws it already. True where something changed. */
    fun pick(m: MutablePreferences, palette: Palette, from: String? = null): Boolean {
        if (MapLookModel.look(palette) == MapLookModel.drawn(m)) return false
        take(m, palette, from)
        return true
    }

    /** The switch [bit] (PaletteFlags) of the map's palette [on]. */
    fun switch(m: MutablePreferences, bit: Int, on: Boolean) {
        m.put(SettingsKeys.MAP_PALETTE, MapLookModel.keep(MapLookModel.withSwitch(current(m), bit, on)))
    }

    /** Atlas's guesses [on]. */
    fun guesses(m: MutablePreferences, on: Boolean) = switch(m, PaletteFlags.NO_GUESSES, !on)

    /** The Tone at [strength] (0 to 2, in steps of 5 %): 0 is off, and keeps the last strength for the switch. */
    fun toneTo(m: MutablePreferences, strength: Float) {
        val s = (Math.round(strength * 20f).coerceIn(0, 40)) / 20f
        if (s <= 0f) {
            m[SettingsKeys.MAP_TONE_ON] = false
        } else {
            m[SettingsKeys.MAP_TONE_ON] = true
            m[SettingsKeys.MAP_TONE] = s
        }
    }

    /** The Tone's switch [on]: on brings the last strength back (today's 1 where there is none). */
    fun toneSwitch(m: MutablePreferences, on: Boolean) {
        if (!on) {
            m[SettingsKeys.MAP_TONE_ON] = false
            return
        }
        m[SettingsKeys.MAP_TONE] = m[SettingsKeys.MAP_TONE]?.takeIf { it > 0f } ?: 1f
        m[SettingsKeys.MAP_TONE_ON] = true
    }

    /** What adding a code did. */
    sealed interface Added {
        data class New(val name: String) : Added
        data class Yours(val name: String) : Added
    }

    /**
     * Adds [palette] to the saved palettes as [name] (or as the next "[base] n" without one) and the map takes it; one
     * saved alike is the user's already, and nothing changes.
     */
    fun add(m: MutablePreferences, palette: Palette, name: String?, base: String): Added {
        val saved = MapLookModel.savedOf(m[SettingsKeys.MAP_PALETTES])
        val p = MapLookModel.lookPalette(palette)
        val alike = MapLookModel.savedIndex(saved, MapLookModel.look(p))
        if (alike >= 0) return Added.Yours(saved[alike].name)
        val names = saved.map { it.name }
        val kept = name?.let(MapLookModel::clean)?.takeIf { it.isNotEmpty() }?.let { MapLookModel.unique(it, names) } ?: MapLookModel.newName(base, names)
        val code = PaletteCode.write(p)
        m.put(SettingsKeys.MAP_PALETTES, MapLookModel.textOf(saved + SavedPalette(kept, code)))
        take(m, p, code)
        return Added.New(kept)
    }

    /**
     * Colour the map's Save: the map draws [palette] (the editor's, its Tone the map's), kept as [name]: in place of the
     * saved palette [replace] (its code) where that is still saved, else as a new one. The name as saved.
     */
    fun save(m: MutablePreferences, palette: Palette, name: String, replace: String?): String {
        val saved = MapLookModel.savedOf(m[SettingsKeys.MAP_PALETTES])
        val at = replace?.let { c -> saved.indexOfFirst { it.code == c } } ?: -1
        val others = saved.filterIndexed { i, _ -> i != at }.map { it.name }
        val kept = MapLookModel.clean(name).ifEmpty { MapLookModel.newName(palette.base.code, others) }.let { MapLookModel.unique(it, others) }
        val code = PaletteCode.write(MapLookModel.withTone(MapLookModel.lookPalette(palette), MapLookModel.tone(m).drawn))
        val next = if (at >= 0) saved.mapIndexed { i, s -> if (i == at) SavedPalette(kept, code) else s } else saved + SavedPalette(kept, code)
        m.put(SettingsKeys.MAP_PALETTES, MapLookModel.textOf(next))
        m.put(SettingsKeys.MAP_PALETTE, MapLookModel.keep(palette))
        m.put(SettingsKeys.MAP_LOOK_FROM, code)
        return kept
    }

    /** The saved palette at [index] is no longer kept; the map draws what it drew. */
    fun remove(m: MutablePreferences, index: Int) {
        val saved = MapLookModel.savedOf(m[SettingsKeys.MAP_PALETTES])
        if (index !in saved.indices) return
        m.put(SettingsKeys.MAP_PALETTES, MapLookModel.textOf(saved.filterIndexed { i, _ -> i != index }))
        if (m[SettingsKeys.MAP_LOOK_FROM] == saved[index].code) m.remove(SettingsKeys.MAP_LOOK_FROM)
    }

    /** The saved palette at [index] is called [name]; left as it was for a blank one or one another palette has. */
    fun rename(m: MutablePreferences, index: Int, name: String) {
        val saved = MapLookModel.savedOf(m[SettingsKeys.MAP_PALETTES])
        if (index !in saved.indices) return
        val clean = MapLookModel.clean(name)
        if (MapLookModel.nameSays(clean, saved.filterIndexed { i, _ -> i != index }.map { it.name }) != MapLookModel.NameSays.Fine) return
        m.put(SettingsKeys.MAP_PALETTES, MapLookModel.textOf(saved.mapIndexed { i, s -> if (i == index) s.copy(name = clean) else s }))
    }

    private fun current(p: Preferences): Palette = MapLookModel.current(p[SettingsKeys.MAP_PALETTE])

    private fun <T> MutablePreferences.put(key: Preferences.Key<T>, value: T?) {
        if (value == null) remove(key) else this[key] = value
    }
}

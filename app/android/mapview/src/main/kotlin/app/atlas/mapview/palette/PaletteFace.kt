package app.atlas.mapview.palette

/**
 * How a building's face takes its colour in a palette (Colour the map's taps), as the core draws it from the face's
 * slot word (the tiles' wall_slot or roof_slot, style/Palette.hpp's layout): the palette's [slot] that paints it, where
 * one does, and why it does or does not.
 */
data class PaletteFace(val slot: Slot?, val colour: How) {
    enum class How {
        /** Atlas guessed it as [slot], which paints it. */
        GUESSED,

        /** Atlas guessed it as [slot], but the palette's guesses are off: drawn plain until they are on. */
        GUESSED_PLAIN,

        /** OpenStreetMap maps its colour, which moves with [slot], its nearest, as the palette's mapped colours follow. */
        MAPPED,

        /** OpenStreetMap maps its colour and the palette keeps mapped colours as mapped: no slot paints it. */
        MAPPED_OWN,

        /** No colour of the palette goes with it: a houseboat's paint, or a building whose colour nothing tells. */
        NONE,
    }

    companion object {
        /** A slot word's bit for a colour OpenStreetMap maps (style::slot_mapped). */
        const val MAPPED = 256

        /** Its bit for a colour Atlas guessed that goes with no slot (style::slot_guessed). */
        const val GUESSED = 512

        /** Where a guessed flat top's mid grey lies (1 to 3, 0 none; style::slot_grey_shift). */
        const val GREY_SHIFT = 10

        /** The face of slot word [word] as a palette with switches [flags] (PaletteFlags) draws it. */
        fun of(word: Int, flags: Int): PaletteFace {
            val code = word and 0xFF
            return when {
                word and GUESSED != 0 || code == 0 -> PaletteFace(null, How.NONE)
                word and MAPPED != 0 -> {
                    val slot = Slot.ofCode(code)
                    if (slot != null && flags and PaletteFlags.MAPPED_FOLLOW != 0) PaletteFace(slot, How.MAPPED) else PaletteFace(null, How.MAPPED_OWN)
                }
                else -> {
                    // a guessed flat top takes its mid grey where the palette's flat greys are on
                    val grey = (word shr GREY_SHIFT) and 3
                    val drawn = if (grey != 0 && flags and PaletteFlags.FLAT_GREYS != 0) Slot.FLAT_GREY_1.code + grey - 1 else code
                    val slot = Slot.ofCode(drawn) ?: return PaletteFace(null, How.NONE)
                    PaletteFace(slot, if (flags and PaletteFlags.NO_GUESSES != 0) How.GUESSED_PLAIN else How.GUESSED)
                }
            }
        }
    }
}

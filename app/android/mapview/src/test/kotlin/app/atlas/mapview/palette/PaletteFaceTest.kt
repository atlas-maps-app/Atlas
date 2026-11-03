package app.atlas.mapview.palette

import app.atlas.mapview.palette.PaletteFace.How
import org.junit.Assert.assertEquals
import org.junit.Test

/** A tapped face's slot word as the core draws it in a palette: guessed, plain, mapped, mapped as mapped, or none. */
class PaletteFaceTest {
    private val none = 0
    private val follow = PaletteFlags.MAPPED_FOLLOW
    private val greys = PaletteFlags.FLAT_GREYS
    private val plain = PaletteFlags.NO_GUESSES

    @Test
    fun aGuessedColourIsItsSlotsAndPlainWithTheGuessesOff() {
        assertEquals(PaletteFace(Slot.RED_TILES, How.GUESSED), PaletteFace.of(80, none))
        assertEquals(PaletteFace(Slot.OLD_BRICK_2, How.GUESSED), PaletteFace.of(49, follow))
        assertEquals(PaletteFace(Slot.OLD_BRICK_2, How.GUESSED_PLAIN), PaletteFace.of(49, plain))
    }

    @Test
    fun aGuessedFlatTopTakesItsGreyWhereTheFlatGreysAreOn() {
        // bitumen with the second grey above it (style::flat_roof_slot): its own without the greys, the grey with them
        val word = 87 or (2 shl PaletteFace.GREY_SHIFT)
        assertEquals(PaletteFace(Slot.BITUMEN, How.GUESSED), PaletteFace.of(word, none))
        assertEquals(PaletteFace(Slot.FLAT_GREY_2, How.GUESSED), PaletteFace.of(word, greys))
        assertEquals(PaletteFace(Slot.FLAT_GREY_3, How.GUESSED_PLAIN), PaletteFace.of(86 or (3 shl PaletteFace.GREY_SHIFT), greys or plain))
    }

    @Test
    fun aMappedColourFollowsItsNearestOnlyWhereMappedColoursFollow() {
        val word = PaletteFace.MAPPED or 81
        assertEquals(PaletteFace(Slot.DARK_TILES_1, How.MAPPED), PaletteFace.of(word, follow))
        assertEquals(PaletteFace(Slot.DARK_TILES_1, How.MAPPED), PaletteFace.of(word, follow or plain))
        assertEquals(PaletteFace(null, How.MAPPED_OWN), PaletteFace.of(word, none))
        assertEquals(PaletteFace(null, How.MAPPED_OWN), PaletteFace.of(word, greys))
    }

    @Test
    fun noSlotForABoatsPaintOrTheClay() {
        assertEquals(PaletteFace(null, How.NONE), PaletteFace.of(PaletteFace.GUESSED, follow))
        assertEquals(PaletteFace(null, How.NONE), PaletteFace.of(0, follow))
        // a code this Atlas does not know
        assertEquals(PaletteFace(null, How.NONE), PaletteFace.of(77, none))
    }
}

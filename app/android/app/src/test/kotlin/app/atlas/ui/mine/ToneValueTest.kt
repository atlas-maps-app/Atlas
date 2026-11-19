package app.atlas.ui.mine

import app.atlas.ui.kit.BoardFonts
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/** Map look's Tone value: a strength in the mono face, the word Off in the page's, so it can't read as "0ff". */
class ToneValueTest {
    @Test
    fun offIsNotInTheMonoFace() {
        assertNotEquals(BoardFonts.of(MONO), toneValueStyle(0f).fontFamily)
        assertNotEquals(BoardFonts.of(MONO), toneValueStyle(0.001f).fontFamily)
        assertEquals(BoardFonts.of(MONO), toneValueStyle(0.5f).fontFamily)
    }
}

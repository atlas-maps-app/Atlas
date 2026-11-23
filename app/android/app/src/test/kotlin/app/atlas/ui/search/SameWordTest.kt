package app.atlas.ui.search

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** [sameWord], which keeps a line from saying one word twice ("Pizza · Pizza"): when a fact is the kind's own word. */
class SameWordTest {
    @Test
    fun equalButForCaseAndOuterSpacesIsOneWord() {
        assertTrue(sameWord("Pizza ", "pizza"))
        assertTrue(sameWord("  burger", "Burger  "))
        assertTrue(sameWord("Pizza", "Pizza"))
    }

    @Test
    fun aWordAndALongerOneAreTwo() {
        assertFalse(sameWord("Pizza", "Pizzeria"))
        assertFalse(sameWord("Pizzeria", "Pizza"))
        // a space inside a word counts: only the space round it is ignored
        assertFalse(sameWord("Fish and chips", "Fish  and chips"))
    }

    @Test
    fun aNullOnEitherSideIsNeverTheSameWord() {
        assertFalse(sameWord(null, "Pizza"))
        assertFalse(sameWord("Pizza", null))
        assertFalse(sameWord(null, null))
    }
}

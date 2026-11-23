package app.atlas.mapview

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The window shown again shows the core once the return's moment has been handed ([ShownAgain]): the core's two calls,
 * the moment's hand-over and the show, are recorded in the order the core would take them. MapController's own core
 * cannot run on the JVM (MapCore calls the native library), so the order is held here, where MapController takes it
 * from.
 */
class ShownAgainTest {
    private val calls = mutableListOf<String>()
    private val shownAgain = ShownAgain { calls += "show" }
    private var back: (() -> Unit)? = null

    /** A hand-over that starts: the core's moment, and its return kept for the test to run. */
    private fun handing(b: () -> Unit): Boolean {
        calls += "moment"
        back = b
        return true
    }

    @Test
    fun aReturnAfterTheMomentsStepChangedHandsTheMomentBeforeTheShow() {
        shownAgain.returned(::handing)
        assertEquals("the core was shown before the return's moment came back", listOf("moment"), calls)
        back!!() // the hand-over came back, taken or refused: shown all the same
        assertEquals(listOf("moment", "show"), calls)
        back!!() // nothing twice
        assertEquals(listOf("moment", "show"), calls)
    }

    @Test
    fun aReturnWithNoChangeShowsAtOnce() {
        shownAgain.returned { false }
        assertEquals(listOf("show"), calls)
    }

    @Test
    fun aHandOverThatComesBackAtOnceShowsOnce() {
        shownAgain.returned { b ->
            calls += "moment"
            b()
            true
        }
        assertEquals(listOf("moment", "show"), calls)
    }

    @Test
    fun hiddenAgainBeforeTheMomentCameBackShowsNothing() {
        shownAgain.returned(::handing)
        val first = back!!
        shownAgain.hidden()
        first()
        assertEquals(listOf("moment"), calls)
        // the next return, with nothing due, shows at once; the first one's late return adds nothing
        shownAgain.returned { false }
        first()
        assertEquals(listOf("moment", "show"), calls)
    }
}

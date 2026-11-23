package app.atlas.mapcore

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Who hides the process's one core ([CoreHiding]): it is hidden while it has at least one map and none of them is
 * shown, and the core is told only when that answer changes. The maps are the activities' controllers; an old one's
 * late stop and destroy (Back at the root and a quick return, where Back still ends the activity: Android 10 and 11)
 * come after the new one joined and shows. The core's calls are recorded in place of the native one.
 */
class CoreHidingTest {
    private val told = mutableListOf<Boolean>()
    private val hiding = CoreHiding { told += it }

    @Test
    fun anOldMapsStopAndLeaveAfterANewOneShowsTellTheCoreNothing() {
        val old = Any()
        val new = Any()
        hiding.join(old, shown = true)
        // Back at the root ends the old activity's window, the launcher brings a new one at once: the new controller
        // takes the core and shows; then the old one's deferred stop and destroy.
        hiding.join(new, shown = true)
        hiding.show(old, shown = false)
        hiding.leave(old)
        assertEquals("the core was hidden or shown under the new map's window", emptyList<Boolean>(), told)
        assertFalse(hiding.hidden)
    }

    @Test
    fun oneMapsStopHidesTheCoreAndItsStartShowsIt() {
        val map = Any()
        hiding.join(map, shown = true)
        hiding.show(map, shown = false)
        hiding.show(map, shown = false) // a second ON_STOP: nothing more
        assertTrue(hiding.hidden)
        hiding.show(map, shown = true)
        assertEquals(listOf(true, false), told)
        assertFalse(hiding.hidden)
    }

    @Test
    fun aMapThatJoinsWhileItsWindowIsHiddenHidesTheCore() {
        hiding.join(Any(), shown = false)
        assertEquals(listOf(true), told)
        assertTrue(hiding.hidden)
    }

    @Test
    fun theLastMapLeavingWhileHiddenShowsTheCore() {
        val map = Any()
        hiding.join(map, shown = true)
        hiding.show(map, shown = false)
        hiding.leave(map)
        assertEquals(listOf(true, false), told)
        assertFalse(hiding.hidden)
        // and a core with no map at all (VK facts, VK bench) is never hidden
        hiding.leave(map)
        assertEquals(listOf(true, false), told)
    }

    @Test
    fun aMapThatHasNotJoinedSaysNothing() {
        hiding.show(Any(), shown = false)
        assertEquals(emptyList<Boolean>(), told)
        assertFalse(hiding.hidden)
    }
}

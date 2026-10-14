package app.atlas.nav

import androidx.compose.runtime.saveable.SaverScope
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NavigatorTest {
    @Test
    fun goBackAndReplace() {
        val nav = BackStackNavigator()
        assertEquals(Dest.Home(), nav.current)
        assertFalse(nav.back())
        nav.go(Dest.Search("x"))
        nav.go(Dest.Search("x"))
        assertEquals(2, nav.stack.size)
        nav.replace(Dest.Category("eat"))
        assertEquals(Dest.Category("eat"), nav.current)
        assertTrue(nav.back())
        assertEquals(Dest.Home(), nav.current)
    }

    @Test
    fun tabsSitOnTheMap() {
        val nav = BackStackNavigator()
        nav.go(Dest.Search())
        nav.tab(Tab.MINE)
        assertEquals(listOf(Dest.Home(), Dest.Mine), nav.stack.toList())
        nav.go(Dest.Settings())
        nav.tab(Tab.GO)
        assertEquals(listOf(Dest.Home(), Dest.Go), nav.stack.toList())
        assertTrue(nav.back())
        assertEquals(Dest.Home(), nav.current)
        nav.tab(Tab.MAP)
        assertEquals(listOf(Dest.Home()), nav.stack.toList())
    }

    @Test
    fun resetPutsTheMapUnderneath() {
        val nav = BackStackNavigator()
        nav.reset(listOf(Dest.Place(52.0, 4.0)))
        assertEquals(listOf(Dest.Home(), Dest.Place(52.0, 4.0)), nav.stack.toList())
        nav.reset(emptyList())
        assertEquals(listOf(Dest.Home()), nav.stack.toList())
    }

    @Test
    fun theStackIsSavedAsRoutes() {
        val nav = BackStackNavigator(listOf(Dest.Home(52.1, 4.2, 17.0), Dest.Brand("albertheijn-1cf55d"), Dest.Settings("units")))
        val saved = with(BackStackNavigator.Saver) { SaverScope { true }.save(nav) }!!
        val back = BackStackNavigator.Saver.restore(saved)!!
        assertEquals(nav.stack.toList(), back.stack.toList())
        // A route an older build wrote that this one does not know is dropped, not a crash.
        val odd = BackStackNavigator.Saver.restore(arrayListOf("home", "retired?x=1", "mine"))!!
        assertEquals(listOf(Dest.Home(), Dest.Mine), odd.stack.toList())
    }
}

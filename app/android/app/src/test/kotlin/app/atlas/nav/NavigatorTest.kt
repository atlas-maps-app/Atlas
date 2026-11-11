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
    fun aLinkLandsOnTopOfWhereTheUserWas() {
        val nav = BackStackNavigator()
        nav.tab(Tab.MINE)
        nav.go(Dest.Settings())
        val was = nav.stack.toList()
        // the link's screen over the screens open, its Map home not pushed: back returns to where the user was
        val point = listOf(Dest.Home(52.37, 4.88, 17.0), Dest.WhatsHere(52.37, 4.88, go = true))
        nav.land(point)
        nav.land(point)
        assertEquals(was + Dest.WhatsHere(52.37, 4.88, go = true), nav.stack.toList())
        assertTrue(nav.back())
        assertEquals(was, nav.stack.toList())
        assertFalse(nav.pins(Dest.Home(52.37, 4.88, 17.0)))
        // a map view is the Map home at its view, over the screens open, and it pins the view's point
        val view = Dest.Home(52.3745, 4.8838, 17.0)
        nav.land(listOf(view))
        assertEquals(was + view, nav.stack.toList())
        assertTrue(nav.pins(view))
        assertFalse(nav.pins(Dest.Home()))
        assertTrue(nav.back())
        assertEquals(was, nav.stack.toList())
        // opened from cold: a map view pins its point, the map under a link's screen pins nothing
        assertTrue(BackStackNavigator(listOf(view)).pins(view))
        assertFalse(BackStackNavigator(point).pins(Dest.Home(52.37, 4.88, 17.0)))
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

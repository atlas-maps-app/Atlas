package app.atlas.nav

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DestTest {
    @Test
    fun everyCiScreenRoundTripsThroughItsRoute() {
        for ((name, dest) in CiScreens.screens) {
            val back = Dest.parse(dest.route)
            assertEquals("route of $name: ${dest.route}", dest, back)
        }
    }

    @Test
    fun everyRouteNameHasAParserAndEveryCiNameIsADestination() {
        val routeNames = CiScreens.screens.values.map { Routes.split(it.route).first }.toSet()
        assertTrue(Dest.names.containsAll(routeNames))
        assertTrue("CI names: ${CiScreens.list()}", CiScreens.list().split(' ').toSet() == CiScreens.screens.keys)
    }

    @Test
    fun argumentsWithSpecialCharactersSurvive() {
        val d = Dest.Search("café & bar = 100% \"open\" ☕", category = "eat")
        assertEquals(d, Dest.parse(d.route))
        val p = Dest.Place(52.374523, 4.883767, "w99205257", "Westerkerk?&", key = "osm:w99205257")
        assertEquals(p, Dest.parse(p.route))
        assertEquals(Dest.Navigate(52.0, 4.0, "Rotterdam", "CAR", demo = true), Dest.parse(Dest.Navigate(52.0, 4.0, "Rotterdam", "CAR", demo = true).route))
    }

    @Test
    fun fillInRoundTripsAndNeedsItsBuilding() {
        val d = Dest.FillIn("way", 266616341, 52.3756, 4.8845)
        assertEquals(d, Dest.parse(d.route))
        assertNull(Dest.parse("fillin?type=way&lat=52.0&lon=4.0"))
    }

    @Test
    fun aPlaceNeedsAPointOrAnId() {
        assertNull(Dest.parse("place?name=Nowhere"))
        assertEquals(Dest.Place(osmId = "n123"), Dest.parse("place?id=n123"))
        assertNull(Dest.Place(osmId = "n123").at)
    }

    @Test
    fun unknownRoutesAreNull() {
        assertNull(Dest.parse("nowhere"))
        assertNull(Dest.parse("brand"))
        assertNull(Dest.parse(""))
    }

    @Test
    fun tabRootsAreTheFive() {
        assertEquals(Tab.entries.toSet(), Tab.entries.filter { it.root.isTabRoot }.toSet())
        assertTrue(!Dest.Local("guide").isTabRoot)
    }

    @Test
    fun aMalformedRouteIsNullNotACrash() {
        // CI and deep links hand routes in by hand: a stray % must not take the app down.
        assertNull(Dest.parse("search?q=100%"))
        assertNull(CiScreens.stackFor("search?q=%zz"))
    }

    @Test
    fun aRestoredStackStillEndsOnTheMap() {
        // A saved stack whose map entry an older build wrote differently still has the map underneath.
        val nav = BackStackNavigator.Saver.restore(arrayListOf("retired?x=1", "mine", "settings"))!!
        assertEquals(listOf(Dest.Home(), Dest.Mine, Dest.Settings()), nav.stack.toList())
    }
}

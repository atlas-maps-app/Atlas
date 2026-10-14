package app.atlas.nav

import app.atlas.geo.CoordinateFormat
import app.atlas.geo.LatLon
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LinksTest {
    @Test
    fun geoPointOpensTheMapThereWithWhatIsHere() {
        val s = Links.forUri("geo:52.37452,4.88377?z=18")!!
        assertEquals(Dest.Home(52.37452, 4.88377, 18.0), s[0])
        assertEquals(Dest.WhatsHere(52.37452, 4.88377), s[1])
    }

    @Test
    fun geoLabelledPointOpensThePlace() {
        val s = Links.forUri("geo:0,0?q=52.37452,4.88377(Westerkerk)")!!
        assertEquals(Dest.Place(52.37452, 4.88377, name = "Westerkerk"), s.last())
    }

    @Test
    fun geoQuerySearches() {
        assertEquals(Dest.Search("coffee shop"), Links.forUri("geo:0,0?q=coffee+shop")!!.last())
        assertEquals(Dest.Search("Prinsengracht 263"), Links.forUri("geo:0,0?q=Prinsengracht%20263")!!.last())
    }

    @Test
    fun osmElementLinksOpenThePlace() {
        assertEquals(Dest.Place(osmId = "w99205257"), Links.forUri("https://www.openstreetmap.org/way/99205257")!!.last())
        val withView = Links.forUri("https://www.openstreetmap.org/node/240316872#map=19/52.37000/4.87000")!!
        assertEquals(Dest.Place(52.37, 4.87, "n240316872"), withView.last())
        assertEquals(Dest.Home(52.37, 4.87, 19.0), withView.first())
    }

    @Test
    fun osmMapViewsMoveTheMapAndMarkersShowThePoint() {
        assertEquals(listOf<Dest>(Dest.Home(52.3745, 4.8838, 17.0)), Links.forUri("https://www.openstreetmap.org/#map=17/52.3745/4.8838"))
        val marker = Links.forUri("https://www.openstreetmap.org/?mlat=52.3745&mlon=4.8838#map=18/52.3745/4.8838")!!
        assertEquals(Dest.WhatsHere(52.3745, 4.8838), marker.last())
    }

    @Test
    fun sharedTextWithCoordinatesOrAPlusCode() {
        assertEquals(Dest.WhatsHere(52.3745, 4.8838), Links.forText("52.3745, 4.8838")!!.last())
        assertEquals(Dest.WhatsHere(52.3745, 4.8838), Links.forText("Look: https://www.openstreetmap.org/?mlat=52.3745&mlon=4.8838")!!.last())
        val code = CoordinateFormat.plusCode(LatLon(52.37452, 4.88377))
        val s = Links.forText(code)!!
        val p = (s.last() as Dest.WhatsHere).at
        assertTrue("$code → $p", app.atlas.geo.Geo.distance(p, LatLon(52.37452, 4.88377)) < 20)
    }

    @Test
    fun otherSharedTextIsASearch() {
        assertEquals(Dest.Search("Albert Heijn Westermarkt"), Links.forText("Albert Heijn Westermarkt")!!.last())
        assertNull(Links.forText("   "))
    }

    @Test
    fun geoPointWithASearchSearchesThere() {
        // RFC 5870 / Android: geo:lat,lon?q=words is a search for the words near that point.
        val s = Links.forUri("geo:52.37452,4.88377?q=apotheek")!!
        assertEquals(Dest.Home(52.37452, 4.88377, 17.0), s.first())
        assertEquals(Dest.Search("apotheek"), s.last())
        // The same point repeated in q, with no label, is just the point.
        assertEquals(Dest.WhatsHere(52.37452, 4.88377), Links.forUri("geo:52.37452,4.88377?q=52.37452,4.88377")!!.last())
    }

    @Test
    fun shortOsmDomainElementLinks() {
        assertEquals(Dest.Place(osmId = "n240316872"), Links.forUri("https://osm.org/node/240316872")!!.last())
    }
}

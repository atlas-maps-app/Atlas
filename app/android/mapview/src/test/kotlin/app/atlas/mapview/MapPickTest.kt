package app.atlas.mapview

import app.atlas.geo.LatLon
import app.atlas.model.OsmId
import app.atlas.model.OsmType
import app.atlas.model.Place
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The core's line of a pick (`MapCore.pick`) as the map hands it to the screens: a way's roof, a relation's part's
 * wall, or none.
 */
class MapPickTest {
    @Test
    fun theCoresLineReadsBack() {
        assertEquals(
            MapPick(OsmId(OsmType.WAY, 266556292), 0, LatLon(52.3745123, 4.8838456), roof = true, slot = 80, rgb = 0xAE573F),
            MapPick.parse("way 266556292 0 52.3745123 4.8838456 roof 80 11425599"),
        )
        assertEquals(
            MapPick(OsmId(OsmType.RELATION, 5), 77, LatLon(-33.9, 151.2), roof = false, slot = 256 + 49, rgb = 0x7A4434),
            MapPick.parse("relation 5 77 -33.9000000 151.2000000 wall 305 8012852"),
        )
    }

    @Test
    fun aTapOnABuildingOpensItsPlaceOrIsAnEmptyTapOnTheStreet() {
        val picked = MapPick(OsmId(OsmType.WAY, 99205257), 0, LatLon(52.3745, 4.8838))
        val street = LatLon(52.3744, 4.8837)
        val church = Place(picked.id, LatLon(52.37455, 4.88385), mapOf("name" to "Westerkerk", "amenity" to "place_of_worship"))
        // the place the building holds, at the point the tap met the building
        assertEquals(MapHit.OnFeature(church, picked.at), featureHit(church, picked, street))
        // a house: an empty tap, on the street under the finger, or where the ray meets none on the building itself
        assertEquals(MapHit.Empty(street), featureHit(null, picked, street))
        assertEquals(MapHit.Empty(picked.at), featureHit(null, picked, null))
    }

    @Test
    fun noneWhereTheCoreMetNone() {
        assertNull(MapPick.parse(""))
        assertNull(MapPick.parse("way 1 0 52.3"))
        assertNull(MapPick.parse("way x 0 52.3 4.8 roof 80 0"))
        assertNull(MapPick.parse("way 1 0 52.3 4.8"))
        assertNull(MapPick.parse("way 1 0 52.3 4.8 lid 80 0"))
        assertNull(MapPick.parse("way 1 0 52.3 4.8 roof x 0"))
    }
}

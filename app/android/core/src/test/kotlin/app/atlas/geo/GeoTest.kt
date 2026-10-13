package app.atlas.geo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GeoTest {
    private val westerkerk = LatLon(52.37452, 4.88377)
    private val dam = LatLon(52.37310, 4.89320)

    @Test fun distanceAndBearing() {
        assertEquals(660.0, Geo.distance(westerkerk, dam), 10.0)
        assertEquals(104.0, Geo.bearing(westerkerk, dam), 3.0)
        val back = Geo.destination(westerkerk, Geo.bearing(westerkerk, dam), Geo.distance(westerkerk, dam))
        assertEquals(0.0, Geo.distance(back, dam), 0.5)
    }

    @Test fun mercatorRoundTripAndTiles() {
        assertEquals(westerkerk.lat, Mercator.lat(Mercator.y(westerkerk.lat)), 1e-9)
        assertEquals(TileId(14, 8414, 5384), Mercator.tileAt(westerkerk, 14))
        assertTrue(westerkerk in TileId(14, 8414, 5384).bbox)
    }

    @Test fun snapAlongAPolyline() {
        val line = Polyline(listOf(LatLon(52.0, 4.0), LatLon(52.0, 4.01), LatLon(52.01, 4.01)))
        val s = line.snap(LatLon(52.0001, 4.005))!!
        assertEquals(0, s.segment)
        assertEquals(11.1, s.offset, 0.5)
        assertEquals(-1, s.side) // north of an eastward line is on its left
        assertEquals(342.0, s.along, 2.0)
        assertEquals(line.length, line.cumulative.last(), 0.0)
        assertEquals(0.0, Geo.distance(line.pointAt(line.length), LatLon(52.01, 4.01)), 0.01)
    }
}

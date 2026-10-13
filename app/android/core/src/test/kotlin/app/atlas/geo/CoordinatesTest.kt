package app.atlas.geo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class CoordinatesTest {
    private val amsterdam = LatLon(52.37, 4.89)
    private val eiffel = LatLon(48.8582, 2.2945)

    private fun assertAt(lat: Double, lon: Double, text: String, near: LatLon? = null, tolerance: Double = 1e-6) {
        val p = CoordinateParser.parse(text, near)
        assertNotNull("could not parse '$text'", p)
        assertEquals("lat of '$text'", lat, p!!.lat, tolerance)
        assertEquals("lon of '$text'", lon, p.lon, tolerance)
    }

    @Test fun decimalPairs() {
        assertAt(52.3745, 4.8838, "52.3745, 4.8838")
        assertAt(52.3745, 4.8838, "52.3745 4.8838")
        assertAt(52.3745, 4.8838, " 52.3745;4.8838 ")
        assertAt(-33.8688, 151.2093, "-33.8688 151.2093")
        assertAt(52.3745, 4.8838, "52,3745 4,8838")
        assertAt(52.3745, 4.8838, "52,3745; 4,8838")
        assertAt(52.0, 4.0, "52,4")
    }

    @Test fun eitherOrderWhenOnlyOneFits() {
        assertAt(-33.8688, 151.2093, "151.2093, -33.8688")
        // Both orders fit: latitude first, unless a nearby reference says otherwise.
        assertAt(4.8838, 52.3745, "4.8838, 52.3745")
        assertAt(52.3745, 4.8838, "4.8838, 52.3745", near = amsterdam)
        assertAt(40.7, -74.0, "40.7, -74.0", near = amsterdam)
    }

    @Test fun hemispheres() {
        assertAt(52.37, 4.88, "52.37N 4.88E")
        assertAt(52.37, 4.88, "N52.37 E4.88")
        assertAt(52.37, 4.88, "52.37° N, 4.88° E")
        assertAt(52.37, 4.88, "4.88E 52.37N")
        assertAt(-33.8688, 151.2093, "S 33.8688 E 151.2093")
        assertAt(52.37, -4.88, "52.37 north 4.88 west")
    }

    @Test fun degreesMinutesSeconds() {
        val lat = 52 + 22 / 60.0 + 28.3 / 3600
        val lon = 4 + 53 / 60.0 + 1.6 / 3600
        assertAt(lat, lon, "52°22'28.3\"N 4°53'01.6\"E")
        assertAt(lat, lon, "52°22′28.3″N 4°53′01.6″E")
        assertAt(lat, lon, "52º 22’ 28.3” N, 4º 53’ 1.6” E")
        assertAt(lat, lon, "52d22m28.3s N 4d53m1.6s E")
        assertAt(lat, lon, "52 22 28.3 N 4 53 1.6 E")
        assertAt(lat, lon, "52:22:28.3N 4:53:1.6E")
        assertAt(lat, lon, "52 22 28.3 4 53 1.6")
        assertAt(lat, lon, "52°22'28.3'' N 4°53'1.6'' E")
        assertAt(40 + 41 / 60.0 + 21 / 3600.0, -(74 + 2 / 60.0 + 40 / 3600.0), "40°41'21\"N 74°02'40\"W")
        assertAt(-(33 + 52 / 60.0 + 10 / 3600.0), 151 + 12 / 60.0 + 30 / 3600.0, "33 52 10 S 151 12 30 E")
    }

    @Test fun degreesAndDecimalMinutes() {
        assertAt(52 + 22.471 / 60, 4 + 53.027 / 60, "N 52° 22.471' E 4° 53.027'")
        assertAt(52 + 22.471 / 60, 4 + 53.027 / 60, "52 22.471 4 53.027")
        assertAt(52 + 22.471 / 60, 4 + 53.027 / 60, "52°22,471'N 4°53,027'E")
        assertAt(-(33 + 52 / 60.0), 151.2, "33°52'S 151°12'E")
    }

    @Test fun geoUris() {
        assertAt(52.3745, 4.8838, "geo:52.3745,4.8838")
        assertAt(52.3745, 4.8838, "geo:52.3745,4.8838,12;u=35")
        assertAt(52.3745, 4.8838, "geo:52.3745,4.8838?z=17")
        assertAt(52.3745, 4.8838, "geo:0,0?q=52.3745,4.8838(Westerkerk)")
        assertAt(52.3745, 4.8838, "GEO:52.3745,4.8838")
        assertNull(CoordinateParser.parse("geo:0,0?q=Westerkerk+Amsterdam"))
    }

    @Test fun mapLinks() {
        assertAt(52.37452, 4.88377, "https://www.openstreetmap.org/#map=17/52.37452/4.88377")
        assertAt(52.3745, 4.8838, "https://www.openstreetmap.org/?mlat=52.3745&mlon=4.8838#map=17/52.0/4.0")
        assertAt(52.3745, 4.8838, "https://example.org/maps/place/x/@52.3745,4.8838,17z/data")
        assertAt(52.3745, 4.8838, "https://example.org/?lat=52.3745&lon=4.8838")
        assertAt(52.3745, 4.8838, "https://example.org/map?ll=52.3745%2C4.8838&z=12")
        assertAt(52.3745, 4.8838, "https://openfreemap.example/#16.5/52.3745/4.8838")
        val link = "https://osm.org/go/" + OsmShortLink.encode(Fixtures.westerkerk, 17)
        val p = CoordinateParser.parse(link)!!
        assertTrue(Geo.distance(p, Fixtures.westerkerk) < 10)
        assertNull(CoordinateParser.parse("https://www.openstreetmap.org/way/99205257"))
    }

    @Test fun osmShortLinksRoundTrip() {
        val rnd = Random(5)
        repeat(50) {
            val p = LatLon(rnd.nextDouble(-80.0, 80.0), rnd.nextDouble(-179.0, 179.0))
            for (zoom in listOf(10, 14, 18)) {
                val back = OsmShortLink.decode(OsmShortLink.encode(p, zoom))!!
                val cell = 360.0 / (1L shl (zoom + 8)) * 4
                assertEquals(p.lat, back.lat, cell)
                assertEquals(p.lon, back.lon, cell)
            }
        }
    }

    @Test fun plusCodes() {
        val full = PlusCode.encode(Fixtures.westerkerk)
        val p = CoordinateParser.parse(full)!!
        assertTrue(Geo.distance(p, Fixtures.westerkerk) < 10)
        assertTrue(Geo.distance(CoordinateParser.parse("9VFM+RG Amsterdam", amsterdam)!!, Fixtures.westerkerk) < 10)
        assertTrue(Geo.distance(CoordinateParser.parse("fm+rg", amsterdam)!!, Fixtures.westerkerk) < 10)
        assertNull(CoordinateParser.parse("FM+RG"))
    }

    @Test fun utmAndMgrs() {
        val p = CoordinateParser.parse("31U 448252 5411933")!!
        assertTrue(Geo.distance(p, eiffel) < 1.0)
        assertTrue(Geo.distance(CoordinateParser.parse("31N 448252 5411933")!!, eiffel) < 1.0)
        assertTrue(Geo.distance(CoordinateParser.parse("31U 448252mE 5411933mN")!!, eiffel) < 1.0)
        assertTrue(Geo.distance(CoordinateParser.parse("31U DQ 48251 11932")!!, eiffel) < 1.5)
        assertTrue(Geo.distance(CoordinateParser.parse("31UDQ4825111932")!!, eiffel) < 1.5)
        assertTrue(Geo.distance(CoordinateParser.parse("31U DQ 482 119")!!, eiffel) < 150)
        val brief = CoordinateParser.parse("31U 628472 5803466")!!
        assertTrue(Geo.distance(brief, Fixtures.westerkerk) < 2000)
        // "S" is both a band and the southern hemisphere: Sydney is in 56H, written here as 56S.
        val sydney = LatLon(-33.8688, 151.2093)
        val u = Utm.fromLatLon(sydney)!!
        val text = "56S ${u.easting.toLong()} ${u.northing.toLong()}"
        assertTrue(Geo.distance(CoordinateParser.parse(text)!!, sydney) < 2)
    }

    @Test fun labelledPairs() {
        assertAt(52.3745, 4.8838, "lat: 52.3745, lon: 4.8838")
        assertAt(52.3745, 4.8838, "Latitude 52.3745 Longitude 4.8838")
        assertAt(52.3745, 4.8838, "lng=4.8838&lat=52.3745")
    }

    @Test fun rejectsWhatIsNotACoordinate() {
        for (text in listOf("", "Cafe Amsterdam", "12 34", "52.37", "91, 200", "abc 52.3 4.8", "52°61' N 4° E",
            "N52 N4", "52.3.4 4.8", "1 2 3", "Prinsengracht 279", "52.37N 4.88N")) {
            assertNull("'$text' should not parse", CoordinateParser.parse(text))
        }
    }

    @Test fun formatsEveryStyle() {
        val p = Fixtures.westerkerk
        assertEquals("52.37452, 4.88377", CoordinateFormat.decimal(p))
        assertEquals("52°22′28.3″N 4°53′01.6″E", CoordinateFormat.dms(p))
        assertEquals("52°22.471′N 4°53.026′E", CoordinateFormat.dm(p))
        assertEquals("31U 448252 5411933", CoordinateFormat.utm(eiffel))
        assertEquals("31U DQ 48251 11932", CoordinateFormat.mgrs(eiffel))
        assertEquals("9F469VFM+RG", CoordinateFormat.plusCode(p))
        assertEquals("FM+RG", CoordinateFormat.plusCodeNear(p, amsterdam))
        assertEquals("geo:52.374520,4.883770", CoordinateFormat.geoUri(p))
        // Rounding carries into the next unit, and the southern and western hemispheres show.
        assertEquals("11°00′00.0″N 0°00′00.0″E", CoordinateFormat.dms(LatLon(10.999999, 0.0)))
        assertEquals("33°52′07.7″S 151°12′33.5″E", CoordinateFormat.dms(LatLon(-33.8688, 151.2093)))
        assertNull(CoordinateFormat.utm(LatLon(85.0, 0.0)))
        assertEquals(CoordinateFormat.decimal(LatLon(85.0, 0.0)), CoordinateFormat.format(LatLon(85.0, 0.0), CoordinateFormat.Style.UTM))
    }

    @Test fun everyFormatParsesBack() {
        val rnd = Random(11)
        repeat(300) {
            val p = LatLon(rnd.nextDouble(-79.0, 83.0), rnd.nextDouble(-179.9, 179.9))
            for (style in CoordinateFormat.Style.entries) {
                val text = CoordinateFormat.format(p, style)
                val back = CoordinateParser.parse(text, p)
                assertNotNull("$style '$text'", back)
                val tolerance = when (style) {
                    CoordinateFormat.Style.DECIMAL -> 1.0
                    CoordinateFormat.Style.DMS -> 3.5
                    CoordinateFormat.Style.DM -> 2.0
                    CoordinateFormat.Style.UTM -> 1.0
                    CoordinateFormat.Style.MGRS -> 1.5
                    CoordinateFormat.Style.PLUS_CODE -> 15.0
                }
                assertTrue("$style '$text' -> $back", Geo.distance(p, back!!) < tolerance)
            }
        }
    }

    @Test fun utmKnownValuesAndZones() {
        val origin = Utm.fromLatLon(LatLon(0.0, 0.0))!!
        assertEquals(31, origin.zone)
        assertEquals(166021.443, origin.easting, 0.01)
        assertEquals(0.0, origin.northing, 1e-6)
        val cm = Utm.fromLatLon(LatLon(0.0, 3.0))!!
        assertEquals(500000.0, cm.easting, 1e-6)
        // Northing on the central meridian is k0 times the meridian arc: 4 984 944.378 m at 45°.
        assertEquals(0.9996 * 4984944.378, Utm.fromLatLon(LatLon(45.0, 3.0))!!.northing, 0.01)
        assertEquals(32, Utm.zone(60.0, 5.0)) // Norway's wide zone
        assertEquals(31, Utm.zone(60.0, 2.0))
        assertEquals(33, Utm.zone(78.0, 15.0)) // Svalbard
        assertEquals(31, Utm.zone(78.0, 8.0))
        assertEquals(37, Utm.zone(78.0, 35.0))
        assertEquals('U', Utm.band(52.37))
        assertEquals('X', Utm.band(84.0))
        assertNull(Utm.band(-80.5))
        val rnd = Random(2)
        repeat(500) {
            val p = LatLon(rnd.nextDouble(-80.0, 84.0), rnd.nextDouble(-180.0, 180.0))
            val back = Utm.toLatLon(Utm.fromLatLon(p)!!)
            assertEquals(p.lat, back.lat, 1e-9)
            assertEquals(p.lon, back.lon, 1e-9)
        }
    }
}

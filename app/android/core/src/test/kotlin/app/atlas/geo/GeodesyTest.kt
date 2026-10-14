package app.atlas.geo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

class GeodesyTest {
    @Test fun midpointAndIntermediatePoints() {
        val m = Geo.midpoint(LatLon(0.0, 0.0), LatLon(0.0, 90.0))
        assertEquals(0.0, m.lat, 1e-12)
        assertEquals(45.0, m.lon, 1e-12)
        // A great circle bows towards the pole.
        assertTrue(Geo.midpoint(LatLon(52.0, 4.0), LatLon(52.0, 14.0)).lat > 52.0)
        val a = Fixtures.westerkerk
        val b = LatLon(48.8582, 2.2945)
        val q = Geo.intermediate(a, b, 0.25)
        assertEquals(Geo.distance(a, b) * 0.25, Geo.distance(a, q), 0.01)
        assertEquals(a, Geo.intermediate(a, a, 0.5))
    }

    @Test fun crossTrackAndAlongTrack() {
        val a = LatLon(0.0, 0.0)
        val b = LatLon(0.0, 10.0)
        val degree = Geo.EARTH_RADIUS * Math.PI / 180
        // North of an eastward path is on its left: negative, as Snap's side.
        assertEquals(-degree, Geo.crossTrack(LatLon(1.0, 5.0), a, b), 1.0)
        assertEquals(degree, Geo.crossTrack(LatLon(-1.0, 5.0), a, b), 1.0)
        assertEquals(5 * degree, Geo.alongTrack(LatLon(1.0, 5.0), a, b), 50.0)
        assertTrue(Geo.alongTrack(LatLon(1.0, -2.0), a, b) < 0)
        val line = Polyline(listOf(LatLon(52.0, 4.0), LatLon(52.0, 4.01)))
        val p = LatLon(52.0001, 4.005)
        assertEquals(-line.snap(p)!!.offset, Geo.crossTrack(p, line.points[0], line.points[1]), 0.05)
        assertEquals(line.snap(p)!!.along, Geo.alongTrack(p, line.points[0], line.points[1]), 0.5)
    }

    @Test fun bearingHelpers() {
        assertEquals(20.0, Geo.bearingDelta(350.0, 10.0), 1e-12)
        assertEquals(180.0, Geo.bearingDelta(0.0, 180.0), 1e-12)
        assertEquals(30.0, Geo.turnAngle(720.0, 30.0), 1e-12)
        assertEquals(-20.0, Geo.turnAngle(10.0, -370.0), 1e-12)
        assertTrue(Geo.isBearingWithin(355.0, 5.0, 15.0))
        assertFalse(Geo.isBearingWithin(30.0, 5.0, 15.0))
        assertEquals(0.0, Geo.meanBearing(doubleArrayOf(350.0, 10.0)), 1e-9)
        assertEquals(90.0, Geo.meanBearing(doubleArrayOf(45.0, 90.0, 135.0)), 1e-9)
        assertTrue(Geo.meanBearing(doubleArrayOf(90.0, 270.0)).isNaN())
        assertEquals(270.0, Geo.reverseBearing(90.0), 0.0)
        assertEquals(Compass.N, Compass.of(350.0))
        assertEquals(Compass.NNE, Compass.of(22.0))
        assertEquals(Compass.E, Compass.of(100.0, 8))
        assertEquals(Compass.E, Compass.of(130.0, 4))
        assertEquals(Compass.N, Compass.of(-20.0, 4))
        assertEquals(Compass.NW, Compass.of(Geo.bearing(Fixtures.westerkerk, LatLon(52.38, 4.87)), 8))
    }

    @Test fun distancesForDisplayAndSpeech() {
        assertEquals("120 m", Quantity.distance(120.0, UnitSystem.METRIC).toString())
        assertEquals("1.2 km", Quantity.distance(1234.0, UnitSystem.METRIC).toString())
        assertEquals("0.3 mi", Quantity.distance(482.8, UnitSystem.IMPERIAL).toString())
        assertEquals("2.1 nm", Quantity.distance(3889.0, UnitSystem.NAUTICAL).toString())
        assertEquals("7 m", Quantity.distance(7.2, UnitSystem.METRIC).toString())
        assertEquals("45 m", Quantity.distance(47.0, UnitSystem.METRIC).toString())
        assertEquals("1.0 km", Quantity.distance(999.0, UnitSystem.METRIC).toString())
        assertEquals("10 km", Quantity.distance(9960.0, UnitSystem.METRIC).toString())
        assertEquals("123 km", Quantity.distance(123456.0, UnitSystem.METRIC).toString())
        assertEquals("330 ft", Quantity.distance(100.0, UnitSystem.IMPERIAL).toString())
        assertEquals("110 yd", Quantity.distance(100.0, UnitSystem.IMPERIAL_UK).toString())
        assertEquals("150 m", Quantity.distance(150.0, UnitSystem.NAUTICAL).toString())
        // Spoken: coarser steps, quarter miles, half kilometres.
        assertEquals("350 m", Quantity.distance(340.0, UnitSystem.METRIC, forSpeech = true).toString())
        assertEquals("1.5 km", Quantity.distance(1300.0, UnitSystem.METRIC, forSpeech = true).toString())
        assertEquals("2 km", Quantity.distance(2100.0, UnitSystem.METRIC, forSpeech = true).toString())
        assertEquals("0.25 mi", Quantity.distance(434.0, UnitSystem.IMPERIAL, forSpeech = true).toString())
        assertEquals(1234.0, Quantity.distance(1234.0, UnitSystem.METRIC).baseValue, 50.0)
    }

    @Test fun localeAwareNumbers() {
        assertEquals("1,2 km", Quantity.distance(1234.0, UnitSystem.METRIC).format(Locale.forLanguageTag("nl-NL")))
        assertEquals("1.2 km", Quantity.distance(1234.0, UnitSystem.METRIC).format(Locale.UK))
        assertEquals("1.234 km", Quantity.distance(1_234_000.0, UnitSystem.METRIC).format(Locale.GERMANY))
        assertEquals(UnitSystem.IMPERIAL, UnitSystem.forLocale(Locale.US))
        assertEquals(UnitSystem.IMPERIAL_UK, UnitSystem.forLocale(Locale.UK))
        assertEquals(UnitSystem.METRIC, UnitSystem.forLocale(Locale.forLanguageTag("nl-NL")))
    }

    @Test fun speedsHeightsAndDurations() {
        assertEquals("50 km/h", Quantity.speed(13.9, UnitSystem.METRIC).toString())
        assertEquals("4.5 km/h", Quantity.speed(1.25, UnitSystem.METRIC).toString())
        assertEquals("5.8 kn", Quantity.speed(3.0, UnitSystem.NAUTICAL).toString())
        assertEquals("60 mph", Quantity.speed(26.8224, UnitSystem.IMPERIAL).toString())
        assertEquals("3.8 m", Quantity.height(3.8, UnitSystem.METRIC).toString())
        assertEquals("12 ft", Quantity.height(3.8, UnitSystem.IMPERIAL).toString())
        assertEquals("45 s", Quantity.duration(45.0).toString())
        assertEquals("2 min", Quantity.duration(125.0).toString())
        assertEquals("1 h 25 min", Quantity.duration(5100.0).toString())
        assertEquals("1 h", Quantity.duration(3599.0).toString())
        assertEquals("1 d 1 h", Quantity.duration(90_000.0).toString())
        assertEquals(5100.0, Quantity.duration(5100.0).baseValue, 0.0)
    }
}

class CoreFixesTest {
    @Test fun bufferCoversTheWholeBoxEvenWhenTall() {
        // A box from 50°N to 60°N grown by 1 km must reach 1 km east of its north-east corner too.
        val box = BBox(4.0, 50.0, 5.0, 60.0).buffer(1000.0)
        val corner = Geo.destination(LatLon(60.0, 5.0), 90.0, 999.0)
        assertTrue(corner in box)
        val around = BBox.around(Fixtures.westerkerk, 100.0)
        assertTrue(Geo.destination(Fixtures.westerkerk, 90.0, 99.0) in around)
        assertTrue(Geo.destination(Fixtures.westerkerk, 0.0, 99.0) in around)
    }

    @Test fun turnAngleAcceptsUnnormalisedBearings() {
        assertEquals(-90.0, Geo.turnAngle(-270.0, 0.0), 1e-12)
        assertEquals(90.0, Geo.turnAngle(1075.0, -275.0), 1e-12) // 355° to 85°
        assertEquals(-180.0, Geo.turnAngle(0.0, 180.0), 1e-12)
    }
}

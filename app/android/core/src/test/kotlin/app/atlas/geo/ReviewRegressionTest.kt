package app.atlas.geo

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.abs
import kotlin.random.Random

/** Bugs found in review, each test failing before its fix; then guards for edge cases that already held. */
class ReviewRegressionTest {
    private fun assertAt(lat: Double, lon: Double, text: String, tolerance: Double = 1e-6) {
        val p = CoordinateParser.parse(text)
        assertNotNull("could not parse '$text'", p)
        assertEquals("lat of '$text'", lat, p!!.lat, tolerance)
        assertEquals("lon of '$text'", lon, p.lon, tolerance)
    }

    @Test fun aLoneHemisphereLetterStaysWithTheNumberItTouches() {
        assertAt(52.37, 4.88, "52.37N 4.88")
        assertAt(52.37, 4.88, "52.37 E4.88")
        assertAt(52.37, 4.88, "4.88E 52.37")
        assertAt(52.37, 4.88, "N52.37 4.88")
        // Spaced on both sides: the reading that keeps latitude first.
        assertAt(52.37, 4.88, "52.37 N 4.88")
        assertAt(52.37, 4.88, "52.37 E 4.88")
        assertAt(-33.8688, 151.2093, "33.8688 S 151.2093")
        val lat = 52 + 22 / 60.0
        val lon = 4 + 53 / 60.0
        assertAt(lat, lon, "52°22'N 4°53'")
        assertAt(lat, lon, "52°22' N 4°53'")
        assertAt(lat, lon, "52°22' E4°53'")
    }

    @Test fun geoUriQueriesArePercentDecoded() {
        assertAt(52.3745, 4.8838, "geo:0,0?q=52.3745%2C4.8838")
        assertAt(52.3745, 4.8838, "geo:0,0?q=52.3745,4.8838%20(Westerkerk)")
        assertAt(52.3745, 4.8838, "geo:0,0?q=52.3745%2C4.8838(Wester+kerk)")
    }

    @Test fun osmShortLinksMatchTheWebsiteAndDecodeToTheCellCentre() {
        assertEquals("0E4~0ldF-", OsmShortLink.encode(LatLon(52.37452, 4.88377), 14))
        assertEquals("0E4~0ldFC--", OsmShortLink.encode(LatLon(52.37452, 4.88377), 18))
        assertEquals("uN~ROOdVC-", OsmShortLink.encode(LatLon(-33.8688, 151.2093), 17))
        assertEquals("Zct8BJ", OsmShortLink.encode(LatLon(40.7, -74.0), 10))
        assertEquals("0E4~--", OsmShortLink.encode(LatLon(52.37452, 4.88377), 3))
        assertEquals("~~~77Um3u-", OsmShortLink.encode(LatLon(89.9, 179.99), 17))
        // The east edge of the world stays in range instead of wrapping into the west.
        val east = OsmShortLink.decode(OsmShortLink.encode(LatLon(10.0, 180.0), 17))!!
        assertTrue("east edge decoded to $east", east.lon > 179.99)
        // A code names a cell; its centre is at most half a cell from the point that made it.
        val rnd = Random(17)
        repeat(200) {
            val p = LatLon(rnd.nextDouble(-85.0, 85.0), rnd.nextDouble(-180.0, 180.0))
            for (zoom in listOf(4, 10, 16)) {
                val code = OsmShortLink.encode(p, zoom)
                val bits = 3 * code.count { it != '-' }
                val back = OsmShortLink.decode(code)!!
                val halfLon = 360.0 / (1L shl bits) / 2
                val halfLat = 180.0 / (1L shl bits) / 2
                assertEquals("$code lon", p.lon, back.lon, halfLon * 1.0001 + 1e-9)
                assertEquals("$code lat", p.lat, back.lat, halfLat * 1.0001 + 1e-9)
            }
        }
    }

    @Test fun plusCodesWrapLongitudesFarBelowMinus180() {
        val code = PlusCode.encode(10.0, -540.0)
        assertTrue("$code should be valid", PlusCode.isValid(code))
        assertEquals(PlusCode.encode(10.0, 180.0), code)
        assertEquals(PlusCode.encode(10.0, -180.0), PlusCode.encode(10.0, -900.0))
    }

    @Test fun labelPointOfAThinSliverIsQuick() {
        // A tile-clipped sliver: 1 km long and a tenth of a millimetre wide.
        val sliver = doubleArrayOf(0.0, 0.0, 1000.0, 0.0, 1000.0, 1e-4, 0.0, 1e-4)
        val started = System.nanoTime()
        val p = Shapes.labelPoint(sliver)
        val ms = (System.nanoTime() - started) / 1e6
        assertTrue("took $ms ms", ms < 300)
        assertTrue(p[1] in 0.0..1e-4)
        assertTrue(p[0] in 0.0..1000.0)
        // A long, narrow but real shape still finds its widest part.
        val bottle = doubleArrayOf(0.0, 0.0, 900.0, 0.0, 900.0, -20.0, 1000.0, -20.0, 1000.0, 22.0, 900.0, 22.0, 900.0, 2.0, 0.0, 2.0)
        val q = Shapes.labelPoint(bottle, precision = 0.5)
        assertTrue("bottle label at ${q.toList()}", q[0] > 900 && q[2] > 19.0)
    }

    @Test fun clipLineDropsSinglePointTouches() {
        // A line grazing the box's top-left corner touches it at one point: no part.
        assertEquals(0, Shapes.clipLine(doubleArrayOf(-5.0, 5.0, 5.0, 15.0), 0.0, 0.0, 10.0, 10.0).size)
        // A part ending on the edge and then leaving has no repeated end point.
        val parts = Shapes.clipLine(doubleArrayOf(5.0, 5.0, 10.0, 5.0, 15.0, 5.0), 0.0, 0.0, 10.0, 10.0)
        assertEquals(1, parts.size)
        assertArrayEquals(doubleArrayOf(5.0, 5.0, 10.0, 5.0), parts[0], 0.0)
        // Coming back in through the same edge point continues as a new part.
        val back = Shapes.clipLine(doubleArrayOf(5.0, 5.0, 10.0, 5.0, 15.0, 5.0, 10.0, 5.0, 5.0, 6.0), 0.0, 0.0, 10.0, 10.0)
        assertEquals(2, back.size)
        assertArrayEquals(doubleArrayOf(10.0, 5.0, 5.0, 6.0), back[1], 0.0)
        // clipSegment still reports a touch, as the segment does meet the box there.
        val out = DoubleArray(4)
        assertTrue(Shapes.clipSegment(-5.0, 5.0, 5.0, 15.0, 0.0, 0.0, 10.0, 10.0, out))
        assertArrayEquals(doubleArrayOf(0.0, 10.0, 0.0, 10.0), out, 1e-12)
    }

    @Test fun corridorKeepsASmallRealHole() {
        // A square loop 245 m a side with a 100 m corridor leaves a real 45 m square gap in the middle, whose centre is
        // 122.5 m from the line. It used to be dropped as grid noise (under 4 cells²), so the centre counted as inside.
        val o = LatLon(52.0, 4.0)
        val e = Geo.destination(o, 90.0, 245.0)
        val loop = listOf(o, e, Geo.destination(e, 0.0, 245.0), Geo.destination(o, 0.0, 245.0), o)
        val rings = Shapes.corridorRings(Polyline(loop), 100.0)
        assertEquals(2, rings.size)
        val centre = Geo.destination(Geo.destination(o, 90.0, 122.5), 0.0, 122.5)
        assertFalse(Shapes.contains(rings, centre))
        assertTrue(Shapes.geodesicArea(rings[1]) in 500.0..2025.0)
    }

    @Test fun theNightTheMidnightSunEndsHasItsSunset() {
        // Tromsø's latitude, 25 July 2026: by noon's declination the sun never dips to −0.833°, but by midnight it
        // does. The sunset used to come back null, with polar NONE and no events at all.
        val utc = ZoneId.of("UTC")
        val apparent = Sun.SUNRISE_ALTITUDE + Sun.refraction(Sun.SUNRISE_ALTITUDE)
        assertEquals(SunTimes.Polar.DAY, Sun.times(LocalDate.of(2026, 7, 24), 69.6, 15.0, utc).polar)
        val first = Sun.times(LocalDate.of(2026, 7, 25), 69.6, 15.0, utc)
        assertEquals(SunTimes.Polar.NONE, first.polar)
        assertEquals(null, first.sunrise) // the night before was still light
        val sunset = first.sunset!!
        assertEquals(apparent, Sun.position(sunset.toInstant(), 69.6, 15.0).altitudeDeg, 0.01)
        assertTrue(abs(java.time.Duration.between(first.solarNoon, sunset).toMinutes() - 720) < 60)
        // The next solar day rises soon after that midnight.
        val sunrise = Sun.times(LocalDate.of(2026, 7, 26), 69.6, 15.0, utc).sunrise!!
        assertTrue(sunrise.toInstant() > sunset.toInstant())
        assertEquals(apparent, Sun.position(sunrise.toInstant(), 69.6, 15.0).altitudeDeg, 0.01)
    }

    @Test fun negativeRadiiFindNothing() {
        val rnd = Random(4)
        val points = PointIndex(500)
        val boxes = PackedRTree(500)
        repeat(500) { val x = rnd.nextDouble(0.0, 100.0); val y = rnd.nextDouble(0.0, 100.0); points.add(x, y); boxes.add(x, y) }
        points.finish(); boxes.finish()
        assertEquals(0, points.within(50.0, 50.0, -20.0).size)
        assertEquals(0, points.nearest(50.0, 50.0, maxDistance = -20.0).size)
        assertEquals(0, boxes.neighbors(50.0, 50.0, maxDistance = -20.0).size)
    }

    // Edge checks that held already; kept as guards.

    @Test fun labelPointAvoidsAHole() {
        // A 100 m square with a 20 m hole in the middle. The best spots lie on the diagonals, as far from the two
        // walls as from the hole's corner: t = √2 (40 − t), so t = 40√2 / (1 + √2) ≈ 23.43 m clear.
        val square = doubleArrayOf(0.0, 0.0, 100.0, 0.0, 100.0, 100.0, 0.0, 100.0, 40.0, 40.0, 40.0, 60.0, 60.0, 60.0, 60.0, 40.0)
        val p = Shapes.labelPoint(square, intArrayOf(4), precision = 0.1)
        assertFalse(Shapes.contains(square, intArrayOf(4), 50.0, 50.0))
        assertTrue(Shapes.contains(square, intArrayOf(4), p[0], p[1]))
        assertEquals(40 * Math.sqrt(2.0) / (1 + Math.sqrt(2.0)), p[2], 0.1)
    }

    @Test fun corridorOfAHairpinHasNoSpuriousHoles() {
        val a = LatLon(52.0, 4.0)
        val b = Geo.destination(a, 90.0, 3000.0)
        val c = Geo.destination(b, 0.0, 150.0)
        val d = Geo.destination(c, 270.0, 3000.0)
        val rings = Shapes.corridorRings(Polyline(listOf(a, b, c, d)), 100.0)
        assertEquals(1, rings.size)
        val between = Geo.destination(Geo.midpoint(a, b), 0.0, 75.0)
        assertTrue(Shapes.contains(rings, between))
        assertFalse(Shapes.contains(rings, Geo.destination(Geo.midpoint(a, b), 180.0, 110.0)))
    }

    @Test fun spatialIndexesAtTheSmallestNodeSize() {
        val rnd = Random(23)
        val n = 3000
        val xs = DoubleArray(n) { rnd.nextDouble(0.0, 100.0) }
        val ys = DoubleArray(n) { rnd.nextDouble(0.0, 100.0) }
        val tree = PackedRTree(n, 2)
        val points = PointIndex(n, 2)
        for (i in 0 until n) { tree.add(xs[i], ys[i]); points.add(xs[i], ys[i]) }
        tree.finish(); points.finish()
        repeat(50) {
            val x0 = rnd.nextDouble(0.0, 100.0); val y0 = rnd.nextDouble(0.0, 100.0)
            val expected = (0 until n).filter { xs[it] in x0..x0 + 10 && ys[it] in y0..y0 + 10 }.toIntArray()
            assertArrayEquals(expected, tree.search(x0, y0, x0 + 10, y0 + 10).sortedArray())
            assertArrayEquals(expected, points.range(x0, y0, x0 + 10, y0 + 10).sortedArray())
            fun d(i: Int) = (xs[i] - x0) * (xs[i] - x0) + (ys[i] - y0) * (ys[i] - y0)
            val nearest = (0 until n).sortedBy { d(it) }.take(7).map { d(it) }
            assertEquals(nearest, tree.neighbors(x0, y0, 7).map { d(it) })
            assertEquals(nearest, points.nearest(x0, y0, 7).map { d(it) })
        }
    }

    @Test fun sunEventsStayOnTheirDateAcrossDaylightSavingChanges() {
        val zone = ZoneId.of("Europe/Amsterdam")
        for (date in listOf(LocalDate.of(2026, 3, 29), LocalDate.of(2026, 10, 25))) {
            val t = Sun.times(date, 52.37, 4.89, zone)
            assertEquals(date, t.sunrise!!.toLocalDate())
            assertEquals(date, t.sunset!!.toLocalDate())
            assertTrue(abs(t.dayLength.toMinutes() - (if (date.monthValue == 3) 760 else 590)) < 30)
            val m = Moon.times(date, 52.37, 4.89, zone)
            m.rise?.let { assertEquals(date, it.toLocalDate()) }
            m.set?.let { assertEquals(date, it.toLocalDate()) }
        }
    }
}

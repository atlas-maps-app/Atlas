package app.atlas.geo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Duration
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import kotlin.math.abs
import kotlin.random.Random

/** Randomised checks against brute force and against an independent algorithm. */
class RandomisedCheckTest {
    private val rnd = Random(2026)

    private fun randomWalk(start: LatLon, steps: Int, stepMetres: Double): List<LatLon> {
        val out = ArrayList<LatLon>()
        var p = start
        var bearing = rnd.nextDouble(0.0, 360.0)
        out += p
        repeat(steps) {
            bearing += rnd.nextDouble(-120.0, 120.0)
            p = Geo.destination(p, bearing, rnd.nextDouble(stepMetres * 0.2, stepMetres))
            out += p
        }
        return out
    }

    @Test fun corridorsOfRandomWalksHoldThePointsWithinTheirWidth() {
        // Random walks double back and cross themselves, so their buffers have holes and notches. The outline is
        // within a few percent of the width, and cuts across a notch by at most about a tenth of it.
        repeat(12) {
            val line = Polyline(randomWalk(LatLon(rnd.nextDouble(-60.0, 70.0), rnd.nextDouble(-170.0, 170.0)), 25, 400.0))
            val width = rnd.nextDouble(20.0, 300.0)
            val prepared = PreparedPolygon(Shapes.corridorRings(line, width))
            val box = line.bbox!!.buffer(width * 1.5)
            var checked = 0
            repeat(1500) {
                val p = LatLon(rnd.nextDouble(box.south, box.north), rnd.nextDouble(box.west, box.east))
                val d = line.snap(p)!!.offset
                if (d < width * 0.95) { assertTrue("$d m from a $width m corridor", prepared.contains(p)); checked++ }
                if (d > width * 1.10) assertFalse("$d m from a $width m corridor", prepared.contains(p))
            }
            assertTrue(checked > 50)
        }
    }

    @Test fun lineDistanceMatchesAllPairs() {
        repeat(40) {
            val origin = LatLon(rnd.nextDouble(-60.0, 60.0), rnd.nextDouble(-170.0, 170.0))
            val a = Polyline(randomWalk(origin, rnd.nextInt(1, 40), 300.0))
            val b = Polyline(randomWalk(Geo.destination(origin, rnd.nextDouble(0.0, 360.0), rnd.nextDouble(0.0, 3000.0)), rnd.nextInt(1, 40), 300.0))
            // The same local frame the search uses, so the pairs compare exactly.
            val frame = LocalFrame(a.bbox!!.union(b.bbox!!).center)
            val out = DoubleArray(5)
            fun gap(i: Int, j: Int): Double {
                val i1 = minOf(i + 1, a.points.size - 1); val j1 = minOf(j + 1, b.points.size - 1)
                return LineDistance.segmentGap(
                    frame.x(a.points[i].lon), frame.y(a.points[i].lat), frame.x(a.points[i1].lon), frame.y(a.points[i1].lat),
                    frame.x(b.points[j].lon), frame.y(b.points[j].lat), frame.x(b.points[j1].lon), frame.y(b.points[j1].lat), out,
                )
            }
            var brute = Double.POSITIVE_INFINITY
            for (i in 0 until maxOf(a.points.size - 1, 1)) for (j in 0 until maxOf(b.points.size - 1, 1)) brute = minOf(brute, gap(i, j))
            val found = Shapes.nearest(a, b)!!
            assertEquals(brute, gap(found.segmentA, found.segmentB), 1e-9 * maxOf(1.0, brute))
            // Reported on the sphere: the flat frame agrees to well under a percent at this size.
            assertEquals(brute, found.distance, 0.01 + brute * 0.005)
        }
    }

    @Test fun handTypedCoordinatesInManyStyles() {
        repeat(300) {
            val lat = rnd.nextDouble(-89.0, 89.0)
            val lon = rnd.nextDouble(-179.0, 179.0)
            val ns = if (lat < 0) "S" else "N"
            val ew = if (lon < 0) "W" else "E"
            val la = abs(lat)
            val lo = abs(lon)
            fun dm(v: Double) = "${v.toInt()}°${String.format(java.util.Locale.ROOT, "%.4f", (v - v.toInt()) * 60)}'"
            val styles = listOf(
                String.format(java.util.Locale.ROOT, "%.6f, %.6f", lat, lon),
                String.format(java.util.Locale.ROOT, "%.6f %.6f", lat, lon),
                String.format(java.util.Locale.ROOT, "%.6f%s %.6f%s", la, ns, lo, ew),
                String.format(java.util.Locale.ROOT, "%s%.6f %s%.6f", ns, la, ew, lo),
                String.format(java.util.Locale.ROOT, "%s %.6f, %s %.6f", ns, la, ew, lo),
                String.format(java.util.Locale.ROOT, "%.6f° %s, %.6f° %s", la, ns, lo, ew),
                String.format(java.util.Locale.ROOT, "%.6f%s %.6f%s", lo, ew, la, ns),
                "${dm(la)}$ns ${dm(lo)}$ew",
                "$ns ${dm(la)} $ew ${dm(lo)}",
                String.format(java.util.Locale.ROOT, "geo:%.6f,%.6f", lat, lon),
                String.format(java.util.Locale.ROOT, "https://www.openstreetmap.org/#map=15/%.5f/%.5f", lat, lon),
            )
            for (text in styles) {
                val p = CoordinateParser.parse(text)
                assertNotNull("'$text'", p)
                assertTrue("'$text' -> $p", Geo.distance(p!!, LatLon(lat, lon)) < 2.0)
            }
        }
    }

    @Test fun everyDayOfTheYearAtHighLatitudesIsConsistent() {
        val utc = ZoneId.of("UTC")
        for (lat in listOf(60.0, 66.0, 67.5, 69.6, 74.0, 78.2, 85.0, -68.0, -77.8)) {
            var day = LocalDate.of(2026, 1, 1)
            var polarDays = 0
            while (day.year == 2026) {
                val t = Sun.times(day, lat, 15.0, utc)
                val noon = t.solarNoon.toInstant()
                if (t.sunrise != null) assertTrue("$lat $day", t.sunrise!!.toInstant() < noon)
                if (t.sunset != null) assertTrue("$lat $day", t.sunset!!.toInstant() > noon)
                if (t.sunrise == null && t.sunset == null) {
                    assertTrue("$lat $day", t.polar != SunTimes.Polar.NONE)
                    polarDays++
                    // Polar day: the sun is up at noon; polar night: it is down.
                    assertEquals("$lat $day", t.polar == SunTimes.Polar.DAY, Sun.position(noon, lat, 15.0).up)
                } else {
                    assertEquals(SunTimes.Polar.NONE, t.polar)
                }
                if (t.civilDawn != null && t.sunrise != null) assertTrue(t.civilDawn!! < t.sunrise)
                val m = Moon.times(day, lat, 15.0, utc)
                assertFalse(m.alwaysUp && m.alwaysDown)
                if (m.rise != null || m.set != null) assertFalse(m.alwaysUp || m.alwaysDown)
                day = day.plusDays(1)
            }
            // Refraction and the sun's radius bring the midnight sun down to about 65.7°.
            if (abs(lat) >= 66.0) assertTrue("$lat polar days $polarDays", polarDays > 10)
            if (abs(lat) <= 65.0) assertEquals("$lat", 0, polarDays)
        }
    }

    @Test fun amsterdamSunMatchesAnIndependentAlmanac() {
        // The USNO low-precision almanac formulas, solved for −0.8333°, give 07:41:16 and 19:17:50 CEST at this point;
        // the brief's 07:42 and 19:18 are those to the minute.
        val t = Sun.times(LocalDate.of(2026, 10, 1), 52.3676, 4.9041, ZoneId.of("Europe/Amsterdam"))
        assertTrue(abs(Duration.between(LocalTime.of(7, 41, 16), t.sunrise!!.toLocalTime()).seconds) <= 15)
        assertTrue(abs(Duration.between(LocalTime.of(19, 17, 50), t.sunset!!.toLocalTime()).seconds) <= 15)
    }
}

package app.atlas.geo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import kotlin.math.abs

class SunMoonTest {
    private val amsterdamZone = ZoneId.of("Europe/Amsterdam")
    private val amsterdam = LatLon(52.3676, 4.9041)

    private fun assertNear(expected: LocalTime, actual: LocalTime?, seconds: Long, what: String) {
        assertNotNull(what, actual)
        val d = abs(Duration.between(expected, actual).seconds)
        assertTrue("$what was $actual, expected about $expected", d <= seconds)
    }

    @Test fun amsterdamSunriseAndSunsetOnTheFirstOfOctober() {
        val t = Sun.times(LocalDate.of(2026, 10, 1), amsterdam, amsterdamZone)
        assertNear(LocalTime.of(7, 42), t.sunrise?.toLocalTime(), 90, "sunrise")
        assertNear(LocalTime.of(19, 18), t.sunset?.toLocalTime(), 90, "sunset")
        assertNear(LocalTime.of(13, 30), t.solarNoon.toLocalTime(), 120, "solar noon")
        assertEquals(SunTimes.Polar.NONE, t.polar)
        assertEquals(11.6, t.dayLength.toMinutes() / 60.0, 0.1)
        // The day's events in order.
        val order = listOf(t.astronomicalDawn, t.nauticalDawn, t.civilDawn, t.blueHourMorningEnd, t.sunrise, t.goldenHourMorningEnd,
            t.solarNoon, t.goldenHourEveningStart, t.sunset, t.blueHourEveningStart, t.civilDusk, t.nauticalDusk, t.astronomicalDusk)
        for (i in 1 until order.size) assertTrue("event $i", order[i - 1]!!.isBefore(order[i]))
        // The sun's apparent altitude at its own events: the true altitude plus refraction.
        fun apparent(trueAltitude: Double) = trueAltitude + Sun.refraction(trueAltitude)
        assertEquals(apparent(-0.833), Sun.position(t.sunrise!!.toInstant(), amsterdam).altitudeDeg, 0.01)
        assertEquals(apparent(-6.0), Sun.position(t.civilDusk!!.toInstant(), amsterdam).altitudeDeg, 0.01)
        assertEquals(apparent(6.0), Sun.position(t.goldenHourEveningStart!!.toInstant(), amsterdam).altitudeDeg, 0.01)
    }

    @Test fun solarPositionMatchesTheNrelReference() {
        // NREL SPA's worked example: Golden, Colorado, 17 October 2003 12:30:30 −7:00 (zenith 50.11°, azimuth 194.34°).
        val p = Sun.position(Instant.parse("2003-10-17T19:30:30Z"), 39.742476, -105.1786)
        assertEquals(194.34024, p.azimuthDeg, 0.05)
        assertEquals(90 - 50.11162, p.altitudeDeg, 0.05)
        val noon = Sun.times(LocalDate.of(2026, 10, 1), amsterdam, amsterdamZone).solarNoon.toInstant()
        assertEquals(180.0, Sun.position(noon, amsterdam).azimuthDeg, 0.2)
        val equinox = Sun.times(LocalDate.of(2026, 3, 20), 0.0, 0.0, ZoneId.of("UTC"))
        assertTrue(equinox.noonAltitudeDeg > 89.5)
    }

    @Test fun polarDayAndNight() {
        val tromso = Sun.times(LocalDate.of(2026, 12, 21), 69.6496, 18.956, ZoneId.of("Europe/Oslo"))
        assertEquals(SunTimes.Polar.NIGHT, tromso.polar)
        assertNull(tromso.sunrise)
        assertNull(tromso.sunset)
        assertNotNull(tromso.civilDawn) // the sun still climbs to about −3°
        assertEquals(Duration.ZERO, tromso.dayLength)
        val svalbard = Sun.times(LocalDate.of(2026, 6, 21), 78.2232, 15.6267, ZoneId.of("Arctic/Longyearbyen"))
        assertEquals(SunTimes.Polar.DAY, svalbard.polar)
        assertNull(svalbard.sunrise)
        assertEquals(Duration.ofHours(24), svalbard.dayLength)
    }

    @Test fun zonesFarFromSolarTimeKeepTheirDate() {
        val date = LocalDate.of(2026, 10, 1)
        val kiritimati = Sun.times(date, 1.87, -157.4, ZoneId.of("Pacific/Kiritimati"))
        assertEquals(date, kiritimati.solarNoon.toLocalDate())
        assertEquals(date, kiritimati.sunrise!!.toLocalDate())
        assertEquals(date, kiritimati.sunset!!.toLocalDate())
    }

    @Test fun shadows() {
        val s = Sun.shadowVector(45.0, 180.0)!!
        assertEquals(0.0, s.east, 1e-12)
        assertEquals(1.0, s.north, 1e-12)
        val low = Sun.shadowVector(10.0, 270.0)!!
        assertEquals(1 / kotlin.math.tan(Math.toRadians(10.0)), low.east, 1e-9)
        assertEquals(0.0, low.north, 1e-9)
        assertNull(Sun.shadowVector(-1.0, 90.0))
        val noon = Sun.position(Instant.parse("2026-06-21T11:40:00Z"), amsterdam)
        val shadow = Sun.shadowVector(noon)!!
        assertTrue(shadow.north > 0 && shadow.length < 1)
        assertTrue(noon.up)
    }

    @Test fun moonAgainstMeeusExample() {
        // Meeus, Astronomical Algorithms, example 47.a: 1992 April 12, 0h TD.
        // ΔT was 59 s, so 0h TD was 23:59:01 UT the day before. The full series reproduces Meeus' figures.
        val g = Moon.geocentric(Instant.parse("1992-04-11T23:59:01Z"))
        assertEquals(133.162655, g[3], 0.001)
        assertEquals(-3.229126, g[4], 0.001)
        assertEquals(368409.7, g[2], 1.0)
        // Meeus' right ascension and declination are apparent (with nutation); ours are geometric.
        assertEquals(134.688470, g[0], 0.01)
        assertEquals(13.768368, g[1], 0.005)
    }

    @Test fun moonPhasesAtTheEclipsesOf2026() {
        // A total lunar eclipse on 3 March 2026 (full) and an annular solar eclipse on 17 February 2026 (new).
        val full = Moon.phase(Instant.parse("2026-03-03T11:38:00Z"))
        assertTrue("full ${full.illumination}", full.illumination > 0.995)
        assertEquals(0.5, full.phase, 0.01)
        val new = Moon.phase(Instant.parse("2026-02-17T12:01:00Z"))
        assertTrue("new ${new.illumination}", new.illumination < 0.005)
        assertTrue(new.phase < 0.01 || new.phase > 0.99)
        // Waxing in between, the lit limb towards the sun in the west; waning after, lit in the east.
        var last = 0.0
        for (day in 18..28) {
            val p = Moon.phase(Instant.parse("2026-02-${day}T12:00:00Z"))
            assertTrue(p.waxing && p.illumination > last)
            assertTrue("limb ${p.brightLimbDeg}", p.brightLimbDeg in 180.0..360.0)
            last = p.illumination
        }
        val waning = Moon.phase(Instant.parse("2026-03-10T12:00:00Z"))
        assertFalse(waning.waxing)
        assertTrue(waning.brightLimbDeg in 0.0..180.0)
        val totalSolar = Moon.phase(Instant.parse("2026-08-12T17:46:00Z"))
        assertTrue(totalSolar.illumination < 0.005)
    }

    @Test fun moonriseAndMoonset() {
        var found = 0
        for (d in 1..30) {
            val date = LocalDate.of(2026, 10, d)
            val t = Moon.times(date, amsterdam, amsterdamZone)
            t.rise?.let {
                assertEquals(date, it.toLocalDate())
                assertTrue(abs(Moon.position(it.toInstant(), amsterdam).altitudeDeg) < 1.0)
                found++
            }
            t.set?.let {
                assertEquals(date, it.toLocalDate())
                assertTrue(abs(Moon.position(it.toInstant(), amsterdam).altitudeDeg) < 1.0)
                found++
            }
            if (t.rise == null && t.set == null) assertTrue(t.alwaysUp || t.alwaysDown)
        }
        // About one rise and one set a day, less the day each one skips.
        assertTrue("found $found", found in 54..60)
        val pos = Moon.position(Instant.parse("2026-03-03T23:00:00Z"), amsterdam)
        assertTrue(pos.altitudeDeg > 20 && pos.distanceKm in 356_000.0..407_000.0)
    }
}

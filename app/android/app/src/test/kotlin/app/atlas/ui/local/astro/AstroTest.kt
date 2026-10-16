package app.atlas.ui.local.astro

import app.atlas.geo.Sun
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.hypot
import kotlin.math.sqrt

/**
 * The sky maths against independent references: JPL Horizons for the planets (fetched on 15 October 2026, in
 * `horizons_*.json`), Skyfield with the DE421 ephemeris for the space station's passes (the same TLE), and `:core`
 * `Sun` for the terminator.
 */
class AstroTest {
    private fun resource(name: String) = javaClass.getResourceAsStream("/local/$name")!!.bufferedReader().readText()

    private fun angleBetween(a: Vec3, b: Vec3): Double = Math.toDegrees(acos((a.dot(b) / (a.length * b.length)).coerceIn(-1.0, 1.0)))

    // ---- planets ----------------------------------------------------------------------------------------------------

    @Test
    fun heliocentricPositionsMatchHorizonsWithinTheMethodsAccuracy() {
        val root = Json.parseToJsonElement(resource("horizons_helio.json")) as JsonObject
        // JPL's table 1 errors in longitude (arcseconds), generously doubled; distance errors are in thousands of km.
        val limitDeg = mapOf(1 to 0.02, 2 to 0.02, 3 to 0.02, 4 to 0.04, 5 to 0.25, 6 to 0.35, 7 to 0.05, 8 to 0.02)
        val planets = listOf(Planet.MERCURY, Planet.VENUS, Planet.EARTH, Planet.MARS, Planet.JUPITER, Planet.SATURN, Planet.URANUS, Planet.NEPTUNE)
        var worst = 0.0
        var points = 0
        for ((date, byPlanet) in root) {
            // 1900 is outside the fitted span (1800-2050 is fine, but give it extra room); 2040 is inside.
            for ((idx, p) in planets.withIndex()) {
                val row = ((byPlanet as JsonObject)[(idx + 1).toString()] as JsonArray).map { (it as JsonPrimitive).doubleOrNull!! }
                val ref = Vec3(row[1], row[2], row[3])
                val mine = Planets.heliocentric(p, row[0])
                val angle = angleBetween(ref, mine)
                val dist = abs(ref.length - mine.length) * Astro.AU_KM
                assertTrue("$p on $date: ${"%.4f".format(angle)} degrees off", angle < limitDeg.getValue(idx + 1) * 2)
                assertTrue("$p on $date: ${"%.0f".format(dist)} km off in distance", dist < 6_000_000.0 * (idx + 1) / 4 + 100_000)
                worst = maxOf(worst, angle / limitDeg.getValue(idx + 1))
                points++
            }
        }
        println("heliocentric: $points positions, worst error ${"%.2f".format(worst)} x the allowed")
    }

    @Test
    fun geocentricRightAscensionAndDeclinationMatchHorizons() {
        val root = Json.parseToJsonElement(resource("horizons_geo.json")) as JsonObject
        val at = Instant.parse("2026-09-30T12:00:00Z")
        val limits = mapOf("Mercury" to 0.1, "Venus" to 0.1, "Mars" to 0.1, "Jupiter" to 0.25, "Saturn" to 0.3, "Uranus" to 0.1, "Neptune" to 0.1)
        val planets = mapOf("Mercury" to Planet.MERCURY, "Venus" to Planet.VENUS, "Mars" to Planet.MARS, "Jupiter" to Planet.JUPITER, "Saturn" to Planet.SATURN, "Uranus" to Planet.URANUS, "Neptune" to Planet.NEPTUNE)
        for ((name, planet) in planets) {
            // "2026-Sep-30 12:00, , ,   206.96075,  -12.92344, ..."
            val cols = (root[name] as JsonPrimitive).content.split(',').map { it.trim() }
            val ra = cols[3].toDouble()
            val dec = cols[4].toDouble()
            val mine = Planets.geocentricJ2000(planet, at)
            val sep = Math.toDegrees(acos((Math.sin(Math.toRadians(dec)) * Math.sin(Math.toRadians(mine.decDeg)) + Math.cos(Math.toRadians(dec)) * Math.cos(Math.toRadians(mine.decDeg)) * Math.cos(Math.toRadians(ra - mine.raDeg))).coerceIn(-1.0, 1.0)))
            assertTrue("$name: ${"%.3f".format(sep)} degrees from Horizons (RA ${mine.raDeg}, Dec ${mine.decDeg})", sep < limits.getValue(name))
        }
    }

    @Test
    fun precessionMovesJ2000TowardsTheDateAtFiftyArcsecondsAYear() {
        // A star at RA 0, Dec 0 in 2000 is at RA about 0.00 + 26 years x 46.1" in 2026.
        val v = Astro.precess(Vec3(1.0, 0.0, 0.0), 2461314.0)
        val r = Astro.raDec(v)
        assertEquals(26.2 * 46.1 / 3600.0, r.raDeg, 0.01)
        assertEquals(26.2 * 20.0 / 3600.0, r.decDeg, 0.01)
    }

    @Test
    fun tonightsPlanetsOnThe30thOfSeptember2026AreTheOnesTheBoardShows() {
        // The Planets board: Venus after sunset, low in the west; Saturn all night; Jupiter and Mars before dawn.
        // The board is set in Williamsburg, Brooklyn.
        val night = Tonight.planets(Instant.parse("2026-09-30T16:00:00Z"), 40.71, -73.96, ZoneId.of("America/New_York")).associateBy { it.planet }
        assertEquals(NightView.EVENING, night.getValue(Planet.VENUS).view)
        assertTrue(night.getValue(Planet.VENUS).low)
        assertTrue(Astro.compass8(night.getValue(Planet.VENUS).bestAzimuthDeg) in listOf("W", "SW"))
        assertEquals(NightView.ALL_NIGHT, night.getValue(Planet.SATURN).view)
        assertEquals(NightView.BEFORE_DAWN, night.getValue(Planet.JUPITER).view)
        assertEquals(NightView.BEFORE_DAWN, night.getValue(Planet.MARS).view)
        assertTrue("Mercury hugs the horizon at best", night.getValue(Planet.MERCURY).low)
    }

    // ---- terminator ---------------------------------------------------------------------------------------------------

    @Test
    fun theSubSolarPointHasTheSunOverhead() {
        for (iso in listOf("2026-03-20T12:00:00Z", "2026-06-21T03:30:00Z", "2026-10-15T17:30:00Z", "2026-12-21T22:10:00Z")) {
            val t = Instant.parse(iso)
            val p = Terminator.subSolar(t)
            val alt = Sun.position(t, p.latDeg, p.lonDeg).altitudeDeg
            assertTrue("$iso: the sun stands at $alt degrees at the sub-solar point ${p.latDeg}, ${p.lonDeg}", alt > 89.5)
        }
    }

    @Test
    fun theTerminatorAndTheTwilightLinesAreWhereTheSunReachesThoseAltitudes() {
        val t = Instant.parse("2026-10-15T17:30:00Z")
        for (alt in listOf(0.0, -6.0, -12.0, -18.0)) {
            for (p in Terminator.line(t, alt, count = 36)) {
                val real = Sun.position(t, p.latDeg, p.lonDeg).altitudeDeg
                // Core's altitude includes refraction (about half a degree at the horizon, none below -1 degree).
                val allowed = if (alt == 0.0) 0.8 else 0.25
                assertTrue("sun at $real degrees on the $alt line", abs(real - alt) < allowed)
            }
        }
    }

    @Test
    fun theNightRingIsTheOppositeCircle() {
        val t = Instant.parse("2026-10-15T17:30:00Z")
        for (p in Terminator.nightRing(t, 0.0, 24)) {
            assertTrue(abs(Sun.position(t, p.latDeg, p.lonDeg).altitudeDeg) < 0.8)
        }
        // Amsterdam is in the dark at 17:30 UTC in mid October, Honolulu in daylight.
        assertTrue(Sun.position(t, 52.37, 4.89).altitudeDeg < 0)
        assertTrue(Sun.position(t, 21.3, -157.9).altitudeDeg > 0)
    }

    // ---- the orthographic view ----------------------------------------------------------------------------------------

    @Test
    fun rimsStayInsideTheDiscAndTheFrontOfTheGlobeFacesUs() {
        val view = OrthoView(centreLonDeg = 5.0)
        assertTrue(view.faces(52.0, 5.0))
        assertTrue(!view.faces(-52.0, 185.0))
        val big = DoubleArray(2 * 36) { i -> if (i % 2 == 0) -180.0 + 10 * (i / 2) else 10.0 }
        val clipped = view.ring(big, ccw = null)
        assertNotNull(clipped)
        var max = 0.0
        for (i in clipped!!.indices step 2) max = maxOf(max, hypot(clipped[i].toDouble(), clipped[i + 1].toDouble()))
        assertTrue("a point at $max from the middle", max <= 1.0001)
    }

    @Test
    fun aCapAroundTheAntisolarPointFillsTheNightSideAndOnlyIt() {
        val t = Instant.parse("2026-10-15T17:30:00Z")
        val sub = Terminator.subSolar(t)
        val view = OrthoView(centreLonDeg = 5.0)
        val ring = view.cap(-sub.latDeg, Astro.norm180(sub.lonDeg + 180), 90.0)
        assertNotNull(ring)
        // The shape's area: Amsterdam (night) must lie inside it, the point under the sun (day) outside it.
        fun inside(poly: FloatArray, x: Double, y: Double): Boolean {
            var c = false
            var j = poly.size / 2 - 1
            for (i in 0 until poly.size / 2) {
                val xi = poly[2 * i]; val yi = poly[2 * i + 1]; val xj = poly[2 * j]; val yj = poly[2 * j + 1]
                if ((yi > y) != (yj > y) && x < (xj - xi) * (y - yi) / (yj - yi) + xi) c = !c
                j = i
            }
            return c
        }
        val ams = view.screen(52.37, 4.89)!!
        assertTrue("Amsterdam is in the dark", inside(ring!!, ams.first, ams.second))
        val noon = view.screen(sub.latDeg, sub.lonDeg)
        if (noon != null) assertTrue("the sub-solar point is not", !inside(ring, noon.first, noon.second))
    }

    // ---- the space station --------------------------------------------------------------------------------------------

    private val iss = Sgp4(Tle.parse(resource("iss_2026-10-15.tle"))!!)

    @Test
    fun theStationPassesMatchSkyfieldOverTheWesterkerk() {
        val passes = SatellitePasses(iss).passes(Instant.parse("2026-10-15T20:00:00Z"), Duration.ofHours(36), 52.37452, 4.88377)
        // Skyfield (same TLE, DE421): ten passes above the horizon in these 36 hours.
        fun near(a: Instant, iso: String, secs: Long = 4) = abs(Duration.between(a, Instant.parse(iso)).seconds) <= secs
        assertEquals(passes.joinToString { it.rise.toString() }, 10, passes.size)
        val second = passes[1]
        assertTrue("rise ${second.rise}", near(second.rise, "2026-10-16T05:12:55Z"))
        assertTrue("peak ${second.peak}", near(second.peak, "2026-10-16T05:17:59Z", 6))
        assertTrue("set ${second.set}", near(second.set, "2026-10-16T05:23:05Z"))
        assertEquals(22.04, second.maxElevationDeg, 0.15)
        assertEquals(214.6, second.riseAzimuthDeg, 0.5)
        assertEquals(78.8, second.setAzimuthDeg, 0.5)
        // Visible stretch, from Skyfield with the sun below -6 degrees, the station lit and above 10 degrees.
        assertTrue(second.visible)
        assertTrue("visible from ${second.visibleFrom}", near(second.visibleFrom!!, "2026-10-16T05:15:20Z", 12))
        assertTrue("visible to ${second.visibleTo}", near(second.visibleTo!!, "2026-10-16T05:20:40Z", 12))
        val visible = passes.filter { it.visible }
        assertEquals(visible.joinToString { it.rise.toString() }, 2, visible.size)
        assertTrue(near(visible[1].visibleFrom!!, "2026-10-17T04:28:43Z", 12))
        assertEquals(16.0, visible[1].maxElevationDeg, 0.15)
        // The high daytime passes cannot be seen.
        assertTrue(passes.first { abs(it.maxElevationDeg - 62.6) < 0.5 }.visible.not())
        // Each pass carries a ground track from just before the rise to just after the set.
        assertTrue(second.track.size > 20)
        assertTrue(second.track.first().time.isBefore(second.rise) && second.track.last().time.isAfter(second.set))
    }

    @Test
    fun theStationIsInTheEarthsShadowWhereItShouldBe() {
        val t = Instant.parse("2026-10-16T05:17:59Z")
        val s = iss.propagate(t)!!
        assertTrue(SatellitePasses.sunlit(Vec3(s.x, s.y, s.z), t))
        // On the other side of the Earth from the sun at 7000 km from the centre it is in shadow.
        val sun = Astro.sunEci(t)
        val behind = sun * (-7000.0 / sun.length)
        assertTrue(!SatellitePasses.sunlit(behind, t))
    }

    @Test
    fun theTleParsesTheGeometry() {
        val c = sqrt(iss.tle.eccentricity)
        assertTrue(c < 0.1)
        assertEquals(51.6327, Math.toDegrees(iss.tle.inclination), 1e-6)
        val s = iss.propagate(iss.tle.epoch)!!
        // The orbit is about 420 km up: 6798 km from the centre.
        assertEquals(6798.0, sqrt(s.x * s.x + s.y * s.y + s.z * s.z), 25.0)
    }
}

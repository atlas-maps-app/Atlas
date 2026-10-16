package app.atlas.ui.local.astro

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import kotlin.math.sqrt

/**
 * SGP4 against Vallado's verification set: `SGP4-VER.TLE` (33 element sets, near-Earth and deep-space, the resonant
 * Molniyas, a Lyddane-choice case, sub-orbital and decaying ones) and the ephemerides his reference C++ wrote for each
 * (`<satnum>.e`: position and velocity in the TEME frame, every few minutes), from the same celestrak.org package.
 */
class Sgp4Test {
    private class Case(val name: String, val l1: String, val l2: String, val startMin: Double, val stopMin: Double, val stepMin: Double)

    private fun resource(name: String): List<String> =
        javaClass.getResourceAsStream("/local/sgp4/$name")!!.bufferedReader().readLines().map { it.trimEnd() }

    private fun cases(): List<Case> {
        val lines = resource("SGP4-VER.TLE").filter { it.isNotBlank() }
        val out = ArrayList<Case>()
        var name = ""
        var i = 0
        while (i < lines.size) {
            val l = lines[i]
            if (l.startsWith("#")) { name = l.trimStart('#', ' '); i++; continue }
            if (l.startsWith("1 ")) {
                val l2 = lines[i + 1]
                val extra = l2.substring(69).trim().split(Regex("\\s+")).filter { it.isNotEmpty() }.map { it.toDouble() }
                // A line pair without the test span is propagated for a day.
                out += if (extra.size >= 3) Case(name, l, l2.substring(0, 69), extra[0], extra[1], extra[2]) else Case(name, l, l2.substring(0, 69), 0.0, 1440.0, 120.0)
                i += 2
            } else i++
        }
        return out
    }

    private class Row(val seconds: Double, val v: DoubleArray)

    private fun ephemeris(satnum: String): List<Row> {
        val rows = ArrayList<Row>()
        var inside = false
        for (l in resource("$satnum.e")) {
            if (l.startsWith("EphemerisTimePosVel")) { inside = true; continue }
            if (!inside || l.isBlank() || l.trim().startsWith("END")) continue
            val n = l.trim().split(Regex("\\s+")).map { it.toDouble() }
            if (n.size == 7) rows += Row(n[0], n.toDoubleArray().copyOfRange(1, 7))
        }
        return rows
    }

    @Test
    fun theElementSetsReadLikeTheReference() {
        val iss = Tle.parse(
            "ISS (ZARYA)\n1 25544U 98067A   26288.49932320  .00005222  00000+0  10330-3 0  9998\n2 25544  51.6327  64.7113 0006646 265.3101  94.7128 15.48874993590405",
        )
        assertNotNull(iss)
        assertEquals("25544", iss!!.catalogue)
        assertEquals("ISS (ZARYA)", iss.name)
        assertEquals(51.6327, Math.toDegrees(iss.inclination), 1e-9)
        assertEquals(0.0006646, iss.eccentricity, 1e-12)
        assertEquals(1.033e-4, iss.bstar, 1e-12)
        // Day 288.49932320 of 2026 is 15 October, 11:59:02.6.
        assertEquals(Instant.parse("2026-10-15T11:59:00Z").epochSecond.toDouble(), iss.epochSeconds, 5.0)
        assertEquals(15.48874993, iss.meanMotion * 1440.0 / (2 * Math.PI), 1e-8)
        assertNull("a flipped digit fails the checksum", Tle.parse("", "1 25544U 98067A   26288.49932320  .00005222  00000+0  10330-3 0  9990", iss.line2))
    }

    private class Result(val misses: Int, val points: Int, val worstPosition: Double, val worstVelocity: Double)

    private fun compare(tle: Tle, rows: List<Row>, afspc: Boolean): Result {
        val sgp = Sgp4(tle, Gravity.WGS72, afspc)
        var misses = 0
        var points = 0
        var worstP = 0.0
        var worstV = 0.0
        for (row in rows) {
            val s = sgp.propagate(row.seconds / 60.0)
            if (s == null) { misses++; continue }
            points++
            worstP = maxOf(worstP, sqrt(sq(s.x - row.v[0]) + sq(s.y - row.v[1]) + sq(s.z - row.v[2])))
            worstV = maxOf(worstV, sqrt(sq(s.vx - row.v[3]) + sq(s.vy - row.v[4]) + sq(s.vz - row.v[5])))
        }
        return Result(misses, points, worstP, worstV)
    }

    /**
     * Cases whose reference file starts with a row the reference code printed from a failed initialisation (an
     * unusable orbit): the propagator must fail there too, which is checked on its own.
     */
    private val failing = setOf("33333", "33334", "33335")

    @Test
    fun everyVerificationCaseMatchesTheReferenceEphemeris() {
        var compared = 0
        var worstPosition = 0.0
        var worstVelocity = 0.0
        val report = StringBuilder()
        for (c in cases()) {
            val tle = Tle.read(c.name, c.l1, c.l2)
            val rows = ephemeris(tle.catalogue)
            assertTrue("no ephemeris for ${tle.catalogue}", rows.isNotEmpty())
            val improved = compare(tle, rows, afspc = false)
            val afspc = compare(tle, rows, afspc = true)
            report.append("${tle.catalogue}: improved ${improved.points}/${rows.size} pos ${"%.1e".format(improved.worstPosition)} vel ${"%.1e".format(improved.worstVelocity)}; afspc pos ${"%.1e".format(afspc.worstPosition)} (${c.name})\n")
            if (tle.catalogue in failing) continue
            // The reference's ephemerides were written in one operation mode; the other differs only where the
            // Lyddane choice does (low inclinations), so at least one of them must reproduce every row.
            val best = listOf(improved, afspc).minBy { it.worstPosition + it.misses * 1e6 }
            assertEquals("${tle.catalogue} (${c.name}) has rows the propagator refuses\n$report", 0, best.misses)
            assertTrue("${tle.catalogue} (${c.name}): off by ${best.worstPosition} km\n$report", best.worstPosition < 2e-3)
            assertTrue("${tle.catalogue} (${c.name}): velocity off by ${best.worstVelocity} km/s\n$report", best.worstVelocity < 2e-6)
            compared += best.points
            worstPosition = maxOf(worstPosition, best.worstPosition)
            worstVelocity = maxOf(worstVelocity, best.worstVelocity)
        }
        println(report)
        println("SGP4 verification: $compared points, worst position ${"%.2e".format(worstPosition)} km, velocity ${"%.2e".format(worstVelocity)} km/s")
        assertTrue("$compared points", compared > 600)
    }

    @Test
    fun deepSpaceSetsAreFlaggedAndNearEarthOnesAreNot() {
        val byNumber = cases().associateBy { Tle.read(it.name, it.l1, it.l2).catalogue }
        fun deep(n: String) = Sgp4(Tle.read("x", byNumber.getValue(n).l1, byNumber.getValue(n).l2)).deepSpace
        assertTrue("Molniya 2-14", deep("08195"))
        assertTrue("a geosynchronous set", deep("23599") || deep("11801"))
        assertTrue("Delta 1 debris is near Earth", !deep("06251"))
    }

    @Test
    fun brokenOrbitsReturnAnErrorNotNumbers() {
        val byNumber = cases().associateBy { Tle.read(it.name, it.l1, it.l2).catalogue }
        fun sat(n: String) = Sgp4(Tle.read("x", byNumber.getValue(n).l1, byNumber.getValue(n).l2))
        // 33334: a mean motion of 0.00001 revolutions a day, whose periodic terms push the eccentricity past 1.
        val unusable = sat("33334")
        assertNull(unusable.propagate(0.0))
        assertEquals(Sgp4Error.PERT_ELEMENTS, unusable.error)
        // 29141: "last stage of decay, lost in under 420 minutes".
        val lost = sat("29141")
        assertNotNull(lost.propagate(400.0))
        assertNull(lost.propagate(440.0))
        assertNotNull(lost.error)
        // A good orbit recovers after asking for a bad time (the state is per call).
        assertNotNull(sat("88888").also { it.propagate(0.0) }.propagate(360.0))
    }

    @Test
    fun theLyddaneCaseNeedsTheAfspcModeToMatchTheReferenceFile() {
        val c = cases().first { it.l1.contains("23599U") }
        val tle = Tle.read(c.name, c.l1, c.l2)
        val rows = ephemeris("23599")
        assertTrue(compare(tle, rows, afspc = true).worstPosition < 1e-5)
        assertTrue(compare(tle, rows, afspc = false).worstPosition > 0.1)
    }

    private fun sq(x: Double) = x * x

}

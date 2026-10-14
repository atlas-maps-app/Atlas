package app.atlas.services

import app.atlas.geo.LatLon
import app.atlas.model.Fix
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Instant

class FixChooserTest {
    private val t0 = Instant.parse("2026-10-13T12:00:00Z")
    private fun fix(seconds: Long, accuracy: Double) = Fix(LatLon(52.37, 4.88), t0.plusSeconds(seconds), accuracy)

    @Test
    fun theFirstFixWins() {
        val c = FixChooser()
        assertEquals(fix(0, 30.0), c.offer(fix(0, 30.0), "network"))
    }

    @Test
    fun aMoreAccurateFixWinsEvenIfSlightlyOlder() {
        val c = FixChooser()
        c.offer(fix(5, 40.0), "network")
        assertEquals(fix(3, 5.0), c.offer(fix(3, 5.0), "gps"))
    }

    @Test
    fun aLessAccurateFixFromAnotherProviderLoses() {
        val c = FixChooser()
        c.offer(fix(0, 5.0), "gps")
        assertNull(c.offer(fix(2, 60.0), "network"))
        // From the same provider a newer, somewhat worse fix still wins: the phone moved into an urban canyon.
        assertEquals(fix(3, 30.0), c.offer(fix(3, 30.0), "gps"))
    }

    @Test
    fun aMuchNewerFixAlwaysWinsAndAMuchOlderOneNever() {
        val c = FixChooser()
        c.offer(fix(0, 5.0), "gps")
        assertEquals(fix(30, 500.0), c.offer(fix(30, 500.0), "network"))
        assertNull(c.offer(fix(1, 1.0), "gps"))
    }
}

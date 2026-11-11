package app.atlas.route

import app.atlas.geo.BBox
import app.atlas.geo.Geo
import app.atlas.geo.LatLon
import app.atlas.geo.Polyline
import app.atlas.model.Mode
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Round walks on the real streets of Amsterdam, where the routing data is on this machine: from the middle of town, from
 * a park's edge, beside a small park, beside the IJ. The parks' boxes are as near as a map shows them.
 */
class RoundWalkRealTest {
    @Before fun data() = RealData.assumeAmsterdam()

    private val parks = listOf(
        Green(BBox(4.8600, 52.3835, 4.8790, 52.3885), "Westerpark"),
        Green(BBox(4.8555, 52.3550, 4.8815, 52.3610), "Vondelpark"),
        Green(BBox(4.9175, 52.3585, 4.9260, 52.3625), "Oosterpark"),
        Green(BBox(4.8935, 52.3535, 4.8990, 52.3560), "Sarphatipark"),
        Green(BBox(4.8510, 52.3760, 4.8600, 52.3800), "Erasmuspark"),
        Green(BBox(4.8440, 52.3620, 4.8560, 52.3720), "Rembrandtpark"),
        Green(BBox(4.9130, 52.3930, 4.9230, 52.3990), "Noorderpark"),
    )

    private val vondel = LatLon(52.3600, 4.8790)
    private val pijp = LatLon(52.3545, 4.8960)
    private val oost = LatLon(52.3640, 4.9220)
    private val noord = LatLon(52.3900, 4.9180)

    private fun walk(at: LatLon, minutes: Int, greens: List<Green> = parks): RoundWalk = runBlocking {
        when (val r = RoundWalks.find(RealData.router(), at, minutes, greens)) {
            is RoundWalks.Found -> r.walk
            is RoundWalks.Failed -> throw AssertionError("$minutes min from $at: ${r.why.message}")
        }
    }

    private fun twice(w: RoundWalk): Double {
        val line = Polyline(w.route.points)
        return RoundWalks.twice((0..(line.length / 15).toInt()).map { line.pointAt(it * 15.0) })
    }

    @Test
    fun everyWalkIsARoundOfAboutTheTimeAskedFor() {
        for (at in listOf(RealData.WESTERKERK, vondel, pijp, oost, noord, RealData.CENTRAAL)) for (minutes in listOf(20, 40, 60)) {
            val w = walk(at, minutes)
            val took = w.route.duration / 60
            assertTrue("$minutes min from $at took $took", took in minutes * 0.8..minutes * 1.2)
            assertTrue("$at: ${w.route.points.first()} is the start", Geo.distance(at, w.route.points.first()) < 80)
            assertTrue("$at: ${w.route.points.last()} is the end", Geo.distance(at, w.route.points.last()) < 80)
            assertEquals(Mode.WALK, w.request.mode)
            assertEquals(at, w.request.waypoints.first())
            assertEquals(at, w.request.waypoints.last())
            assertTrue("$minutes min from $at walks ${twice(w)} of it twice", twice(w) < 0.3)
        }
    }

    @Test
    fun aParkInReachIsWalkedThrough() {
        assertEquals("Vondelpark", walk(vondel, 60).through)
        assertEquals("Noorderpark", walk(noord, 40).through)
        assertEquals("Westerpark", walk(RealData.WESTERKERK, 60).through)
        assertEquals("Oosterpark", walk(oost, 40).through)
        assertNotNull(walk(pijp, 20).through)
    }

    @Test
    fun noParkInReachClaimsNone() {
        assertNull(walk(RealData.CENTRAAL, 20).through)
        assertNull(walk(vondel, 40, emptyList()).through)
    }
}

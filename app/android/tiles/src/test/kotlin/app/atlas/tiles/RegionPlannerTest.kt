package app.atlas.tiles

import app.atlas.geo.BBox
import app.atlas.geo.LatLon
import app.atlas.geo.Mercator
import app.atlas.geo.TileId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RegionPlannerTest {
    private val amsterdam = BBox(4.75, 52.30, 5.02, 52.43)

    @Test
    fun countsTilesPerZoom() {
        val plan = RegionPlanner.plan(amsterdam, 0, 14)
        for (z in 0..14) assertEquals(Mercator.tilesIn(amsterdam, z).size.toLong(), plan.tilesAt(z)!!.count)
        assertEquals((0..14).sumOf { Mercator.tilesIn(amsterdam, it).size.toLong() }, plan.tileCount)
        assertEquals(plan.tileCount, plan.tiles().count().toLong())
        assertTrue(Fixtures.CENTRE in plan)
        assertFalse(TileId(14, 0, 0) in plan)
        // Low zooms first, so a half-done download is already a map.
        assertEquals(0, plan.tiles().first().z)
        assertTrue(plan.tiles().zipWithNext().all { (a, b) -> a.z <= b.z })
    }

    @Test
    fun estimatesFromTheFixtures() {
        // The densest zoom is calibrated on the nine real z14 tiles, stored gzipped.
        val measured = Fixtures.z14.map { Gzip.compress(Fixtures.bytes(it)).size.toLong() }.average()
        val dense = RegionPlanner.typicalBytes(14, Density.DENSE_CITY)
        assertTrue("dense $dense vs measured $measured", dense > measured * 0.8 && dense < measured * 1.25)
        assertTrue(RegionPlanner.typicalBytes(14, Density.RURAL) < RegionPlanner.typicalBytes(14, Density.CITY))
        // Low zooms hardly depend on density.
        assertEquals(RegionPlanner.typicalBytes(5, Density.RURAL), RegionPlanner.typicalBytes(5, Density.DENSE_CITY))
        val plan = RegionPlanner.plan(amsterdam, 0, 14, Density.CITY)
        val mb = plan.estimatedBytes / 1e6
        // Greater Amsterdam to z14: a few hundred tiles, tens of megabytes.
        assertTrue("estimate $mb MB for ${plan.tileCount} tiles", mb in 20.0..200.0)
        assertTrue(plan.estimatedBytes > RegionPlanner.plan(amsterdam, 0, 14, Density.RURAL).estimatedBytes)
    }

    @Test
    fun corridorFollowsTheLine() {
        // Amsterdam to Haarlem, roughly along the canal.
        val line = listOf(LatLon(52.3745, 4.8838), LatLon(52.3800, 4.7500), LatLon(52.3800, 4.6400))
        val corridor = RegionPlanner.planCorridor(line, 500.0, 10, 14)
        val box = RegionPlanner.plan(BBox.of(line)!!.buffer(500.0), 10, 14)
        assertTrue(corridor.tileCount <= box.tileCount)
        for (p in line) assertTrue(Mercator.tileAt(p, 14) in corridor)
        // A point 5 km north of the line is not in the corridor.
        assertFalse(Mercator.tileAt(LatLon(52.43, 4.75), 14) in corridor)
        val z14 = corridor.tilesAt(14)!!.tiles().toList()
        assertEquals(z14.size.toLong(), corridor.tilesAt(14)!!.count)
        assertTrue(z14.size in 15..60)
    }
}

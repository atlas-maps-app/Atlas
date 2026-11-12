package app.atlas.packer.heights

import app.atlas.rendertiles.model.Heights
import app.atlas.tiles.WorldIndex
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * A region's terrain built from two stand-in sources (ENGINE.md part 6, step 1), with no host and no GDAL: a national
 * model, a smooth hill over the land west of a coast with a pond its fill closes and a lake it does not, and a world
 * model that stands 30 m above it. The tiles hold the national model's heights within half a step, the pond filled
 * from its banks, the lake from the world model feathered onto the national one, the open sea at 0 m; a level whose
 * source is no finer than the level above is left to it; every tile reads back, every apron is its neighbour's own,
 * and the same build gives the same bytes.
 */
class HeightsBuildTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private fun registry(read: Double) = TerrainSources.parse(
        """{"sources": [
          {"id": "land", "name": "A national model", "area": ["somewhere"], "priority": 100, "format": "wcs", "hosts": ["https://example.invalid/wcs"],
           "wcs": {"coverage": "c", "axes": ["E", "N"], "block": 1000}, "crs": "EPSG:32631", "datum": "its own", "model": "dtm",
           "resolution_m": 1, "read_m": $read, "fill_m": 60, "feather_m": 200, "licence": "CC0", "credit": "The national model", "read": "2026-11-12"},
          {"id": "world", "name": "A world model", "area": "world", "priority": 10, "format": "cog-tiles", "hosts": ["https://example.invalid/"], "pattern": "{NS}{lat:02}{EW}{lon:03}.tif",
           "crs": "EPSG:4326", "datum": "EGM2008", "model": "dsm", "resolution_m": 30, "read_m": 30, "licence": "its own", "credit": "The world model", "notice": "No liability.", "read": "2026-11-12"}],
          "regions": [{"id": "somewhere", "sources": ["land", "world"], "levels": [{"levels": [13, 14], "area": "whole"}], "check_views": []}]}""",
    )

    // the land lies west of the coast; the pond and the lake are holes in the national model
    private val coast = 4.6000
    private val pond = doubleArrayOf(52.4005, 4.5700, 40.0) // lat, lon, radius in metres
    private val lake = doubleArrayOf(52.3920, 4.5800, 420.0)

    private fun metres(lat: Double, lon: Double, of: DoubleArray): Double {
        val dy = (lat - of[0]) * 111_320.0
        val dx = (lon - of[1]) * 111_320.0 * Math.cos(Math.toRadians(of[0]))
        return Math.sqrt(dx * dx + dy * dy)
    }

    private fun hill(lat: Double, lon: Double): Double = 20.0 + 15.0 * Math.sin((lon - 4.5) * 400.0) * Math.cos((lat - 52.4) * 500.0)

    private val national = NodeSource { w ->
        var any = false
        FloatArray(w.size) { i ->
            val lat = w.lattice.lat((w.y0 + i / w.width).toDouble())
            val lon = w.lattice.lon((w.x0 + i % w.width).toDouble())
            if (lon >= coast || metres(lat, lon, pond) < pond[2] || metres(lat, lon, lake) < lake[2]) Float.NaN else hill(lat, lon).toFloat().also { any = true }
        }.takeIf { any }
    }

    // the world model: the hill 30 m higher (a surface model over a forest), the sea east of 4.605° without a sample
    private val world = NodeSource { w ->
        FloatArray(w.size) { i ->
            val lat = w.lattice.lat((w.y0 + i / w.width).toDouble())
            val lon = w.lattice.lon((w.x0 + i % w.width).toDouble())
            if (lon >= 4.605) Float.NaN else (hill(lat, lon) + 30.0).toFloat()
        }
    }

    private val box = doubleArrayOf(4.56, 52.385, 4.66, 52.405)

    private fun build(dir: File, read: Double = 1.0): HeightsBuild.Built {
        val r = registry(read)
        val region = r.region("somewhere")
        val plan = HeightsBuild.plan(r, region, box, tmp.root, null, null)
        val sources = listOf(HeightsBuild.Read(r.source("land"), national, listOf(doubleArrayOf(4.0, 52.0, coast, 53.0))), HeightsBuild.Read(r.source("world"), world, null))
        return HeightsBuild.build("somewhere", plan, sources, dir, File(dir, "work"), 2) {}
    }

    @Test
    fun aRegionsTerrainHoldsItsSourcesHeightsAndReadsBack() {
        val dir = tmp.newFolder("out")
        val built = build(dir)
        val index = WorldIndex.read(File(dir, "somewhere-terrain.idx").readBytes())
        assertEquals(built.index.entries.size, index.entries.size)
        assertTrue(index.entries.all { it.z == HeightsBuild.BLOCK && it.firstLevel == 13 && it.lastLevel == 14 })
        assertEquals(index.entries.sumOf { it.tiles }, built.tiles.size)
        assertTrue(built.tiles.any { it.level == 13 } && built.tiles.any { it.level == 14 })
        // no tile east of the coast's tiles: a window the national model does not reach holds none
        assertTrue(built.tiles.all { Heights.lonOf(it.level, it.x, 0, 2) < coast })

        val terrain = HeightsCheck.Terrain(index, dir)
        assertEquals(emptyList<String>(), HeightsCheck.tiles(terrain, null) {})
        val log = ArrayList<String>()
        assertEquals(emptyList<String>(), HeightsCheck.aprons(terrain, log::add))
        assertTrue(log.single(), Regex("(\\d+) apron and edge samples of (\\d+) pairs").find(log.single())!!.groupValues.let { it[1].toLong() > 100_000 && it[2].toInt() >= 8 })

        // a sample of the land, the pond, the lake's middle, its shore, and the sea
        fun at(level: Int, lat: Double, lon: Double): Triple<Double, app.atlas.rendertiles.model.HeightRaster, Pair<Double, Double>> {
            val tile = Lattice(level, 2)
            val tx = Math.floor(tile.x(lon)).toInt()
            val ty = Math.floor(tile.y(lat)).toInt()
            val r = assertNotNull(terrain.raster(level, tx, ty)).let { terrain.raster(level, tx, ty)!! }
            val l = Lattice(level, r.side)
            val c = (Math.rint(l.x(lon)).toLong() - tx.toLong() * l.per).toInt()
            val row = (Math.rint(l.y(lat)).toLong() - ty.toLong() * l.per).toInt()
            return Triple(r.metres(row, c), r, l.lat((ty.toLong() * l.per + row).toDouble()) to l.lon((tx.toLong() * l.per + c).toDouble()))
        }
        for (level in listOf(13, 14)) {
            val (h, r, p) = at(level, 52.3990, 4.5650)
            assertEquals(257, r.side)
            assertEquals(8, r.apron)
            assertEquals("L$level land", hill(p.first, p.second), h, r.step / 2 + 1e-4)
            assertEquals(listOf("land", "world").take(r.sources.size), r.sources)
        }
        // the pond is closed from its banks: near the hill's own height there, the national model's, and marked filled
        val (pondH, pondR, pondAt) = at(14, pond[0], pond[1])
        assertEquals(hill(pondAt.first, pondAt.second), pondH, 2.5)
        assertTrue((pondR.flags and Heights.FLAG_FILLED) != 0)
        // the lake's middle, beyond the fill and the feather, is the world model's; its shore meets the land without a step
        val (lakeH, _, lakeAt) = at(14, lake[0], lake[1])
        assertEquals(hill(lakeAt.first, lakeAt.second) + 30.0, lakeH, 0.1)
        val shore = lake[1] + 421.0 / (111_320.0 * Math.cos(Math.toRadians(lake[0])))
        var previous = at(14, lake[0], shore).first
        var lon = shore
        var largest = 0.0
        repeat(70) {
            lon -= 8.0 / (111_320.0 * Math.cos(Math.toRadians(lake[0])))
            val h = at(14, lake[0], lon).first
            largest = maxOf(largest, Math.abs(h - previous))
            previous = h
        }
        assertTrue("a step of $largest m from the land across the lake's shore", largest < 4.0)
        // the open sea beyond the world model stands at 0 m, in a tile that says so
        val seaTile = built.tiles.filter { it.level == 14 }.maxBy { it.x }
        val sea = terrain.raster(14, seaTile.x, seaTile.y)!!
        assertTrue(Heights.lonOf(14, seaTile.x, 256, 257) > 4.61)
        assertTrue((sea.flags and Heights.FLAG_NODATA) != 0)
        assertEquals(0.0, sea.metres(100, 256), 0.0)
        assertTrue(sea.shares.sum() < sea.samples.size)
        // the numbers: every tile's bytes, each source's megabytes per 1,000 km²
        val stats = HeightsBuild.stats("somewhere", built, null, built.sources, 1.0)
        assertTrue(stats.toString(), stats.toString().contains("\"mb_per_1000_km2\""))
        assertTrue(built.tiles.all { it.stored > 0 })

        // the same build again: the same bytes
        val again = tmp.newFolder("again")
        build(again)
        for (p in index.parts) assertArrayEquals(p.name, File(dir, p.name).readBytes(), File(again, p.name).readBytes())
        assertArrayEquals(File(dir, "somewhere-terrain.idx").readBytes(), File(again, "somewhere-terrain.idx").readBytes())
    }

    @Test
    fun aLevelNoFinerThanTheOneAboveIsLeftToIt() {
        // a national model read at 10 m: L13's samples are 12 m apart, L14's would be 6 m
        val built = build(tmp.newFolder("coarse"), read = 10.0)
        assertTrue(built.tiles.isNotEmpty() && built.tiles.all { it.level == 13 })
        assertEquals(1, built.skipped.size)
        assertTrue(built.skipped.single(), built.skipped.single().startsWith("L14"))
        assertNull(HeightsCheck.Terrain(built.index, File(tmp.root, "coarse")).raster(14, 8401 * 2, 5380 * 2))
    }
}

package app.atlas.packer.world

import app.atlas.packer.pbf.PbfReader
import app.atlas.rendertiles.Format3Reader
import app.atlas.rendertiles.model.Layer
import app.atlas.rendertiles.model.Profile
import app.atlas.tiles.WorldIndex
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.locationtech.jts.geom.Coordinate
import org.locationtech.jts.geom.GeometryFactory
import java.io.File
import java.security.MessageDigest

/**
 * The packer's `world` (ENGINE.md step 5) on Monaco's extract (the test's resources) and a stand-in for osmdata's land
 * polygons: Planetiler in-process with the world's profile, the land under it, the tiles cut at their cells and
 * written as format 3, a fine pack for the L6 block and a coarse one for its L3 block, end to end in their parts, and
 * the index. Every pack's bytes are its entry's (its SHA-256), its head the world's, every tile in its block and found
 * by the index, and every tile's ground covers its cells exactly; Monaco's land cover, roads and places are there. With
 * the core's stage P tool (-Datlas.stagep, as CI's unit-rest job gives it), the core reads every pack as its store
 * does, a range of its part, verifies it and decodes every tile to the same canonical hash as the packer's tile.
 */
class WorldTilesTest {
    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun monacosBlockIsBuiltIntoPacksAndAnIndexWhoseEveryTileCoversItsCells() {
        val pbf = File(javaClass.getResource("/monaco-2026-10-13.osm.pbf")!!.toURI())
        // the land north of a straight coast at 43.725° N, and an island off it, in osmdata's EPSG:4326 layer
        val f = GeometryFactory()
        fun box(w: Double, s: Double, e: Double, n: Double) = f.createPolygon(arrayOf(Coordinate(w, s), Coordinate(w, n), Coordinate(e, n), Coordinate(e, s), Coordinate(w, s)))
        val land = File(tmp.root, "land-polygons-split-4326.zip")
        ShapefileWriter.zip(land, "land_polygons", listOf(ShapefileWriter.Record(box(7.30, 43.725, 7.60, 43.90)), ShapefileWriter.Record(box(7.45, 43.70, 7.46, 43.71))))
        val out = tmp.newFolder("world")
        val scratch = File(tmp.root, "scratch")
        val built = WorldTiles.build(pbf, land, out, scratch, 2, listOf(33, 23, 33, 23), false, ::println)
        assertEquals(0.0, built.worstCover, 0.0)
        assertEquals(0, built.ringsOutside)
        assertEquals((6..11).toList(), built.levels.keys.toList())
        assertFalse("the MBTiles is not kept", File(scratch, "world.mbtiles").exists())

        val index = WorldIndex.read(File(out, "world.idx").readBytes())
        assertEquals(PbfReader(pbf).header.replicationTime, index.date)
        assertEquals(listOf("world-mid.000", "world-coarse.000"), index.parts.map { it.name })
        assertEquals(listOf(Triple(3, 4, 2), Triple(6, 33, 23)), index.entries.map { Triple(it.z, it.x, it.y) })
        val kinds = HashMap<Layer, MutableSet<String>>()
        var tiles = 0
        var off = 0
        for (e in index.entries) {
            val part = File(out, index.parts[e.part].name)
            assertEquals(index.parts[e.part].bytes, part.length())
            val bytes = part.readBytes().copyOfRange(e.offset.toInt(), (e.offset + e.length).toInt())
            assertArrayEquals(MessageDigest.getInstance("SHA-256").digest(bytes), e.sha256)
            val pack = Format3Reader(bytes)
            assertEquals(Profile.WORLD_BASE, pack.head.profile)
            assertEquals((e.lastLevel downTo e.firstLevel).toList(), pack.head.levels.toList())
            assertEquals(e.tiles, pack.chunks.size)
            for (t in pack.tiles()) {
                tiles++
                assertSame("${t.level}/${t.x}/${t.y}", e, index.holding(t.level, t.x, t.y))
                Covers.assertCovers(t)
                off += Covers.offLattice(t)
                for ((layer, list) in t.layers) {
                    for (feature in list) (feature["kind"] ?: feature["hw"])?.let { kinds.getOrPut(layer) { HashSet() } += it.toString() }
                }
            }
        }
        assertEquals(built.levels.values.sumOf { it.tiles }, tiles)
        // every ring's vertex on its tile's lattice, but where a cell's edge cuts
        assertEquals(0, off)

        // the core reads each pack as its store does, a range of its part, and decodes every tile as the packer wrote it
        for (e in index.entries) {
            val part = File(out, index.parts[e.part].name)
            Covers.assertCoreReads(part, e.offset, e.length, (e.lastLevel downTo e.firstLevel).toList())
        }
        // Monaco's land cover, its roads by class, its places
        println("world kinds: $kinds")
        assertTrue(kinds[Layer.GROUND]!!.contains("land"))
        assertTrue(kinds[Layer.GROUND]!!.any { it in StagePTags.STAGE_P.land })
        assertTrue(kinds[Layer.LABEL]!!.isNotEmpty())
        assertTrue(kinds[Layer.LINE]!!.isNotEmpty() || kinds[Layer.ROAD]!!.isNotEmpty())
    }
}

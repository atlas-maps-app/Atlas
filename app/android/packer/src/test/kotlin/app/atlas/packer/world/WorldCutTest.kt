package app.atlas.packer.world

import app.atlas.rendertiles.Format3Reader
import app.atlas.rendertiles.codec.Format3Writer
import app.atlas.rendertiles.model.Layer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.locationtech.jts.geom.Coordinate
import org.locationtech.jts.geom.Geometry
import org.locationtech.jts.geom.GeometryFactory
import org.locationtech.jts.geom.Polygon
import java.math.BigInteger

/**
 * The world's cut (ENGINE.md step 5: "their geometry is cut at their 4 × 4 cells"): in every cell of a tile the land
 * under everything and the sea fill the cell exactly on the grid, whatever the coast, its lakes and islands, every
 * ring, line and point lies in its cell's box, and every ring's vertex on the tile's lattice but where a cell's edge
 * cuts; land cover, roads and places are cut at the cells and keep their kinds; open sea with nothing in it has no tile.
 */
class WorldCutTest {
    private val f = GeometryFactory()

    /** A ring in the tile's pixels (256 a side, y down) from x, y pairs. */
    private fun ring(vararg xy: Double): Array<Coordinate> = Array(xy.size / 2 + 1) { i -> val k = (i % (xy.size / 2)) * 2; Coordinate(xy[k], xy[k + 1]) }

    private fun polygon(shell: Array<Coordinate>, vararg holes: Array<Coordinate>): Polygon =
        f.createPolygon(f.createLinearRing(shell), holes.map { f.createLinearRing(it) }.toTypedArray())

    /** Amsterdam's L9 tile. */
    private val z = 9
    private val x = 262
    private val y = 168

    /** A coast across the tile with a lake holding an island, crossing the cells' edges, and an island off it. */
    private val land: Geometry = f.createMultiPolygon(
        arrayOf(
            polygon(
                ring(0.0, 0.0, 150.0, 0.0, 140.5, 60.25, 171.0, 128.0, 119.75, 200.0, 160.0, 256.0, 0.0, 256.0),
                ring(20.0, 20.0, 20.0, 100.0, 100.0, 100.0, 100.0, 20.0),
            ),
            polygon(ring(50.0, 50.0, 70.0, 50.0, 70.0, 70.0, 50.0, 70.0)), // in the lake
            polygon(ring(200.0, 200.0, 240.0, 200.0, 240.0, 240.0, 200.0, 240.0)),
            polygon(ring(250.5, 3.0, 256.0, 3.0, 256.0, 9.0, 250.5, 9.0)), // on the tile's east edge
        ),
    )

    @Test
    fun everyCellIsFilledExactlyByItsLandAndSeaAndNothingCrossesItsEdge() {
        val cut = WorldCut().cut(z, x, y, emptyList(), land)
        val tile = cut.tile!!
        assertEquals(0L, cut.cover.worstDifference)
        assertEquals(0, cut.cover.ringsOutside)
        Covers.assertCovers(tile)
        // every ring's vertex on the lattice (L9: 512 units of longitude, 512 of latitude at 52° N), but where a cell's
        // edge cuts
        assertEquals(listOf(512, 512), WorldGeometry.lattice(z, y).toList())
        assertEquals(0, Covers.offLattice(tile))
        val base = tile.layers[Layer.GROUND]!!.filter { Covers.source(it) == WorldCut.LAND_BASE }
        assertTrue(base.all { it["kind"] == "land" && it["ground_key"] == null })
        val sea = tile.layers[Layer.WATER]!!
        assertTrue(sea.all { it["tidal"] == true && it["shore"] == true })
        // the lake's island and the island off the coast are land; the cells wholly land or sea have one ring each
        assertTrue(base.size > 16)
        // the tile reads back as written
        val back = Format3Reader.decodeTile(Format3Writer.encoded(tile).raw)
        assertEquals(tile.layers.mapValues { it.value.size }, back.layers.mapValues { it.value.size })
    }

    @Test
    fun theSeaAloneIsNoTileAndTheSeaWithAPlaceFillsEveryCell() {
        assertNull(WorldCut().cut(z, x, y, emptyList(), null).tile)
        val place = WorldCut.Item(WorldProfile.PLACE, mapOf("kind" to "town", "name" to "Zaandam", "population" to "78000"), f.createPoint(Coordinate(100.0, 30.0)))
        val cut = WorldCut().cut(z, x, y, listOf(place), null)
        val tile = cut.tile!!
        Covers.assertCovers(tile)
        assertEquals(16, tile.layers[Layer.WATER]!!.size)
        val label = tile.layers[Layer.LABEL]!!.single()
        assertEquals("Zaandam", label["name"])
        assertEquals("town", label["kind"])
        assertEquals(1, (label["source"] as Number).toInt())
    }

    @Test
    fun landCoverRoadsAndRiversAreCutAtTheCellsAndKeepTheirKinds() {
        val items = listOf(
            WorldCut.Item(WorldProfile.LAND, mapOf("kind" to "farmland"), polygon(ring(10.0, 110.0, 120.0, 110.0, 120.0, 250.0, 10.0, 250.0))),
            WorldCut.Item(WorldProfile.LAND, mapOf("kind" to "no_such_kind"), polygon(ring(10.0, 10.0, 15.0, 10.0, 15.0, 15.0, 10.0, 15.0))),
            WorldCut.Item(WorldProfile.ROAD, mapOf("hw" to "secondary"), f.createLineString(arrayOf(Coordinate(5.0, 5.0), Coordinate(250.0, 250.0)))),
            WorldCut.Item(WorldProfile.BIG, mapOf("hw" to "motorway"), f.createLineString(arrayOf(Coordinate(5.0, 140.0), Coordinate(250.0, 141.0)))),
            WorldCut.Item(WorldProfile.RIVER, emptyMap(), f.createLineString(arrayOf(Coordinate(128.0, 5.0), Coordinate(129.0, 250.0)))),
        )
        val cut = WorldCut().cut(z, x, y, items, land)
        val tile = cut.tile!!
        assertEquals(0L, cut.cover.worstDifference)
        assertEquals(0, cut.cover.ringsOutside)
        Covers.assertCovers(tile)
        assertEquals(0, Covers.offLattice(tile))
        val cover = tile.layers[Layer.GROUND]!!.filter { Covers.source(it) == 1 }
        // the farmland over 2 by 3 cells, each piece its own; a kind stage P has not, left out
        assertEquals(setOf("farmland"), cover.map { it["kind"] }.toSet())
        assertEquals(6, cover.map { it.cell }.toSet().size)
        // the road across the diagonal's four cells and their corners' neighbours it touches; the motorway across a row
        val roads = tile.layers[Layer.ROAD]!!
        assertTrue(roads.size >= 4 && roads.all { it["hw"] == "secondary" && it["wguess"] == true })
        val lines = tile.layers[Layer.LINE]!!
        assertEquals(setOf(2 to "motorway", WorldCut.RIVERS to null), lines.map { Covers.source(it) to it["hw"] }.toSet())
        assertEquals(4, lines.count { Covers.source(it) == 2 })
    }

    @Test
    fun aCellOfManyLakesAndIslandsIsPartedExactly() {
        // a cell's worth of land with a grid of lakes, each holding an island: the land and the sea share every edge
        val box = WorldGeometry.cellBoxes(z, x, y)[5]
        val w = box[1].toDouble()
        val s = box[0].toDouble()
        val cw = (box[3] - box[1]).toDouble()
        val ch = (box[2] - box[0]).toDouble()
        val lakes = ArrayList<Array<Coordinate>>()
        val islands = ArrayList<Polygon>()
        for (i in 0 until 5) for (j in 0 until 5) {
            val x0 = w + cw * (0.1 + 0.17 * i)
            val y0 = s + ch * (0.1 + 0.17 * j)
            lakes += arrayOf(Coordinate(x0, y0), Coordinate(x0, y0 + ch * 0.12), Coordinate(x0 + cw * 0.12, y0 + ch * 0.12), Coordinate(x0 + cw * 0.12, y0), Coordinate(x0, y0))
            islands += polygon(arrayOf(Coordinate(x0 + cw * 0.04, y0 + ch * 0.04), Coordinate(x0 + cw * 0.08, y0 + ch * 0.04), Coordinate(x0 + cw * 0.08, y0 + ch * 0.08), Coordinate(x0 + cw * 0.04, y0 + ch * 0.08), Coordinate(x0 + cw * 0.04, y0 + ch * 0.04)))
        }
        val shell = arrayOf(Coordinate(w - cw, s - ch), Coordinate(w + 2 * cw, s - ch), Coordinate(w + 2 * cw, s + 2 * ch), Coordinate(w - cw, s + 2 * ch), Coordinate(w - cw, s - ch))
        val grid = f.createMultiPolygon((listOf(polygon(shell, *lakes.toTypedArray())) + islands).toTypedArray())
        val (landRings, seaRings) = WorldCut.partition(grid, box)
        var sum = BigInteger.ZERO
        for (g in landRings + seaRings) {
            sum += Covers.twice(g)
            for (k in g.lat.indices) assertTrue(g.lat[k] in box[0]..box[2] && g.lon[k] in box[1]..box[3])
        }
        assertEquals(BigInteger.valueOf(2L * (box[2] - box[0]).toLong() * (box[3] - box[1]).toLong()), sum)
        assertTrue(seaRings.size >= 25)
    }

    @Test
    fun theWatersNamesFollowThePlacesByRankAndTheRiversByTheirLengthOnceATile() {
        fun point(px: Double, py: Double) = f.createPoint(Coordinate(px, py))
        fun line(vararg xy: Double) = f.createLineString(Array(xy.size / 2) { Coordinate(xy[2 * it], xy[2 * it + 1]) })
        val items = listOf(
            WorldCut.Item(WorldProfile.PLACE, mapOf("kind" to "town", "name" to "Zaandam", "population" to "78000", "id" to 1L), point(100.0, 30.0)),
            WorldCut.Item(WorldProfile.WATER_NAME, mapOf("kind" to "lake", "name" to "Sloterplas", "id" to 2L, "area" to 900_000L), point(60.0, 200.0)),
            WorldCut.Item(WorldProfile.WATER_NAME, mapOf("kind" to "lake", "name" to "IJsselmeer", "id" to -3L, "area" to 1_100_000_000L), point(200.0, 40.0)),
            WorldCut.Item(WorldProfile.WATER_NAME, mapOf("kind" to "sea", "name" to "Noordzee", "id" to 4L, "area" to 0L), point(20.0, 20.0)),
            // in the tile's buffer, beyond its edge: the neighbour's
            WorldCut.Item(WorldProfile.WATER_NAME, mapOf("kind" to "bay", "name" to "Elsewhere", "id" to 5L, "area" to 0L), point(300.0, 20.0)),
            // a river in two pieces from beyond the north edge, the name at the middle of the longer, once
            WorldCut.Item(WorldProfile.RIVER_NAME, mapOf("name" to "Amstel"), line(128.0, -10.0, 128.0, 140.0)),
            WorldCut.Item(WorldProfile.RIVER_NAME, mapOf("name" to "Amstel"), line(128.0, 140.0, 130.0, 200.0)),
            WorldCut.Item(WorldProfile.RIVER_NAME, mapOf("name" to "Zaan"), line(10.0, 100.0, 60.0, 100.0)),
        )
        val tile = WorldCut().cut(z, x, y, items, land).tile!!
        val labels = tile.layers[Layer.LABEL]!!
        fun source(l: app.atlas.rendertiles.model.Feature) = (l["source"] as Number).toInt()
        val bySource = labels.groupBy(::source).mapValues { (_, l) -> l.sortedBy { (it["index"] as Number).toLong() }.map { it["name"] } }
        assertEquals(
            mapOf(1 to listOf("Zaandam"), WorldCut.WATER_NAMES to listOf("Noordzee", "IJsselmeer", "Sloterplas"), WorldCut.RIVER_NAMES to listOf("Amstel", "Zaan")),
            bySource,
        )
        // a water's kind, id and area (count), at its point; none of a place's tags
        val ij = labels.single { it["name"] == "IJsselmeer" }
        assertEquals("lake", ij["kind"])
        assertEquals(-3L, (ij["id"] as Number).toLong())
        assertEquals(1_100_000_000L, (ij["count"] as Number).toLong())
        assertEquals(ij["pos"], ij["prev"])
        assertNull(ij[app.atlas.rendertiles.model.Schema.tag("place")])
        // a river's name at the middle of its longest piece in the tile, prev before it along its run (south here), its
        // length in the tile in metres at the name's latitude
        val amstel = labels.single { it["name"] == "Amstel" }
        val pos = amstel["pos"] as app.atlas.rendertiles.model.GridPoint
        val prev = amstel["prev"] as app.atlas.rendertiles.model.GridPoint
        assertEquals(WorldGeometry.latOfPixel(z, y, 70.0).toInt(), pos.lat)
        assertEquals(WorldGeometry.lonOfPixel(z, x, 128.0).toInt(), pos.lon)
        assertEquals(pos.lon, prev.lon)
        assertEquals(WorldGeometry.latOfPixel(z, y, 70.0 - WorldCut.NAME_HALF).toInt().toDouble(), prev.lat.toDouble(), 2.0)
        val perPixel = WorldProfile.EQUATOR_METRES * Math.cos(Math.toRadians(pos.lat / 1e7)) / (256.0 * (1 shl z))
        assertEquals((140.0 + Math.hypot(2.0, 60.0)) * perPixel, (amstel["count"] as Number).toDouble(), 2.0)
        assertEquals("river", amstel["kind"])
        // the sea alone with a water's name is a tile: its name has no other tile to be in
        val sea = WorldCut().cut(z, x, y, listOf(items[3]), null).tile!!
        assertEquals(16, sea.layers[Layer.WATER]!!.size)
        assertEquals(listOf("Noordzee"), sea.layers[Layer.LABEL]!!.map { it["name"] })
    }
}

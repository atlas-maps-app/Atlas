package app.atlas.packer.world

import app.atlas.packer.UsageException
import app.atlas.rendertiles.model.Grid
import app.atlas.tiles.WorldIndex
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.locationtech.jts.geom.Coordinate
import org.locationtech.jts.geom.GeometryFactory
import java.time.Instant

/**
 * world.yml's plan (`packer world-plan`): the L6 blocks an area meets, weighted and split into regions of about equal
 * weight, each a rectangle of blocks, every block in one region; their extracts' boxes and osmium's configurations.
 */
class WorldPlanTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val f = GeometryFactory()

    private fun box(w: Double, s: Double, e: Double, n: Double) = f.createPolygon(arrayOf(Coordinate(w, s), Coordinate(e, s), Coordinate(e, n), Coordinate(w, n), Coordinate(w, s)))

    private fun geojson(w: Double, s: Double, e: Double, n: Double, bytes: Long? = null, id: String = "x") =
        """{"type":"Feature","properties":{"id":"$id"${bytes?.let { ",\"bytes\":$it" } ?: ""}},"geometry":{"type":"Polygon","coordinates":[[[$w,$s],[$e,$s],[$e,$n],[$w,$n],[$w,$s]]]}}"""

    @Test
    fun theNetherlandsMeetsItsFourBlocks() {
        // the Netherlands' extract's box, 3.2° to 7.3° E and 50.7° to 53.7° N: the four blocks its world was built of
        assertEquals(listOf(32 to 20, 33 to 20, 32 to 21, 33 to 21), WorldPlan.blocksMeeting(box(3.2, 50.7, 7.3, 53.7)))
        // a multipolygon of two islands far apart meets only their blocks
        val two = WorldPlan.GeoJson.geometry(Json.parseToJsonElement("""{"type":"MultiPolygon","coordinates":[[[[4.8,52.3],[4.9,52.3],[4.9,52.4],[4.8,52.4],[4.8,52.3]]],[[[-16.0,28.0],[-15.9,28.0],[-15.9,28.1],[-16.0,28.1],[-16.0,28.0]]]]}"""))
        assertEquals(listOf(32 to 21, 29 to 26), WorldPlan.blocksMeeting(two))
    }

    @Test
    fun regionsShareTheWeightAndEveryBlockIsInOne() {
        // a field of 12 by 8 blocks, a dense middle (the Benelux and Germany of it) a hundred times as heavy
        val blocks = (16..23).flatMap { y -> (28..39).map { x -> x to y } }
        val w = blocks.associateWith { (x, y) -> if (x in 32..34 && y in 19..21) 100.0 else 1.0 }
        val parts = WorldPlan.split(blocks, w, 8)
        assertEquals(8, parts.size)
        assertEquals(blocks.toSet(), parts.flatten().toSet())
        assertEquals(blocks.size, parts.sumOf { it.size })
        val total = w.values.sum()
        for (p in parts) {
            val share = p.sumOf { w.getValue(it) } / total
            // a block is the smallest piece: the dense blocks weigh 100 of the total 984
            assertTrue("a region of %.3f of the weight".format(share), share in 0.02..0.25)
            // a rectangle: every block of its box is its own
            val inBox = blocks.filter { (x, y) -> x in p.minOf { it.first }..p.maxOf { it.first } && y in p.minOf { it.second }..p.maxOf { it.second } }
            assertEquals(inBox.toSet(), p.toSet())
        }
        // one block cannot be split further
        assertEquals(1, WorldPlan.split(listOf(33 to 21), mapOf((33 to 21) to 1.0), 4).size)
    }

    @Test
    fun theSizesSpreadOverTheirExtractsEachCountedOnce() {
        // two extracts side by side, a small one inside the east (Monaco in France) and one of the two put together
        // (Geofabrik's dach of Germany, Austria and Switzerland)
        val json = Json.parseToJsonElement(
            """{"type":"FeatureCollection","features":[${geojson(6.0, 49.0, 11.25, 52.0, 3000, "west")},${geojson(11.25, 49.0, 16.5, 52.0, 1000, "east")},""" +
                """${geojson(12.0, 50.0, 12.1, 50.1, 50, "inside")},${geojson(6.0, 49.0, 16.5, 52.0, 99999, "both")}]}""",
        )
        val lines = ArrayList<String>()
        val weights = WorldPlan.Weights.ofSizes(json, lines::add)
        // west lies in block 33/21 alone, east in 34/21; both's bytes are theirs, and east holds the one inside it,
        // which keeps its own 50 of east's 1000 in its corner of 34/21
        assertEquals(listOf("world-plan: left out as made of the others: both", "world-plan: less the extracts within them: east (1)"), lines)
        assertEquals(setOf(33 to 21, 34 to 21), weights.of.keys)
        assertEquals(3000.0, weights.of.getValue(33 to 21), 1e-6)
        assertEquals(1000.0, weights.of.getValue(34 to 21), 1e-6)
        // a run's index: each fine pack's bytes
        val index = WorldIndex(
            Instant.EPOCH, listOf(WorldIndex.Part("world-mid.000", 300)),
            listOf(
                WorldIndex.Entry(6, 32, 21, 9, 11, 0, 0, 100, 1, Grid.tileBox(6, 32, 21), ByteArray(32)),
                WorldIndex.Entry(6, 33, 21, 9, 11, 0, 100, 200, 1, Grid.tileBox(6, 33, 21), ByteArray(32)),
            ),
        )
        assertEquals(mapOf((32 to 21) to 100.0, (33 to 21) to 200.0), WorldPlan.Weights.ofIndex(index).of)
    }

    @Test
    fun anExtractReachingFurtherOutToSeaThanItsPartsKeepsOnlyItsBytesBeyondTheirs() {
        // west and east, and a region of the two whose polygon reaches six degrees further north, out to sea (Geofabrik's
        // us-south of its states): too little of it is theirs for it to be left out, so its parts keep their bytes and
        // it weighs only what it holds beyond them, over its whole polygon
        fun weights(regionBytes: Long, lines: MutableList<String> = ArrayList()) = WorldPlan.Weights.ofSizes(
            Json.parseToJsonElement(
                """{"type":"FeatureCollection","features":[${geojson(6.0, 49.0, 11.25, 52.0, 3000, "west")},${geojson(11.25, 49.0, 16.5, 52.0, 1000, "east")},""" +
                    """${geojson(6.0, 49.0, 16.5, 58.0, regionBytes, "region")}]}""",
            ),
            lines::add,
        )
        val lines = ArrayList<String>()
        val exact = weights(4000, lines)
        assertEquals(listOf("world-plan: less the extracts within them: region (2)"), lines)
        assertEquals(mapOf((33 to 21) to 3000.0, (34 to 21) to 1000.0), exact.of)
        // 300 bytes beyond its parts', spread over its six blocks: every byte counted once (each block's share by the
        // area of its piece, each piece's by the cosine of its own latitude: to a tenth of a percent of the 300)
        val more = weights(4300)
        assertEquals(setOf(33 to 19, 34 to 19, 33 to 20, 34 to 20, 33 to 21, 34 to 21), more.of.keys)
        assertEquals(4300.0, more.of.values.sum(), 0.3)
        assertTrue(more.of.getValue(33 to 21) in 3000.0..3100.0)
        assertTrue(more.of.getValue(33 to 19) in 1.0..100.0)
    }

    @Test
    fun aLastWorldOfAnotherExtractWeighsItsBlocksAsItsExtractsBytesBesideTheOthersSizes() {
        // the last world's packs (300 bytes) as its extract's 600, added to the other extracts' sizes
        val sizes = WorldPlan.Weights(mapOf((33 to 21) to 3000.0, (34 to 21) to 1000.0), "the extracts' sizes")
        val index = WorldPlan.Weights(mapOf((32 to 21) to 100.0, (33 to 21) to 200.0), "the last run's fine packs")
        val w = WorldPlan.Weights.combined(sizes, index, 600)
        assertEquals(mapOf((32 to 21) to 200.0, (33 to 21) to 3400.0, (34 to 21) to 1000.0), w.of)
        assertEquals("the last run's fine packs as 600 bytes, and the extracts' sizes", w.from)
        // both kinds of weights take the extract's bytes, and only they do
        for (args in listOf(listOf("--weights-index", "a.idx", "--weights-sizes", "s.geojson"), listOf("--weights-index", "a.idx", "--index-bytes", "600"), listOf("--weights-sizes", "s.geojson", "--index-bytes", "600"))) {
            assertThrows(UsageException::class.java) { WorldPlan.run(listOf("world-plan", "area.geojson", "out") + args) {} }
        }
        assertThrows(UsageException::class.java) { WorldPlan.run(listOf("world-plan", "area.geojson", "out", "--weights-index", "a.idx", "--weights-sizes", "s.geojson", "--index-bytes", "0")) {} }
    }

    @Test
    fun thePlanIsWrittenAsRegionsAndOsmiumsConfigurations() {
        val area = tmp.newFile("benelux.geojson").apply { writeText(geojson(2.5, 49.4, 7.3, 53.6)) }
        val out = tmp.newFolder("plan")
        val lines = ArrayList<String>()
        assertEquals(0, WorldPlan.run(listOf("world-plan", area.path, out.path, "--regions", "3", "--group", "2", "--margin", "0.25"), lines::add))
        println(lines.joinToString("\n"))
        val plan = Json.parseToJsonElement(out.resolve("regions.json").readText()).jsonObject
        assertEquals("benelux", plan.getValue("area").jsonPrimitive.content)
        assertEquals(4, plan.getValue("blocks").jsonPrimitive.int)
        val regions = plan.getValue("regions").jsonArray.map { it.jsonObject }
        assertEquals(listOf("r01", "r02", "r03"), regions.map { it.getValue("id").jsonPrimitive.content })
        val all = regions.flatMap { WorldTiles.parseBlocks(it.getValue("blocks").jsonPrimitive.content) }
        assertEquals(setOf(32 to 20, 33 to 20, 32 to 21, 33 to 21), all.toSet())
        assertEquals(4, all.size)
        assertEquals(1.0, regions.sumOf { it.getValue("weight").jsonPrimitive.double }, 1e-3)
        // each region's box is its blocks' and the margin
        for (r in regions) {
            val bs = WorldTiles.parseBlocks(r.getValue("blocks").jsonPrimitive.content).map { Grid.tileBox(6, it.first, it.second) }
            val b = r.getValue("box").jsonArray.map { it.jsonPrimitive.double }
            assertEquals(Grid.deg(bs.minOf { it[1] }) - 0.25, b[0], 1e-6)
            assertEquals(Grid.deg(bs.minOf { it[0] }) - 0.25, b[1], 1e-6)
            assertEquals(Grid.deg(bs.maxOf { it[3] }) + 0.25, b[2], 1e-6)
            assertEquals(Grid.deg(bs.maxOf { it[2] }) + 0.25, b[3], 1e-6)
        }
        // two passes of osmium extract: two regions, then one
        assertEquals(listOf(listOf("r01", "r02"), listOf("r03")), plan.getValue("groups").jsonArray.map { g -> g.jsonArray.map { it.jsonPrimitive.content } })
        val first = Json.parseToJsonElement(out.resolve("extract-1.json").readText()).jsonObject.getValue("extracts").jsonArray.map { it.jsonObject }
        assertEquals(listOf("scratch-r01.osm.pbf", "scratch-r02.osm.pbf"), first.map { it.getValue("output").jsonPrimitive.content })
        assertEquals(regions[0].getValue("box"), first[0].getValue("bbox"))
        assertTrue(out.resolve("extract-2.json").isFile)
        assertTrue(!out.resolve("extract-3.json").exists())
    }
}

package app.atlas.packer.world

import app.atlas.rendertiles.Format3Reader
import app.atlas.rendertiles.codec.Format3Writer
import app.atlas.rendertiles.model.Grid
import app.atlas.rendertiles.model.Reach
import app.atlas.tiles.WorldIndex
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.locationtech.jts.geom.Coordinate
import org.locationtech.jts.geom.GeometryFactory
import java.io.File
import java.security.MessageDigest
import java.time.Instant

/**
 * A world built in regions and put together (ENGINE.md step 5's world.yml: a job a region, then `world-assemble`)
 * holds the packs one run of all the regions' blocks makes, byte for byte: on Monaco's extract with the land of two L6
 * blocks of one L3 block (33/23, Monaco's coast, and 34/23, an island of the stand-in's), built at once and as two
 * regions, so the L3 block's coarse pack is made of both regions' tiles. The regions' fine packs stay in the parts they
 * named, the coarse packs go into the world's own, and the regions' numbers add up. Regions not of one run are refused.
 */
class WorldAssembleTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val source = WorldTiles.Source("monaco-latest.osm.pbf", "5b3c0e4f")

    private fun land(): File {
        val f = GeometryFactory()
        fun box(w: Double, s: Double, e: Double, n: Double) = f.createPolygon(arrayOf(Coordinate(w, s), Coordinate(w, n), Coordinate(e, n), Coordinate(e, s), Coordinate(w, s)))
        val land = File(tmp.root, "land-polygons-split-4326.zip")
        if (!land.isFile) {
            ShapefileWriter.zip(
                land, "land_polygons",
                listOf(ShapefileWriter.Record(box(7.30, 43.725, 7.60, 43.90)), ShapefileWriter.Record(box(7.45, 43.70, 7.46, 43.71)), ShapefileWriter.Record(box(12.0, 43.80, 12.1, 43.90))),
            )
        }
        return land
    }

    private val pbf get() = File(javaClass.getResource("/monaco-2026-10-13.osm.pbf")!!.toURI())

    @Test
    fun twoRegionsPutTogetherHoldTheSamePacksAsOneRunOfTheirBlocks() {
        val land = land()
        // one thread, as the order Planetiler's threads finish in may move a merged feature's vertices
        val one = tmp.newFolder("one")
        WorldTiles.build(pbf, land, one, File(tmp.root, "scratch-one"), 1, WorldTiles.Run(listOf(33 to 23, 34 to 23), source = source), false, ::println)
        val hand = tmp.newFolder("handed")
        val dirs = listOf("r01" to (33 to 23), "r02" to (34 to 23)).map { (id, block) ->
            val dir = tmp.newFolder(id)
            WorldTiles.build(pbf, land, dir, File(tmp.root, "scratch-$id"), 1, WorldTiles.Run(listOf(block), "world-mid-$id", File(dir, "coarse.tiles"), source), false, ::println)
            File(dir, "world.idx").copyTo(File(hand, "scratch-$id${WorldAssemble.INDEX}"))
            File(dir, "coarse.tiles").copyTo(File(hand, "scratch-$id${WorldAssemble.COARSE}"))
            File(dir, WorldTiles.STATS).copyTo(File(hand, "scratch-$id${WorldAssemble.STATS}"))
            dir
        }
        val out = tmp.newFolder("world")
        val a = WorldAssemble.assemble(out, listOf(File(hand, "scratch-r01"), File(hand, "scratch-r02")), ::println)

        val single = WorldIndex.read(File(one, "world.idx").readBytes())
        val read = WorldIndex.read(File(out, "world.idx").readBytes())
        assertEquals(single.date, read.date)
        assertEquals(listOf(Triple(3, 4, 2), Triple(6, 33, 23), Triple(6, 34, 23)), read.entries.map { Triple(it.z, it.x, it.y) })
        assertEquals(single.entries.map { Triple(it.z, it.x, it.y) }, read.entries.map { Triple(it.z, it.x, it.y) })
        for ((s, m) in single.entries.zip(read.entries)) {
            val what = "${m.z}/${m.x}/${m.y}"
            assertArrayEquals(what, s.sha256, m.sha256)
            assertEquals(what, s.length, m.length)
            assertEquals(what, s.tiles, m.tiles)
            assertEquals(what, s.firstLevel to s.lastLevel, m.firstLevel to m.lastLevel)
            assertArrayEquals(what, s.box, m.box)
        }
        // the coarse pack's box is both blocks', whose tiles it holds
        val coarse = read.entries.first { it.z == 3 }
        val b33 = Grid.tileBox(6, 33, 23)
        val b34 = Grid.tileBox(6, 34, 23)
        assertArrayEquals(intArrayOf(b33[0], b33[1], b33[2], b34[3]), coarse.box)
        // the regions' parts as they named them, then the world's coarse part, and every pack's bytes its entry's there
        assertEquals(listOf("world-mid-r01.000", "world-mid-r02.000", "world-coarse.000"), read.parts.map { it.name })
        val where = mapOf("world-mid-r01.000" to dirs[0], "world-mid-r02.000" to dirs[1], "world-coarse.000" to out)
        for (e in read.entries) {
            val part = File(where.getValue(read.parts[e.part].name), read.parts[e.part].name)
            assertEquals(read.parts[e.part].bytes, part.length())
            val bytes = part.readBytes().copyOfRange(e.offset.toInt(), (e.offset + e.length).toInt())
            assertArrayEquals(MessageDigest.getInstance("SHA-256").digest(bytes), e.sha256)
            val pack = Format3Reader(bytes)
            assertEquals(e.tiles, pack.chunks.size)
            for (t in pack.tiles()) assertEquals(e, read.holding(t.level, t.x, t.y))
        }
        // world-check finds every part at its length, of its packs end to end, and every pack of its SHA-256; a part
        // with a byte changed or cut short is not as the index says
        val fetched = tmp.newFolder("fetched")
        for ((name, dir) in where) File(dir, name).copyTo(File(fetched, name))
        assertEquals(emptyList<String>(), WorldCheck.check(read, fetched, null, 2, ::println))
        val damaged = File(fetched, "world-mid-r02.000")
        damaged.writeBytes(damaged.readBytes().also { it[it.size / 2] = (it[it.size / 2] + 1).toByte() })
        assertEquals(listOf("pack 6/34/23: its bytes are not of the SHA-256 the index names"), WorldCheck.check(read, fetched, null, 2, ::println))
        damaged.writeBytes(damaged.readBytes().copyOf(damaged.length().toInt() - 1))
        assertEquals(listOf("world-mid-r02.000: ${damaged.length()} bytes, the index gives ${damaged.length() + 1}"), WorldCheck.check(read, fetched, null, 2, ::println))
        // the coarse pack holds both regions' tiles
        val coarsePack = Format3Reader(File(out, "world-coarse.000").readBytes().copyOfRange(coarse.offset.toInt(), (coarse.offset + coarse.length).toInt()))
        val blocks = coarsePack.chunks.map { (it.x shr (it.z - 6)) to (it.y shr (it.z - 6)) }.toSet()
        assertEquals(setOf(33 to 23, 34 to 23), blocks)

        // the numbers add up: each level's tiles are the two regions', and so are its bytes
        val stats = Json.parseToJsonElement(File(out, WorldTiles.STATS).readText()).jsonObject
        val oneStats = Json.parseToJsonElement(File(one, WorldTiles.STATS).readText()).jsonObject
        assertEquals(2, stats.getValue("blocks").jsonPrimitive.int)
        for (z in 6..11) {
            val l = stats.getValue("levels").jsonObject.getValue("$z").jsonObject
            val o = oneStats.getValue("levels").jsonObject.getValue("$z").jsonObject
            for (k in listOf("tiles", "features", "stored", "raw")) assertEquals("L$z $k", o.getValue(k).jsonPrimitive.long, l.getValue(k).jsonPrimitive.long)
        }
        assertEquals(setOf("scratch-r01", "scratch-r02"), stats.getValue("regions").jsonObject.keys)

        // world.json: the release, the index's and the globe's sizes and hashes, the parts
        val globe = File(tmp.root, "world-globe.atlas").apply { writeBytes(ByteArray(1000) { it.toByte() }) }
        val json = Json.parseToJsonElement(WorldAssemble.worldJson(a, "world-20261103", "https://example.org/releases/download/world-20261103", File(out, "world.idx"), globe)).jsonObject
        assertEquals("world-20261103", json.getValue("release").jsonPrimitive.content)
        assertEquals("https://example.org/releases/download/world-20261103/", json.getValue("url").jsonPrimitive.content)
        assertEquals(File(out, "world.idx").length(), json.getValue("index").jsonObject.getValue("bytes").jsonPrimitive.long)
        assertEquals(WorldPack.sha256(globe), json.getValue("globe").jsonObject.getValue("sha256").jsonPrimitive.content)
        assertEquals(read.parts.map { it.name }, json.getValue("parts").jsonArray.map { it.jsonObject.getValue("name").jsonPrimitive.content })
        assertEquals(3, json.getValue("packs").jsonPrimitive.int)
        assertTrue(json.getValue("sources").jsonArray.first().jsonPrimitive.content.endsWith("monaco-latest.osm.pbf"))
    }

    /** A region's hand-off made up: its index of one fine pack (in a part of [part]) and a coarse tile of [block]. */
    private fun region(id: String, block: Pair<Int, Int>, part: String, facts: WorldTiles.Facts, date: Instant = Instant.ofEpochSecond(1_762_000_000)): File {
        val dir = File(tmp.root, "made-up").apply { mkdirs() }
        val sha = ByteArray(32)
        val box = Grid.tileBox(6, block.first, block.second)
        WorldIndex(date, listOf(WorldIndex.Part("$part.000", 100)), listOf(WorldIndex.Entry(6, block.first, block.second, 9, 11, 0, 0, 100, 1, box, sha)))
            .write(File(dir, "$id${WorldAssemble.INDEX}").outputStream())
        val tile = Format3Writer.Encoded(8, block.first * 4, block.second * 4, ByteArray(16), 0, Reach(box[0], box[1], box[2], box[3], 0), emptyMap())
        CoarseTiles.write(File(dir, "$id${WorldAssemble.COARSE}"), CoarseTiles.Region(facts, listOf(block), listOf(tile)))
        // its numbers count the coarse tile it hands on, its bytes stored once the coarse packs are made
        File(dir, "$id${WorldAssemble.STATS}").writeText("""{"blocks":1,"levels":{"8":{"tiles":1,"features":0,"stored":0,"raw":16}},"seconds":{},"worst_cover":0,"rings_outside":0,"mended":0,"coarser":[]}""")
        return File(dir, id)
    }

    @Test
    fun regionsNotOfOneRunAreRefused() {
        val facts = WorldTiles.Facts("2026-11-02T20:21:00Z", listOf("OpenStreetMap: europe-latest.osm.pbf"), "abc")
        fun refused(vararg regions: File) = assertThrows(IllegalArgumentException::class.java) { WorldAssemble.assemble(tmp.newFolder(), regions.toList()) {} }
        val a = region("a", 33 to 21, "world-mid-a", facts)
        // another extract
        refused(a, region("b", 34 to 21, "world-mid-b", WorldTiles.Facts(facts.dataTime, facts.sources, "def")))
        // another day's data
        refused(a, region("c", 34 to 21, "world-mid-c", WorldTiles.Facts("2026-10-02T20:21:00Z", facts.sources, facts.source)))
        // a block built twice, a part named twice
        refused(a, region("d", 33 to 21, "world-mid-d", facts))
        refused(a, region("e", 34 to 21, "world-mid-a", facts))
        // an index of another date
        refused(a, region("f", 34 to 21, "world-mid-f", facts, Instant.ofEpochSecond(1_700_000_000)))
        // and a region of the run is taken
        val ok = WorldAssemble.assemble(tmp.newFolder(), listOf(a, region("g", 34 to 21, "world-mid-g", facts))) {}
        assertEquals(listOf("world-mid-a.000", "world-mid-g.000", "world-coarse.000"), ok.index.parts.map { it.name })
    }

    @Test
    fun coarseTilesComeBackAsTheyWereWritten() {
        val facts = WorldTiles.Facts(null, listOf("one", "two ✓"), "f00")
        val tiles = listOf(
            Format3Writer.Encoded(6, 33, 23, byteArrayOf(1, 2, 3), 7, Reach(-1, -2, 3, 4, 500), linkedMapOf("ground" to 3, "label" to 4)),
            Format3Writer.Encoded(8, 135, 92, ByteArray(0), 0, Reach(0, 0, 0, 0, 0), emptyMap()),
        )
        val f = File(tmp.root, "x.coarse")
        CoarseTiles.write(f, CoarseTiles.Region(facts, listOf(33 to 23, 34 to 23), tiles))
        val r = CoarseTiles.read(f)
        assertEquals(null, r.facts.dataTime)
        assertEquals(facts.sources, r.facts.sources)
        assertEquals("f00", r.facts.source)
        assertEquals(listOf(33 to 23, 34 to 23), r.blocks)
        assertEquals(2, r.tiles.size)
        for ((a, b) in tiles.zip(r.tiles)) {
            assertEquals(listOf(a.level, a.x, a.y, a.featureCount), listOf(b.level, b.x, b.y, b.featureCount))
            assertArrayEquals(a.raw, b.raw)
            assertEquals(a.reach, b.reach)
            assertEquals(a.layerCounts, b.layerCounts)
        }
        // cut short, it is refused
        f.writeBytes(f.readBytes().copyOf(f.length().toInt() - 1))
        assertThrows(java.io.IOException::class.java) { CoarseTiles.read(f) }
    }
}

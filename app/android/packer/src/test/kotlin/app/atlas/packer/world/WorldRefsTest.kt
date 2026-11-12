package app.atlas.packer.world

import app.atlas.packer.country.WindowPack
import app.atlas.render.data.PackManifest
import app.atlas.render.data.PackTiles
import app.atlas.rendertiles.model.Format
import app.atlas.rendertiles.model.Grid
import app.atlas.tiles.WorldIndex
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.locationtech.jts.geom.Coordinate
import org.locationtech.jts.geom.GeometryFactory
import java.io.File
import java.io.RandomAccessFile
import kotlin.math.acos
import kotlin.math.cos
import kotlin.math.sin

/**
 * `packer world-refs` (ENGINE.md step 5's references around the world): the walk takes the tiles about a view's target
 * down to L11 near the eye and only the globe's levels from afar, on the sphere about a pole too; a pack's tiles cut
 * from Monaco's world are the part's, byte for byte, with their reach, in a pack of its block, levels, id, data's date,
 * sources and inputs' hash; `--needed` names the parts that hold them. The views mapcorecheck draws walk to a few
 * thousand tiles at most, the refs job's budget (ENGINE.md: about 30 MB with the globe).
 */
class WorldRefsTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val frame = WorldRefs.Frame(1080, 2400, 1080.0 / 412.0, 0.48)

    private fun key(z: Int, lat: Double, lon: Double) = WorldRefs.key(z, PackTiles.tileX(lon, z), PackTiles.tileY(lat, z))

    /** The angle, degrees, between tile [k]'s nearest corner or middle and ([lat], [lon]). */
    private fun degreesFrom(k: Long, lat: Double, lon: Double): Double {
        val b = Grid.tileBox((k ushr 48).toInt(), ((k ushr 24) and 0xFFFFFF).toInt(), (k and 0xFFFFFF).toInt())
        val lats = listOf(Grid.deg(b[0]), Grid.deg(b[2]), (Grid.deg(b[0]) + Grid.deg(b[2])) / 2)
        val lons = listOf(Grid.deg(b[1]), Grid.deg(b[3]), (Grid.deg(b[1]) + Grid.deg(b[3])) / 2)
        fun angle(a: Double, o: Double): Double {
            val p1 = Math.toRadians(lat)
            val p2 = Math.toRadians(a)
            val c = sin(p1) * sin(p2) + cos(p1) * cos(p2) * cos(Math.toRadians(o - lon))
            return Math.toDegrees(acos(c.coerceIn(-1.0, 1.0)))
        }
        return lats.minOf { a -> lons.minOf { o -> angle(a, o) } }
    }

    @Test
    fun nearTheEyeTheWalkGoesToTheFinestLevelAndNoFurtherThanTheView() {
        val v = WorldRefs.View("amsterdam", 52.3731, 4.8922, 25_000.0, 0.0, 0.0)
        val tiles = WorldRefs.tiles(v, frame)
        for (z in 6..11) assertTrue("L$z under the target", key(z, v.lat, v.lon) in tiles)
        assertTrue(tiles.all { (it ushr 48).toInt() in 6..11 })
        // straight down from 25 km the frame sees about 16 km across: the L11 tiles lie within 0.5° of the target
        assertTrue(tiles.filter { (it ushr 48).toInt() == 11 }.all { degreesFrom(it, v.lat, v.lon) < 0.5 })
        // and a view of the same place 500 km off shares none of them
        val far = WorldRefs.tiles(WorldRefs.View("off", 52.3731, 12.3, 25_000.0, 0.0, 0.0), frame)
        assertTrue(tiles.filter { (it ushr 48).toInt() >= 9 }.none { it in far })
    }

    @Test
    fun aPitchedViewTakesTheGroundItLooksAt() {
        // facing south-west, pitched 60°: the ground beyond the target that way, not behind the eye
        val v = WorldRefs.View("zermatt", 46.0207, 7.7491, 25_000.0, 60.0, 225.0)
        val tiles = WorldRefs.tiles(v, frame)
        assertTrue(key(11, v.lat, v.lon) in tiles)
        assertTrue("ahead", key(10, v.lat - 0.25, v.lon - 0.35) in tiles)
        assertFalse("behind", key(10, v.lat + 0.45, v.lon + 0.65) in tiles)
    }

    @Test
    fun fromAfarOnlyTheGlobeDraws() {
        // 6,000 km over the open sea: no tile nearer than L5's start, so none of the world's own levels
        assertEquals(emptySet<Long>(), WorldRefs.tiles(WorldRefs.View("open sea", 35.0, -40.0, 6_000_000.0, 0.0, 0.0), frame))
        assertEquals(emptySet<Long>(), WorldRefs.tiles(WorldRefs.View("the board", 20.0, 10.0, 53_773_000.0, 0.0, 0.0), frame))
    }

    @Test
    fun theWalkIsOnTheSphereAboutAPoleAndAcrossTheAntimeridian() {
        // from 600 km over 85° N the frame's width spans about 20° of longitude there, and nothing lies 6° off
        val pole = WorldRefs.View("pole", 85.0, -40.0, 600_000.0, 0.0, 0.0)
        val tiles = WorldRefs.tiles(pole, frame)
        for (z in 6..7) assertTrue("L$z under the target", key(z, pole.lat, pole.lon) in tiles)
        for (lon in listOf(-45.0, -35.0)) assertTrue("84.8° N $lon° E", key(6, 84.8, lon) in tiles)
        assertTrue(tiles.all { degreesFrom(it, pole.lat, pole.lon) < 6.0 })
        // from 25 km over the antimeridian the tiles on both sides of it
        val date = WorldRefs.tiles(WorldRefs.View("dateline", 0.0, 179.99, 25_000.0, 0.0, 0.0), frame)
        assertTrue(key(11, 0.0, 179.95) in date)
        assertTrue(key(11, 0.0, -179.97) in date)
    }

    @Test
    fun theViewsMapcorecheckDrawsWalkToAFewThousandTiles() {
        val file = File(System.getProperty("atlas.repo") ?: "../..", "app/android/mapcorecheck/src/test/resources/likeness/world-refs.json")
        val (f, views) = WorldRefs.views(file)
        assertEquals(15, views.size)
        val all = HashSet<Long>()
        for (v in views) {
            val t = WorldRefs.tiles(v, f)
            println("world-refs: ${v.name}: ${t.size} tiles, ${t.groupingBy { (it ushr 48).toInt() }.eachCount().toSortedMap()}")
            all += t
        }
        println("world-refs: ${all.size} tiles in all")
        assertTrue("${all.size} tiles", all.size in 100..4_000)
    }

    @Test
    fun theCutPacksHoldTheChosenTilesAsThePartsHoldThem() {
        val f = GeometryFactory()
        fun box(w: Double, s: Double, e: Double, n: Double) = f.createPolygon(arrayOf(Coordinate(w, s), Coordinate(w, n), Coordinate(e, n), Coordinate(e, s), Coordinate(w, s)))
        val land = File(tmp.root, "land-polygons-split-4326.zip")
        ShapefileWriter.zip(land, "land_polygons", listOf(ShapefileWriter.Record(box(7.30, 43.725, 7.60, 43.90))))
        val pbf = File(javaClass.getResource("/monaco-2026-10-13.osm.pbf")!!.toURI())
        val world = tmp.newFolder("world")
        WorldTiles.build(pbf, land, world, File(tmp.root, "scratch"), 1, WorldTiles.Run(listOf(33 to 23), source = WorldTiles.Source("monaco-latest.osm.pbf", "5b3c0e4f")), false, ::println)
        val indexFile = File(world, "world.idx")
        val index = WorldIndex.read(indexFile.readBytes())

        val views = File(tmp.root, "views.json").apply {
            writeText(
                """{"frame": {"width": 1080, "height": 2400, "density": 2.6213592233009706, "cy": 0.48},
                   "frames": [{"name": "monaco", "lat": 43.7384, "lon": 7.4246, "distance": 25000, "pitch": 0, "moment": "sunny"}]}""",
            )
        }
        // --needed: each pack's part, its range there and its file, as the refs job fetches them
        val needed = ArrayList<String>()
        assertEquals(0, WorldRefs.run(listOf("world-refs", indexFile.path, views.path, "--needed"), needed::add))
        val coarse = index.entries.single { it.z == 3 }
        val fine = index.entries.single { it.z == 6 }
        assertEquals(
            listOf("world-coarse.000 ${coarse.offset} ${coarse.length} world-3-4-2.atlas", "world-mid.000 ${fine.offset} ${fine.length} world-6-33-23.atlas"),
            needed,
        )
        // cut from the packs fetched by their ranges, and from the parts themselves: the same packs
        val fetched = tmp.newFolder("fetched")
        for (line in needed) {
            val (part, offset, length, file) = line.split(' ')
            RandomAccessFile(File(world, part), "r").use { r ->
                File(fetched, file).writeBytes(ByteArray(length.toInt()).also { r.seek(offset.toLong()); r.readFully(it) })
            }
        }
        val out = tmp.newFolder("refs")
        val said = ArrayList<String>()
        assertEquals(0, WorldRefs.run(listOf("world-refs", indexFile.path, views.path, fetched.path, out.path), said::add))
        println(said.joinToString("\n"))
        val fromParts = tmp.newFolder("refs-from-parts")
        assertEquals(0, WorldRefs.run(listOf("world-refs", indexFile.path, views.path, world.path, fromParts.path)) {})
        val chosen = WorldRefs.tiles(WorldRefs.views(views).second.single(), frame)
        val written = out.listFiles()!!.map { it.name }.sorted()
        assertEquals(listOf("world-3-4-2.atlas", "world-6-33-23.atlas"), written)
        for (name in written) assertArrayEquals(name, File(out, name).readBytes(), File(fromParts, name).readBytes())
        // a pack of other bytes than the index's is refused
        File(fetched, "world-6-33-23.atlas").writeBytes(File(fetched, "world-6-33-23.atlas").readBytes().also { it[100] = (it[100] + 1).toByte() })
        assertTrue(runCatching { WorldRefs.run(listOf("world-refs", indexFile.path, views.path, fetched.path, tmp.newFolder("refused").path)) {} }.isFailure)
        for (e in index.entries) {
            val whole = RandomAccessFile(File(world, index.parts[e.part].name), "r").use { r ->
                ByteArray(e.length.toInt()).also { r.seek(e.offset); r.readFully(it) }
            }.let(WindowPack::read)
            val cut = WindowPack.read(File(out, "world-${e.z}-${e.x}-${e.y}.atlas").readBytes())
            val kept = whole.chunks.filter { WorldRefs.key(it.z, it.x, it.y) in chosen }
            // (Monaco's few tiles are all in its 25 km view: the cut's choice is the walk's, tested above)
            assertTrue("pack ${e.z}/${e.x}/${e.y} keeps some of its ${whole.chunks.size} tiles", kept.isNotEmpty())
            // the tiles kept, in the writer's order, each the part's bytes with its features and its reach
            assertEquals(kept.map { Triple(it.z, it.x, it.y) }, cut.chunks.map { Triple(it.z, it.x, it.y) })
            val reachWhole = reach(whole)
            val reachCut = reach(cut)
            for ((i, c) in cut.chunks.withIndex()) {
                val o = whole.chunks.indexOfFirst { it.z == c.z && it.x == c.x && it.y == c.y }
                assertArrayEquals(whole.tile(whole.chunks[o]), cut.tile(c))
                assertEquals(whole.chunks[o].count, c.count)
                assertArrayEquals(reachWhole[o], reachCut[i])
            }
            // the pack's head and manifest as the world's: its block, levels, profile, id, date, sources and inputs' hash
            assertArrayEquals(whole.stored(Format.SECTION_HEAD), cut.stored(Format.SECTION_HEAD))
            assertArrayEquals(whole.style(), cut.style())
            val m0 = PackManifest(Json.parseToJsonElement(String(whole.manifest)).jsonObject)
            val m1 = PackManifest(Json.parseToJsonElement(String(cut.manifest)).jsonObject)
            for (k in listOf("id", "data_time", "sources", "source_sha256", "profile", "levels", "config", "engine_version", "style_version")) {
                assertEquals(k, m0.json[k], m1.json[k])
            }
        }
    }

    /** [pack]'s reach entries as bytes, an entry a chunk. */
    private fun reach(pack: WindowPack): List<ByteArray> {
        val s = pack.stored(Format.SECTION_REACH)
        val entry = (s[6].toInt() and 0xFF) or ((s[7].toInt() and 0xFF) shl 8)
        return List(pack.chunks.size) { i -> s.copyOfRange(12 + i * entry, 12 + (i + 1) * entry) }
    }
}

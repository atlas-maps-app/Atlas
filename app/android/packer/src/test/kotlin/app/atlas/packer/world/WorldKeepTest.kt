package app.atlas.packer.world

import app.atlas.packer.country.WindowPack
import app.atlas.rendertiles.model.Format
import app.atlas.tiles.WorldIndex
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.locationtech.jts.geom.Coordinate
import org.locationtech.jts.geom.Geometry
import org.locationtech.jts.geom.GeometryFactory
import org.locationtech.jts.geom.prep.PreparedGeometryFactory
import java.io.File
import java.io.RandomAccessFile
import java.util.Random
import kotlin.math.pow

/**
 * `packer world-keep` (ENGINE.md step 11, packs for offline use): every tile a view walks to, its target anywhere in a
 * country, any distance from the world's first out to the globe, any pitch the camera allows there, any bearing, the
 * phone upright or sideways, is among the country's kept tiles, in the Low Countries, the tropics and the far north;
 * a level keeps the tiles within its reach and only those; a country's area is its polygon less its holes, within its
 * box where it clips; and Monaco's world keeps each pack's tiles with its head as the phone fetches it (its bytes but
 * its chunks), its tiles' chunks' bytes as stored, cut again into packs of their own as world-refs cuts them.
 */
class WorldKeepTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val f = GeometryFactory()

    private fun polygon(vararg lonLat: Double): Geometry =
        f.createPolygon(Array(lonLat.size / 2 + 1) { i -> (i % (lonLat.size / 2)).let { j -> Coordinate(lonLat[2 * j], lonLat[2 * j + 1]) } })

    @Test
    fun everyTileAViewOfAKeptCountryDrawsIsKept() {
        println("world-keep: reach ${(6..11).joinToString { "L$it ${Math.round(reach[it] / 1000.0)} km" }}")
        for (z in 7..11) assertTrue("L${z - 1} reaches at least as far as L$z", reach[z - 1] >= reach[z])
        assertTrue("L11's reach ${reach[11]} m", reach[11] in 20_000.0..300_000.0)
        assertTrue("L6's reach ${reach[6]} m", reach[6] in 300_000.0..6_000_000.0)
        val areas = listOf(
            "the Low Countries" to polygon(3.2, 50.8, 7.2, 51.2, 6.8, 53.5, 4.6, 53.2),
            "the tropics" to polygon(-62.0, 9.6, -60.4, 9.8, -60.6, 11.0, -61.9, 10.8),
            "the far north" to polygon(16.8, 68.4, 20.2, 68.5, 21.0, 70.2, 17.4, 70.0),
        )
        val frames = listOf(
            WorldRefs.Frame(1080, 2400, 1080.0 / 412.0, 0.48),
            WorldRefs.Frame(2400, 1080, 1080.0 / 412.0, 0.48),
            WorldRefs.Frame(1600, 2560, 2.0, 0.5),
            WorldRefs.Frame(2560, 1600, 2.0, 0.5),
        )
        val random = Random(5)
        for ((name, area) in areas) {
            val kept = WorldKeep.tiles(area, reach)
            val inside = PreparedGeometryFactory.prepare(area)
            val env = area.envelopeInternal
            val edge = area.boundary.coordinates
            val missing = ArrayList<String>()
            var walked = 0
            repeat(VIEWS) { n ->
                // half the targets on the country's edge, where views reach farthest beyond it; the others inside
                val (lat, lon) = if (n % 2 == 0) {
                    val i = random.nextInt(edge.size - 1)
                    val t = random.nextDouble()
                    (edge[i].y + (edge[i + 1].y - edge[i].y) * t) to (edge[i].x + (edge[i + 1].x - edge[i].x) * t)
                } else {
                    var p: Pair<Double, Double>
                    do {
                        p = (env.minY + random.nextDouble() * env.height) to (env.minX + random.nextDouble() * env.width)
                    } while (!inside.contains(f.createPoint(Coordinate(p.second, p.first))))
                    p
                }
                val d = WorldKeep.NEAREST * (WorldKeep.FARTHEST / WorldKeep.NEAREST).pow(random.nextDouble())
                // pitches toward the steepest, where views see farthest
                val pitch = WorldKeep.pitchLimit(d) * (1 - random.nextDouble().pow(3))
                val view = WorldRefs.View("v$n", lat, lon, d, pitch, random.nextDouble() * 360)
                val frame = frames[random.nextInt(frames.size)]
                val tiles = WorldRefs.tiles(view, frame)
                walked += tiles.size
                for (k in tiles) if (k !in kept) {
                    missing += "${k ushr 48}/${(k ushr 24) and 0xFFFFFF}/${k and 0xFFFFFF} of $lat, $lon at ${Math.round(d)} m, pitch ${"%.1f".format(pitch)}"
                }
            }
            println("world-keep: $name: ${kept.size} tiles kept; $VIEWS views walked $walked tiles, ${missing.size} not kept")
            assertTrue("$name: tiles its views draw are not kept: ${missing.take(12)}", missing.isEmpty())
        }
    }

    @Test
    fun aLevelKeepsItsTilesWithinItsReachOfTheCountry() {
        val area = polygon(5.0, 52.0, 5.6, 52.0, 5.6, 52.4, 5.0, 52.4)
        val kept = WorldKeep.tiles(area, reach)
        // the outline's points some 500 m apart: each tile's distance to them is its true one, or at most 250 m more
        val outline = org.locationtech.jts.densify.Densifier.densify(area.boundary, 0.005).coordinates
        for (z in 8..11) {
            val r = reach[z]
            var near = 0
            var far = 0
            val n = 1 shl z
            // the tiles a degree beyond the reach each way
            val dLat = Math.toDegrees(r / WorldRefs.EARTH_RADIUS) + 1.0
            val dLon = dLat / kotlin.math.cos(Math.toRadians(52.4 + dLat))
            val west = ((5.0 - dLon + 180.0) / 360.0 * n).toInt()
            val east = ((5.6 + dLon + 180.0) / 360.0 * n).toInt()
            val (top, bottom) = rows(z, 52.4 + dLat, 52.0 - dLat)
            for (x in west..east) for (y in top..bottom) {
                val b = WorldKeep.box(z, x, y)
                val over = b[0] <= 52.4 && b[2] >= 52.0 && b[1] <= 5.6 && b[3] >= 5.0
                val d = if (over) 0.0 else outline.minOf { WorldKeep.boxDistance(it.y, it.x, b[0], b[1], b[2], b[3]) }
                val k = WorldRefs.key(z, x, y)
                if (d <= r) {
                    near++
                    assertTrue("L$z tile $x/$y lies ${Math.round(d)} m from the country, within the reach $r: kept", k in kept)
                } else if (d > r + WorldKeep.SLACK + 1_000.0) {
                    far++
                    assertTrue("L$z tile $x/$y lies ${Math.round(d)} m from the country, beyond the reach $r: not kept", k !in kept)
                }
            }
            assertTrue("L$z: tiles within its reach ($near) and beyond it ($far)", near > 0 && far > 0)
        }
        // the levels' kept tiles: none coarser than L6 nor finer than L11
        assertTrue(kept.all { (it ushr 48).toInt() in 6..11 })
    }

    /** The rows of level [z] from latitude [north] to [south]. */
    private fun rows(z: Int, north: Double, south: Double): Pair<Int, Int> {
        fun row(lat: Double): Int {
            val p = Math.toRadians(lat)
            return ((1 - kotlin.math.ln(kotlin.math.tan(p) + 1 / kotlin.math.cos(p)) / Math.PI) / 2 * (1 shl z)).toInt()
        }
        return row(north) to row(south)
    }

    @Test
    fun boxDistancesAreThoseOnTheSphere() {
        // within the box's longitudes, straight north or south; beyond them, to a corner or a meridian's edge
        assertEquals(0.0, WorldKeep.boxDistance(52.1, 5.1, 52.0, 5.0, 52.2, 5.2), 0.0)
        assertEquals(WorldKeep.distance(52.5, 5.1, 52.2, 5.1), WorldKeep.boxDistance(52.5, 5.1, 52.0, 5.0, 52.2, 5.2), 1e-6)
        assertEquals(WorldKeep.distance(52.5, 6.0, 52.2, 5.2), WorldKeep.boxDistance(52.5, 6.0, 52.0, 5.0, 52.2, 5.2), 1e-6)
        // a place beside the edge, between its corners: nearer than either corner
        val beside = WorldKeep.boxDistance(52.1, 6.0, 52.0, 5.0, 52.2, 5.2)
        assertTrue(beside < WorldKeep.distance(52.1, 6.0, 52.0, 5.2) && beside < WorldKeep.distance(52.1, 6.0, 52.2, 5.2))
        assertEquals(WorldKeep.distance(52.1, 6.0, 52.1003, 5.2), beside, 60.0)
        // across the antimeridian
        assertEquals(WorldKeep.distance(10.0, 179.9, 10.0, -180.0), WorldKeep.boxDistance(10.0, 179.9, 9.0, -180.0, 11.0, -174.375), 1.0)
        assertEquals(0.0, WorldKeep.boxDistance(10.0, -179.0, 9.0, -180.0, 11.0, -174.375), 0.0)
        // more than 90° of longitude away the nearest point of an edge's great circle lies past a pole: here the
        // nearest corner is the north-west one, not the edge's south end (CI's sample, 12 November: 107 km farther)
        assertEquals(WorldKeep.distance(-34.5, 75.1, 64.4, -152.2), WorldKeep.boxDistance(-34.5, 75.1, 61.8, -152.2, 64.4, -143.7), 1e-6)
        // beyond the pole from a place in the north: a corner of the box's pole-ward edge, nearer than that edge's
        // point on the place's far meridian (24.98° against 25°)
        assertEquals(WorldKeep.distance(70.0, 20.0, 85.0, -155.0), WorldKeep.boxDistance(70.0, 20.0, 80.0, -165.0, 85.0, -155.0), 1e-6)
        assertTrue(WorldKeep.boxDistance(70.0, 20.0, 80.0, -165.0, 85.0, -155.0) < WorldKeep.distance(70.0, 20.0, 85.0, -160.0))
        // sampled along the box's edges, never nearer than the closed form
        val random = Random(3)
        repeat(200) {
            val lat = random.nextDouble() * 160 - 80
            val lon = random.nextDouble() * 360 - 180
            val s = random.nextDouble() * 140 - 70
            val w = random.nextDouble() * 360 - 180
            val n = s + random.nextDouble() * 10
            val e = w + random.nextDouble() * 10
            val d = WorldKeep.boxDistance(lat, lon, s, w, n, e)
            var sampled = Double.MAX_VALUE
            for (i in 0..400) {
                val t = i / 400.0
                for ((la, lo) in listOf(s + (n - s) * t to w, s + (n - s) * t to e, s to w + (e - w) * t, n to w + (e - w) * t)) {
                    sampled = minOf(sampled, WorldKeep.distance(lat, lon, la, lo))
                }
            }
            val inside = lat in s..n && ((lon - w) % 360 + 360) % 360 <= e - w
            if (inside) {
                assertEquals(0.0, d, 0.0)
            } else {
                // the closed form is the nearest point's: never farther than a sample, nearer by half a sample's step at most
                assertTrue("$lat, $lon to $s..$n, $w..$e: $d against $sampled", d <= sampled + 1e-3 && sampled - d <= 1_500.0)
            }
        }
    }

    @Test
    fun aCountrysAreaIsItsPolygonLessItsHolesWithinItsBoxWhereItClips() {
        val polys = tmp.newFolder("polys")
        File(polys, "lowlands.poly").writeText(
            "lowlands\n1\n   4.0 51.0\n   6.0 51.0\n   6.0 53.0\n   4.0 53.0\nEND\n!2\n   4.5 51.5\n   5.0 51.5\n   5.0 52.0\n   4.5 52.0\nEND\nEND\n",
        )
        val regions = File(tmp.root, "regions.json").apply {
            writeText(
                """{"regions": [
                    {"id": "lowlands", "name": "Lowlands", "country": "NL", "url": "https://download.geofabrik.de/europe/lowlands-latest.osm.pbf", "bbox": [4.0, 51.0, 5.0, 52.5]},
                    {"id": "lowlands", "name": "Lowlands clipped", "url": "https://download.geofabrik.de/europe/lowlands-latest.osm.pbf", "bbox": [4.0, 51.0, 5.0, 52.5], "clip": true},
                    {"id": "fjords", "name": "Fjords", "url": "https://download.geofabrik.de/europe/fjords-latest.osm.pbf", "bbox": [5.0, 58.0, 30.0, 71.0], "windows": false}
                ]}""",
            )
        }
        val (whole, clipped) = WorldKeep.regions(regions)
        assertEquals(2, WorldKeep.regions(regions).size)
        assertEquals("NL", whole.country)
        assertEquals(null, clipped.country)
        val a = WorldKeep.area(whole, polys)
        assertEquals("poly", a.from)
        assertEquals(4.0 - 0.25, a.geometry.area, 1e-9)
        val c = WorldKeep.area(clipped, polys)
        assertEquals("poly within the box", c.from)
        assertEquals(1.5 - 0.25, c.geometry.area, 1e-9)
        assertTrue(runCatching { WorldKeep.area(WorldKeep.Region("elsewhere", "Elsewhere", null, doubleArrayOf(0.0, 0.0, 1.0, 1.0), false), polys) }.isFailure)
    }

    @Test
    fun aCountrysPacksKeepTheirHeadsAndTilesAsThePhoneFetchesThem() {
        fun box(w: Double, s: Double, e: Double, n: Double) = f.createPolygon(arrayOf(Coordinate(w, s), Coordinate(w, n), Coordinate(e, n), Coordinate(e, s), Coordinate(w, s)))
        val land = File(tmp.root, "land-polygons-split-4326.zip")
        ShapefileWriter.zip(land, "land_polygons", listOf(ShapefileWriter.Record(box(7.30, 43.725, 7.60, 43.90))))
        val pbf = File(javaClass.getResource("/monaco-2026-10-13.osm.pbf")!!.toURI())
        val world = tmp.newFolder("world")
        WorldTiles.build(pbf, land, world, File(tmp.root, "scratch"), 1, WorldTiles.Run(listOf(33 to 23), source = WorldTiles.Source("monaco-latest.osm.pbf", "5b3c0e4f")), false, ::println)
        val indexFile = File(world, "world.idx")
        val index = WorldIndex.read(indexFile.readBytes())
        val regions = File(tmp.root, "regions.json").apply {
            writeText(
                """{"regions": [{"id": "monaco", "name": "Monaco", "country": "MC", "url": "https://download.geofabrik.de/europe/monaco-latest.osm.pbf", "bbox": [7.40, 43.72, 7.44, 43.76]}]}""",
            )
        }
        val polys = tmp.newFolder("polys")
        File(polys, "monaco.poly").writeText("monaco\n1\n   7.40 43.72\n   7.44 43.72\n   7.44 43.76\n   7.40 43.76\nEND\nEND\n")

        // --needed: each pack's part and the range of its first bytes there, its file
        val needed = ArrayList<String>()
        assertEquals(0, WorldKeep.run(listOf("world-keep", indexFile.path, regions.path, polys.path, "--needed"), needed::add))
        val coarse = index.entries.single { it.z == 3 }
        val fine = index.entries.single { it.z == 6 }
        fun probe(e: WorldIndex.Entry) = minOf(e.length, WorldKeep.HEAD_PROBE.toLong())
        assertEquals(
            listOf("world-coarse.000 ${coarse.offset} ${probe(coarse)} world-3-4-2.atlas", "world-mid.000 ${fine.offset} ${probe(fine)} world-6-33-23.atlas"),
            needed,
        )
        val whole = index.entries.associateWith { e ->
            RandomAccessFile(File(world, index.parts[e.part].name), "r").use { r -> ByteArray(e.length.toInt()).also { r.seek(e.offset); r.readFully(it) } }
        }
        // the heads from their first bytes: the coarse pack's whole, the fine pack's cut just past its chunk table
        val heads = tmp.newFolder("heads")
        File(heads, "world-3-4-2.atlas").writeBytes(whole.getValue(coarse))
        val finePack = WindowPack.read(whole.getValue(fine))
        val table = finePack.section(Format.SECTION_TILES)
        File(heads, "world-6-33-23.atlas").writeBytes(whole.getValue(fine).copyOf(table.offset + table.stored + 7))
        val keepFile = File(tmp.root, "out/world-keep.json")
        val said = ArrayList<String>()
        assertEquals(0, WorldKeep.run(listOf("world-keep", indexFile.path, regions.path, polys.path, heads.path, keepFile.path, "--release", "world-20261013-test", "--threads", "2"), said::add))
        println(said.joinToString("\n"))

        val json = Json.parseToJsonElement(keepFile.readText()).jsonObject
        assertEquals(1, json["format"]!!.jsonPrimitive.int)
        assertEquals("world-20261013-test", json["release"]!!.jsonPrimitive.content)
        assertEquals(indexFile.length(), json["index"]!!.jsonObject["bytes"]!!.jsonPrimitive.long)
        assertEquals((6..11).map { "$it" }, json["reach"]!!.jsonObject.keys.toList())
        val country = json["countries"]!!.jsonArray.single().jsonObject
        assertEquals("monaco", country["id"]!!.jsonPrimitive.content)
        assertEquals("poly", country["area"]!!.jsonPrimitive.content)
        val packs = country["packs"]!!.jsonArray.map { it.jsonObject }
        assertEquals(listOf(3 to 4, 6 to 33), packs.map { it["z"]!!.jsonPrimitive.int to it["x"]!!.jsonPrimitive.int })
        val kept = WorldKeep.kept(json, "monaco")
        var heads0 = 0L
        var bytes0 = 0L
        var tiles0 = 0
        for (p in packs) {
            val e = index.find(p["z"]!!.jsonPrimitive.int, p["x"]!!.jsonPrimitive.int, p["y"]!!.jsonPrimitive.int)!!
            val pack = WindowPack.read(whole.getValue(e))
            // its head: its bytes but its chunks, as WorldFetch.head fetches it
            assertEquals(e.length - pack.section(Format.SECTION_CHUNKS).stored, p["head"]!!.jsonPrimitive.long)
            // Monaco's world holds only tiles about Monaco, all within reach: every chunk of the pack is kept
            val keys = kept.getValue(Triple(e.z, e.x, e.y))
            assertEquals(pack.chunks.map { WorldRefs.key(it.z, it.x, it.y) }.toSet(), keys)
            assertEquals(pack.chunks.sumOf { it.stored.toLong() }, p["bytes"]!!.jsonPrimitive.long)
            heads0 += p["head"]!!.jsonPrimitive.long
            bytes0 += p["bytes"]!!.jsonPrimitive.long
            tiles0 += keys.size
        }
        assertEquals(heads0, country["heads"]!!.jsonPrimitive.long)
        assertEquals(heads0 + bytes0, country["bytes"]!!.jsonPrimitive.long)
        assertEquals(tiles0, country["tiles"]!!.jsonPrimitive.int)
        val levels = country["levels"]!!.jsonObject
        assertEquals(tiles0, levels.values.sumOf { it.jsonObject["tiles"]!!.jsonPrimitive.int })
        assertEquals(bytes0, levels.values.sumOf { it.jsonObject["bytes"]!!.jsonPrimitive.long })

        // first bytes that end inside the chunk table are refused
        File(heads, "world-6-33-23.atlas").writeBytes(whole.getValue(fine).copyOf(table.offset + table.stored / 2))
        assertTrue(runCatching { WorldKeep.run(listOf("world-keep", indexFile.path, regions.path, polys.path, heads.path, File(tmp.root, "x.json").path)) {} }.isFailure)

        // cut: the country's packs whole, then its tiles in packs of their own, each tile as the part holds it
        val wholeNeeded = ArrayList<String>()
        assertEquals(0, WorldKeep.run(listOf("world-keep", "cut", indexFile.path, keepFile.path, "monaco", "--needed"), wholeNeeded::add))
        assertEquals(listOf("world-coarse.000 ${coarse.offset} ${coarse.length} world-3-4-2.atlas", "world-mid.000 ${fine.offset} ${fine.length} world-6-33-23.atlas"), wholeNeeded)
        val fetched = tmp.newFolder("fetched")
        for ((e, b) in whole) File(fetched, WorldRefs.fileOf(e)).writeBytes(b)
        val cut = tmp.newFolder("cut")
        assertEquals(0, WorldKeep.run(listOf("world-keep", "cut", indexFile.path, keepFile.path, "monaco", fetched.path, cut.path)) { println(it) })
        for (e in index.entries) {
            val a = WindowPack.read(whole.getValue(e))
            val b = WindowPack.read(File(cut, WorldRefs.fileOf(e)).readBytes())
            assertEquals(a.chunks.map { Triple(it.z, it.x, it.y) }, b.chunks.map { Triple(it.z, it.x, it.y) })
            for ((i, c) in b.chunks.withIndex()) assertArrayEquals(a.tile(a.chunks[i]), b.tile(c))
        }
        // world-refs --kept: Monaco's view draws from its kept tiles alone, and a tile the keep lacks is named
        val views = File(tmp.root, "views.json").apply {
            writeText(
                """{"frame": {"width": 1080, "height": 2400, "density": 2.6213592233009706, "cy": 0.48},
                   "frames": [{"name": "monaco", "lat": 43.7384, "lon": 7.4246, "distance": 25000, "pitch": 0, "moment": "sunny"}]}""",
            )
        }
        val viewsCut = tmp.newFolder("views-cut")
        val all = ArrayList<String>()
        assertEquals(0, WorldRefs.run(listOf("world-refs", indexFile.path, views.path, fetched.path, viewsCut.path, "--kept", keepFile.path, "monaco"), all::add))
        val total = viewsCut.listFiles()!!.sumOf { WindowPack.read(it.readBytes()).chunks.size }
        assertTrue(all.joinToString("\n"), "world-refs: kept for monaco: all $total of the views' tiles" in all)
        assertTrue(all.joinToString("\n"), all.none { "not kept" in it })
        // the keep without the first of the fine pack's tiles the view draws
        val gone = WindowPack.read(File(viewsCut, WorldRefs.fileOf(fine)).readBytes()).chunks.first()
        val keepJson = Json.parseToJsonElement(keepFile.readText()).jsonObject
        fun without(p: JsonObject): JsonObject = if (p["z"]!!.jsonPrimitive.int != fine.z) p else JsonObject(
            p + ("tiles" to JsonObject(p["tiles"]!!.jsonObject.mapValues { (z, xy) ->
                val pairs = xy.jsonArray.map { it.jsonPrimitive.int }.chunked(2).filter { (x, y) -> Triple(z.toInt(), x, y) != Triple(gone.z, gone.x, gone.y) }
                JsonArray(pairs.flatten().map { JsonPrimitive(it) })
            })),
        )
        val lacking = JsonObject(
            keepJson + ("countries" to JsonArray(keepJson["countries"]!!.jsonArray.map { c ->
                JsonObject(c.jsonObject + ("packs" to JsonArray(c.jsonObject["packs"]!!.jsonArray.map { without(it.jsonObject) })))
            })),
        )
        val lackingFile = File(tmp.root, "lacking.json").apply { writeText(lacking.toString()) }
        val some = ArrayList<String>()
        assertEquals(0, WorldRefs.run(listOf("world-refs", indexFile.path, views.path, fetched.path, tmp.newFolder("views-cut-2").path, "--kept", lackingFile.path, "monaco"), some::add))
        assertTrue(some.joinToString("\n"), "world-refs: ${WorldRefs.fileOf(fine)}: not kept for monaco: 1 (${gone.z}/${gone.x}/${gone.y})" in some)
        assertTrue(some.joinToString("\n"), "world-refs: not kept for monaco: 1 of the views' $total tiles" in some)
        // --kept names a world-keep.json and a country, after the folders
        assertTrue(runCatching { WorldRefs.run(listOf("world-refs", indexFile.path, views.path, fetched.path, tmp.newFolder("x").path, "--kept", keepFile.path)) {} }.isFailure)
        assertTrue(runCatching { WorldRefs.run(listOf("world-refs", indexFile.path, views.path, "--needed", "--kept", keepFile.path, "monaco")) {} }.isFailure)
        // a world-keep.json of another world is refused
        indexFile.appendBytes(byteArrayOf(0))
        assertTrue(runCatching { WorldKeep.run(listOf("world-keep", "cut", indexFile.path, keepFile.path, "monaco", "--needed")) {} }.isFailure)
    }

    companion object {
        /** The views each area is held to. */
        const val VIEWS = 160

        /** The levels' reach, measured once for the class. */
        val reach: DoubleArray by lazy { WorldKeep.reach(Runtime.getRuntime().availableProcessors().coerceIn(1, 4)) }
    }
}

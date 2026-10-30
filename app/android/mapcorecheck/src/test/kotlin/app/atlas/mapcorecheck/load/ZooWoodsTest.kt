package app.atlas.mapcorecheck.load

import app.atlas.mapcore.Native
import app.atlas.mapcorecheck.tiles.TileHost
import app.atlas.tiles.WindowsIndex
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.BeforeClass
import org.junit.Test
import java.io.File
import java.io.RandomAccessFile
import java.nio.file.Files
import java.util.Locale
import java.util.zip.GZIPInputStream
import kotlin.math.PI
import kotlin.math.atan
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.roundToLong
import kotlin.math.sinh

/**
 * The woods a window plants about a zoo or a theme park (load/Regions.cpp, RegionConfig::zoo_woods; stage P core-p10),
 * as the renders plant a zoo's or a park's region (regions.py's `zoo` and `park`, render/woods.py): today's map drew
 * Artis's woods with their trees where the windows drew them dark and bare (the user, 29 October: "Make sure no
 * quality is lost"). Each window is built by the core through the façade (JNI buildWindow) from an Overpass answer, as
 * the phone builds its windows, and its tile's planted trees (items of source 5, in the core's canonical dump of it:
 * TileHost.dump) are counted by wood: a wood in the zoo
 * and one within 200 m of its outline (a window's margin, so no window's edge cuts the planting) are planted, a farther
 * one is not; a city farm (zoo=petting_zoo) and a zoo under 5 ha plant nothing; a theme park plants as a zoo does.
 *
 * With `-Datlas.windows` (a region's windows' data, as WindowPackBuildTest takes it), it measures what the trees cost
 * about Artis: the six windows around it built as they are and with Artis taken for a city farm (`zoo=petting_zoo`, so
 * nothing else in them changes), their trees, bytes and build times; with `-Datlas.zoowoods.out` it keeps both sets
 * (`<out>/planted/windows`, `<out>/bare/windows`), the phone look tool's windows folders (PhoneLook's
 * `-Datlas.phonelook.work`), so frames can be drawn from them.
 */
class ZooWoodsTest {
    companion object {
        /** Artis's window: 4.9017° to 4.9237° east, 52.3654° to 52.3790° north. */
        const val X = 8415
        const val Y = 5384

        @BeforeClass
        @JvmStatic
        fun load() {
            val path = System.getProperty("atlas.mapcore.host")
            assumeTrue("no host build (-Datlas.mapcore.host)", path != null)
            assertNull(Native.load(path))
        }
    }

    /** A closed rectangular way 2·[halfX] by 2·[halfZ] metres about ([lat], [lon]), with [tags]. */
    private fun square(id: Long, lat: Double, lon: Double, halfX: Double, halfZ: Double, tags: String): String {
        val dLat = halfZ / 111_200.0
        val dLon = halfX / (111_320.0 * cos(Math.toRadians(lat)))
        val ring = listOf(lat - dLat to lon - dLon, lat - dLat to lon + dLon, lat + dLat to lon + dLon, lat + dLat to lon - dLon, lat - dLat to lon - dLon)
        val points = ring.joinToString(", ") { (a, o) -> """{"lat": ${"%.7f".format(Locale.ROOT, a)}, "lon": ${"%.7f".format(Locale.ROOT, o)}}""" }
        return """{"type": "way", "id": $id, "tags": {$tags}, "geometry": [$points]}"""
    }

    /** Metres east of [lon] at [lat], as a longitude. */
    private fun east(lat: Double, lon: Double, metres: Double) = lon + metres / (111_320.0 * cos(Math.toRadians(lat)))

    private val zooLat = 52.3720
    private val zooLon = 4.9100

    /** The woods of the test (60 m squares): in the 400 m wide zoo, 300 m east of its middle (70 to 130 m beyond its
     *  edge) and 560 m east of it (330 to 390 m beyond). */
    private val woods = listOf(
        "in" to (zooLat to east(zooLat, zooLon, 50.0)),
        "near" to (zooLat to east(zooLat, zooLon, 300.0)),
        "far" to (zooLat to east(zooLat, zooLon, 560.0)),
    )

    /** A planted tree of a window's tile: where it stands (frame metres), its height and its crown. */
    private class Tree(val x: Double, val z: Double, val h: Double, val crown: Double)

    /** The planted trees (items of source 5) of window [x]/[y]'s tile in the pack at [path], from its canonical dump. */
    private fun trees(path: String, x: Int = X, y: Int = Y): List<Tree> {
        val dump = TileHost.dump(path, 14, x, y)
        if (dump.startsWith("error=")) return emptyList()
        val field = Regex("""\b(source|x|z|h|crown)=(\S+)""")
        return dump.lineSequence().filter { it.startsWith("item @") }.mapNotNull { line ->
            val f = field.findAll(line).associate { it.groupValues[1] to it.groupValues[2] }
            if (f["source"] != "5") return@mapNotNull null
            // a double is "d" and its bits in hex, as Kotlin's CanonicalDump writes it
            fun real(k: String) = Double.fromBits(java.lang.Long.parseUnsignedLong(f.getValue(k).removePrefix("d"), 16))
            Tree(real("x"), real("z"), real("h"), real("crown"))
        }.toList()
    }

    /** The window's frame (load/Window.cpp's frame_of): its L10 cell's box's centre to four places, lat0, lon0, kx, kz. */
    private val frame: DoubleArray = run {
        val n = 1 shl 10
        val cx = X shr 4
        val cy = Y shr 4
        fun lat(y: Int) = Math.toDegrees(atan(sinh(PI * (1 - 2.0 * y / n))))
        fun round4(v: Double) = (v * 1e4).roundToLong() / 1e4
        val lat0 = round4((lat(cy) + lat(cy + 1)) / 2)
        val lon0 = round4((cx + 0.5) / n * 360 - 180)
        doubleArrayOf(lat0, lon0, 111_320.0 * cos(Math.toRadians(lat0)), 110_540.0)
    }

    /** The window built from an answer holding [zoo] (an element, or none) and the three woods: its planted trees. */
    private fun build(zoo: String?): List<Tree> {
        val elements = listOfNotNull(zoo) + woods.mapIndexed { i, (_, at) -> square(100L + i, at.first, at.second, 30.0, 30.0, """"landuse": "forest"""") }
        val answer = """{"version": 0.6, "osm3s": {"timestamp_osm_base": "2026-10-30T06:00:00Z"}, "elements": [
${elements.joinToString(",\n")}
]}"""
        val dir = Files.createTempDirectory("atlas-zoo-woods").toFile()
        try {
            val json = File(dir, "answer.json").apply { writeText(answer) }
            val pack = File(dir, "14-$X-$Y.atlas")
            assertEquals("the core refused the window", "", Native.buildWindow(14, X, Y, json.path, pack.path))
            return trees(pack.path)
        } finally {
            dir.deleteRecursively()
        }
    }

    /** How many of [trees] stand in each wood (within 45 m of its middle), by name; "elsewhere" too. */
    private fun byWood(trees: List<Tree>): Map<String, Int> {
        val (lat0, lon0, kx, kz) = frame
        val middles = woods.map { (name, at) -> name to ((at.second - lon0) * kx to -(at.first - lat0) * kz) }
        val out = sortedMapOf<String, Int>()
        for (t in trees) {
            val name = middles.firstOrNull { (_, m) -> hypot(t.x - m.first, t.z - m.second) <= 45.0 }?.first ?: "elsewhere"
            out.merge(name, 1, Int::plus)
        }
        return out
    }

    /** A zoo of [width] × [depth] metres about the zoo's middle, with [tags]. */
    private fun zoo(width: Double, depth: Double, tags: String) = square(10, zooLat, zooLon, width / 2, depth / 2, tags)

    @Test
    fun aZoosWoodsArePlantedWithinItsReachAndNoFarther() {
        // 400 × 250 m, 10 ha: its woods and those within 200 m of its outline
        val planted = build(zoo(400.0, 250.0, """"tourism": "zoo", "name": "Zoo""""))
        val got = byWood(planted)
        println("a zoo's window: its planted trees by wood $got")
        assertTrue("the wood in the zoo is planted: $got", (got["in"] ?: 0) >= 30)
        assertTrue("the wood 70 to 130 m beyond its edge is planted: $got", (got["near"] ?: 0) >= 30)
        assertNull("the wood 330 to 390 m beyond its edge is not: $got", got["far"])
        assertNull("no tree stands outside the woods: $got", got["elsewhere"])
        // the renders' trees: 13 to 21 m tall, crowns of 6 to 9 m
        for (t in planted) {
            assertTrue("height ${t.h}", t.h in 13.0..21.0)
            assertTrue("crown ${t.crown}", t.crown in 6.0..9.0)
        }
    }

    @Test
    fun aThemeParkPlantsItsWoodsAsAZooDoes() {
        val got = byWood(build(zoo(400.0, 250.0, """"tourism": "theme_park", "name": "Park"""")))
        println("a theme park's window: its planted trees by wood $got")
        assertTrue("$got", (got["in"] ?: 0) >= 30 && (got["near"] ?: 0) >= 30)
        assertNull("$got", got["far"])
    }

    @Test
    fun aCityFarmASmallZooAndNoZooPlantNothing() {
        // a city farm, as De Dierencapel on the Bickersgracht is mapped (tourism=zoo, zoo=petting_zoo), however big
        assertEquals(0, build(zoo(400.0, 250.0, """"tourism": "zoo", "zoo": "petting_zoo"""")).size)
        // a zoo of 150 × 150 m: 2.25 ha, under the 5 ha of a zoo whose woods are planted
        assertEquals(0, build(zoo(150.0, 150.0, """"tourism": "zoo"""")).size)
        // no zoo: the woods stay outlines, as the renders' regions but a park's and a zoo's draw them
        assertEquals(0, build(null).size)
    }

    /** Window [e]'s answer from the windows' data. */
    private fun answer(file: RandomAccessFile, e: WindowsIndex.Entry): ByteArray {
        val member = ByteArray(e.length)
        file.seek(e.offset)
        file.readFully(member)
        return GZIPInputStream(member.inputStream()).use { it.readBytes() }
    }

    /** An answer with every zoo and theme park in it taken for a city farm (zoo=petting_zoo): nothing else changes. */
    private fun bare(text: String): String {
        val root = Json.parseToJsonElement(text).jsonObject
        val elements = root.getValue("elements").jsonArray.map { e ->
            val o = e.jsonObject
            val tags = o["tags"] as? JsonObject
            val tourism = tags?.get("tourism")?.jsonPrimitive?.content
            if (tags == null || (tourism != "zoo" && tourism != "theme_park")) e else JsonObject(o + ("tags" to JsonObject(tags + ("zoo" to JsonPrimitive("petting_zoo")))))
        }
        return Json.encodeToString(JsonElement.serializer(), JsonObject(root + ("elements" to JsonArray(elements))))
    }

    @Test
    fun whatArtisWoodsCost() {
        val data = System.getProperty("atlas.windows")?.let(::File)
        assumeTrue("a region's windows' data holding Artis's (-Datlas.windows)", data != null && data.isFile)
        val index = WindowsIndex.read(File(data!!.path + ".idx").readBytes())
        val keep = System.getProperty("atlas.zoowoods.out")?.takeIf { it.isNotBlank() }?.let(::File)
        val dir = Files.createTempDirectory("atlas-zoo-woods-cost").toFile()
        var planted = 0
        try {
            RandomAccessFile(data, "r").use { file ->
                // Artis's window, its neighbours west and east, and the row south of them (Artis reaches into it)
                for (y in Y..Y + 1) for (x in X - 1..X + 1) {
                    val e = index.find(x, y)
                    assertNotNull("the data holds window 14/$x/$y", e)
                    val text = String(answer(file, e!!), Charsets.UTF_8)
                    val line = StringBuilder("window 14/$x/$y:")
                    for ((name, json) in listOf("planted" to text, "bare" to bare(text))) {
                        val input = File(dir, "$name.json").apply { writeText(json) }
                        val pack = File(dir, "$name-14-$x-$y.atlas")
                        val t0 = System.nanoTime()
                        assertEquals("14/$x/$y ($name)", "", Native.buildWindow(14, x, y, input.path, pack.path))
                        val ms = (System.nanoTime() - t0) / 1_000_000
                        val trees = trees(pack.path, x, y).size
                        if (name == "planted") planted += trees else assertEquals("14/$x/$y with Artis a city farm plants nothing", 0, trees)
                        line.append(" $name ${pack.length()} bytes, $trees trees, built in $ms ms;")
                        keep?.let { k ->
                            val windows = File(k, "$name/windows").apply { mkdirs() }
                            pack.copyTo(File(windows, "14-$x-$y.atlas"), overwrite = true)
                            File(windows, "14-$x-$y.tiles").writeText("built ${data.name}\n")
                        }
                        pack.delete()
                    }
                    println(line)
                }
            }
        } finally {
            dir.deleteRecursively()
        }
        assertTrue("Artis's woods are planted: $planted trees", planted > 100)
        println("Artis's woods: $planted trees planted in its six windows")
    }
}

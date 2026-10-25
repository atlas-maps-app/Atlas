package app.atlas.mapcorecheck.load

import app.atlas.rendertiles.compile.CoreTiles
import app.atlas.rendertiles.compile.SceneToTiles
import app.atlas.rendertiles.model.RenderHead
import app.atlas.rendertiles.model.StyleTables
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.io.IOException
import java.nio.file.Files

/**
 * The packer on the core's stage P (ENGINE.md step 3): the bundled render packs made by the packer with the core's tool
 * (`atlas_stagep`, `-Datlas.stagep`) making their content (the tiles, the head and the style) are the packer's own
 * bundled packs (`-Datlas.tiles`, made from the port's scene), byte for byte: the Westerkerk's living map and the canal
 * ring, every tile, the manifest, the head and the style. So a pack and a window the phone builds from the same data come
 * from the same code. The tool also refuses what the port's scene would refuse (a survey's heights).
 */
class StagePPackerTest {
    private val tool = System.getProperty("atlas.stagep")?.let(::File)
    private val repo = System.getProperty("atlas.repo")?.let(::File)

    @Test
    fun theBundledPacksCutByTheCoresStagePAreThePackersOwnByteForByte() {
        assumeTrue("the core's stage P tool (-Datlas.stagep)", tool != null && tool.canExecute())
        val tiles = File(System.getProperty("atlas.tiles") ?: error("atlas.tiles names no bundled render packs"))
        val root = repo ?: error("atlas.repo names no repository")
        val sources = listOf(
            "amsterdam" to File(root, "app/android/app/src/main/assets/packs/amsterdam.atlas"),
            "grachten" to File(root, "cache/packs/regions/grachten.atlas"),
        )
        val work = Files.createTempDirectory("atlas-stagep").toFile()
        try {
            for ((id, element) in sources) {
                if (!element.isFile) {
                    println("stagep: no element pack ${element.path}: $id is not compared here")
                    continue
                }
                val want = File(tiles, "$id.atlas")
                assertTrue("no bundled render pack ${want.path}", want.isFile)
                val made = File(work, "$id.atlas")
                val t0 = System.nanoTime()
                val p = SceneToTiles.pack(element, made, File(work, "engine-$id"), core = tool) { println("stagep $it") }
                println("stagep $id: ${p.written.chunks.size} tiles, ${p.written.bytes} bytes in ${(System.nanoTime() - t0) / 1_000_000} ms")
                assertEquals("$id: the pack cut by the core's stage P is not the packer's: ${difference(made.readBytes(), want.readBytes())}", "the same", difference(made.readBytes(), want.readBytes()))
            }
        } finally {
            work.deleteRecursively()
        }
    }

    /**
     * The port's and Schiphol's packs with their height survey left out (SceneToTiles.withoutSurvey: the tiles cannot
     * carry a survey yet), and the Efteling's and ARTIS's, cut by the core's stage P, are the packer's own cut of them
     * byte for byte: what they hold that the bundled packs do not (gantries and conveyors, cranes, turbines, masts,
     * runways, the sea, a theme park's coaster tracks, a park's and a zoo's planted woods) included. On the Mac, where
     * their element packs are (CI has the canal ring's alone).
     */
    @Test
    fun theRegionsWithoutTheirSurveyCutByTheCoresStagePAreThePackersOwn() {
        assumeTrue("the core's stage P tool (-Datlas.stagep)", tool != null && tool.canExecute())
        val root = repo ?: error("atlas.repo names no repository")
        val work = Files.createTempDirectory("atlas-stagep-survey").toFile()
        try {
            for (id in listOf("port", "schiphol", "efteling", "artis")) {
                val element = File(root, "cache/packs/regions/$id.atlas")
                if (!element.isFile) {
                    println("stagep: no element pack ${element.path}: $id is not compared here")
                    continue
                }
                val mine = File(work, "$id-core.atlas")
                val theirs = File(work, "$id-packer.atlas")
                // the port's and Schiphol's surveys left out; the others have none (and their packs' own files, a park's
                // rides' profiles among them, unpack only from the pack itself)
                val survey = id !in listOf("port", "schiphol")
                val t0 = System.nanoTime()
                val c = SceneToTiles.pack(element, mine, File(work, "core-$id"), core = tool, survey = survey) { println("stagep $it") }
                val t1 = System.nanoTime()
                val p = SceneToTiles.pack(element, theirs, File(work, "packer-$id"), survey = survey) { println("packer $it") }
                val t2 = System.nanoTime()
                println("stagep $id without its survey: ${c.written.chunks.size} tiles, ${c.written.bytes} bytes in ${(t1 - t0) / 1_000_000} ms; the packer's ${p.written.chunks.size} tiles in ${(t2 - t1) / 1_000_000} ms")
                assertEquals("$id: the pack cut by the core's stage P is not the packer's: ${difference(mine.readBytes(), theirs.readBytes())}", "the same", difference(mine.readBytes(), theirs.readBytes()))
            }
        } finally {
            work.deleteRecursively()
        }
    }

    @Test
    fun theToolsRefusalsAndItsTilesFile() {
        assumeTrue("the core's stage P tool (-Datlas.stagep)", tool != null && tool.canExecute())
        // a usage error says so, with exit code 2
        val p = ProcessBuilder(tool!!.path).redirectErrorStream(true).start()
        val said = p.inputStream.bufferedReader().readText()
        assertEquals(2, p.waitFor())
        assertTrue(said, said.startsWith("usage: atlas_stagep tiles"))
        // a tiles file that is not one, or of the format before the core wrote the head and the style, is refused,
        // never read as tiles
        val bad = runCatching { CoreTiles.read("ATSQ".toByteArray()) }.exceptionOrNull()
        assertTrue("$bad", bad is IOException)
        val old = runCatching { CoreTiles.read(byteArrayOf(65, 84, 83, 80, 1, 0, 0, 0, 0, 0, 0, 0)) }.exceptionOrNull()
        assertTrue("$old", old is IOException && "format 1" in old.message!!)
        val short = runCatching { CoreTiles.read(byteArrayOf(65, 84, 83, 80, 2, 0, 0, 0, 1, 0, 0, 0, 14)) }.exceptionOrNull()
        assertTrue("$short", short is IOException && "ends early" in short.message!!)
        val headless = runCatching { CoreTiles.read(byteArrayOf(65, 84, 83, 80, 2, 0, 0, 0, 0, 0, 0, 0)) }.exceptionOrNull()
        assertTrue("$headless", headless is IOException && "ends early" in headless.message!!)
        val empty = CoreTiles.read(byteArrayOf(65, 84, 83, 80, 2, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0))
        assertEquals(0, empty.tiles.size)
        assertEquals(0, empty.head.size + empty.style.size)
    }

    @Test
    fun theToolRefusesWhatThePortsSceneWouldAndHeadsARegionFromItsRegionJson() {
        assumeTrue("the core's stage P tool (-Datlas.stagep)", tool != null && tool.canExecute())
        val dir = Files.createTempDirectory("atlas-stagep-regions").toFile()
        try {
            val box = """"bbox": [52.36, 4.907, 52.3725, 4.9265], "origin": [52.3662, 4.9165]"""
            val views = """"views": {"": {"target": [0, 0, 0], "yaw": 95, "pitch": 42, "dist": 1600}, "close": {"target": [1, 0, 1], "yaw": 95, "pitch": 40, "dist": 430}}"""
            // the tool on a region folder with no data: its exit code and what it said
            fun run(name: String, region: String, survey: Boolean = false, parkRoot: File? = null): Pair<Int, String> {
                val folder = File(dir, name).apply { mkdirs() }
                File(folder, "raw.json").writeText("""{"elements": []}""")
                File(folder, "region.json").writeText(region)
                if (survey) File(folder, "dem.json").writeText("{}")
                val job = File(dir, "$name.json")
                job.writeText(
                    buildJsonObject {
                        put("profile", "region")
                        put("region", name)
                        putJsonArray("levels") { add(14); add(12) }
                        putJsonArray("frame") { for (d in listOf(52.3662, 4.9165, 68182.6, 110540.0)) add(JsonPrimitive("%016x".format(d.toRawBits()))) }
                        put("engine_version", "a1-test")
                        put("source", "00ff")
                        put("region_json", File(folder, "region.json").path)
                        if (parkRoot != null) put("park_root", parkRoot.path)
                        putJsonArray("woods") { for (d in listOf(0.0, 0.0, 500.0)) add(JsonPrimitive("%016x".format(d.toRawBits()))) }
                    }.toString(),
                )
                val p = ProcessBuilder(tool!!.path, "tiles", File(folder, "raw.json").path, job.path, File(dir, "$name.tiles").path).redirectErrorStream(true).start()
                val said = p.inputStream.bufferedReader().readText()
                return p.waitFor() to said
            }
            // a zoo plants its woods, a theme park its woods and its coaster tracks, their rides' profiles from the job's
            // park_root (with no data: nothing); a park whose job names none is refused as an input error
            val zoo = run("zoo", """{$box, "zoo": "artis", $views}""")
            assertEquals(zoo.second, 0, zoo.first)
            val park = run("park", """{$box, "park": "efteling", $views}""", parkRoot = File(repo ?: error("atlas.repo names no repository"), "data/park"))
            assertEquals(park.second, 0, park.first)
            val unpacked = run("unpacked", """{$box, "park": "efteling", $views}""")
            assertEquals(unpacked.second, 2, unpacked.first)
            assertTrue(unpacked.second, "park_root" in unpacked.second)
            // a survey's heights: what the port's scene holds and its tiles cannot carry yet (SceneToTiles.unsupported)
            val survey = run("survey", """{$box, $views}""", survey = true)
            assertEquals(survey.second, CoreTiles.NOT_CARRIED, survey.first)
            assertTrue(survey.second, "a survey's heights" in survey.second)
            // without them, the head's views come from region.json nearest first, with the live map's bands, and the
            // style's scene is the region loaded whole
            val plain = run("plain", """{$box, $views}""")
            assertEquals(plain.second, 0, plain.first)
            val read = CoreTiles.read(File(dir, "plain.tiles").readBytes())
            assertEquals(0, read.tiles.size)
            val head = RenderHead.fromBytes(read.head)
            assertEquals(listOf("plain_close", "plain"), head.views.map { it.name })
            assertEquals(5, head.bands.size)
            assertEquals("a1-test", head.engineVersion)
            assertEquals("00ff", head.source)
            val scene = StyleTables.fromBytes(read.style)["scene"] as Map<*, *>
            assertEquals("plain", scene["region"])
            assertEquals("plain_whole", scene["view"])
            assertEquals(null, scene["tradition"])
        } finally {
            dir.deleteRecursively()
        }
    }

    /** Where two byte strings first differ, or "the same". */
    private fun difference(a: ByteArray, b: ByteArray): String {
        for (i in 0 until minOf(a.size, b.size)) {
            if (a[i] != b[i]) return "byte $i: ${a[i].toInt() and 0xFF}, not ${b[i].toInt() and 0xFF}"
        }
        return if (a.size != b.size) "${a.size} bytes, not ${b.size}" else "the same"
    }
}

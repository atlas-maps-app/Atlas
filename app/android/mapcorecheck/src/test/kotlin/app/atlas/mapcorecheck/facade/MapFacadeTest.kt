package app.atlas.mapcorecheck.facade

import app.atlas.anchor.AnchorCases
import app.atlas.mapcore.Native
import app.atlas.mapcore.state.LabelBlock
import app.atlas.mapcore.state.ThemeBlock
import app.atlas.render.Amsterdam
import app.atlas.render.Regions
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.BeforeClass
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.file.Files

/**
 * The app's map through the façade on the JVM against the core's host build (plan-vector K11, `CP/jni/Bridge.cpp`'s
 * map, packs, moment, labels and screen): the packs the app lists open in the map's world (listed before it is made, opened
 * after, kept when listed again, a broken one refused with its reason), the map turns on without a window (hosts have
 * none: it draws once one comes) and off, the moment's theme block is checked as the core reads it, and the labels'
 * block as `state.LabelBlock` writes it is read by the core (a sprite without points, or wider than a sprite may be,
 * or large images at a scale below 1, refused for that: so its fields are read where they are written; a block in the
 * first layout, LBL1, refused).
 */
class MapFacadeTest {
    companion object {
        @BeforeClass
        @JvmStatic
        fun load() {
            val path = System.getProperty("atlas.mapcore.host")
            assumeTrue("no host build (-Datlas.mapcore.host)", path != null)
            assertNull(Native.load(path))
        }
    }

    /** A pack's JSON manifest, after its 20-byte header (whose bytes 12 to 15 give its length, big-endian). */
    private fun manifestOf(file: File): String {
        val bytes = file.readBytes()
        return String(bytes, 20, ByteBuffer.wrap(bytes, 12, 4).int, Charsets.UTF_8)
    }

    private fun packs(handle: Long, tiles: File, vararg ids: String): String = Native.packs(
        handle, arrayOf(*ids), LongArray(ids.size) { it + 1L }, IntArray(ids.size), Array(ids.size) { File(tiles, "${ids[it]}.atlas").path },
        IntArray(ids.size) { -1 }, LongArray(ids.size), LongArray(ids.size),
    )

    @Test
    fun theMapsPacksMomentAndSwitchReachTheCore() {
        val tiles = System.getProperty("atlas.tiles")?.let(::File)
        assumeTrue("the bundled render packs (-Datlas.tiles)", tiles != null && File(tiles, "amsterdam.atlas").isFile)
        tiles!!
        val dir = Files.createTempDirectory("atlas-map-facade").toFile()
        try {
            val guard = dir.resolve("vk").path
            val handle = Native.create(dir.path, guard, "jvm-test", "", 0, "", 0L)
            assertNotEquals("create refused: ${Native.lastError()}", 0L, handle)
            try {
                // Before the map is made the packs are listed; once it is, they open in its world.
                assertEquals("amsterdam listed\n", packs(handle, tiles, "amsterdam"))
                assertEquals("", Native.map(handle, 1))
                assertEquals("amsterdam kept\ngrachten opened\n", packs(handle, tiles, "amsterdam", "grachten"))
                assertEquals("amsterdam kept\ngrachten kept\n", packs(handle, tiles, "amsterdam", "grachten"))
                // A file that is no pack: refused, saying why; a number of 0 or a kind past the known ones: not listed.
                val junk = dir.resolve("junk.atlas").apply { writeText("not a pack") }
                val said = Native.packs(handle, arrayOf("junk"), longArrayOf(3), intArrayOf(1), arrayOf(junk.path), intArrayOf(-1), longArrayOf(0), longArrayOf(0))
                assertTrue(said, said.startsWith("junk: "))
                assertEquals(
                    "bad: not a pack the map can list\n",
                    Native.packs(handle, arrayOf("bad"), longArrayOf(0), intArrayOf(0), arrayOf(junk.path), intArrayOf(-1), longArrayOf(0), longArrayOf(0)),
                )
                assertEquals(
                    "packs: the arrays differ in length",
                    Native.packs(handle, arrayOf("a", "b"), longArrayOf(1), intArrayOf(0, 0), arrayOf("", ""), intArrayOf(-1, -1), longArrayOf(0, 0), longArrayOf(0, 0)),
                )

                // The moment: the port's sunny theme as the app writes it; a block of another version is refused.
                Regions.resetPlace()
                val th = Amsterdam.THEMES.getValue("sunny")
                val (az, el, sun) = Amsterdam.sunOf(th)
                val block = ThemeBlock.allocate()
                ThemeBlock.write(block, th, doubleArrayOf(sun.x, sun.y, sun.z), az, el, golden = false, tone = false)
                assertEquals("", Native.moment(handle, block))
                block.putInt(ThemeBlock.VERSION_AT, 2)
                assertEquals("the theme was refused: version", Native.moment(handle, block))
                assertEquals("the theme must be a direct buffer of 256 bytes", Native.moment(handle, ByteBuffer.allocate(256)))

                // The labels: two sprites as the app writes them (the first with its image at the band's top); none (an
                // empty block); refused, and why.
                fun image(width: Int, height: Int) = LabelBlock.Image(width, height, width / 2, height - 2, IntArray(width * height) { it * 0x01010101 })
                fun sprite(id: Int, width: Int, height: Int, points: DoubleArray, large: LabelBlock.Image? = null) =
                    LabelBlock.Sprite(id, 3, 40.5f, 10.5f, 20f, -7f, image(width, height), large, points)
                val two = listOf(
                    sprite(1, 6, 4, doubleArrayOf(52.37, 4.88), image(9, 6)),
                    sprite(2, 3, 5, doubleArrayOf(52.38, 4.89, 52.381, 4.891)),
                )
                assertEquals(12 + 2 * (44 + 16) + 8 * 6 + 4 * (24 + 54 + 15), LabelBlock.size(two))
                assertEquals("", Native.labels(handle, LabelBlock.write(two), 1L, 0.7f))
                assertEquals("", Native.labels(handle, LabelBlock.write(emptyList()), 2L, 0f))
                assertEquals(
                    "the labels were refused: the large images' scale is not 0 or from 1 to 4, or 0 with a large image",
                    Native.labels(handle, LabelBlock.write(two, largeScale = 0.5f), 7L, 0f),
                )
                assertEquals(
                    "the labels were refused: a label has no points, or more than 1024",
                    Native.labels(handle, LabelBlock.write(listOf(sprite(3, 2, 2, DoubleArray(0)))), 3L, 0f),
                )
                assertEquals(
                    "the labels were refused: sprite 0 is larger than a sprite may be",
                    Native.labels(handle, LabelBlock.write(listOf(sprite(4, 3000, 1, doubleArrayOf(52.37, 4.88)))), 4L, 0f),
                )
                val notLabels = ByteBuffer.allocateDirect(8).order(java.nio.ByteOrder.LITTLE_ENDIAN).putInt(7).putInt(0).flip() as ByteBuffer
                assertEquals("the labels were refused: not a block of label sprites", Native.labels(handle, notLabels, 5L, 0f))
                val first = ByteBuffer.allocateDirect(12).order(java.nio.ByteOrder.LITTLE_ENDIAN).putInt(0x314C424C).putInt(0).putInt(0).flip() as ByteBuffer
                assertEquals("the labels were refused: not a block of label sprites", Native.labels(handle, first, 8L, 0f))
                assertEquals("the labels must be a direct buffer", Native.labels(handle, ByteBuffer.allocate(8), 6L, 0f))

                // The screen's haze and vignette off, then on again (the app's Look settings).
                Native.screen(handle, false, false)
                Native.screen(handle, true, true)

                // Off, and modes the façade does not have.
                assertEquals("", Native.map(handle, 0))
                assertEquals("no map mode 7", Native.map(handle, 7))
                assertEquals("", Native.map(handle, 2)) // on again, as a TextureView's
                assertEquals("", Native.map(handle, 0))
            } finally {
                Native.close(handle)
            }
            // The close let go of the map's world behind the close stage: no marker left.
            assertTrue(!dir.resolve("vk/guard").exists())
        } finally {
            Regions.resetPlace()
            dir.deleteRecursively()
        }
    }

    @Test
    fun aWindowBuiltThroughTheFacadeJoinsTheMapsPacks() {
        // the Westerkerk's L14 tile, its data the Westerkerk's raw.json (the renders' query of a larger box)
        val raw = File(AnchorCases.westerkerk().dir, "raw.json")
        assumeTrue("the Westerkerk's raw.json", raw.isFile)
        val x = ((4.8840 + 180.0) / 360.0 * 16384).toInt()
        val lat = Math.toRadians(52.3746)
        val y = ((1.0 - Math.log(Math.tan(lat) + 1.0 / Math.cos(lat)) / Math.PI) / 2.0 * 16384).toInt()
        val id = "window/14/$x/$y"
        val box = Native.windowBox(14, x, y)
        assertEquals(4, box.size)
        assertTrue(box.joinToString(), box[0] < 52.3746 && 52.3746 < box[2] && box[1] < 4.8840 && 4.8840 < box[3])
        val dir = Files.createTempDirectory("atlas-window-facade").toFile()
        try {
            // its data a file, which the core maps
            val file = File(dir, "14-$x-$y.atlas")
            assertEquals("", Native.buildWindow(14, x, y, raw.path, file.path))
            assertTrue(file.isFile && file.length() > 0)
            assertTrue(!File(dir, "14-$x-$y.atlas.part").exists())
            // its manifest counts its tile and names the version of a window built now, as the core gives it
            val version = Native.windowVersion()
            assertTrue(version, Regex("""core-p\d+ s\S+""").matches(version))
            val manifest = manifestOf(file)
            assertTrue(manifest, manifest.contains(""""chunks":1,"engine_version":"${version.substringBefore(' ')}","style_version":"${version.substringAfter(' ')}""""))
            // a window of another level, data that is no Overpass answer, or no data: refused, saying why
            assertTrue(Native.buildWindow(13, x / 2, y / 2, raw.path, File(dir, "w13").path).contains("no window"))
            val junk = File(dir, "junk.json").apply { writeText("nope") }
            assertTrue(Native.buildWindow(14, x, y, junk.path, File(dir, "junk.atlas").path).startsWith("the data does not read"))
            assertTrue(Native.buildWindow(14, x, y, File(dir, "none.json").path, File(dir, "none.atlas").path).startsWith("the window's data"))
            // listed as a pack of kind window, it opens in the map's world as its frame cell's
            val guard = dir.resolve("vk").path
            val handle = Native.create(dir.path, guard, "jvm-test", "", 0, "", 0L)
            assertNotEquals("create refused: ${Native.lastError()}", 0L, handle)
            try {
                assertEquals("", Native.map(handle, 1))
                val said = Native.packs(handle, arrayOf(id), longArrayOf(1L), intArrayOf(2), arrayOf(file.path), intArrayOf(-1), LongArray(1), LongArray(1))
                assertEquals("$id opened\n", said)
                // no frame drawn yet: no window wanted, none in view
                assertEquals(0, Native.wantedWindows(handle).size)
                assertEquals(0, Native.windowsInView(handle).size)
            } finally {
                Native.close(handle)
            }
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun aWindowsKeptDataMergedWithOverpassChangesThroughTheFacadeIsTheFreshAnswer() {
        // the Westerkerk's raw.json kept (Overpass's own answer, the repository's data/ams/raw.json), and a bench placed
        // since: Overpass's diff of the query creates it
        val raw = generateSequence(File("").absoluteFile) { it.parentFile }.take(6).map { File(it, "data/ams/raw.json") }.firstOrNull { it.isFile }
        assumeTrue("the repository's data/ams/raw.json", raw != null)
        val source = raw!!
        val kept = source.readText()
        val bench = "{\n  \"type\": \"node\",\n  \"id\": 26973328,\n  \"lat\": 52.3745000,\n  \"lon\": 4.8838000,\n" +
            "  \"tags\": {\n    \"amenity\": \"bench\",\n    \"name\": \"A \\\"bench\\\" & co\"\n  }\n}"
        val second = kept.indexOf("{\n  \"type\": \"node\",\n  \"id\": 34044750,")
        assumeTrue("the Westerkerk's raw.json as the tests know it", kept.contains("\"id\": 26973327,") && second > 0)
        val fresh = kept.substring(0, second) + bench + ",\n" + kept.substring(second)
        fun diff(actions: String) = (
            "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<osm version=\"0.6\" generator=\"Overpass API 0.7.62.11 87bfad18\">\n" +
                "<meta osm_base=\"2026-10-09T08:46:19Z\"/>\n\n$actions\n</osm>\n"
            )
        fun direct(s: String) = s.toByteArray().let { ByteBuffer.allocateDirect(it.size).put(it).flip() as ByteBuffer }
        val created = diff(
            "<action type=\"create\">\n  <node id=\"26973328\" lat=\"52.3745000\" lon=\"4.8838000\">\n" +
                "    <tag k=\"amenity\" v=\"bench\"/>\n    <tag k=\"name\" v=\"A &quot;bench&quot; &amp; co\"/>\n  </node>\n</action>\n",
        )
        val x = ((4.8840 + 180.0) / 360.0 * 16384).toInt()
        val lat = Math.toRadians(52.3746)
        val y = ((1.0 - Math.log(Math.tan(lat) + 1.0 / Math.cos(lat)) / Math.PI) / 2.0 * 16384).toInt()
        val dir = Files.createTempDirectory("atlas-window-merge").toFile()
        val out = File(dir, "merged.json")
        assertTrue(Native.mergeWindow(14, x, y, source.path, direct(created), out.path))
        assertTrue(!File(out.path + ".part").exists())
        val text = out.readText()
        // element for element the fresh answer's bytes, with the diff's date and the date the data was fetched whole
        assertEquals(fresh.substring(fresh.indexOf("\"elements\": [")), text.substring(text.indexOf("\"elements\": [")))
        assertTrue(text.contains("\"timestamp_osm_base\": \"2026-10-09T08:46:19Z\""))
        assertTrue(text.contains("\"whole\": \"2026-10-02T08:46:19Z\""))
        // a diff that changes nothing: false, and nothing written; one that cannot be merged exactly: why
        val none = File(dir, "none.json")
        assertTrue(!Native.mergeWindow(14, x, y, source.path, direct(diff("")), none.path))
        assertTrue(!none.exists())
        val refused = runCatching { Native.mergeWindow(14, x, y, source.path, direct(diff("<node id=\"1\" lat=\"1\" lon=\"2\"/>")), none.path) }
        assertTrue(refused.exceptionOrNull()?.message.orEmpty(), refused.exceptionOrNull()?.message.orEmpty().startsWith("the diff is refused: unsupported"))
        val remark = runCatching { Native.mergeWindow(14, x, y, source.path, direct(diff("<remark> runtime error: Query timed out </remark>")), none.path) }
        assertTrue(remark.exceptionOrNull()?.message.orEmpty().contains("runtime error: Query timed out"))
        val disordered = File(dir, "disordered.json").apply {
            writeText("{\"osm3s\": {\"timestamp_osm_base\": \"2026-10-09T08:46:19Z\"}, \"elements\": [{\"type\": \"way\", \"id\": 1}, {\"type\": \"node\", \"id\": 1}]}")
        }
        val unsorted = runCatching { Native.mergeWindow(14, x, y, disordered.path, direct(created), none.path) }
        assertTrue(unsorted.exceptionOrNull()?.message.orEmpty().startsWith("the merge is refused: kept"))
        // the window built from the kept data, then built again from the merge at the same path: listed with the file's
        // time and size, the core opens the new bytes anew, and keeps them while they stay
        try {
            val file = File(dir, "14-$x-$y.atlas")
            val id = "window/14/$x/$y"
            assertEquals("", Native.buildWindow(14, x, y, source.path, file.path))
            val handle = Native.create(dir.path, dir.resolve("vk").path, "jvm-test", "", 0, "", 0L)
            assertNotEquals("create refused: ${Native.lastError()}", 0L, handle)
            try {
                assertEquals("", Native.map(handle, 1))
                fun hand() = Native.packs(handle, arrayOf(id), longArrayOf(1L), intArrayOf(2), arrayOf(file.path), intArrayOf(-1), longArrayOf(file.lastModified()), longArrayOf(file.length()))
                assertEquals("$id opened\n", hand())
                assertEquals("$id kept\n", hand())
                assertEquals("", Native.buildWindow(14, x, y, out.path, file.path))
                assertTrue(file.setLastModified(file.lastModified() + 7 * 24 * 3600 * 1000L))
                assertEquals("$id opened\n", hand())
                assertEquals("$id kept\n", hand())
            } finally {
                Native.close(handle)
            }
        } finally {
            dir.deleteRecursively()
        }
    }
}


package app.atlas.packer

import app.atlas.geo.LatLon
import app.atlas.render.data.PackReader
import app.atlas.render.live.EngineVersion
import app.atlas.render.live.HttpGet
import app.atlas.render.live.PackLibrary
import app.atlas.rendertiles.model.Format
import app.atlas.rendertiles.model.StyleVersion
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.net.URI

/**
 * `packer render-tiles`: its command line, the canal ring's fetch, the bundled set as Gradle makes it, and the render
 * packs of every region of packs-renders' list (K20a) with the list as the library reads it.
 */
class RenderTilesTest {
    @get:Rule val tmp = TemporaryFolder()

    private val repo = File(System.getProperty("atlas.repo") ?: error("atlas.repo is not set"))

    /** packs-renders' element packs and its packs.json, kept in the repo's cache on the owner's machine. */
    private val cache = File(repo, "cache/packs/regions")
    private val list = File(cache, "packs.json")

    private fun obj(f: File) = Json.parseToJsonElement(f.readText()) as JsonObject
    private fun JsonObject.str(k: String) = (this[k] as? JsonPrimitive)?.content
    private fun JsonObject.packs() = (this["packs"] as JsonArray).map { it as JsonObject }
    private fun JsonObject.files() = (this["files"] as JsonArray).map { it as JsonObject }
    private fun JsonObject.file(kind: String) = files().firstOrNull { it.str("kind") == kind }

    /** Whether [list] and every element pack it names are in the cache (no test downloads them). */
    private fun cachedList(): Boolean = list.isFile && obj(list).packs().all { p -> p.file("atlas")?.str("name")?.let { File(cache, it).isFile } == true }

    @Test
    fun `a bad command line is a usage error`() {
        val json = tmp.newFile("packs.json").apply { writeText("""{"packs":[]}""") }
        val westerkerk = File(repo, RenderTiles.WESTERKERK).path
        for (
            args in listOf(
                listOf("render-tiles"), listOf("render-tiles", "--bundled"), listOf("render-tiles", "--bundled", "a", "b"), listOf("render-tiles", "--nope", "x"),
                listOf("render-tiles", "--renders", "nope.atlas", "out.atlas"), listOf("render-tiles", "--renders", json.path),
                listOf("render-tiles", "--renders", json.path, "out", "--repo", "."), listOf("render-tiles", "--bundled", "out", "--only", "paris"),
                listOf("render-tiles", "--renders", westerkerk, "out.atlas", "--only", "paris"), listOf("render-tiles", "--renders", westerkerk, "out.atlas", "--packs", "."),
                listOf("render-tiles", "--renders", json.path, tmp.root.path, "--only", "paris"),
            )
        ) {
            try {
                RenderTiles.run(args) {}
                fail("$args ran")
            } catch (e: UsageException) {
            }
        }
    }

    @Test
    fun `the library reads the render packs the writer makes`() {
        assertEquals(Format.PAYLOAD, PackLibrary.RENDER_PAYLOAD)
        assertEquals(Format.VERSION, PackLibrary.RENDER_VERSION)
        assertEquals(Format.LAYOUT, PackLibrary.RENDER_LAYOUT)
    }

    @Test
    fun `a refusal is told from a failure`() {
        val carry = "artis: render tiles cannot carry a park's planted woods (K15) yet"
        val column = "tree 54 (5927576885): the field style (Style) has no column in the tree layer: give it one, or add it to Schema.DROPPED with why the renders never read it"
        assertEquals(carry, RenderTiles.refusal(UnsupportedOperationException(carry)))
        assertEquals(column, RenderTiles.refusal(IllegalStateException(column)))
        assertEquals(carry, RenderTiles.refusal(RuntimeException("loading", UnsupportedOperationException(carry))))
        for (
            other in listOf(
                IllegalStateException("building 7: z = 1.0 is not on the grid of paris's frame"), UnsupportedOperationException(),
                UnsupportedOperationException("Operation is not supported for read-only collection"), IllegalArgumentException(carry), IOException(column),
                OutOfMemoryError("Java heap space"),
            )
        ) assertNull("$other", RenderTiles.refusal(other))
    }

    @Test
    fun `the canal ring is taken from the cache only when its hash is the published one`() {
        val dir = tmp.newFolder("packs")
        val src = RenderTiles.Source("grachten", "https://127.0.0.1:1/grachten.atlas", "00".repeat(32), 5)
        File(dir, "grachten.atlas").writeBytes(byteArrayOf(1, 2, 3, 4, 5))
        try {
            RenderTiles.fetch(src, dir)
            fail("a cached file of another hash was used")
        } catch (e: IOException) {
        }
        try {
            RenderTiles.fetch(RenderTiles.Source("grachten", "http://example.invalid/grachten.atlas", "00".repeat(32), 5), dir)
            fail("an element pack came over plain http")
        } catch (e: IOException) {
            assertTrue(e.message!!.contains("https"))
        }
        val cached = File(repo, "cache/packs/regions/grachten.atlas")
        assumeTrue(cached.isFile)
        assertEquals(cached.canonicalFile, RenderTiles.fetch(RenderTiles.CANAL_RING, cached.parentFile).canonicalFile)
        // the pinned hash is packs.json's
        val packs = File(repo, "cache/packs/regions/packs.json")
        if (packs.isFile) {
            val entry = (Json.parseToJsonElement(packs.readText()) as JsonObject)["packs"] as JsonArray
            val g = entry.map { it as JsonObject }.first { it["id"]!!.jsonPrimitive.content == "grachten" }
            val f = (g["files"] as JsonArray).first() as JsonObject
            assertEquals(f["sha256"]!!.jsonPrimitive.content, RenderTiles.CANAL_RING.sha256)
            assertEquals(f["url"]!!.jsonPrimitive.content, RenderTiles.CANAL_RING.url)
            assertEquals(f["bytes"]!!.jsonPrimitive.content.toLong(), RenderTiles.CANAL_RING.bytes)
        }
    }

    @Test
    fun `the bundled set is made with its index`() {
        assumeTrue(File(repo, "cache/packs/regions/grachten.atlas").isFile)
        val out = tmp.newFolder("tiles")
        val lines = ArrayList<String>()
        assertEquals(0, RenderTiles.run(listOf("render-tiles", "--bundled", out.path, "--repo", repo.path, "--work", tmp.newFolder("work").path), lines::add))
        val index = Json.parseToJsonElement(File(out, RenderTiles.INDEX).readText()) as JsonObject
        val packs = (index["packs"] as JsonArray).map { it as JsonObject }
        assertEquals(listOf("amsterdam", "grachten"), packs.map { it["id"]!!.jsonPrimitive.content })
        for (p in packs) {
            val f = File(out, p["file"]!!.jsonPrimitive.content)
            assertEquals(f.length(), p["bytes"]!!.jsonPrimitive.content.toLong())
            assertEquals(RenderTiles.sha256(f), p["sha256"]!!.jsonPrimitive.content)
            val head = f.readBytes().copyOf(10)
            assertEquals("ATLASPCK", String(head, 0, 8, Charsets.US_ASCII))
            assertEquals(3, head[9].toInt())
        }
        assertEquals(setOf("amsterdam.atlas", "grachten.atlas", RenderTiles.INDEX), out.list()!!.toSet())
        println("RENDERTILES ${lines.joinToString(" | ")}")
        assertTrue(packs.first { it["id"]!!.jsonPrimitive.content == "grachten" }["profile"]!!.jsonPrimitive.content == "region")
    }

    /**
     * The app's set (ENGINE.md step 3, one Netherlands): the index lists the canal ring alone, which the map draws, the
     * Westerkerk's place among it; the Westerkerk's pack is written beside it for VK facts' hash lines; a pack the set
     * does not hold is refused before anything is made.
     */
    @Test
    fun `the app's set lists the packs the map draws, the Westerkerk beside them for the checks`() {
        assumeTrue(File(repo, "cache/packs/regions/grachten.atlas").isFile)
        val out = tmp.newFolder("tiles")
        val args = listOf("render-tiles", "--bundled", out.path, "--repo", repo.path, "--work", tmp.newFolder("work").path, "--quiet")
        try {
            RenderTiles.run(args + listOf("--draw", "grachten,rotterdam")) {}
            fail("a pack the set does not hold was listed")
        } catch (e: UsageException) {
            assertTrue(e.message!!.contains("rotterdam"))
        }
        assertEquals(0, RenderTiles.run(args + listOf("--draw", "grachten")) {})
        val index = Json.parseToJsonElement(File(out, RenderTiles.INDEX).readText()) as JsonObject
        val packs = (index["packs"] as JsonArray).map { it as JsonObject }
        assertEquals(listOf("grachten"), packs.map { it["id"]!!.jsonPrimitive.content })
        assertEquals(RenderTiles.sha256(File(out, "grachten.atlas")), packs.single()["sha256"]!!.jsonPrimitive.content)
        assertEquals(setOf("amsterdam.atlas", "grachten.atlas", RenderTiles.INDEX), out.list()!!.toSet())
        assertEquals("ATLASPCK", String(File(out, "amsterdam.atlas").readBytes().copyOf(8), Charsets.US_ASCII))
    }

    /**
     * The renders' list as packs.yml's step runs it: every region gets its render pack or SceneToTiles' own reason; the
     * render packs are the bundled set's bytes where both are made, within 35 % of their element packs (grachten's
     * measured 35.5 % excepted); a second run over its own list changes nothing; and the library reads the list, fetches
     * a render pack with its element pack on the first visit and treats a region without one as normal.
     */
    @Test
    fun `every region of the renders' list gets its render pack or SceneToTiles' reason, and the library reads the list`() {
        assumeTrue("packs-renders' packs in the cache", cachedList())
        val out = tmp.newFolder("renders")
        val work = tmp.newFolder("work")
        val lines = ArrayList<String>()
        assertEquals(0, RenderTiles.run(listOf("render-tiles", "--renders", list.path, out.path, "--packs", cache.path, "--work", work.path), lines::add))
        val input = obj(list)
        val output = obj(File(out, RenderTiles.LIST))
        assertEquals("the list's other fields stay", input.keys, output.keys)
        for (k in input.keys - setOf("packs", "generated")) assertEquals(k, input[k], output[k])
        assertEquals(input.packs().map { it.str("id") }, output.packs().map { it.str("id") })
        val built = LinkedHashMap<String, JsonObject>()
        val gaps = LinkedHashMap<String, String>()
        for ((before, after) in input.packs().zip(output.packs())) {
            val id = after.str("id")!!
            for (k in before.keys - setOf("files", PackLibrary.RENDER_GAP)) assertEquals("$id's $k", before[k], after[k])
            assertEquals("$id's other files stay", before.files().filter { it.str("kind") != PackLibrary.RENDER_KIND }, after.files().filter { it.str("kind") != PackLibrary.RENDER_KIND })
            val element = after.file("atlas")!!
            val render = after.file(PackLibrary.RENDER_KIND)
            val gap = after.str(PackLibrary.RENDER_GAP)
            assertTrue("$id has a render pack or a reason, not both", (render == null) != (gap == null))
            val f = File(out, "$id${PackLibrary.RENDER_SUFFIX}")
            if (gap != null) {
                assertNotNull("$id's reason is SceneToTiles' refusal: $gap", RenderTiles.refusal(if ("cannot carry" in gap) UnsupportedOperationException(gap) else IllegalStateException(gap)))
                assertFalse("$id has no render pack file", f.exists())
                gaps[id] = gap
                continue
            }
            render!!
            assertEquals(f.name, render.str("name"))
            assertEquals(f.length(), render.str("bytes")!!.toLong())
            assertEquals(RenderTiles.sha256(f), render.str("sha256"))
            assertEquals(URI(element.str("url")!!).resolve(f.name).toString(), render.str("url"))
            assertEquals(element.str("sha256"), render.str("source_sha256"))
            assertEquals("atlas-pack", render.str("format"))
            assertEquals(Format.VERSION.toString(), render.str("version"))
            assertEquals(Format.LAYOUT, render.str("layout"))
            assertEquals(Format.PAYLOAD.toString(), render.str("payload"))
            assertEquals(EngineVersion.ID, render.str("engine"))
            assertEquals(StyleVersion.ID, render.str("style"))
            val head = PackLibrary.renderHead(f)
            assertEquals(id, head.id)
            assertEquals(Format.PAYLOAD, head.payload)
            val share = 100.0 * f.length() / element.str("bytes")!!.toLong()
            // the canal ring's is a measured exception (35.5 % on 18 October, the coordinator's ruling)
            assertTrue("$id's render pack is %.1f %% of its element pack".format(share), share <= if (id == "grachten") 36.0 else 35.0)
            built[id] = render
            println("RENDERS %-13s %9d bytes  %4.1f %% of %9s".format(id, f.length(), share, element.str("bytes")))
        }
        for ((id, why) in gaps) println("RENDERS %-13s no render pack: %s".format(id, why))
        assertTrue("the bundled set's regions build: ${built.keys}", built.keys.containsAll(listOf("amsterdam", "grachten")))
        assertEquals(input.packs().size, built.size + gaps.size)

        // the renders' cases (CASES/cases/renders.json): every view of every region is a case where its render pack is
        // published, in any case file, and waits where it is not
        val casesDir = File(repo, "app/android/mapcorecheck/src/test/resources/likeness/cases")
        val cased = HashMap<String, MutableSet<String>>()
        for (f in casesDir.listFiles { x -> x.name.endsWith(".json") }.orEmpty()) for (c in (obj(f)["cases"] as? JsonArray).orEmpty().map { it as JsonObject }) {
            val scene = c["scene"] as? JsonObject ?: continue
            val entry = input.packs().firstOrNull { it.str("id") == scene.str("pack") } ?: continue
            assertEquals("${c.str("name")}: the element pack's SHA-256", entry.file("atlas")!!.str("sha256"), scene.str("sha256"))
            (c["camera"] as? JsonObject)?.str("view")?.let { cased.getOrPut(scene.str("pack")!!) { HashSet() } += it }
        }
        val waiting = (obj(File(casesDir, "renders.json"))["waiting"] as JsonArray).map { it as JsonObject }.associateBy { it.str("region")!! }
        assertTrue("only regions of the list wait: ${waiting.keys}", input.packs().map { it.str("id") }.containsAll(waiting.keys))
        for (p in input.packs()) {
            val id = p.str("id")!!
            // the Westerkerk is the bundled living map, without named views: smoke.json's "bundled" cases
            if (id == "amsterdam") continue
            val element = p.file("atlas")!!
            val views = PackReader.open(File(cache, element.str("name")!!)).use { r -> r.manifest.views?.keys.orEmpty().map { k -> if (k.isEmpty()) id else "${id}_$k" }.toSet() }
            assertTrue("$id has named views", views.isNotEmpty())
            val waits = waiting[id]
            if (id in gaps) {
                assertTrue("$id has no render pack: no case draws it yet (${cased[id]})", cased[id].isNullOrEmpty())
                assertEquals("$id's views wait", views, (waits?.get("views") as? JsonArray)?.map { it.jsonPrimitive.content }?.toSet())
                assertEquals("$id waits on its element pack", element.str("sha256"), waits?.str("sha256"))
            } else {
                assertNull("$id has its render pack: its views are cases, not waiting", waits)
                assertEquals("$id's views are cases", views, cased[id].orEmpty())
            }
        }

        // the bundled set's packs are the same bytes
        val bundled = tmp.newFolder("bundled")
        RenderTiles.run(listOf("render-tiles", "--bundled", bundled.path, "--repo", repo.path, "--packs", cache.path, "--work", work.path, "--quiet")) {}
        for (id in listOf("amsterdam", "grachten")) assertEquals("$id as bundled", built.getValue(id).str("sha256"), RenderTiles.sha256(File(bundled, "$id.atlas")))

        // a second run over its own list, of one region, changes nothing
        val again = tmp.newFolder("again")
        RenderTiles.run(listOf("render-tiles", "--renders", File(out, RenderTiles.LIST).path, again.path, "--packs", cache.path, "--only", "amsterdam", "--work", work.path, "--quiet")) {}
        assertEquals(File(out, RenderTiles.LIST).readText(), File(again, RenderTiles.LIST).readText())
        assertEquals(RenderTiles.sha256(File(out, "amsterdam.render.atlas")), RenderTiles.sha256(File(again, "amsterdam.render.atlas")))

        // the library over this "release": the list and its files served from the folders by name
        val http = HttpGet { url ->
            val name = url.substringAfterLast('/')
            val f = listOf(File(out, name), File(cache, name)).firstOrNull { it.isFile } ?: throw IOException("$url: 404")
            f.inputStream() to f.length()
        }
        val lib = PackLibrary(tmp.newFolder("phone"), listOf("https://example.invalid/packs-renders/packs.json"), http)
        val entries = lib.refresh()
        assertEquals(output.packs().map { it.str("id") }, entries.map { it.id })
        for (e in entries) {
            assertEquals("${e.id} has a render pack", e.id in built, e.render != null)
            assertEquals(gaps[e.id], e.renderGap)
        }
        val canal = entries.first { it.id == "grachten" }
        val inCanal = LatLon(52.3655, 4.9010)
        assertEquals(listOf("grachten"), lib.rendersAt(inCanal).second.map { it.id })
        lib.download(canal)
        assertEquals(built.getValue("grachten").str("sha256"), lib.localRender("grachten")!!.sha256)
        assertEquals(listOf("grachten"), lib.rendersAt(inCanal).first.map { it.id })
        assertTrue(lib.rendersAt(inCanal).second.isEmpty())
        // a region without a render pack downloads as today, and nothing is fetched for the core
        entries.firstOrNull { it.render == null }?.let { none ->
            lib.download(none)
            assertNotNull(lib.local(none.id))
            assertNull(lib.localRender(none.id))
            assertNull(lib.renderFailure(none.id))
            assertTrue(lib.rendersAt(none.bbox.center).let { (here, there) -> here.none { it.id == none.id } && there.none { it.id == none.id } })
        }
    }

    @Test
    fun `a failure that is no refusal fails the run and writes no list`() {
        val dir = tmp.newFolder("bad")
        val broken = File(dir, "broken.atlas").apply { writeBytes(ByteArray(4096) { it.toByte() }) }
        val listFile = File(dir, "packs.json")
        listFile.writeText(
            """{"format":1,"packs":[{"id":"broken","name":"broken","bbox":[0,0,1,1],"files":[{"kind":"atlas","name":"broken.atlas","bytes":4096,
            |"sha256":"${RenderTiles.sha256(broken)}","url":"https://127.0.0.1:1/broken.atlas"}]}]}""".trimMargin(),
        )
        val out = tmp.newFolder("out")
        try {
            RenderTiles.run(listOf("render-tiles", "--renders", listFile.path, out.path)) {}
            fail("a broken element pack was skipped")
        } catch (e: IllegalStateException) {
            assertTrue(e.message!!, e.message!!.startsWith("broken: "))
        }
        assertFalse(File(out, RenderTiles.LIST).exists())
        assertFalse(File(out, "broken.render.atlas").exists())
    }

    @Test
    fun `an element pack the list does not vouch for is never used`() {
        val westerkerk = File(repo, RenderTiles.WESTERKERK)
        assumeTrue(westerkerk.isFile)
        val dir = tmp.newFolder("packs")
        westerkerk.copyTo(File(dir, "amsterdam.atlas"))
        fun entry(file: String) = """{"format":1,"packs":[{"id":"amsterdam","name":"amsterdam","bbox":[4.878,52.371,4.891,52.378],"files":[$file]}]}"""
        val sha = RenderTiles.sha256(westerkerk)
        val bytes = westerkerk.length()
        for (
            (file, why) in listOf(
                // another hash: the cached file is not used, and the download fails (nothing listens there)
                """{"kind":"atlas","name":"amsterdam.atlas","bytes":$bytes,"sha256":"${"00".repeat(32)}","url":"https://127.0.0.1:1/amsterdam.atlas"}""" to "",
                """{"kind":"atlas","name":"amsterdam.atlas","bytes":${bytes + 1},"sha256":"$sha","url":"http://127.0.0.1:1/amsterdam.atlas"}""" to "https",
                """{"kind":"atlas","name":"amsterdam.atlas","bytes":$bytes,"url":"https://127.0.0.1:1/amsterdam.atlas"}""" to "SHA-256",
                """{"kind":"atlas","name":"../amsterdam.atlas","bytes":$bytes,"sha256":"$sha","url":"https://127.0.0.1:1/amsterdam.atlas"}""" to "file name",
            )
        ) {
            val listFile = File(dir, "packs.json").apply { writeText(entry(file)) }
            val out = tmp.newFolder()
            try {
                RenderTiles.run(listOf("render-tiles", "--renders", listFile.path, out.path)) {}
                fail("'$why': built")
            } catch (e: IOException) {
                assertTrue("'$why': ${e.message}", e.message.orEmpty().contains(why))
            }
            assertFalse(File(out, RenderTiles.LIST).exists())
        }
        // vouched for: built, and published beside its element pack
        val listFile = File(dir, "packs.json").apply { writeText(entry("""{"kind":"atlas","name":"amsterdam.atlas","bytes":$bytes,"sha256":"$sha","url":"https://example.invalid/r/amsterdam.atlas"}""")) }
        val out = tmp.newFolder("good")
        RenderTiles.run(listOf("render-tiles", "--renders", listFile.path, out.path, "--quiet")) {}
        val render = obj(File(out, RenderTiles.LIST)).packs().single().file(PackLibrary.RENDER_KIND)!!
        assertEquals("https://example.invalid/r/amsterdam.render.atlas", render.str("url"))
    }
}

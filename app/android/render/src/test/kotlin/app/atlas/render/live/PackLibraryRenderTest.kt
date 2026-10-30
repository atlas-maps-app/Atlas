package app.atlas.render.live

import app.atlas.geo.LatLon
import app.atlas.render.data.ByteSink
import app.atlas.render.data.PackFormatException
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.security.MessageDigest

/**
 * The library's render packs (plan-vector K20a): listed beside the element packs in `packs.json`, fetched with a
 * region's element pack on its first visit, verified by size, SHA-256 and their own container before they replace
 * anything, kept apart from the element packs, and a region without one treated as normal.
 */
class PackLibraryRenderTest {
    private val repo = File(System.getProperty("atlas.repo") ?: "../../..").canonicalFile
    private val pack = File(repo, "app/android/app/src/main/assets/packs/amsterdam.atlas")
    private lateinit var server: MockWebServer
    private lateinit var dir: File
    private val served = HashMap<String, ByteArray>()
    private val requests = ArrayList<String>()
    private val westerkerk = LatLon(52.37452, 4.88377)

    private fun sha(b: ByteArray) = MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }

    /** A render pack's container as far as the library reads it: D1's header and a format-3 manifest. */
    private fun renderPack(id: String = "amsterdam", payload: Int = 1, version: Int = 3, layout: String = "render-tiles"): ByteArray {
        val manifest = """{"format":"atlas-pack","version":$version,"layout":"$layout","payload":$payload,"id":"$id",
            |"bbox":{"west":4.878,"south":52.371,"east":4.891,"north":52.378}}""".trimMargin().toByteArray()
        val s = ByteSink()
        s.bytes("ATLASPCK".toByteArray(Charsets.US_ASCII))
        s.byte(0); s.byte(version); s.byte(0); s.byte(0)
        s.u32(manifest.size.toLong())
        s.u32(0)
        s.bytes(manifest)
        // a few tile bytes, so sizes differ between packs
        s.bytes(ByteArray(64) { (it * 7 + payload).toByte() })
        return s.toByteArray()
    }

    /** `packs.json` with the Westerkerk's element pack and, unless [render] is null, its render pack's file. */
    private fun manifest(render: ByteArray?, renderSha: String? = render?.let(::sha), payload: Int = 1, source: String? = null, gap: String? = null): String {
        val bytes = pack.readBytes()
        val elementSha = sha(bytes)
        val renderFile = render?.let {
            """,{"kind":"render","name":"amsterdam.render.atlas","bytes":${it.size},"sha256":"$renderSha","url":"${server.url("/r/amsterdam.render.atlas")}",
            |"format":"atlas-pack","version":3,"layout":"render-tiles","payload":$payload,"engine":"e","style":"s","source_sha256":"${source ?: elementSha}"}""".trimMargin()
        } ?: ""
        val gapField = gap?.let { ""","render_gap":"$it"""" } ?: ""
        return """{"format":1,"packs":[{"id":"amsterdam","name":"amsterdam","bbox":[4.878,52.371,4.891,52.378],"release":"packs-renders",
            |"files":[{"kind":"atlas","name":"amsterdam.atlas","bytes":${bytes.size},"sha256":"$elementSha","url":"${server.url("/r/amsterdam.atlas")}"}$renderFile]$gapField}]}""".trimMargin()
    }

    @Before
    fun start() {
        assumeTrue("the bundled pack", pack.isFile)
        server = MockWebServer()
        server.start()
        dir = Files.createTempDirectory("packs-render").toFile()
        served["/r/amsterdam.atlas"] = pack.readBytes()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.url.encodedPath
                synchronized(requests) { requests += path }
                val body = synchronized(served) { served[path] } ?: return MockResponse.Builder().code(404).build()
                return MockResponse.Builder().code(200).body(Buffer().write(body)).build()
            }
        }
    }

    @After
    fun stop() {
        if (::server.isInitialized) server.close()
        if (::dir.isInitialized) dir.deleteRecursively()
    }

    private fun serve(manifest: String, render: ByteArray? = null) {
        synchronized(served) {
            served["/r/packs.json"] = manifest.toByteArray()
            if (render != null) served["/r/amsterdam.render.atlas"] = render else served.remove("/r/amsterdam.render.atlas")
        }
    }

    private fun library(renders: Boolean = true, payloads: (Int) -> Boolean = { it == PackLibrary.RENDER_PAYLOAD }) =
        PackLibrary(File(dir, "packs"), listOf(server.url("/r/packs.json").toString()), renders = renders, readsPayload = payloads)

    @Test
    fun `a region's render pack comes with its element pack on the first visit and is kept apart`() {
        val render = renderPack()
        serve(manifest(render), render)
        val lib = library()
        val e = lib.refresh().single()
        assertNotNull(e.render)
        assertEquals(1, e.render!!.payload)
        assertEquals(e.sha256, e.render!!.source)
        assertEquals(e.render, lib.renderToFetch(e))
        assertEquals(listOf("amsterdam"), lib.rendersAt(westerkerk).second.map { it.id })
        var last = 0L
        var total = 0L
        lib.download(e) { done, all -> assertTrue("progress runs forward", done >= last); last = done; total = all }
        assertEquals("the progress counts both files", pack.length() + render.size, total)
        assertEquals(total, last)
        val here = lib.localRender("amsterdam")!!
        assertEquals(sha(render), here.sha256)
        assertEquals(File(lib.renderDir, "amsterdam.atlas"), here.file)
        assertTrue(render.contentEquals(here.file.readBytes()))
        assertNull(lib.renderFailure("amsterdam"))
        // the element packs' own list never sees a render pack
        assertEquals(listOf("amsterdam"), lib.local().map { it.id })
        assertEquals(pack.length(), lib.local("amsterdam")!!.file.length())
        assertEquals(listOf("amsterdam") to emptyList<String>(), lib.rendersAt(westerkerk).let { (h, r) -> h.map { it.id } to r.map { it.id } })
        assertNull("nothing left to fetch", lib.renderToFetch(e))
        // offline: a new library over the same folder knows both from the kept list and its files
        val offline = PackLibrary(File(dir, "packs"), emptyList())
        assertEquals(e, offline.published.single())
        assertEquals(here, offline.localRender("amsterdam"))
        assertEquals(listOf("amsterdam"), offline.rendersAt(westerkerk).first.map { it.id })
        assertTrue(offline.rendersAt(LatLon(48.86, 2.34)).let { it.first.isEmpty() && it.second.isEmpty() })
        offline.remove("amsterdam")
        assertTrue(offline.localRenders().isEmpty())
        assertTrue(offline.local().isEmpty())
    }

    @Test
    fun `a region without a render pack is normal, and its gap is kept`() {
        serve(manifest(null, gap = "paris: render tiles cannot carry a survey's heights (K28) yet"))
        val lib = library()
        val e = lib.refresh().single()
        assertNull(e.render)
        assertEquals("paris: render tiles cannot carry a survey's heights (K28) yet", e.renderGap)
        assertNull(lib.renderToFetch(e))
        assertTrue(lib.rendersAt(westerkerk).let { it.first.isEmpty() && it.second.isEmpty() })
        var total = 0L
        lib.download(e) { _, all -> total = all }
        assertEquals(pack.length(), total)
        assertNull(lib.renderFailure("amsterdam"))
        assertTrue(lib.localRenders().isEmpty())
        assertTrue("no render file was asked for: $requests", requests.none { it.contains("render") })
        try {
            lib.downloadRender(e)
            fail("a render pack that is not published was downloaded")
        } catch (x: IOException) {
            assertTrue(x.message!!.contains("K28"))
        }
        // the gap survives the kept list
        assertEquals(e.renderGap, PackLibrary(File(dir, "packs"), emptyList()).published.single().renderGap)
    }

    @Test
    fun `a render pack whose hash or size differs is refused and the element pack stays`() {
        val render = renderPack()
        serve(manifest(render, renderSha = "00".repeat(32)), render)
        val lib = library()
        val e = lib.refresh().single()
        val local = lib.download(e)
        assertEquals("amsterdam", local.id)
        assertNull(lib.localRender("amsterdam"))
        assertTrue(lib.renderFailure("amsterdam")!!.contains("SHA-256"))
        assertTrue("nothing half-written stays: ${lib.renderDir.list()?.toList()}", lib.renderDir.listFiles().orEmpty().isEmpty())
        // a short file
        serve(manifest(render), render.copyOf(render.size - 3))
        val again = library().refresh().single()
        try {
            lib.downloadRender(again)
            fail("a short render pack was kept")
        } catch (x: IOException) {
            assertTrue(x.message!!.contains("bytes"))
        }
        assertTrue(lib.renderDir.listFiles().orEmpty().isEmpty())
        // the right file now: the retry places it
        serve(manifest(render), render)
        assertEquals(sha(render), lib.downloadRender(library().refresh().single()).sha256)
    }

    @Test
    fun `a file that is not this region's render pack, or one this core does not read, is refused`() {
        for ((bad, why) in listOf(renderPack(id = "paris") to "paris", renderPack(version = 2) to "format 2", renderPack(layout = "tiles") to "not a render pack", pack.readBytes() to "format 1")) {
            serve(manifest(bad), bad)
            val lib = library()
            val e = lib.refresh().single()
            try {
                lib.downloadRender(e)
                fail("$why: kept")
            } catch (x: PackFormatException) {
                assertTrue("$why: ${x.message}", x.message!!.contains(why))
            }
            assertTrue(lib.renderDir.listFiles().orEmpty().isEmpty())
        }
        // payload 2: listed, but this build's core reads payload 1 only
        val two = renderPack(payload = 2)
        serve(manifest(two, payload = 2), two)
        synchronized(requests) { requests.clear() }
        val lib = library()
        val e = lib.refresh().single()
        assertEquals(2, e.render!!.payload)
        assertNull(lib.renderToFetch(e))
        lib.download(e)
        assertTrue("payload 2 was not fetched: $requests", requests.none { it.contains("render") })
        try {
            lib.downloadRender(e)
            fail("payload 2 was downloaded")
        } catch (x: IOException) {
        }
        // a core that reads payload 2 takes it; this one does not see it on the disk
        val newer = library(payloads = { it == 2 })
        assertEquals(2, newer.downloadRender(e).payload)
        assertNull(library().localRender("amsterdam"))
        assertEquals(2, library(payloads = { it == 2 }).localRender("amsterdam")!!.payload)
    }

    @Test
    fun `a render pack made from another element pack is not the region's`() {
        val render = renderPack()
        serve(manifest(render, source = "11".repeat(32)), render)
        val e = library().refresh().single()
        assertNull(e.render)
        assertEquals("its render pack was made from another element pack", e.renderGap)
    }

    @Test
    fun `renders off fetches none, and a newer one replaces the one here`() {
        val render = renderPack()
        serve(manifest(render), render)
        // the app's library: the map draws no render pack (ENGINE.md step 3, one pack per country)
        val off = library(renders = false)
        off.download(off.refresh().single())
        assertTrue(requests.none { it.contains("render") })
        assertTrue(off.localRenders().isEmpty())
        // a newer render pack: the one here stands until the new one is verified, then it is replaced
        val lib = library()
        val first = lib.downloadRender(lib.refresh().single())
        val next = renderPack(payload = 1).copyOf(render.size + 5)
        serve(manifest(next), next)
        val e2 = lib.refresh().single()
        assertEquals(first, lib.localRender("amsterdam"))
        assertNotNull(lib.renderToFetch(e2))
        assertEquals(listOf("amsterdam"), lib.rendersAt(westerkerk).second.map { it.id })
        assertEquals(sha(next), lib.downloadRender(e2).sha256)
        assertEquals(sha(next), lib.localRender("amsterdam")!!.sha256)
        assertNull(lib.renderToFetch(e2))
    }
}

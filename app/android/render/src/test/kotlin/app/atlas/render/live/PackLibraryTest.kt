package app.atlas.render.live

import app.atlas.geo.BBox
import app.atlas.geo.LatLon
import app.atlas.render.data.PackReader
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * The region packs: the release manifest, a download verified by size and SHA-256, the offline use of what is kept, the
 * bundled pack, and the live map's choice of region, against a local server serving the real Amsterdam pack.
 */
class PackLibraryTest {
    private val repo = File(System.getProperty("atlas.repo") ?: "../../..").canonicalFile
    private val pack = File(repo, "app/android/app/src/main/assets/packs/amsterdam.atlas")
    private lateinit var server: MockWebServer
    private lateinit var dir: File
    private val requests = ArrayList<String>()
    private var down = false

    private fun sha(b: ByteArray) = MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }

    private fun manifest(sha: String, bytes: Int) = """{"format":1,"packs":[{"id":"amsterdam","name":"amsterdam","bbox":[4.878,52.371,4.891,52.378],
        |"release":"packs-renders","files":[{"kind":"atlas","name":"amsterdam.atlas","bytes":$bytes,"sha256":"$sha",
        |"url":"${server.url("/packs-renders/amsterdam.atlas")}"}]}]}""".trimMargin()

    /** The Westerkerk's pack and grachten's, whose 5.2 MB make a scene of some 300 MB: too big for a small heap. */
    private fun withGrachten(sha: String, bytes: Int) = manifest(sha, bytes).replace(
        "]}]}",
        """]},{"id":"grachten","name":"grachten","bbox":[4.872,52.36,4.922,52.393],"release":"packs-renders","files":[{"kind":"atlas",
        |"name":"grachten.atlas","bytes":5190931,"sha256":"${"11".repeat(32)}","url":"${server.url("/packs-renders/grachten.atlas")}"}]}]}""".trimMargin(),
    )

    @Before
    fun start() {
        assumeTrue("the bundled pack", pack.isFile)
        server = MockWebServer()
        server.start()
        dir = Files.createTempDirectory("packs").toFile()
        val bytes = pack.readBytes()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.url.encodedPath
                synchronized(requests) { requests += path }
                if (down) return MockResponse.Builder().code(503).build()
                return when {
                    path.endsWith("packs.json") -> MockResponse.Builder().code(200).body(manifest(sha(bytes), bytes.size)).build()
                    path.endsWith("bad.json") -> MockResponse.Builder().code(200).body(manifest("00".repeat(32), bytes.size)).build()
                    path.endsWith("big.json") -> MockResponse.Builder().code(200).body(withGrachten(sha(bytes), bytes.size)).build()
                    path.endsWith("amsterdam.atlas") -> MockResponse.Builder().code(200).body(Buffer().write(bytes)).build()
                    else -> MockResponse.Builder().code(404).build()
                }
            }
        }
    }

    @After
    fun stop() {
        if (::server.isInitialized) server.close()
        if (::dir.isInitialized) dir.deleteRecursively()
    }

    @Test
    fun `the manifest is read, the pack downloads, verifies and is used offline`() {
        val lib = PackLibrary(dir, listOf(server.url("/packs-renders/packs.json").toString()))
        val list = lib.refresh()
        assertEquals(listOf("amsterdam"), list.map { it.id })
        assertEquals(BBox(4.878, 52.371, 4.891, 52.378), list[0].bbox)
        val westerkerk = LatLon(52.37452, 4.88377)
        assertEquals(listOf("amsterdam"), lib.at(westerkerk).second.map { it.id })
        var last = 0L
        val local = lib.download(list[0]) { done, total -> assertTrue(done >= last); last = done; assertEquals(pack.length(), total) }
        assertEquals(pack.length(), last)
        assertEquals(pack.length(), local.file.length())
        assertEquals("amsterdam", PackReader.open(local.file).use { it.manifest.id })
        // offline: a new library over the same folder knows the pack and the manifest without the network
        down = true
        val offline = PackLibrary(dir, listOf(server.url("/packs-renders/packs.json").toString()))
        assertEquals(listOf("amsterdam"), offline.at(westerkerk).first.map { it.id })
        assertEquals(listOf("amsterdam"), offline.published.map { it.id })
        try {
            offline.refresh()
            throw AssertionError("refresh must fail with the server down")
        } catch (_: IOException) {
        }
        assertEquals(listOf("amsterdam"), offline.local().map { it.id })
    }

    @Test
    fun `a pack whose SHA-256 differs from the manifest's is refused and nothing is kept`() {
        val lib = PackLibrary(dir, listOf(server.url("/packs-renders/bad.json").toString()))
        val e = lib.refresh().single()
        try {
            lib.download(e)
            throw AssertionError("a wrong hash must not pass")
        } catch (x: IOException) {
            assertTrue(x.message!!.contains("SHA-256"))
        }
        assertTrue(lib.local().isEmpty())
        assertTrue(dir.listFiles()!!.none { it.name.endsWith(".atlas") || it.name.endsWith(".part") })
    }

    @Test
    fun `the bundled pack installs once, with no network`() {
        val lib = PackLibrary(dir, emptyList())
        var opened = 0
        lib.install("amsterdam") { opened++; pack.inputStream() }
        lib.install("amsterdam") { opened++; pack.inputStream() }
        assertEquals(1, opened)
        assertEquals("amsterdam", lib.local("amsterdam")!!.id)
        assertTrue(requests.isEmpty())
    }

    @Test
    fun `the live map downloads the pack for a view in its box, then draws it`() {
        LiveEngine.workDir = File(dir, "engine")
        val lib = PackLibrary(File(dir, "packs"), listOf(server.url("/packs-renders/packs.json").toString()))
        val pool = Executors.newSingleThreadExecutor()
        var changes = CountDownLatch(1)
        val regions = LiveRegions(lib, pool, online = { true }, overpass = null, onChange = { changes.countDown() })
        val f = RegionFrame.living()
        val lat = f.lat(-10.0, -30.0)
        val lon = f.lon(-10.0, -30.0)
        val view = LiveView(lat, lon, LiveCamera.zoom(lat, 430.0, 915.0), 52.0, 52.0, 412, 915, 440.0)
        var state: LiveRegions.State = regions.stateFor(view)
        val seen = ArrayList<String>()
        val deadline = System.currentTimeMillis() + 60_000
        while (state !is LiveRegions.State.Ready && System.currentTimeMillis() < deadline) {
            seen += state.toString().substringBefore('(')
            changes.await(2, TimeUnit.SECONDS)
            changes = CountDownLatch(1)
            state = regions.stateFor(view)
        }
        pool.shutdown()
        val ready = state as LiveRegions.State.Ready
        assertEquals("amsterdam", ready.region.id)
        assertTrue("the manifest and the pack came from the server: $requests", requests.any { it.endsWith("packs.json") } && requests.any { it.endsWith("amsterdam.atlas") })
        // and it renders
        val r = LiveEngine.render(ready.region, view, Moment.Named("sunny"))
        assertNotNull(r)
        assertEquals("amsterdam", r!!.view)
        // outside every pack with no Overpass: no data here
        val nowhere = regions.stateFor(view.copy(lat = 48.0, lon = 2.0))
        assertTrue(nowhere is LiveRegions.State.Empty)
    }

    @Test
    fun `a region whose scene would not fit the heap is neither downloaded nor loaded`() {
        LiveEngine.workDir = File(dir, "engine")
        val lib = PackLibrary(File(dir, "packs"), listOf(server.url("/packs-renders/big.json").toString()))
        lib.install("amsterdam") { pack.inputStream() }
        // 50 MB for a scene: the Westerkerk's (about 40 MB) fits, grachten's (about 330 MB) does not
        val regions = LiveRegions(lib, Executor { it.run() }, online = { true }, overpass = null, onChange = {}, heapBudget = { 50_000_000L })
        val f = RegionFrame.living()
        val lat = f.lat(-10.0, -30.0)
        val lon = f.lon(-10.0, -30.0)
        // the home's distance, where grachten's views fit far better than the living map's
        val home = LiveView(lat, lon, LiveCamera.zoom(lat, 2300.0, 915.0), 52.0, 46.0, 412, 915, 440.0)
        var state = regions.stateFor(home)
        repeat(3) { if (state !is LiveRegions.State.Ready) state = regions.stateFor(home) }
        val ready = state as LiveRegions.State.Ready
        assertEquals("amsterdam", ready.region.id)
        assertEquals(null, ready.pending)
        assertTrue("grachten was never fetched: $requests", requests.none { it.endsWith("grachten.atlas") })
        // in the canal ring, away from the Westerkerk's pack: the map says why, and builds nothing
        val empty = regions.stateFor(home.copy(lat = 52.3655, lon = 4.9010))
        assertTrue("$empty", empty is LiveRegions.State.Empty && (empty as LiveRegions.State.Empty).reason.contains("grachten"))
    }
}

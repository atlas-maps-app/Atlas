package app.atlas.route

import app.atlas.geo.BBox
import app.atlas.geo.LatLon
import btools.mapaccess.Rd5DiffManager
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.random.Random

class SegmentStoreTest {

    // ------------------------------------------------------------------ tile names

    @Test
    fun tileNamesFloorToFiveDegreesSouthWest() {
        assertEquals("E0_N50.rd5", SegmentStore.tileName(RealData.WESTERKERK))
        assertEquals("E0_N50.rd5", SegmentStore.tileName(RealData.HAARLEM))
        assertEquals("E5_N50.rd5", SegmentStore.tileName(52.09, 5.12)) // Utrecht
        assertEquals("E0_N45.rd5", SegmentStore.tileName(48.8566, 2.3522)) // Paris
        assertEquals("W75_N40.rd5", SegmentStore.tileName(40.7128, -74.0060)) // New York
        assertEquals("E150_S35.rd5", SegmentStore.tileName(-33.8688, 151.2093)) // Sydney
        assertEquals("W5_S5.rd5", SegmentStore.tileName(-0.1, -0.1))
        assertEquals("E5_N50.rd5", SegmentStore.tileName(50.0, 5.0))
        assertEquals("W5_S5.rd5", SegmentStore.tileName(-5.0, -5.0))
        assertEquals("W50_N35.rd5", SegmentStore.tileName(37.0, -48.0)) // the docs' example "W48/N37"
        assertEquals("E5_N45.rd5", SegmentStore.tileName(47.0, 7.0)) // and "E7/N47"
        assertEquals("E175_N85.rd5", SegmentStore.tileName(90.0, 180.0))
    }

    @Test
    fun tileBoxesAndTilesInBoxes() {
        assertEquals(BBox(0.0, 50.0, 5.0, 55.0), SegmentStore.tileBox("E0_N50.rd5"))
        assertEquals(BBox(-5.0, -5.0, 0.0, 0.0), SegmentStore.tileBox("W5_S5"))
        assertEquals(listOf("E0_N50.rd5"), SegmentStore.tilesIn(BBox(4.8, 52.3, 4.95, 52.4)))
        assertEquals(setOf("E0_N50.rd5", "E5_N50.rd5"), SegmentStore.tilesIn(BBox(4.9, 52.0, 5.1, 52.1)).toSet())
        assertEquals(4, SegmentStore.tilesIn(BBox(4.9, 49.9, 5.1, 50.1)).size)
        // a box ending exactly on a tile edge does not spill into the next tile
        assertEquals(listOf("E0_N50.rd5"), SegmentStore.tilesIn(BBox(0.0, 50.0, 5.0, 55.0)))
        try {
            SegmentStore.tileBox("X1_N2.rd5"); fail()
        } catch (_: IllegalArgumentException) {
        }
    }

    @Test
    fun tilesForCorridorAndWaypoints() {
        assertEquals(listOf("E0_N50.rd5"), SegmentStore.tilesForCorridor(listOf(RealData.WESTERKERK, RealData.HAARLEM), 1000.0))
        val toUtrecht = SegmentStore.tilesForCorridor(listOf(RealData.WESTERKERK, LatLon(52.09, 5.12)), 1000.0)
        assertEquals(setOf("E0_N50.rd5", "E5_N50.rd5"), toUtrecht.toSet())
        // a point close to a tile edge pulls in the neighbour within the buffer
        assertTrue("E5_N50.rd5" in SegmentStore.tilesForCorridor(listOf(LatLon(52.0, 4.99)), 2000.0))
        assertEquals(listOf("E0_N50.rd5", "E0_N45.rd5"), SegmentStore.tilesForWaypoints(listOf(RealData.WESTERKERK, RealData.HAARLEM, LatLon(48.85, 2.35))))
    }

    @Test
    fun parsesTheDirectoryIndexAndLookupsVersion() {
        val html = javaClass.getResource("/segments4_index.html")!!.readText()
        val tiles = SegmentStore.parseIndex(html)
        assertEquals(16, tiles.size)
        val ams = tiles.first { it.name == "E0_N50.rd5" }
        assertEquals(79_383_775L, ams.bytes)
        assertTrue(ams.modified > 0)
        assertEquals(LookupVersion(11, 2), SegmentStore.lookupVersion(Profiles.template("lookups.dat")))
        assertNull(SegmentStore.lookupVersion("no header here"))
    }

    // ------------------------------------------------------------------ downloads against a fake brouter.de

    private lateinit var server: MockWebServer
    private lateinit var fake: FakeServer
    private lateinit var dir: File

    @Before
    fun start() {
        fake = FakeServer()
        server = MockWebServer()
        server.dispatcher = fake
        server.start()
        dir = Files.createTempDirectory("atlas-segments").toFile()
    }

    @After
    fun stop() {
        server.close()
        dir.deleteRecursively()
    }

    private fun store(rate: Long = 50_000_000) = SegmentStore(
        dir, baseUrl = server.url("/segments4/").toString(), profilesUrl = server.url("/profiles2/").toString(), maxBytesPerSecond = rate,
    )

    @Test
    fun downloadsWithUserAgentAndProgress() = runBlocking {
        val bytes = Random(1).nextBytes(300_000)
        fake.files["segments4/E0_N50.rd5"] = bytes
        val progress = store().download("E0_N50", verify = false).toList()
        assertArrayEquals(bytes, File(dir, "E0_N50.rd5").readBytes())
        assertEquals(SegmentProgress.Stage.DONE, progress.last().stage)
        assertEquals(300_000L, progress.last().bytes)
        assertTrue(progress.any { it.stage == SegmentProgress.Stage.DOWNLOADING && it.bytes in 1 until 300_000 })
        assertEquals(SegmentStore.USER_AGENT, fake.requests.first().headers["User-Agent"])
        assertFalse(File(dir, "E0_N50.rd5.part").exists())
        assertFalse(File(dir, "E0_N50.rd5.part.etag").exists())
    }

    @Test
    fun resumesAPartialDownloadWithRange() = runBlocking {
        val bytes = Random(2).nextBytes(200_000)
        fake.files["segments4/E0_N50.rd5"] = bytes
        File(dir, "E0_N50.rd5.part").writeBytes(bytes.copyOf(70_000))
        File(dir, "E0_N50.rd5.part.etag").writeText(fake.etag("segments4/E0_N50.rd5"))
        store().download("E0_N50.rd5", verify = false).toList()
        assertArrayEquals(bytes, File(dir, "E0_N50.rd5").readBytes())
        val get = fake.requests.single { it.method == "GET" }
        assertEquals("bytes=70000-", get.headers["Range"])
        assertEquals(fake.etag("segments4/E0_N50.rd5"), get.headers["If-Range"])
        assertEquals(206, fake.lastCode)
    }

    @Test
    fun restartsWhenTheFileChangedOnTheServer() = runBlocking {
        val bytes = Random(3).nextBytes(100_000)
        fake.files["segments4/E0_N50.rd5"] = bytes
        File(dir, "E0_N50.rd5.part").writeBytes(Random(4).nextBytes(60_000))
        File(dir, "E0_N50.rd5.part.etag").writeText("\"stale\"")
        store().download("E0_N50.rd5", verify = false).toList()
        assertArrayEquals(bytes, File(dir, "E0_N50.rd5").readBytes())
        assertEquals(200, fake.lastCode)
    }

    @Test
    fun throttlesTheDownload() = runBlocking {
        val bytes = Random(5).nextBytes(150_000)
        fake.files["segments4/E5_N50.rd5"] = bytes
        val t0 = System.nanoTime()
        store(rate = 200_000).download("E5_N50", verify = false).toList()
        val seconds = (System.nanoTime() - t0) / 1e9
        assertTrue("took $seconds s", seconds >= 0.65)
        assertArrayEquals(bytes, File(dir, "E5_N50.rd5").readBytes())
    }

    @Test
    fun aDownloadLongerThanTheSharedClientsCallLimitStillComes() = runBlocking {
        // the app hands the store its providers' client, whose limit on a whole call is 60 s; E0_N50 is 80 MB, 42 s at the cap
        val bytes = Random(10).nextBytes(400_000)
        fake.files["segments4/E0_N50.rd5"] = bytes
        val shared = okhttp3.OkHttpClient.Builder().callTimeout(1, java.util.concurrent.TimeUnit.SECONDS).build()
        val store = SegmentStore(
            dir, client = shared, baseUrl = server.url("/segments4/").toString(), profilesUrl = server.url("/profiles2/").toString(),
            maxBytesPerSecond = 200_000,
        )
        val t0 = System.nanoTime()
        store.download("E0_N50", verify = false).toList()
        assertTrue("took ${(System.nanoTime() - t0) / 1e9} s, past the call limit", System.nanoTime() - t0 > 1_500_000_000L)
        assertArrayEquals(bytes, File(dir, "E0_N50.rd5").readBytes())
    }

    @Test
    fun refusesACorruptFile() = runBlocking {
        fake.files["segments4/E0_N50.rd5"] = Random(6).nextBytes(10_000)
        try {
            store().download("E0_N50", verify = true).toList()
            fail("a random file passed the check")
        } catch (e: IOException) {
            assertTrue(e.message!!.contains("corrupt"))
        }
        assertFalse(File(dir, "E0_N50.rd5").exists())
        assertFalse(File(dir, "E0_N50.rd5.part").exists())
    }

    @Test
    fun updateIsANoOpWhenTheServerHasAnEmptyDiffForOurFile() = runBlocking {
        val local = Random(7).nextBytes(50_000)
        File(dir, "E0_N50.rd5").writeBytes(local)
        val md5 = Rd5DiffManager.getMD5(File(dir, "E0_N50.rd5"))
        fake.files["segments4/diff/E0_N50/$md5.df5"] = ByteArray(0)
        val progress = store().update("E0_N50", verify = false).toList()
        assertEquals(SegmentProgress.Stage.UP_TO_DATE, progress.last().stage)
        assertArrayEquals(local, File(dir, "E0_N50.rd5").readBytes())
        assertTrue(fake.requests.none { it.method == "GET" })
    }

    @Test
    fun updateDownloadsInFullWhenNoDiffMatches() = runBlocking {
        File(dir, "E0_N50.rd5").writeBytes(Random(8).nextBytes(40_000))
        val fresh = Random(9).nextBytes(60_000)
        fake.files["segments4/E0_N50.rd5"] = fresh
        val progress = store().update("E0_N50", verify = false).toList()
        assertEquals(SegmentProgress.Stage.DONE, progress.last().stage)
        assertArrayEquals(fresh, File(dir, "E0_N50.rd5").readBytes())
        assertTrue(fake.requests.any { it.method == "HEAD" && it.url.encodedPath.contains("/diff/E0_N50/") })
    }

    @Test
    fun remoteSizeIndexAndLookups() = runBlocking {
        fake.files["segments4/E0_N50.rd5"] = ByteArray(1234)
        fake.files["segments4/"] = javaClass.getResource("/segments4_index.html")!!.readBytes()
        fake.files["profiles2/lookups.dat"] = "---lookupversion:12\n---minorversion:0\n\n---context:way\n".toByteArray()
        val s = store()
        assertEquals(1234L, s.remoteSize("E0_N50"))
        assertNull(s.remoteSize("E5_N50"))
        assertEquals(16, s.remoteIndex().size)
        val local = File(dir, "lookups.dat").apply { writeText(Profiles.template("lookups.dat")) }
        val check = s.lookupsCheck(local)
        assertEquals(LookupVersion(11, 2), check.local)
        assertEquals(LookupVersion(12, 0), check.remote)
        assertTrue(check.changed)
        assertEquals("bytes=0-255", fake.requests.last().headers["Range"])
        s.downloadLookups(local)
        assertEquals(LookupVersion(12, 0), SegmentStore.lookupVersion(local.readText()))
        assertEquals(listOf("lookups.dat"), dir.list()!!.toList())
        assertEquals(emptyList<SegmentTile>(), s.installed())
        File(dir, "E0_N50.rd5").writeBytes(ByteArray(10))
        assertEquals(listOf(SegmentTile("E0_N50.rd5", 10, File(dir, "E0_N50.rd5").lastModified())), s.installed())
        assertEquals(listOf("E5_N50.rd5"), s.missing(listOf("E0_N50", "E5_N50.rd5")))
        assertTrue(s.delete("E0_N50"))
        assertFalse(s.has("E0_N50"))
    }

    /** brouter.de as far as the store is concerned: files, HEAD, ETags and byte ranges with If-Range. */
    class FakeServer : Dispatcher() {
        val files = HashMap<String, ByteArray>()
        val requests = CopyOnWriteArrayList<RecordedRequest>()
        @Volatile var lastCode = 0

        fun etag(path: String) = "\"${files[path]!!.contentHashCode()}\""

        override fun dispatch(request: RecordedRequest): MockResponse {
            requests += request
            val path = request.url.encodedPath.removePrefix("/")
            val bytes = files[path] ?: return MockResponse.Builder().code(404).build().also { lastCode = 404 }
            val etag = etag(path)
            if (request.method == "HEAD") {
                lastCode = 200
                return MockResponse.Builder().code(200).setHeader("ETag", etag).setHeader("Content-Length", bytes.size).build()
            }
            val range = request.headers["Range"]
            val ifRange = request.headers["If-Range"]
            if (range != null && (ifRange == null || ifRange == etag)) {
                val (a, b) = range.removePrefix("bytes=").split('-')
                val start = a.toInt()
                val end = (b.toIntOrNull() ?: (bytes.size - 1)).coerceAtMost(bytes.size - 1)
                if (start >= bytes.size) return MockResponse.Builder().code(416).build().also { lastCode = 416 }
                lastCode = 206
                return MockResponse.Builder().code(206).setHeader("ETag", etag)
                    .setHeader("Content-Range", "bytes $start-$end/${bytes.size}")
                    .body(Buffer().write(bytes, start, end - start + 1)).build()
            }
            lastCode = 200
            return MockResponse.Builder().code(200).setHeader("ETag", etag).body(Buffer().write(bytes)).build()
        }
    }
}

package app.atlas.route

import btools.mapaccess.PhysicalFile
import btools.mapaccess.Rd5DiffManager
import btools.mapaccess.Rd5DiffTool
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * The store on the real E0_N50.rd5: a daily diff applied through [SegmentStore.update], and (with -Datlas.online=true)
 * what brouter.de itself says.
 */
class SegmentStoreRealTest {

    @Test
    fun appliesADailyDiffToARealTile() = runBlocking {
        RealData.assumeAmsterdam()
        val work = Files.createTempDirectory("atlas-diff").toFile()
        val server = MockWebServer()
        try {
            // a diff of the tile against itself is a real .df5 that changes nothing, so the result must equal the original
            val diff = File(work, "same.df5")
            Rd5DiffTool.diff2files(RealData.amsterdam, RealData.amsterdam, diff)
            assertTrue(diff.length() > 0)
            val dir = File(work, "segments").apply { mkdirs() }
            val local = File(dir, "E0_N50.rd5")
            try {
                Files.createLink(local.toPath(), RealData.amsterdam.toPath())
            } catch (_: Exception) {
                RealData.amsterdam.copyTo(local)
            }
            val md5 = Rd5DiffManager.getMD5(local)
            val fake = SegmentStoreTest.FakeServer()
            fake.files["segments4/diff/E0_N50/$md5.df5"] = diff.readBytes()
            server.dispatcher = fake
            server.start()
            val store = SegmentStore(dir, baseUrl = server.url("/segments4/").toString())
            val t0 = System.nanoTime()
            val progress = store.update("E0_N50").toList()
            println("TIMING diff of ${diff.length()} B applied and verified in ${(System.nanoTime() - t0) / 1_000_000} ms")
            val stages = progress.map { it.stage }.distinct()
            assertEquals(
                listOf(
                    SegmentProgress.Stage.CHECKING, SegmentProgress.Stage.DOWNLOADING, SegmentProgress.Stage.APPLYING_DIFF,
                    SegmentProgress.Stage.VERIFYING, SegmentProgress.Stage.DONE,
                ),
                stages,
            )
            // only the diff was fetched, and the tile is whole and unchanged
            assertTrue(fake.requests.none { it.method == "GET" && it.url.encodedPath.endsWith(".rd5") })
            assertEquals(md5, Rd5DiffManager.getMD5(local))
            assertNull(PhysicalFile.checkFileIntegrity(local))
            assertEquals(listOf("E0_N50.rd5"), dir.list()!!.toList())
            assertEquals(11, store.segmentVersion("E0_N50"))
        } finally {
            server.close()
            work.deleteRecursively()
        }
    }

    @Test
    fun brouterDeAnswersAsExpected() = runBlocking {
        assumeTrue(System.getProperty("atlas.online") == "true")
        val dir = Files.createTempDirectory("atlas-online").toFile()
        try {
            val store = SegmentStore(dir)
            val size = store.remoteSize("E0_N50")!!
            println("ONLINE E0_N50.rd5 is $size bytes")
            assertTrue(size in 50_000_000L..150_000_000L)
            assertNull(store.remoteSize("W180_S90")) // no data at the south pole's edge
            val index = store.remoteIndex()
            println("ONLINE index: ${index.size} tiles, ${index.sumOf { it.bytes } / 1_000_000} MB")
            assertTrue(index.size > 1000)
            assertEquals(size, index.single { it.name == "E0_N50.rd5" }.bytes)
            val lookups = File(dir, "lookups.dat").apply { writeText(Profiles.template("lookups.dat")) }
            val check = store.lookupsCheck(lookups)
            println("ONLINE lookups: local ${check.local}, published ${check.remote}")
            assertEquals(11, check.remote.major)
            // the diff for our copy: absent while it is the newest build, else a small file
            if (RealData.amsterdam.isFile) {
                val md5 = Rd5DiffManager.getMD5(RealData.amsterdam)
                val url = SegmentStore.SEGMENTS_URL + "diff/E0_N50/$md5.df5"
                OkHttpClient().newCall(Request.Builder().url(url).head().header("User-Agent", SegmentStore.USER_AGENT).build()).execute().use { r ->
                    println("ONLINE diff for our E0_N50: HTTP ${r.code}, ${r.header("Content-Length")} bytes")
                    assertTrue(r.code == 200 || r.code == 404)
                }
            }
        } finally {
            dir.deleteRecursively()
        }
    }
}

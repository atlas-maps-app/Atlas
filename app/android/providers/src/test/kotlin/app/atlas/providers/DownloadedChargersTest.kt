package app.atlas.providers

import app.atlas.geo.LatLon
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Assume
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.time.Instant
import kotlin.time.Duration.Companion.hours

class DownloadedChargersTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val westerkerk = LatLon(52.3745, 4.8838)

    @Test
    fun downloadsOnceThenAsksConditionallyAndWorksOffline() = runBlocking {
        val cache = tmp.newFolder("ndw")
        var now = Instant.parse("2026-10-13T08:00:00Z")
        Mock().use { m ->
            m.enqueueBytes(Fixtures.bytes("ndw_sample.json.gz"), 200, "ETag" to "\"abc\"", "Last-Modified" to "Tue, 13 Oct 2026 07:33:00 GMT")
            m.enqueue("", 304)
            val ndw = NdwChargers(testHttp(), cache, url = m.url("/charging_point_locations_ocpi.json.gz")) { now }
            assertEquals(ProviderState.NEEDS_DOWNLOAD, ndw.state())
            try {
                ndw.near(westerkerk, 500.0)
                fail("nothing is downloaded until asked")
            } catch (e: ProviderException) {
                assertTrue(e.message!!.contains("nothing downloaded yet"))
            }
            assertTrue(ndw.refresh())
            assertEquals(ProviderState.READY, ndw.state())
            assertTrue(File(cache, "ndw-chargers.tsv.gz").exists())
            assertEquals(now, ndw.updated)
            val first = ndw.near(westerkerk, 500.0)
            assertTrue(first.isNotEmpty())
            m.take()
            // Unchanged upstream: a 304 costs nothing and keeps the data.
            now = now.plusSeconds(4 * 3600)
            assertFalse(ndw.refresh())
            val second = m.take()
            assertEquals("\"abc\"", second.headers["If-None-Match"])
            assertEquals("Tue, 13 Oct 2026 07:33:00 GMT", second.headers["If-Modified-Since"])
            assertEquals(now, ndw.updated)
        }
        // A new instance with the server gone (no signal) reads the kept copy, however stale.
        val offline = NdwChargers(testHttp(), cache, url = "http://127.0.0.1:9/gone.json.gz", maxAge = 1.hours, autoDownload = true) { Instant.parse("2026-10-20T00:00:00Z") }
        assertEquals(ProviderState.READY, offline.state())
        val near = offline.near(westerkerk, 500.0)
        assertTrue(near.isNotEmpty())
        val ids = near.take(3).map { it.extra["charger:id"]!! }
        assertEquals(ids.toSet(), offline.status(ids).keys)
    }

    @Test
    fun autoDownloadFetchesWhenStale() = runBlocking {
        Mock().use { m ->
            m.enqueueBytes(Fixtures.bytes("ndw_sample.json.gz"))
            val ndw = NdwChargers(testHttp(), tmp.newFolder("auto"), url = m.url("/f.json.gz"), autoDownload = true)
            assertEquals(ProviderState.READY, ndw.state())
            assertTrue(ndw.near(westerkerk, 1_000.0).isNotEmpty())
            assertEquals(1, m.count)
        }
    }

    @Test
    fun withoutACacheFolderTheDownloadIsStillRememberedInMemory() = runBlocking {
        Mock().use { m ->
            m.dispatch { req ->
                if (req.headers["If-None-Match"] == "\"v1\"") response("", 304)
                else mockwebserver3.MockResponse.Builder().body(okio.Buffer().write(Fixtures.bytes("ndw_sample.json.gz"))).addHeader("ETag", "\"v1\"").build()
            }
            var now = Instant.parse("2026-10-13T08:00:00Z")
            val ndw = NdwChargers(testHttp(), null, url = m.url("/f.json.gz"), maxAge = 3.hours, autoDownload = true) { now }
            assertTrue(ndw.near(westerkerk, 1_000.0).isNotEmpty())
            // Fresh: asking again costs nothing.
            assertTrue(ndw.near(westerkerk, 1_000.0).isNotEmpty())
            assertEquals(1, m.count)
            assertEquals(now, ndw.updated)
            // Stale: a conditional request, answered 304, keeps what is in memory.
            now = now.plusSeconds(4 * 3600)
            assertTrue(ndw.near(westerkerk, 1_000.0).isNotEmpty())
            assertEquals(2, m.count)
            m.take()
            assertEquals("\"v1\"", m.take().headers["If-None-Match"])
        }
    }

    @Test
    fun theRegisterFindsItsMonthlyFileOnItsPage() = runBlocking {
        Mock().use { m ->
            m.dispatch { req ->
                when {
                    req.url.encodedPath.endsWith("start.html") -> response(Fixtures.text("bnetza_page.html").replace("https://data.bundesnetzagentur.de", m.base))
                    req.url.encodedPath.endsWith(".csv") -> response(Fixtures.text("bnetza_head.csv"))
                    else -> response("", 404)
                }
            }
            val reg = BnetzaChargers(testHttp(), tmp.newFolder("bnetza"), pageUrl = m.url("/DE/start.html"))
            assertTrue(reg.refresh())
            val all = reg.index().all()
            assertEquals(35, all.size)
            assertTrue(all.all { it.extra["attribution"] == "Ladesäulenregister: Bundesnetzagentur.de, CC BY 4.0" })
        }
    }

    /** A one-off check on the whole national file: -Datlas.ndw.file=/path/to/charging_point_locations_ocpi.json.gz */
    @Test
    fun theWholeDutchFileParsesInFlatMemory() {
        val path = System.getProperty("atlas.ndw.file")
        Assume.assumeTrue(path != null && File(path).exists())
        val rt = Runtime.getRuntime()
        System.gc()
        val before = rt.totalMemory() - rt.freeMemory()
        val start = System.nanoTime()
        val index = File(path!!).inputStream().use { NdwChargers(testHttp(), null).importFrom(it) }
        val ms = (System.nanoTime() - start) / 1_000_000
        System.gc()
        val after = rt.totalMemory() - rt.freeMemory()
        val near = index.nearNow(westerkerk, 1_000.0)
        val out = java.io.ByteArrayOutputStream()
        index.write(out)
        println("NDW full file: ${index.size} chargers in $ms ms; index ≈ ${(after - before) / 1_048_576} MB in memory, ${out.size() / 1_048_576} MB on disk; ${near.size} within 1 km of the Westerkerk")
        assertTrue(index.size > 50_000)
        assertTrue(near.isNotEmpty())
    }
}

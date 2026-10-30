package app.atlas.providers

import app.atlas.geo.LatLon
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
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
import kotlin.time.Duration.Companion.days
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
        // a week on, the kept copy gives the chargers but not their status: what was free then is not free now
        val ids = near.take(3).map { it.extra["charger:id"]!! }
        assertTrue(offline.status(ids).isEmpty())
        assertTrue(near.none { p -> p.extra.keys.any { it == "status" || it.startsWith("status:") } })
    }

    @Test
    fun aRegisterNeverPassesThroughTheAppsHttpCache() = runBlocking {
        // the app's client keeps answers in a 64 MB disk cache; a register (Germany's: 55 MB) is kept by its source alone
        Mock().use { m ->
            m.enqueueBytes(Fixtures.bytes("ndw_sample.json.gz"), 200, "ETag" to "\"abc\"", "Last-Modified" to "Tue, 13 Oct 2026 07:33:00 GMT")
            val http = Http(cacheDir = tmp.newFolder("http"), policies = emptyMap())
            val ndw = NdwChargers(http, tmp.newFolder("kept"), url = m.url("/charging_point_locations_ocpi.json.gz")) { Instant.parse("2026-10-13T08:00:00Z") }
            assertTrue(ndw.refresh())
            assertTrue(ndw.near(westerkerk, 500.0).isNotEmpty())
            val cache = http.client.cache!!
            assertEquals(0L, cache.size())
            assertEquals(0, cache.writeSuccessCount())
        }
    }

    @Test
    fun aRegisterIsAskedOnlyInItsCountryAndDownloadsOnWifiInTheBackground() = runBlocking {
        Mock().use { m ->
            m.enqueueBytes(Fixtures.bytes("ndw_sample.json.gz"))
            var wifi = false
            val ndw = NdwChargers(
                testHttp(), tmp.newFolder("country"), url = m.url("/f.json.gz"), maxAge = 7.days, autoDownload = true,
                mayDownload = { wifi }, covers = NdwChargers.COUNTRY, background = this,
            )
            // far from the Netherlands: nothing asked, nothing downloaded
            assertTrue(ndw.near(LatLon(40.4168, -3.7038), 25_000.0).isEmpty())
            // on mobile data: nothing downloaded yet, and nothing asked
            assertTrue(runCatching { ndw.near(westerkerk, 500.0) }.exceptionOrNull() is ProviderException)
            assertEquals(0, m.count)
            // on Wi-Fi: the download runs in the background while the screen gets what is kept (nothing yet)
            wifi = true
            assertTrue(runCatching { ndw.near(westerkerk, 500.0) }.exceptionOrNull() is ProviderException)
            ndw.settled()
            assertEquals(1, m.count)
            assertTrue(ndw.near(westerkerk, 500.0).isNotEmpty())
            // a search by the border that reaches into the country asks the kept register, not the server again
            assertTrue(ndw.near(LatLon(51.32, 6.80), 30_000.0).isEmpty())
            assertEquals(1, m.count)
        }
    }

    @Test
    fun aChargersStatusCountsAsLiveForThreeHoursAfterTheDataCame() = runBlocking {
        Mock().use { m ->
            m.enqueueBytes(Fixtures.bytes("ndw_sample.json.gz"))
            var now = Instant.parse("2026-10-30T08:00:00Z")
            val ndw = NdwChargers(testHttp(), tmp.newFolder("live"), url = m.url("/f.json.gz"), maxAge = 7.days, autoDownload = true) { now }
            val fresh = ndw.near(westerkerk, 500.0)
            assertTrue("the sample's live status", fresh.any { it.extra["status:available"] != null })
            val ids = fresh.map { it.extra["charger:id"]!! }
            assertTrue(ndw.status(ids).isNotEmpty())
            now = now.plusSeconds(3 * 3600)
            assertTrue("three hours on, still live", ndw.near(westerkerk, 500.0).any { it.extra["status:available"] != null })
            now = now.plusSeconds(60)
            val later = ndw.near(westerkerk, 500.0)
            assertEquals(fresh.size, later.size)
            assertTrue(later.none { p -> p.extra.keys.any { it == "status" || it.startsWith("status:") } })
            assertTrue(ndw.status(ids).isEmpty())
            assertEquals("not downloaded again within the week", 1, m.count)
        }
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
    fun aCacheFileThatNoLongerReadsIsFetchedWholeNotTrustedToA304() = runBlocking {
        val cache = tmp.newFolder("broken")
        Mock().use { m ->
            m.dispatch { req ->
                if (req.headers["If-None-Match"] != null) response("", 304)
                else mockwebserver3.MockResponse.Builder().body(okio.Buffer().write(Fixtures.bytes("ndw_sample.json.gz"))).addHeader("ETag", "\"v1\"").build()
            }
            val url = m.url("/f.json.gz")
            // The last download was recorded, but its file was cut short (the phone died while writing it).
            File(cache, "ndw-chargers.meta").writeText("url=$url\netag=\"v1\"\nfetched=2026-10-13T08:00:00Z\n")
            File(cache, "ndw-chargers.tsv.gz").writeBytes(byteArrayOf(0x1f, 0x8b.toByte(), 8, 0))
            val ndw = NdwChargers(testHttp(), cache, url = url) { Instant.parse("2026-10-13T12:00:00Z") }
            assertTrue("new data, not a 304", ndw.refresh())
            assertEquals(null, m.take().headers["If-None-Match"])
            assertTrue(ndw.near(westerkerk, 1_000.0).isNotEmpty())
        }
    }

    @Test
    fun callersThatFindTheDataStaleTogetherDownloadItOnce() = runBlocking {
        Mock().use { m ->
            m.dispatch {
                mockwebserver3.MockResponse.Builder().body(okio.Buffer().write(Fixtures.bytes("ndw_sample.json.gz")))
                    .addHeader("ETag", "\"v1\"").headersDelay(300, java.util.concurrent.TimeUnit.MILLISECONDS).build()
            }
            val ndw = NdwChargers(testHttp(), tmp.newFolder("together"), url = m.url("/f.json.gz"), autoDownload = true)
            val answers = (1..3).map { async(Dispatchers.Default) { ndw.near(westerkerk, 1_000.0) } }.awaitAll()
            assertTrue(answers.all { it.isNotEmpty() })
            assertEquals(1, m.count)
        }
    }

    @Test
    fun aFailedRefreshRestsInsteadOfRetryingOnEveryCall() = runBlocking {
        val cache = tmp.newFolder("rest")
        Mock().use { m ->
            m.enqueueBytes(Fixtures.bytes("ndw_sample.json.gz"))
            NdwChargers(testHttp(), cache, url = m.url("/f.json.gz")) { Instant.parse("2026-10-13T08:00:00Z") }.refresh()
        }
        Mock().use { m ->
            m.dispatch { response("busy", 503) }
            var now = Instant.parse("2026-10-13T20:00:00Z")
            val ndw = NdwChargers(testHttp(), cache, url = m.url("/f.json.gz"), maxAge = 3.hours, autoDownload = true) { now }
            // Stale and the server is down: the kept copy answers, and the next calls do not knock again at once.
            repeat(3) { assertTrue(ndw.near(westerkerk, 1_000.0).isNotEmpty()) }
            assertEquals(1, m.count)
            now = now.plusSeconds(10 * 60)
            assertTrue(ndw.near(westerkerk, 1_000.0).isNotEmpty())
            assertEquals(2, m.count)
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
        println("NDW full file: ${index.size} chargers in $ms ms; index ≈ ${mb(after - before)} MB in memory, ${mb(out.size().toLong())} MB on disk; ${near.size} within 1 km of the Westerkerk")
        assertTrue(index.size > 50_000)
        assertTrue(near.isNotEmpty())
    }

    /** The same for Germany's register: -Datlas.bnetza.file=/path/to/Ladesaeulenregister_BNetzA_<date>.csv */
    @Test
    fun theWholeGermanRegisterParsesInFlatMemory() {
        val path = System.getProperty("atlas.bnetza.file")
        Assume.assumeTrue(path != null && File(path).exists())
        val rt = Runtime.getRuntime()
        System.gc()
        val before = rt.totalMemory() - rt.freeMemory()
        val start = System.nanoTime()
        val index = File(path!!).inputStream().use { BnetzaChargers(testHttp(), null).importFrom(it) }
        val ms = (System.nanoTime() - start) / 1_000_000
        System.gc()
        val after = rt.totalMemory() - rt.freeMemory()
        val near = index.nearNow(LatLon(52.5163, 13.3777), 1_000.0)
        val out = java.io.ByteArrayOutputStream()
        index.write(out)
        println("Bundesnetzagentur full file: ${index.size} chargers in $ms ms; index ≈ ${mb(after - before)} MB in memory, ${mb(out.size().toLong())} MB on disk; ${near.size} within 1 km of the Brandenburg Gate")
        assertTrue(index.size > 50_000)
        assertTrue(near.isNotEmpty())
    }

    private fun mb(bytes: Long) = String.format(java.util.Locale.ROOT, "%.1f", bytes / 1_048_576.0)
}

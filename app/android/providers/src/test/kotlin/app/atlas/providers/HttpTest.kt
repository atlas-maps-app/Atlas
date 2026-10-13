package app.atlas.providers

import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import mockwebserver3.MockResponse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.util.concurrent.TimeUnit
import kotlin.time.Duration.Companion.seconds

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class HttpTest {
    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun sendsTheAtlasUserAgentAndReadsJson() = runBlocking {
        Mock().use { m ->
            m.enqueue("""{"a":1}""")
            val json = testHttp().json(m.url("/x")).asObject()!!
            assertEquals(1, json.int("a"))
            val req = m.take()
            assertEquals(USER_AGENT, req.headers["User-Agent"])
            assertEquals("application/json", req.headers["Accept"])
        }
    }

    @Test
    fun non2xxThrowsHttpExceptionWithTheStatus() = runBlocking {
        Mock().use { m ->
            m.enqueue("nope", 404)
            try {
                testHttp().text(m.url("/missing?key=secret"))
                fail("expected HttpException")
            } catch (e: HttpException) {
                assertEquals(404, e.code)
                // The message names the endpoint but never the query, which may hold a key.
                assertFalse(e.message!!.contains("secret"))
            }
        }
    }

    @Test
    fun cancellingTheCoroutineCancelsTheCall() = runBlocking {
        Mock().use { m ->
            m.server.enqueue(MockResponse.Builder().body("late").headersDelay(5, TimeUnit.SECONDS).build())
            val start = System.nanoTime()
            try {
                withTimeout(300) { testHttp().text(m.url("/slow")) }
                fail("expected a timeout")
            } catch (_: TimeoutCancellationException) {
            }
            assertTrue("cancelled promptly", (System.nanoTime() - start) / 1_000_000 < 3_000)
        }
    }

    /** A body arriving at 1 kB/s: 200 kB would take minutes, as a national register does on a slow server. */
    private fun slowBody() = MockResponse.Builder().body(okio.Buffer().write(ByteArray(200_000))).throttleBody(1024, 1, TimeUnit.SECONDS).build()

    @Test
    fun cancellingStopsABodyBeingStreamed() = runBlocking {
        Mock().use { m ->
            m.server.enqueue(slowBody())
            m.server.enqueue(slowBody())
            val http = Http(policies = emptyMap(), callTimeout = 20.seconds)
            for (useExchange in listOf(false, true)) {
                val start = System.nanoTime()
                var read = -1
                try {
                    withTimeout(500) {
                        val request = http.request(m.url("/big")).build()
                        if (useExchange) http.exchange(request) { read = it.body.bytes().size }
                        else http.stream(request) { read = it.readBytes().size }
                    }
                    fail("expected a timeout")
                } catch (_: TimeoutCancellationException) {
                }
                val ms = (System.nanoTime() - start) / 1_000_000
                assertEquals(-1, read)
                assertTrue("a blocked read ended ${ms} ms after the start", ms < 3_000)
            }
        }
    }

    @Test
    fun diskCacheServesARepeatWithoutTheNetwork() = runBlocking {
        Mock().use { m ->
            m.enqueue("""{"v":42}""", 200, "Cache-Control" to "max-age=600")
            val http = Http(cacheDir = tmp.newFolder("cache"), policies = emptyMap())
            val first = http.execute(http.request(m.url("/c")).build())
            val second = http.execute(http.request(m.url("/c")).build())
            assertEquals(1, m.count)
            assertFalse(first.fromCache)
            assertTrue(second.fromCache)
            assertEquals(first.text, second.text)
        }
    }

    @Test
    fun exchangeStreamsAnyStatus() = runBlocking {
        Mock().use { m ->
            m.enqueue("", 304)
            val code = testHttp().let { h -> h.exchange(h.request(m.url("/f")).build()) { it.code } }
            assertEquals(304, code)
        }
    }

    @Test
    fun limiterSpacesRequestsToOneHost() = runTest {
        val limiter = HostLimiter(mapOf("nominatim.openstreetmap.org" to HostPolicy(minIntervalMs = 1_000, maxConcurrent = 1))) { testScheduler.currentTime }
        val starts = (1..3).map { async { limiter.withPermit("nominatim.openstreetmap.org") { testScheduler.currentTime } } }.awaitAll()
        assertEquals(listOf(0L, 1_000L, 2_000L), starts.sorted())
        // Another host is not held back.
        assertEquals(testScheduler.currentTime, limiter.withPermit("example.org") { testScheduler.currentTime })
    }

    @Test
    fun limiterHonoursAPenalty() = runTest {
        val limiter = HostLimiter(mapOf("overpass-api.de" to HostPolicy(maxConcurrent = 1))) { testScheduler.currentTime }
        limiter.penalise("overpass-api.de", 30.seconds)
        val at = limiter.withPermit("overpass-api.de") { testScheduler.currentTime }
        assertEquals(30_000L, at)
    }

    @Test
    fun subdomainsShareTheirParentsPolicy() = runTest {
        val limiter = HostLimiter(mapOf("open-meteo.com" to HostPolicy(minIntervalMs = 500, maxConcurrent = 1))) { testScheduler.currentTime }
        val a = limiter.withPermit("api.open-meteo.com") { testScheduler.currentTime }
        val b = limiter.withPermit("api.open-meteo.com") { testScheduler.currentTime }
        assertEquals(500L, b - a)
    }

    @Test
    fun keysOfIgnoresBlankKeys() {
        val keys = Keys.of("ocm" to "abc", "yelp" to " ")
        assertEquals("abc", keys.key("ocm"))
        assertEquals(null, keys.key("yelp"))
        assertEquals(ProviderState.NEEDS_KEY, stateFor(KeyNeed.REQUIRED, null))
        assertEquals(ProviderState.READY, stateFor(KeyNeed.OPTIONAL, null))
    }
}

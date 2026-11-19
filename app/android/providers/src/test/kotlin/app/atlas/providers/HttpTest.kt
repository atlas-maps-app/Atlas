package app.atlas.providers

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
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
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
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
    fun anAnswerThatIsNotJsonFailsAsAnUnreachableServerDoes() = runBlocking {
        // a Wi-Fi's sign-in page answers 200: an IOException, caught wherever a failed request is
        Mock().use { m ->
            m.enqueue("<html><body>Sign in to the hotel's Wi-Fi</body></html>")
            m.enqueue("<html><body>Sign in to the hotel's Wi-Fi</body></html>")
            val http = testHttp()
            for (read in listOf<suspend () -> Unit>({ http.json(m.url("/x")) }, { http.execute(http.request(m.url("/y")).build()).json() })) {
                try {
                    read()
                    fail("a sign-in page read as JSON")
                } catch (e: java.io.IOException) {
                    assertTrue(e.toString(), e is NotJsonException && e.message!!.contains("Sign in"))
                }
            }
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
    fun anErrorKeepsEnoughOfItsBodyToSayWhyButNoMore() = runBlocking {
        Mock().use { m ->
            val why = "x".repeat(1_500) + "the reason"
            m.enqueue(why, 400)
            m.enqueue(why + "y".repeat(5_000_000), 500)
            val http = testHttp()
            try {
                http.text(m.url("/a"))
                fail()
            } catch (e: HttpException) {
                assertTrue(e.body.endsWith("the reason"))
            }
            try {
                http.stream(http.request(m.url("/b")).build()) { fail("read a failed answer") }
                fail("expected HttpException")
            } catch (e: HttpException) {
                assertEquals(500, e.code)
                assertTrue(e.body.contains("the reason"))
                assertTrue(e.body.length <= Http.MAX_ERROR_BODY)
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
    fun aStalledDownloadFailsAtItsStallLimitNotAtTheEndOfItsLongBudget() = runBlocking {
        Mock().use { m ->
            // One byte, then silence: a phone that lost its signal mid-download.
            m.server.enqueue(MockResponse.Builder().body(okio.Buffer().write(ByteArray(10))).throttleBody(1, 5, TimeUnit.SECONDS).build())
            val http = testHttp()
            val start = System.nanoTime()
            try {
                http.exchange(http.request(m.url("/national.json.gz")).build(), timeout = 15.minutes, stall = 300.milliseconds) { it.body.bytes() }
                fail("a stalled read must fail")
            } catch (_: java.io.IOException) {
            }
            assertTrue((System.nanoTime() - start) / 1_000_000 < 3_000)
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
    fun theMapsBlockingTurnAndTheAppsQueriesShareAServersOneQueryAtATime() = runBlocking {
        val limiter = HostLimiter(mapOf("overpass-api.de" to HostPolicy(maxConcurrent = 1)))
        // the map's window holds the server: the app's query waits for it, and the server is busy meanwhile
        val turn = limiter.turn("overpass-api.de")
        assertTrue(limiter.busy("overpass-api.de"))
        assertFalse("a host without a policy is never busy", limiter.busy("example.org"))
        val query = async(Dispatchers.Default) { limiter.withPermit("overpass-api.de") { "asked" } }
        delay(300)
        assertFalse(query.isCompleted)
        turn.close()
        turn.close() // closed again, it gives nothing more back
        assertEquals("asked", withTimeout(5.seconds) { query.await() })
        assertFalse(limiter.busy("overpass-api.de"))
        // the app's query holds it: the map's turn waits for it
        val held = CompletableDeferred<Unit>()
        val done = CompletableDeferred<Unit>()
        val app = launch(Dispatchers.Default) { limiter.withPermit("overpass-api.de") { held.complete(Unit); done.await() } }
        held.await()
        val got = AtomicBoolean(false)
        val map = thread { limiter.turn("overpass-api.de").use { got.set(true) } }
        map.join(300)
        assertFalse(got.get())
        done.complete(Unit)
        map.join(5_000)
        assertTrue(got.get())
        app.join()
        assertFalse(limiter.busy("overpass-api.de"))
    }

    @Test
    fun subdomainsShareTheirParentsPolicy() = runTest {
        val limiter = HostLimiter(mapOf("open-meteo.com" to HostPolicy(minIntervalMs = 500, maxConcurrent = 1))) { testScheduler.currentTime }
        val a = limiter.withPermit("api.open-meteo.com") { testScheduler.currentTime }
        val b = limiter.withPermit("api.open-meteo.com") { testScheduler.currentTime }
        assertEquals(500L, b - a)
    }

    @Test
    fun aSwitchedOffSourcesHostIsNeverAskedNorByAClientBuiltOnTheShared() = runBlocking {
        Mock().use { m ->
            repeat(2) { m.enqueue("""{"ok":true}""") }
            var off = setOf("photon")
            // the app's gate: the server here is "localhost", photon's host for this test
            val http = Http(policies = emptyMap(), gate = { host, _ -> if (host == "localhost" && "photon" in off) "photon" else null })
            val refused = runCatching { http.text(m.url("/api")) }.exceptionOrNull()
            assertTrue("$refused", refused is SwitchedOffException && refused.source == "photon" && refused.host == "localhost")
            // a client built on the shared one, as the map's are, holds it back too
            val derived = http.client.newBuilder().callTimeout(0, TimeUnit.SECONDS).build()
            val viaDerived = runCatching { derived.newCall(okhttp3.Request.Builder().url(m.url("/api")).build()).execute().close() }.exceptionOrNull()
            assertTrue("$viaDerived", viaDerived is SwitchedOffException)
            assertEquals("nothing was sent", 0, m.count)
            // switched on again: it goes
            off = emptySet()
            assertEquals("""{"ok":true}""", http.text(m.url("/api")))
            assertEquals(1, m.count)
        }
    }

    @Test
    fun aNamedViewStampsItsSourceOnEveryRequestBeforeTheGateAndSharesTheRootsPoolCacheAndLimiter() = runBlocking {
        Mock().use { m ->
            repeat(6) { m.enqueue("ok") }
            val asked = java.util.Collections.synchronizedList(ArrayList<Pair<String, String?>>())
            val root = Http(cacheDir = tmp.newFolder("http"), policies = emptyMap(), gate = { host, source -> asked += host to source; null })
            val view = root.of("panoramax")
            assertEquals("panoramax", view.source)
            // one pool, one cache, one limiter, one agent
            assertTrue(view.client.connectionPool === root.client.connectionPool)
            assertTrue(view.client.cache === root.client.cache && root.client.cache != null)
            assertTrue(view.limiter === root.limiter)
            assertEquals(root.userAgent, view.userAgent)
            // what the gate is asked first for one request: by its host and source; then each hop by its host alone
            suspend fun firstAsked(send: suspend () -> Unit): String? {
                asked.clear()
                send()
                assertTrue("after the first, the gate is asked by host alone: $asked", asked.drop(1).all { it.second == null })
                return asked.first().second
            }
            // through the view's own calls, and through a client built on it with interceptors of its own, after or before
            assertEquals("panoramax", firstAsked { view.text(m.url("/a")) })
            val seen = java.util.concurrent.atomic.AtomicReference<String?>()
            val built = view.client.newBuilder().addInterceptor { c -> seen.set(c.request().tag(SourceTag::class.java)?.id); c.proceed(c.request()) }.build()
            assertEquals("panoramax", firstAsked { built.newCall(okhttp3.Request.Builder().url(m.url("/b")).build()).execute().close() })
            assertEquals("the stamp runs before what is added after it", "panoramax", seen.get())
            val first = view.client.newBuilder().apply { interceptors().add(0, okhttp3.Interceptor { c -> c.proceed(c.request()) }) }.build()
            assertEquals("panoramax", firstAsked { first.newCall(okhttp3.Request.Builder().url(m.url("/c")).build()).execute().close() })
            // the root stamps nothing; a view of another source stamps its own; a request already stamped keeps its stamp
            assertEquals(null, firstAsked { root.text(m.url("/d")) })
            assertEquals("open-meteo", firstAsked { root.of("open-meteo").text(m.url("/e")) })
            assertEquals("commons", firstAsked { view.execute(view.request(m.url("/f")).tag(SourceTag::class.java, SourceTag("commons")).build()) })
            assertTrue(asked.all { it.first == "localhost" })
            assertEquals(6, m.count)
        }
    }

    @Test
    fun eachHopOfARedirectIsCheckedByTheGateAndCarriesTheRequestsSource() = runBlocking {
        Mock().use { m ->
            val port = m.server.port
            // localhost sends on to 127.0.0.1, another host to OkHttp: a hop below the application interceptors
            m.dispatch { r ->
                if (r.headers["Host"]!!.startsWith("localhost")) MockResponse.Builder().code(302).addHeader("Location", "http://127.0.0.1:$port/b").build()
                else MockResponse.Builder().code(200).body("there").build()
            }
            var off = emptySet<String>()
            val root = Http(policies = emptyMap(), gate = { host, _ -> if (host == "127.0.0.1" && "commons" in off) "commons" else null })
            val hops = java.util.Collections.synchronizedList(ArrayList<Pair<String, String?>>())
            val view = root.of("wikidata")
            val watched = view.client.newBuilder().addNetworkInterceptor { c -> hops += c.request().url.host to c.request().tag(SourceTag::class.java)?.id; c.proceed(c.request()) }.build()
            fun get() = watched.newCall(okhttp3.Request.Builder().url("http://localhost:$port/a").build()).execute().use { it.body.string() }
            // on: both hops go, each with the request's source
            assertEquals("there", get())
            assertEquals(listOf("localhost" to "wikidata", "127.0.0.1" to "wikidata"), hops.toList())
            // the hop's host is switched off: refused before it is sent; the first hop alone reached the server
            off = setOf("commons")
            hops.clear()
            val refused = runCatching { get() }.exceptionOrNull()
            assertTrue("$refused", refused is SwitchedOffException && refused.source == "commons" && refused.host == "127.0.0.1")
            assertEquals(listOf("localhost" to "wikidata"), hops.toList())
            assertEquals(3, m.count)
        }
    }

    @Test
    fun aRefusedHopIsNeverLookedUpNorConnected() = runBlocking {
        Mock().use { m ->
            val port = m.server.port
            m.dispatch { r ->
                if (r.headers["Host"]!!.startsWith("a.example")) MockResponse.Builder().code(301).addHeader("Location", "http://b.example:$port/b").build()
                else MockResponse.Builder().code(200).body("b").build()
            }
            val looked = java.util.Collections.synchronizedList(ArrayList<String>())
            val connects = java.util.concurrent.atomic.AtomicInteger()
            // every name is this machine; nothing leaves it whatever the gate does
            val base = okhttp3.OkHttpClient.Builder()
                .dns { name -> looked += name; listOf(java.net.InetAddress.getByAddress(name, byteArrayOf(127, 0, 0, 1))) }
                .eventListener(object : okhttp3.EventListener() {
                    override fun connectStart(call: okhttp3.Call, inetSocketAddress: java.net.InetSocketAddress, proxy: java.net.Proxy) { connects.incrementAndGet() }
                })
                .build()
            val http = Http(policies = emptyMap(), base = base, gate = { host, _ -> if (host == "b.example") "commons" else null }).of("wikidata")
            val refused = runCatching { http.text("http://a.example:$port/a") }.exceptionOrNull()
            assertTrue("$refused", refused is SwitchedOffException && refused.host == "b.example")
            assertEquals("b.example is never looked up", listOf("a.example"), looked.toList())
            assertEquals("one connection, to a.example", 1, connects.get())
            assertEquals(1, m.count)
        }
    }

    @Test
    fun anAnswerFromTheCacheMakesNoHop() = runBlocking {
        Mock().use { m ->
            m.enqueue("kept", 200, "Cache-Control" to "max-age=600")
            val hops = java.util.concurrent.atomic.AtomicInteger()
            val view = Http(cacheDir = tmp.newFolder("c"), policies = emptyMap()).of("wikidata")
            val watched = view.client.newBuilder().addNetworkInterceptor { c -> hops.incrementAndGet(); c.proceed(c.request()) }.build()
            repeat(2) { watched.newCall(okhttp3.Request.Builder().url(m.url("/k")).build()).execute().use { assertEquals("kept", it.body.string()) } }
            assertEquals(1, hops.get())
            assertEquals(1, m.count)
        }
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

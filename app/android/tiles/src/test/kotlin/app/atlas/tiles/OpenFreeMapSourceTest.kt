package app.atlas.tiles

import app.atlas.geo.TileId
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.io.IOException

class OpenFreeMapSourceTest {
    private val dir = tempDir("ofm")
    private val server = FakeTileServer(Fixtures.all.associateWith { Fixtures.bytes(it) })
    private var now = 1_700_000_000_000L

    @After
    fun stop() = server.close()

    private fun source(cache: TileCache? = null, url: String = server.tileJsonUrl) =
        OpenFreeMapSource(url, cache, backoffMillis = 5, clock = { now })

    @Test
    fun resolvesTheTileJsonAndFetchesPolitely() = runBlocking {
        val src = source()
        val tj = src.tileJson()
        assertEquals("20261011_080001_pt", tj.version)
        assertEquals(14, tj.maxZoom)
        assertEquals(listOf("water", "building"), tj.vectorLayers)
        assertArrayEquals(Fixtures.bytes(Fixtures.CENTRE), src.tile(Fixtures.CENTRE))
        val tileReq = server.requests.first { it.url.encodedPath.endsWith(".pbf") }
        assertEquals(Http.USER_AGENT, tileReq.headers["User-Agent"])
        assertEquals("gzip", tileReq.headers["Accept-Encoding"])
        assertEquals("/planet/20261011_080001_pt/14/8414/5384.pbf", tileReq.url.encodedPath)
        // The TileJSON is asked for once and kept.
        src.tile(TileId(13, 4207, 2692))
        assertEquals(1, server.tileJsonRequests.get())
        assertNull(src.tile(TileId(14, 1, 1)))
        assertNull(src.tile(TileId(15, 0, 0)))
        assertEquals(OpenFreeMapSource.ATTRIBUTION, src.attribution)
    }

    @Test
    fun handlesServersThatDoNotGzip() = runBlocking {
        server.gzipTiles = false
        val src = source()
        val r = src.fetch(Fixtures.CENTRE) as FetchResult.Fetched
        assertFalse(r.gzipped)
        assertArrayEquals(Fixtures.bytes(Fixtures.CENTRE), r.raw())
        assertTrue(Gzip.isGzip(r.stored()))
    }

    @Test
    fun retriesWithBackoffThenGivesUp() = runBlocking {
        val src = source()
        server.failNext.set(2)
        assertArrayEquals(Fixtures.bytes(Fixtures.CENTRE), src.tile(Fixtures.CENTRE))
        assertEquals(3, server.tileRequests.get())
        server.failNext.set(10)
        val e = runCatching { src.fetch(TileId(0, 0, 0)) }.exceptionOrNull()
        assertTrue("$e", e is HttpStatusException && e.code == 503)
        assertEquals(3 + 4, server.tileRequests.get())
    }

    @Test
    fun honoursRetryAfter() = runBlocking {
        val src = source()
        src.tileJson()
        server.failCode = 429
        server.retryAfter = "1"
        server.failNext.set(1)
        val t0 = System.nanoTime()
        assertArrayEquals(Fixtures.bytes(Fixtures.CENTRE), src.tile(Fixtures.CENTRE))
        val waited = (System.nanoTime() - t0) / 1_000_000
        assertTrue("waited $waited ms", waited >= 900)
        assertEquals(2, server.tileRequests.get())
    }

    @Test
    fun followsANewReleaseWhenTheOldOneIsGone() = runBlocking {
        val src = source()
        assertEquals("20261011_080001_pt", src.version())
        server.version = "20261018_000000_pt"
        // A release is not retired within a minute of reading the TileJSON: only then does a 404 make the source look again.
        val early = src.fetch(Fixtures.CENTRE)
        assertEquals(FetchResult.Missing, early)
        assertEquals(1, server.tileJsonRequests.get())
        now += 61_000
        val r = src.fetch(Fixtures.CENTRE) as FetchResult.Fetched
        assertEquals("20261018_000000_pt", r.version)
        assertEquals(2, server.tileJsonRequests.get())
    }

    @Test
    fun keepsAtMostFourRequestsInFlightAndSharesDuplicates() = runBlocking {
        server.delayMillis = 60
        val src = source()
        src.tileJson()
        val ids = Fixtures.all
        val results = ids.map { id -> async(kotlinx.coroutines.Dispatchers.IO) { src.tile(id) } } +
            ids.map { id -> async(kotlinx.coroutines.Dispatchers.IO) { src.tile(id) } }
        val got = results.awaitAll()
        for ((i, id) in (ids + ids).withIndex()) assertArrayEquals(Fixtures.bytes(id), got[i])
        assertTrue("max in flight ${server.maxInFlight.get()}", server.maxInFlight.get() in 2..4)
        // Concurrent requests for one tile shared a download (a few may race past each other at the start).
        assertTrue("requests ${server.tileRequests.get()}", server.tileRequests.get() < ids.size * 2)
    }

    @Test
    fun readsThroughTheCacheAndRevalidates() = runBlocking {
        TileCache(File(dir, "c.mbtiles"), maxAgeMillis = 10_000, clock = { now }).use { cache ->
            val src = source(cache)
            assertArrayEquals(Fixtures.bytes(Fixtures.CENTRE), src.tile(Fixtures.CENTRE))
            assertEquals(1, server.tileRequests.get())
            val stored = cache.entry(Fixtures.CENTRE)!!
            assertTrue(Gzip.isGzip(stored.stored))
            assertEquals(server.etag(Fixtures.CENTRE), stored.etag)
            assertEquals("20261011_080001_pt", stored.version)
            // Fresh: no network.
            assertArrayEquals(Fixtures.bytes(Fixtures.CENTRE), src.tile(Fixtures.CENTRE))
            assertEquals(1, server.tileRequests.get())
            // Stale: a conditional request, answered 304, refreshes the age.
            now += 20_000
            assertArrayEquals(Fixtures.bytes(Fixtures.CENTRE), src.tile(Fixtures.CENTRE))
            assertEquals(2, server.tileRequests.get())
            assertEquals(server.etag(Fixtures.CENTRE), server.requests.last().headers["If-None-Match"])
            assertEquals(1, server.served[Fixtures.CENTRE]!!.get())
            assertTrue(cache.isFresh(cache.entry(Fixtures.CENTRE)!!, "20261011_080001_pt"))
        }
    }

    @Test
    fun servesStaleTilesWhenTheNetworkIsGone() = runBlocking {
        val file = File(dir, "offline.mbtiles")
        TileCache(file, maxAgeMillis = 10_000, clock = { now }).use { cache ->
            source(cache).tile(Fixtures.CENTRE)
        }
        server.close()
        now += 1_000_000
        TileCache(file, maxAgeMillis = 10_000, clock = { now }).use { cache ->
            // A new source finds the TileJSON in the cache, cannot reach the server, and serves the stale tile.
            val src = source(cache)
            assertArrayEquals(Fixtures.bytes(Fixtures.CENTRE), src.tile(Fixtures.CENTRE))
            assertEquals("20261011_080001_pt", src.version())
            val e = runCatching { src.tile(TileId(0, 0, 0)) }.exceptionOrNull()
            assertTrue("$e", e is IOException)
        }
    }

    @Test
    fun failsClearlyWithoutTileJson() = runBlocking {
        server.tileJsonStatus = 500
        val e = runCatching { source().tile(Fixtures.CENTRE) }.exceptionOrNull()
        assertTrue("$e", e is IOException)
    }

    @Test
    fun aBurstOfMissingTilesCostsOneTileJsonRefresh() = runBlocking {
        val src = source()
        src.tileJson()
        now += 61_000
        for (x in 0 until 8) assertEquals(FetchResult.Missing, src.fetch(TileId(14, x, 3)))
        // One refresh for the first 404; the rest within the minute reuse it.
        assertEquals(2, server.tileJsonRequests.get())
        assertEquals(8, server.tileRequests.get())
    }

    @Test
    fun offlineStaleTilesAreServedWithoutWaitingOnRetries() = runBlocking {
        TileCache(File(dir, "quiet.mbtiles"), maxAgeMillis = 10_000, clock = { now }).use { cache ->
            val src = OpenFreeMapSource(server.tileJsonUrl, cache, backoffMillis = 200, clock = { now })
            for (id in Fixtures.z14) src.tile(id)
            val before = server.tileRequests.get()
            now += 20_000
            server.failNext.set(1_000)
            val t0 = System.nanoTime()
            for (id in Fixtures.z14) assertArrayEquals(Fixtures.bytes(id), src.tile(id))
            val millis = (System.nanoTime() - t0) / 1_000_000
            // One attempt fails, then the network is left alone: no retries, no backoff.
            assertEquals(1, server.tileRequests.get() - before)
            assertTrue("took $millis ms", millis < 2_000)
            // A tile with no copy gets a single attempt while the network is gone.
            assertTrue(runCatching { src.tile(TileId(13, 4207, 2692)) }.exceptionOrNull() is IOException)
            assertEquals(2, server.tileRequests.get() - before)
            // Later, with the server back, stale tiles are revalidated again.
            server.failNext.set(0)
            now += OpenFreeMapSource.NETWORK_QUIET_MILLIS + 1
            assertArrayEquals(Fixtures.bytes(Fixtures.CENTRE), src.tile(Fixtures.CENTRE))
            assertEquals(3, server.tileRequests.get() - before)
            assertTrue(cache.isFresh(cache.entry(Fixtures.CENTRE)!!))
        }
    }

    @Test
    fun corruptTilesAreNotCached() = runBlocking {
        TileCache(File(dir, "corrupt.mbtiles"), clock = { now }).use { cache ->
            val src = source(cache)
            server.corruptTiles = true
            val e = runCatching { src.tile(Fixtures.CENTRE) }.exceptionOrNull()
            assertTrue("$e", e is TileFormatException)
            assertNull(cache.entry(Fixtures.CENTRE))
            server.corruptTiles = false
            assertArrayEquals(Fixtures.bytes(Fixtures.CENTRE), src.tile(Fixtures.CENTRE))
        }
    }

    @Test
    fun parsesTheRealTileJsonShape() {
        val tj = TileJson.parse(
            """{"bounds":[-180,-85.05113,180,85.05113],"center":[0,0,0],"maxzoom":14,"minzoom":0,"name":"OpenFreeMap",""" +
                """"tilejson":"3.0.0","tiles":["https://tiles.openfreemap.org/planet/20261011_080001_pt/{z}/{x}/{y}.pbf"],""" +
                """"vector_layers":[{"id":"aerodrome_label","fields":{"class":"String"}},{"id":"transportation"}]}""",
        )
        assertEquals("20261011_080001_pt", tj.version)
        assertEquals("https://tiles.openfreemap.org/planet/20261011_080001_pt/14/8414/5384.pbf", tj.url(Fixtures.CENTRE))
        assertEquals(listOf("aerodrome_label", "transportation"), tj.vectorLayers)
        assertEquals(-85.05113, tj.bounds!!.south, 1e-9)
    }

    /** Against the real OpenFreeMap: run with -Datlas.online=true. */
    @Test
    fun onlineOpenFreeMap() = runBlocking {
        assumeTrue(System.getProperty("atlas.online") == "true")
        val src = OpenFreeMapSource()
        val tj = src.tileJson()
        assertTrue(tj.template, tj.template.startsWith("https://tiles.openfreemap.org/planet/"))
        assertEquals(14, tj.maxZoom)
        val raw = src.tile(Fixtures.CENTRE)!!
        val tile = Mvt.decode(raw)
        assertTrue(tile.layerNames.containsAll(setOf("building", "transportation", "water", "poi")))
        assertTrue(tile["poi"]!!.features.any { it.string("name") == "Westerkerk" })
        println("OpenFreeMap ${tj.version}: 14/8414/5384 is ${raw.size / 1024} KB raw, layers ${tile.layerNames}")
    }
}

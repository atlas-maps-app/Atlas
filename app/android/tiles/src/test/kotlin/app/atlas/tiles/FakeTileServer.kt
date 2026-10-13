package app.atlas.tiles

import app.atlas.geo.TileId
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okio.Buffer
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * OpenFreeMap on MockWebServer: a TileJSON at /planet naming a versioned tile URL, and gzipped tiles behind it
 * with ETags. It counts requests, tracks how many run at once, and can fail on demand.
 */
class FakeTileServer(val tiles: Map<TileId, ByteArray>) : Dispatcher(), AutoCloseable {
    val server = MockWebServer()

    @Volatile var version = "20261011_080001_pt"
    @Volatile var gzipTiles = true
    @Volatile var delayMillis = 0L
    /** Answer this many tile requests with [failCode] (and [retryAfter], when set) before serving. */
    val failNext = AtomicInteger()
    @Volatile var failCode = 503
    @Volatile var retryAfter: String? = null
    /** Close the connection without answering this many tile requests. */
    val dropNext = AtomicInteger()
    @Volatile var tileJsonStatus = 200
    /** Serve tiles as gzip that does not inflate (a broken proxy, say). */
    @Volatile var corruptTiles = false

    val tileRequests = AtomicInteger()
    val tileJsonRequests = AtomicInteger()
    private val inFlight = AtomicInteger()
    val maxInFlight = AtomicInteger()
    val requests: MutableList<RecordedRequest> = Collections.synchronizedList(ArrayList())
    val served = ConcurrentHashMap<TileId, AtomicInteger>()

    init {
        server.dispatcher = this
        server.start()
    }

    val tileJsonUrl: String get() = server.url("/planet").toString()

    fun etag(id: TileId) = "\"$version-${id.z}-${id.x}-${id.y}\""

    override fun dispatch(request: RecordedRequest): MockResponse {
        requests += request
        val path = request.url.encodedPath
        if (path == "/planet") {
            tileJsonRequests.incrementAndGet()
            if (tileJsonStatus != 200) return MockResponse.Builder().code(tileJsonStatus).build()
            val base = server.url("/planet").toString()
            val json = """{"tilejson":"3.0.0","tiles":["$base/$version/{z}/{x}/{y}.pbf"],"minzoom":0,"maxzoom":14,""" +
                """"bounds":[-180,-85.05113,180,85.05113],"attribution":"OpenFreeMap © OpenMapTiles","vector_layers":[{"id":"water"},{"id":"building"}]}"""
            return MockResponse.Builder().code(200).setHeader("Content-Type", "application/json").body(json).build()
        }
        tileRequests.incrementAndGet()
        val now = inFlight.incrementAndGet()
        maxInFlight.accumulateAndGet(now) { a, b -> maxOf(a, b) }
        try {
            if (delayMillis > 0) Thread.sleep(delayMillis)
            if (dropNext.getAndUpdate { if (it > 0) it - 1 else 0 } > 0) {
                return MockResponse.Builder().onResponseStart(mockwebserver3.SocketEffect.CloseSocket()).build()
            }
            if (failNext.getAndUpdate { if (it > 0) it - 1 else 0 } > 0) {
                val b = MockResponse.Builder().code(failCode)
                retryAfter?.let { b.setHeader("Retry-After", it) }
                return b.build()
            }
            val m = Regex("/planet/([^/]+)/(\\d+)/(\\d+)/(\\d+)\\.pbf").matchEntire(path) ?: return MockResponse.Builder().code(404).build()
            val (ver, z, x, y) = m.destructured
            if (ver != version) return MockResponse.Builder().code(404).build()
            val id = TileId(z.toInt(), x.toInt(), y.toInt())
            val raw = tiles[id] ?: return MockResponse.Builder().code(404).build()
            if (request.headers["If-None-Match"] == etag(id)) return MockResponse.Builder().code(304).setHeader("ETag", etag(id)).build()
            served.getOrPut(id) { AtomicInteger() }.incrementAndGet()
            if (corruptTiles) {
                val bad = Gzip.compress(raw).also { it[it.size / 2] = (it[it.size / 2] + 1).toByte() }
                return MockResponse.Builder().code(200).setHeader("ETag", etag(id)).setHeader("Content-Encoding", "gzip").body(Buffer().write(bad)).build()
            }
            val gzipOk = request.headers["Accept-Encoding"]?.contains("gzip") == true
            val b = MockResponse.Builder().code(200).setHeader("ETag", etag(id)).setHeader("Content-Type", "application/x-protobuf")
            return if (gzipTiles && gzipOk) b.setHeader("Content-Encoding", "gzip").body(Buffer().write(Gzip.compress(raw))).build()
            else b.body(Buffer().write(raw)).build()
        } finally {
            inFlight.decrementAndGet()
        }
    }

    override fun close() = server.close()
}

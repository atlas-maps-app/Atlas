package app.atlas.tiles

import app.atlas.geo.BBox
import app.atlas.geo.TileId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import kotlin.random.Random

/** A TileJSON document: where the tiles are, which zooms, and the release they belong to. */
class TileJson(
    val tiles: List<String>,
    val minZoom: Int,
    val maxZoom: Int,
    val attribution: String?,
    val bounds: BBox?,
    val vectorLayers: List<String>,
    /** The document as it came, kept so it can be stored and read again. */
    val raw: String,
) {
    val template: String get() = tiles.first()

    /** The data release: OpenFreeMap's version path segment (the one before `{z}`), or the whole template. */
    val version: String
        get() {
            val parts = template.split('/')
            val i = parts.indexOf("{z}")
            return if (i > 0) parts[i - 1] else template
        }

    fun url(id: TileId): String =
        template.replace("{z}", id.z.toString()).replace("{x}", id.x.toString()).replace("{y}", id.y.toString())

    companion object {
        fun parse(text: String): TileJson {
            val m = Json.parseObject(text)
            val tiles = m.list("tiles")?.filterIsInstance<String>()?.takeIf { it.isNotEmpty() }
                ?: throw TileFormatException("TileJSON without tiles")
            val b = m.list("bounds")?.mapNotNull { (it as? Number)?.toDouble() }?.takeIf { it.size == 4 }
            val layers = m.list("vector_layers")?.mapNotNull { (it as? Map<*, *>)?.get("id") as? String } ?: emptyList()
            return TileJson(
                tiles, m.int("minzoom") ?: 0, m.int("maxzoom") ?: 14, m.str("attribution"),
                b?.let { BBox(it[0], it[1], it[2], it[3]) }, layers, text,
            )
        }
    }
}

/**
 * OpenFreeMap's OpenMapTiles vector tiles, online: free, no key.
 *
 * The tile URL carries a release version, so it is always resolved through the TileJSON, which is kept in memory and in
 * the [cache] and refreshed every [tileJsonMaxAgeMillis]. Requests are polite: a real User-Agent, at most [concurrency]
 * at once, retries with exponential backoff on network errors and 408/429/5xx (honouring Retry-After). Responses are asked
 * for gzipped and kept gzipped in the cache; [tile] hands out raw bytes.
 *
 * As a [TileSource] it reads through the [cache]: a fresh cached tile is served without the network, a stale one is
 * revalidated (conditionally, with its ETag, in a single attempt), and when the network fails a stale tile is served rather
 * than nothing. After a failed fetch the network is left alone for [NETWORK_QUIET_MILLIS]: stale tiles are served at once
 * and missing ones get one attempt, so an offline map never waits on retries. Concurrent requests for the same tile share
 * one download. Give the cache to this source rather than to the [TileStack] as well: a stack that asks the cache first
 * would serve stale tiles for ever.
 */
class OpenFreeMapSource(
    val tileJsonUrl: String = DEFAULT_TILEJSON,
    private val cache: TileCache? = null,
    private val client: OkHttpClient = Http.client,
    concurrency: Int = 4,
    private val maxAttempts: Int = 4,
    private val backoffMillis: Long = 500,
    private val tileJsonMaxAgeMillis: Long = 6L * 3600 * 1000,
    private val clock: () -> Long = System::currentTimeMillis,
) : TileSource, TileFetcher {
    private val permits = Semaphore(concurrency)
    private val tileJsonLock = Mutex()
    private val inFlight = ConcurrentHashMap<TileId, CompletableDeferred<ByteArray?>>()

    @Volatile
    private var resolved: TileJson? = null
    private var resolvedAt = 0L
    private var lastFailureAt = Long.MIN_VALUE / 2
    private var lastFailure: IOException? = null

    /** Clock time until which the network counts as gone, after a tile fetch failed. */
    @Volatile
    private var quietUntil = Long.MIN_VALUE

    override val minZoom: Int get() = resolved?.minZoom ?: 0
    override val maxZoom: Int get() = resolved?.maxZoom ?: 14
    override val attribution: String get() = ATTRIBUTION

    /**
     * The TileJSON, from memory, the cache or the network. With [refresh] the network is asked unless the copy is under
     * a minute old (so a burst of missing tiles costs one refresh, not one each). When the network fails a stale copy is
     * used, and for a minute after a failure it is not asked again.
     */
    suspend fun tileJson(refresh: Boolean = false): TileJson = tileJsonLock.withLock {
        val now = clock()
        if (resolved == null && cache != null) withContext(Dispatchers.IO) {
            val text = cache.note(TILEJSON_KEY)
            val at = cache.note(TILEJSON_AT_KEY)?.toLongOrNull()
            if (text != null && at != null) runCatching { TileJson.parse(text) }.getOrNull()?.let { resolved = it; resolvedAt = at }
        }
        val known = resolved
        val maxAge = if (refresh) minOf(MIN_REFRESH_MILLIS, tileJsonMaxAgeMillis) else tileJsonMaxAgeMillis
        if (known != null && now - resolvedAt < maxAge) return@withLock known
        if (now - lastFailureAt < FAILURE_QUIET_MILLIS) {
            if (known != null) return@withLock known
            lastFailure?.let { throw IOException("TileJSON unavailable: ${it.message}", it) }
        }
        try {
            val tj = downloadTileJson()
            resolved = tj
            resolvedAt = now
            lastFailure = null
            if (cache != null) withContext(Dispatchers.IO) {
                cache.note(TILEJSON_KEY, tj.raw)
                cache.note(TILEJSON_AT_KEY, now.toString())
            }
            tj
        } catch (e: IOException) {
            lastFailureAt = now
            lastFailure = e
            known ?: throw e
        }
    }

    override suspend fun version(): String = tileJson().version

    override suspend fun vectorLayers(): List<String> = tileJson().vectorLayers

    override suspend fun tile(id: TileId): ByteArray? {
        if (!id.isValid() || id.z > maxZoom || id.z < minZoom) return null
        val mine = CompletableDeferred<ByteArray?>()
        val other = inFlight.putIfAbsent(id, mine)
        if (other != null) {
            return try {
                other.await()
            } catch (e: CancellationException) {
                // The first caller gave up; this one still wants the tile.
                if (!currentCoroutineContext().isActive) throw e
                load(id)
            }
        }
        try {
            return load(id).also { mine.complete(it) }
        } catch (e: Throwable) {
            mine.completeExceptionally(e)
            throw e
        } finally {
            inFlight.remove(id, mine)
        }
    }

    private suspend fun load(id: TileId): ByteArray? {
        val entry = cache?.let { c -> withContext(Dispatchers.IO) { c.entry(id) } }
        val tj = try {
            tileJson()
        } catch (e: IOException) {
            return entry?.raw() ?: throw e
        }
        if (entry != null && cache.isFresh(entry, tj.version)) return entry.raw()
        val quiet = clock() < quietUntil
        if (entry != null && quiet) return entry.raw()
        val etag = entry?.takeIf { it.version == tj.version }?.etag
        return try {
            // With a copy to fall back on, or the network just gone, one attempt: the map never waits on retries.
            val r = fetch(id, etag, attempts = if (entry != null || quiet) 1 else maxAttempts)
            quietUntil = Long.MIN_VALUE
            when (r) {
                is FetchResult.Fetched -> withContext(Dispatchers.IO) {
                    // Inflated (and so checked) before it is cached: a corrupt body must not sit in the cache as fresh.
                    val raw = r.raw()
                    cache?.put(id, r.stored(), r.etag, r.version)
                    raw
                }
                FetchResult.NotModified -> {
                    if (cache != null) withContext(Dispatchers.IO) { cache.markFresh(id, version = tj.version) }
                    entry?.raw() ?: throw IOException("304 for $id without a cached copy")
                }
                FetchResult.Missing -> null
            }
        } catch (e: IOException) {
            if (e !is TileFormatException) quietUntil = clock() + NETWORK_QUIET_MILLIS
            entry?.raw() ?: throw e
        }
    }

    override suspend fun fetch(id: TileId, etag: String?): FetchResult = fetch(id, etag, maxAttempts)

    private suspend fun fetch(id: TileId, etag: String?, attempts: Int): FetchResult {
        if (!id.isValid()) return FetchResult.Missing
        var tj = tileJson()
        var retriedVersion = false
        var attempt = 0
        while (true) {
            val url = tj.url(id)
            val request = Http.request(url)
                .header("Accept-Encoding", "gzip")
                .apply { if (etag != null) header("If-None-Match", etag) }
                .build()
            val r = try {
                permits.withPermit { client.newCall(request).read() }
            } catch (e: IOException) {
                if (++attempt >= attempts) throw e
                delay(backoff(attempt, null))
                continue
            }
            when (r.code) {
                200 -> return FetchResult.Fetched(r.body, Gzip.isGzip(r.body), r.header("ETag"), tj.version)
                204 -> return FetchResult.Fetched(ByteArray(0), false, r.header("ETag"), tj.version)
                304 -> return FetchResult.NotModified
                404, 410 -> {
                    // A retired release answers 404 everywhere: look for the new one once.
                    if (!retriedVersion) {
                        retriedVersion = true
                        val fresh = tileJson(refresh = true)
                        if (fresh.version != tj.version) {
                            tj = fresh
                            continue
                        }
                    }
                    return FetchResult.Missing
                }
                408, 425, 429, 500, 502, 503, 504 -> {
                    if (++attempt >= attempts) throw HttpStatusException(r.code, "HTTP ${r.code} for $url")
                    delay(backoff(attempt, r.header("Retry-After")))
                }
                else -> throw HttpStatusException(r.code, "HTTP ${r.code} for $url")
            }
        }
    }

    private suspend fun downloadTileJson(): TileJson {
        var attempt = 0
        while (true) {
            val request = Http.request(tileJsonUrl).header("Accept", "application/json").build()
            val r = try {
                client.newCall(request).read(maxBytes = 4L shl 20)
            } catch (e: IOException) {
                if (++attempt >= maxAttempts) throw e
                delay(backoff(attempt, null))
                continue
            }
            when (r.code) {
                200 -> return TileJson.parse(String(r.body, Charsets.UTF_8))
                408, 429, 500, 502, 503, 504 -> {
                    if (++attempt >= maxAttempts) throw HttpStatusException(r.code, "HTTP ${r.code} for $tileJsonUrl")
                    delay(backoff(attempt, r.header("Retry-After")))
                }
                else -> throw HttpStatusException(r.code, "HTTP ${r.code} for $tileJsonUrl")
            }
        }
    }

    /** Milliseconds to wait before attempt [attempt] + 1: Retry-After when given, else doubling with jitter. */
    private fun backoff(attempt: Int, retryAfter: String?): Long {
        retryAfter?.trim()?.toLongOrNull()?.let { return (it * 1000).coerceIn(0, MAX_BACKOFF_MILLIS) }
        val base = backoffMillis * (1L shl (attempt - 1).coerceAtMost(10))
        return (base * Random.nextDouble(0.75, 1.25)).toLong().coerceAtMost(MAX_BACKOFF_MILLIS)
    }

    companion object {
        const val DEFAULT_TILEJSON = "https://tiles.openfreemap.org/planet"
        const val ATTRIBUTION = "© OpenMapTiles © OpenStreetMap contributors"
        private const val TILEJSON_KEY = "openfreemap.tilejson"
        private const val TILEJSON_AT_KEY = "openfreemap.tilejson.fetched"
        private const val FAILURE_QUIET_MILLIS = 60_000L
        private const val MIN_REFRESH_MILLIS = 60_000L

        /** How long the network counts as gone after a tile fetch failed. */
        const val NETWORK_QUIET_MILLIS = 30_000L
        private const val MAX_BACKOFF_MILLIS = 30_000L
    }
}

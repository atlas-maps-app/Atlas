package app.atlas.providers

import app.atlas.geo.BBox
import app.atlas.geo.LatLon
import app.atlas.model.Place
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.CacheControl
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.time.Instant
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * A charger source that works from a whole file it downloads now and then (a national register), indexed on the phone
 * and kept in [cacheDir] so it works with no signal. A refresh is a conditional request: an unchanged file costs a
 * few hundred bytes. The download is streamed straight into a compact [ChargerIndex]; the raw file is never kept.
 *
 * With [autoDownload] false (the default, as these files run to tens of megabytes) nothing is fetched until the app
 * calls [refresh], say on Wi-Fi; until then the state is [ProviderState.NEEDS_DOWNLOAD]. With it, a download starts
 * only while [mayDownload] (the app's: on Wi-Fi), and only for a place within reach of [covers] (the register's
 * country): nobody looking for chargers in Spain downloads the Dutch register.
 *
 * A charger's live status (free now, in use) counts as live only for [LIVE_MAX_AGE] after the data came; older data
 * gives the chargers without it, so a file refreshed weekly never shows last week's free points as free now.
 */
abstract class DownloadedChargers(
    protected val http: Http,
    private val cacheDir: File?,
    /** After this long the data counts as stale and [index] refreshes it when [autoDownload] allows. */
    private val maxAge: Duration,
    private val autoDownload: Boolean,
    /** Keep only chargers in this box, to keep memory small on a phone; null keeps everything. */
    private val region: BBox?,
    private val clock: () -> Instant,
    /** Whether a download may start now (the app: on Wi-Fi); the kept copy serves meanwhile. */
    private val mayDownload: () -> Boolean = { true },
    /** The box of the register's country: a place farther from it than the search's reach is not asked; null: anywhere. */
    private val covers: BBox? = null,
    /** Where a stale register is refreshed while the kept copy answers (the app's); null: before answering. */
    private val background: CoroutineScope? = null,
) : ChargerProvider {
    /** The cache file's base name, such as "ndw". */
    protected abstract val cacheName: String

    /** Where the current file is; a register whose file name changes monthly finds it here. */
    protected abstract suspend fun sourceUrl(): String

    /** Reads the downloaded body and hands each charger in [region] to [add]. */
    protected abstract fun parse(body: InputStream, region: BBox?, add: (Place) -> Unit)

    private val lock = Mutex()

    @Volatile
    private var loaded: ChargerIndex? = null

    // The last fetch, also kept in memory: without a cache directory it is the only record, and it saves a disk read.
    @Volatile
    private var lastMeta: Meta? = null

    private val dataFile: File? get() = cacheDir?.let { File(it, "$cacheName-chargers.tsv.gz") }
    private val metaFile: File? get() = cacheDir?.let { File(it, "$cacheName-chargers.meta") }

    /** When the data was last fetched or confirmed unchanged; null before the first download. */
    val updated: Instant? get() = meta()?.fetched

    override fun state(): ProviderState =
        if (loaded != null || dataFile?.exists() == true || autoDownload) ProviderState.READY else ProviderState.NEEDS_DOWNLOAD

    /** Whether a copy of the register is on the phone (in memory or kept): it answers offline, however old. */
    override fun answersOffline(): Boolean = loaded != null || dataFile?.exists() == true

    override suspend fun near(center: LatLon, radiusM: Double, filters: ChargerFilters): List<Place> {
        if (!reaches(center, radiusM)) return emptyList()
        val found = index().nearNow(center, radiusM, filters)
        return if (live()) found else found.map(::withoutStatus)
    }

    override suspend fun status(ids: List<String>): Map<String, ChargerStatus> = if (live()) index().status(ids) else emptyMap()

    /** Whether a search within [radiusM] of [center] reaches the register's country ([covers]). */
    private fun reaches(center: LatLon, radiusM: Double): Boolean {
        val box = covers ?: return true
        val dLat = radiusM / 111_320.0
        val dLon = radiusM / (111_320.0 * kotlin.math.cos(Math.toRadians(center.lat)).coerceAtLeast(0.01))
        return center.lat in box.south - dLat..box.north + dLat && center.lon in box.west - dLon..box.east + dLon
    }

    /** Whether the data came within [LIVE_MAX_AGE], so its chargers' status is still live. */
    private fun live(): Boolean = meta()?.let { java.time.Duration.between(it.fetched, clock()).toMillis() <= LIVE_MAX_AGE.inWholeMilliseconds } == true

    /** [p] without its live status: what the data said a while ago is not what is free now. */
    private fun withoutStatus(p: Place): Place =
        if (p.extra.keys.none { it == "status" || it.startsWith("status:") }) p else p.copy(extra = p.extra.filterKeys { it != "status" && !it.startsWith("status:") })

    // When an automatic refresh last failed, and why: the kept copy serves until RETRY_AFTER has passed.
    @Volatile
    private var failure: Pair<Instant, String>? = null

    /**
     * The chargers: in memory, else from the cache, refreshed first when stale and [autoDownload] allows; with
     * [background], refreshed there instead, while the kept copy answers now (a register of tens of megabytes never
     * holds up the screen that asked). Callers that find the data stale together share one download; after a failed one
     * the kept copy serves for [RETRY_AFTER] before the server is asked again. Throws [ProviderException] when there is
     * nothing yet.
     */
    suspend fun index(): ChargerIndex {
        if (autoDownload && isStale() && !resting() && mayDownload()) {
            val scope = background
            if (scope != null) {
                refreshIn(scope)
            } else {
                try {
                    refreshNow()
                } catch (e: IOException) {
                    // Offline or the server is down: the kept copy, however old, is better than nothing.
                    loaded ?: readCache() ?: throw e
                }
            }
        }
        return loaded ?: readCache()
            ?: throw ProviderException(id, "${info.name}: nothing downloaded yet" + (failure?.let { " (the last try failed: ${it.second})" } ?: ""))
    }

    /** The refresh under way in [background], if any. */
    @Volatile
    private var refreshing: Job? = null

    /** Refreshes the stale data, remembering a failure for [RETRY_AFTER]. */
    private suspend fun refreshNow() {
        try {
            lock.withLock { if (isStale()) fetch(force = false) }
            failure = null
        } catch (e: IOException) {
            currentCoroutineContext().ensureActive()
            failure = clock() to (e.message ?: e.javaClass.simpleName)
            throw e
        }
    }

    /** Starts [refreshNow] in [scope] unless one is under way there. */
    private fun refreshIn(scope: CoroutineScope) {
        if (refreshing?.isActive == true) return
        refreshing = scope.launch { runCatching { refreshNow() } }
    }

    /** Waits for the refresh under way in [background], if any: for tests. */
    internal suspend fun settled() {
        refreshing?.join()
    }

    /**
     * Fetches the file if it changed since the last time (or always with [force]) and rebuilds the index.
     * Returns true when new data arrived, false when the server said it was unchanged.
     */
    suspend fun refresh(force: Boolean = false): Boolean = lock.withLock { fetch(force) }

    private fun isStale(): Boolean {
        val m = meta() ?: return true
        return java.time.Duration.between(m.fetched, clock()).toMillis() > maxAge.inWholeMilliseconds
    }

    private fun resting(): Boolean =
        failure?.let { (at, _) -> java.time.Duration.between(at, clock()).toMillis() < RETRY_AFTER.inWholeMilliseconds } == true

    /** [refresh] with the lock held. */
    private suspend fun fetch(force: Boolean): Boolean {
        val url = sourceUrl()
        // Ask conditionally only with a copy to fall back on: a cache file that no longer reads must come again whole.
        val kept = if (force) null else loaded ?: readCache()
        val old = meta()?.takeIf { it.url == url && kept != null }
        // never into the app's HTTP cache (64 MB): the register is kept here, and OkHttp copied each one (Germany's is 55
        // MB) into the cache as it came, pushing out every answer it kept
        val request = http.request(url).header("Accept", "*/*").cacheControl(CacheControl.Builder().noStore().build()).apply {
            old?.etag?.let { header("If-None-Match", it) }
            old?.lastModified?.let { header("If-Modified-Since", it) }
        }.build()
        val result = http.exchange(request, DOWNLOAD_TIMEOUT, DOWNLOAD_STALL) { response ->
            when {
                response.code == 304 -> null
                !response.isSuccessful -> throw HttpException(response.code, url, response.errorBody())
                else -> {
                    val builder = ChargerIndex.Builder(info)
                    response.body.byteStream().use { parse(it, region, builder::add) }
                    Triple(builder.build(), response.header("ETag"), response.header("Last-Modified"))
                }
            }
        }
        val now = clock()
        if (result == null) {
            // A 304 only answers a conditional request; without a kept copy it cannot stand for the data.
            val unchanged = old ?: throw ProviderException(id, "${info.name} answered 304 to an unconditional request")
            writeMeta(unchanged.copy(fetched = now))
            return false
        }
        val (index, etag, lastModified) = result
        loaded = index
        writeCache(index)
        writeMeta(Meta(url, etag, lastModified, now))
        return true
    }

    /** Builds the index from a file already on disk (a pack, or a test fixture) in the source's own format. */
    fun importFrom(input: InputStream): ChargerIndex {
        val builder = ChargerIndex.Builder(info)
        input.use { parse(it, region, builder::add) }
        return builder.build().also { loaded = it }
    }

    private suspend fun readCache(): ChargerIndex? {
        val f = dataFile ?: return null
        if (!f.exists()) return null
        return withContext(Dispatchers.IO) {
            runCatching { f.inputStream().use { ChargerIndex.read(it, info) } }.getOrNull()
        }?.also { loaded = it }
    }

    private suspend fun writeCache(index: ChargerIndex) {
        val f = dataFile ?: return
        withContext(Dispatchers.IO) {
            f.parentFile?.mkdirs()
            val tmp = File(f.parentFile, f.name + ".part")
            tmp.outputStream().use(index::write)
            if (!tmp.renameTo(f)) {
                f.delete()
                tmp.renameTo(f)
            }
        }
    }

    private data class Meta(val url: String, val etag: String?, val lastModified: String?, val fetched: Instant)

    private fun meta(): Meta? {
        lastMeta?.let { return it }
        val f = metaFile ?: return null
        if (!f.exists()) return null
        val lines = runCatching { f.readLines() }.getOrNull() ?: return null
        val map = lines.mapNotNull { l -> l.indexOf('=').takeIf { it > 0 }?.let { l.substring(0, it) to l.substring(it + 1) } }.toMap()
        val fetched = map["fetched"]?.let { runCatching { Instant.parse(it) }.getOrNull() } ?: return null
        return Meta(map["url"] ?: return null, map["etag"], map["last-modified"], fetched).also { lastMeta = it }
    }

    private fun writeMeta(m: Meta) {
        lastMeta = m
        val f = metaFile ?: return
        f.parentFile?.mkdirs()
        f.writeText(buildString {
            append("url=").append(m.url).append('\n')
            m.etag?.let { append("etag=").append(it).append('\n') }
            m.lastModified?.let { append("last-modified=").append(it).append('\n') }
            append("fetched=").append(m.fetched).append('\n')
        })
    }

    companion object {
        /** National files are big and some servers are slow (NDW sends about 70 kB/s): allow a long read. */
        val DOWNLOAD_TIMEOUT: Duration = 15.minutes

        /** But a download that stops moving for this long has lost its connection, and fails rather than hang. */
        val DOWNLOAD_STALL: Duration = 60.seconds

        /** Live status files (NDW) are refreshed several times a day upstream. */
        val LIVE_MAX_AGE: Duration = 3.hours

        /** After a failed automatic refresh, how long the kept copy serves before the server is asked again. */
        val RETRY_AFTER: Duration = 5.minutes
    }
}

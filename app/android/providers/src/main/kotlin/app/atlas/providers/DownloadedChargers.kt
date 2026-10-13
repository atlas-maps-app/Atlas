package app.atlas.providers

import app.atlas.geo.BBox
import app.atlas.geo.LatLon
import app.atlas.model.Place
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.time.Instant
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes

/**
 * A charger source that works from a whole file it downloads now and then (a national register), indexed on the phone
 * and kept in [cacheDir] so it works with no signal. A refresh is a conditional request: an unchanged file costs a
 * few hundred bytes. The download is streamed straight into a compact [ChargerIndex]; the raw file is never kept.
 *
 * With [autoDownload] false (the default, as these files run to tens of megabytes) nothing is fetched until the app
 * calls [refresh], say on Wi-Fi; until then the state is [ProviderState.NEEDS_DOWNLOAD].
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

    override suspend fun near(center: LatLon, radiusM: Double, filters: ChargerFilters): List<Place> = index().nearNow(center, radiusM, filters)

    override suspend fun status(ids: List<String>): Map<String, ChargerStatus> = index().status(ids)

    /**
     * The chargers: in memory, else from the cache, refreshed first when stale and [autoDownload] allows.
     * Throws [ProviderException] when there is nothing yet and nothing may be downloaded.
     */
    suspend fun index(): ChargerIndex {
        val now = clock()
        val m = meta()
        val stale = m == null || java.time.Duration.between(m.fetched, now).toMillis() > maxAge.inWholeMilliseconds
        if (stale && autoDownload) {
            try {
                refresh()
            } catch (e: IOException) {
                // Offline or the server is down: the kept copy, however old, is better than nothing.
                loaded ?: readCache() ?: throw e
            }
        }
        return loaded ?: readCache() ?: throw ProviderException(id, "${info.name}: nothing downloaded yet")
    }

    /**
     * Fetches the file if it changed since the last time (or always with [force]) and rebuilds the index.
     * Returns true when new data arrived, false when the server said it was unchanged.
     */
    suspend fun refresh(force: Boolean = false): Boolean = lock.withLock {
        val url = sourceUrl()
        val old = meta()?.takeIf { it.url == url && !force && (loaded != null || dataFile?.exists() == true) }
        val request = http.request(url).header("Accept", "*/*").apply {
            old?.etag?.let { header("If-None-Match", it) }
            old?.lastModified?.let { header("If-Modified-Since", it) }
        }.build()
        val result = http.exchange(request, DOWNLOAD_TIMEOUT) { response ->
            when {
                response.code == 304 -> null
                !response.isSuccessful -> throw HttpException(response.code, url, response.body.string().take(300))
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
            val kept = old ?: throw ProviderException(id, "${info.name} answered 304 to an unconditional request")
            writeMeta(kept.copy(fetched = now))
            return@withLock false
        }
        val (index, etag, lastModified) = result
        loaded = index
        writeCache(index)
        writeMeta(Meta(url, etag, lastModified, now))
        true
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

        /** Live status files (NDW) are refreshed several times a day upstream. */
        val LIVE_MAX_AGE: Duration = 3.hours
    }
}

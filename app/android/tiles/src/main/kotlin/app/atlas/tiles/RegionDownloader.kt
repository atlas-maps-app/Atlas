package app.atlas.tiles

import app.atlas.geo.TileId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/** Where a region download stands. */
data class DownloadProgress(
    val total: Long,
    /** Tiles handled so far: fetched, already present, unchanged, missing or failed. */
    val done: Long,
    val fetched: Long,
    /** Tiles already present and current (a resumed or updated download). */
    val skipped: Long,
    /** Tiles the service does not have. */
    val missing: Long,
    /** Tiles that could not be fetched, or came corrupt; they are not stored, so the next run asks again. */
    val failed: Long,
    /** Bytes written to the pack in this run (gzipped). */
    val bytes: Long,
    val finished: Boolean,
) {
    val fraction: Float get() = if (total == 0L) 1f else (done.toFloat() / total).coerceIn(0f, 1f)
}

/**
 * Downloads a [RegionPlan] into an MBTiles pack, tiles stored gzipped (and said so in the metadata).
 *
 * It resumes (tiles already present are skipped), reports progress as a [Flow], stays polite (at most [concurrency]
 * requests at once through the [fetcher], which identifies itself and backs off) and stops when the flow's collector is
 * cancelled; what was written stays, so the next run picks up from there. After [maxConsecutiveFailures] failures in a
 * row (the network is gone) it gives up with an [IOException]. [update] later refreshes stale tiles in place.
 * The pack's `atlas.version` names the release it holds: it moves to the service's release only when a run completes
 * without failures.
 */
class RegionDownloader(
    private val fetcher: TileFetcher,
    private val concurrency: Int = 4,
    private val batchSize: Int = 32,
    private val maxConsecutiveFailures: Int = 24,
    /** The largest plan it accepts (about the Netherlands to z14 is 52 000 tiles); larger areas belong in a prepared pack. */
    private val maxTiles: Long = 250_000,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    /** Downloads every tile of [plan] missing from [target], creating it if needed. */
    fun download(plan: RegionPlan, target: File, name: String): Flow<DownloadProgress> = run(plan, target, name, refreshOlderThan = null)

    /**
     * Refreshes the pack [target] of [plan]: tiles from an older data release or older than [maxAgeMillis] are fetched
     * again (conditionally when the release is the same), missing ones are filled in, current ones are left alone.
     */
    fun update(plan: RegionPlan, target: File, name: String, maxAgeMillis: Long = 30L * 24 * 3600 * 1000): Flow<DownloadProgress> =
        run(plan, target, name, refreshOlderThan = maxAgeMillis)

    private fun run(plan: RegionPlan, target: File, name: String, refreshOlderThan: Long?): Flow<DownloadProgress> = channelFlow {
        require(plan.tileCount <= maxTiles) { "a region of ${plan.tileCount} tiles is more than the $maxTiles one download may take" }
        val version = fetcher.version()
        require(plan.maxZoom <= fetcher.maxZoom) { "the service has tiles to zoom ${fetcher.maxZoom}, the plan asks for ${plan.maxZoom}" }
        val layers = fetcher.vectorLayers()
        val db = withContext(Dispatchers.IO) { MbTiles(target, writable = true) }
        try {
            val meta = withContext(Dispatchers.IO) { TileMetaTable(db) }
            withContext(Dispatchers.IO) {
                // The pack names the release it holds. An existing pack keeps its own until this run completes: an update
                // stopped half-way still holds mostly the old release.
                val held = db.metadata("atlas.version") ?: version
                db.putMetadata(
                    MbTiles.vectorMetadata(name, plan.bbox, plan.minZoom, plan.maxZoom, fetcher.attribution, gzipped = true, layers) +
                        mapOf("atlas.version" to held),
                )
            }
            val total = plan.tileCount
            val fetched = AtomicLong()
            val skipped = AtomicLong()
            val missing = AtomicLong()
            val failed = AtomicLong()
            val bytes = AtomicLong()
            val streak = AtomicInteger()
            fun progress(finished: Boolean) = DownloadProgress(
                total, fetched.get() + skipped.get() + missing.get() + failed.get(),
                fetched.get(), skipped.get(), missing.get(), failed.get(), bytes.get(), finished,
            )
            send(progress(false))

            class Work(val id: TileId, val etag: String?)
            /** A fetched tile, or (no result) one the server confirmed unchanged. */
            class Done(val id: TileId, val result: FetchResult.Fetched?)

            val work = Channel<Work>(concurrency * 4)
            val results = Channel<Done>(batchSize * 2)
            coroutineScope {
                // Producer: walk the plan, skipping what is already there and current.
                launch(Dispatchers.IO) {
                    try {
                        for (z in plan.minZoom..plan.maxZoom) {
                            val zt = plan.tilesAt(z) ?: continue
                            val present = db.tileKeys(z)
                            for (id in zt.tiles()) {
                                val here = java.util.Arrays.binarySearch(present, (id.x.toLong() shl 32) or id.y.toLong()) >= 0
                                if (!here) { work.send(Work(id, null)); continue }
                                if (refreshOlderThan == null) { skipped.incrementAndGet(); continue }
                                val m = meta.get(id)
                                val current = m != null && m.version == version && clock() - m.fetchedAt < refreshOlderThan
                                if (current) skipped.incrementAndGet()
                                else work.send(Work(id, m?.etag?.takeIf { m.version == version }))
                            }
                        }
                    } finally {
                        work.close()
                    }
                }
                // Workers: fetch politely.
                val workers = List(concurrency) {
                    launch {
                        for (w in work) {
                            val r = try {
                                fetcher.fetch(w.id, w.etag)
                            } catch (e: IOException) {
                                failed.incrementAndGet()
                                if (streak.incrementAndGet() >= maxConsecutiveFailures) {
                                    throw IOException("download stopped after $maxConsecutiveFailures failures in a row: ${e.message}", e)
                                }
                                continue
                            }
                            streak.set(0)
                            when (r) {
                                is FetchResult.Fetched -> results.send(Done(w.id, r))
                                FetchResult.NotModified -> results.send(Done(w.id, null))
                                FetchResult.Missing -> missing.incrementAndGet()
                            }
                        }
                    }
                }
                launch {
                    workers.forEach { it.join() }
                    results.close()
                }
                // Writer: batched inserts in transactions, one progress report per batch.
                val batch = ArrayList<Done>(batchSize)
                suspend fun write() {
                    if (batch.isEmpty()) return
                    withContext(Dispatchers.IO) {
                        // A body that does not inflate (a broken proxy, a cut connection) must not sit in the pack as
                        // current, where neither the map nor a later update would ever replace it.
                        val bodies = arrayOfNulls<ByteArray>(batch.size)
                        for ((k, d) in batch.withIndex()) {
                            val r = d.result ?: continue
                            bodies[k] = try {
                                r.stored().also { Gzip.decompress(it) }
                            } catch (e: TileFormatException) {
                                failed.incrementAndGet()
                                null
                            }
                        }
                        val now = clock()
                        db.transaction {
                            for ((k, d) in batch.withIndex()) {
                                val r = d.result
                                if (r == null) {
                                    meta.get(d.id)?.let { meta.put(it.copy(version = version, fetchedAt = now)) }
                                    skipped.incrementAndGet()
                                    continue
                                }
                                val stored = bodies[k] ?: continue
                                db.putTile(d.id, stored)
                                meta.put(TileMeta(d.id, r.etag, r.version, now, now, stored.size))
                                bytes.addAndGet(stored.size.toLong())
                                fetched.incrementAndGet()
                            }
                        }
                    }
                    batch.clear()
                    send(progress(false))
                }
                for (d in results) {
                    batch += d
                    if (batch.size >= batchSize) write()
                }
                write()
            }
            withContext(Dispatchers.IO) {
                // Only a run that got every tile it asked for moves the pack to the new release.
                val done = if (failed.get() == 0L) mapOf("atlas.version" to version) else emptyMap()
                db.putMetadata(done + ("atlas.updated" to clock().toString()))
                db.compact()
            }
            send(progress(true))
        } finally {
            withContext(kotlinx.coroutines.NonCancellable + Dispatchers.IO) { db.close() }
        }
    }
}

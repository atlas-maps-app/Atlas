package app.atlas.tiles

import androidx.sqlite.execSQL
import app.atlas.geo.TileId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * The disk cache of downloaded tiles: an MBTiles file with a size cap and staleness.
 *
 * Tiles are kept gzip-compressed, as they arrive. Reads are recorded in memory and written in batches, so drawing the
 * map does not write to disk on every tile. When the cache grows past [maxBytes] the least recently used tiles go
 * until it is back under nine tenths of it.
 *
 * As a [TileSource] it serves any tile it holds, fresh or stale (offline-first). [OpenFreeMapSource] uses [entry]
 * and [isFresh] to revalidate stale tiles when the network is there.
 */
class TileCache(
    val file: File,
    val maxBytes: Long = 512L shl 20,
    /** A tile older than this is stale and is revalidated when online. */
    val maxAgeMillis: Long = 7L * 24 * 3600 * 1000,
    private val clock: () -> Long = System::currentTimeMillis,
) : TileSource {
    private val db = MbTiles(file, writable = true)
    private val meta = TileMetaTable(db)
    private val pendingAccess = HashMap<Long, Long>()
    private var total: Long = meta.totalBytes()

    init {
        if (db.metadata("format") == null) {
            db.putMetadata(mapOf("name" to "Atlas tile cache", "format" to "pbf", "type" to "overlay", "compression" to "gzip"))
        }
    }

    override val minZoom: Int get() = 0
    override val maxZoom: Int get() = 14
    override val attribution: String get() = OpenFreeMapSource.ATTRIBUTION

    /** Bytes of tile data held. */
    val sizeBytes: Long get() = synchronized(this) { total }

    fun count(): Long = db.count()

    override suspend fun tile(id: TileId): ByteArray? = withContext(Dispatchers.IO) { entry(id)?.raw() }

    /** The cached tile [id] with what is known about it, recording the read. */
    fun entry(id: TileId): CacheEntry? {
        val data = db.readStored(id) ?: return null
        val m = meta.get(id)
        touch(id)
        return CacheEntry(id, data, m)
    }

    /** True when [entry] is younger than [maxAgeMillis] and belongs to [version] (when both are known). */
    fun isFresh(entry: CacheEntry, version: String? = null): Boolean {
        val m = entry.meta ?: return false
        if (version != null && m.version != null && m.version != version) return false
        return m.age(clock()) < maxAgeMillis
    }

    /** Stores [data] (raw or gzipped; kept gzipped) for [id], then trims the cache if it grew too big. */
    fun put(id: TileId, data: ByteArray, etag: String? = null, version: String? = null) {
        val stored = Gzip.wrap(data)
        val now = clock()
        var over = false
        db.transaction {
            val old = meta.get(id)
            db.putTile(id, stored)
            meta.put(TileMeta(id, etag, version, now, now, stored.size))
            synchronized(this) {
                total += stored.size - (old?.bytes ?: 0)
                over = total > maxBytes
            }
        }
        if (over) trim()
    }

    /** Records that [id] was confirmed current now (an HTTP 304). */
    fun markFresh(id: TileId, etag: String? = null, version: String? = null) {
        val now = clock()
        // One transaction, so a tile evicted meanwhile is not given back a row (and bytes) it no longer has.
        db.transaction {
            val m = meta.get(id) ?: return@transaction
            meta.put(m.copy(etag = etag ?: m.etag, version = version ?: m.version, fetchedAt = now, lastAccess = now))
        }
    }

    fun remove(id: TileId) {
        db.transaction {
            val old = meta.get(id)
            db.deleteTile(id)
            meta.delete(id)
            if (old != null) synchronized(this) { total -= old.bytes }
        }
    }

    /** Evicts least recently used tiles until the cache holds at most [targetBytes]. */
    fun trim(targetBytes: Long = maxBytes * 9 / 10) {
        flush()
        while (sizeBytes > targetBytes) {
            // Victims are chosen and removed in one transaction: two threads trimming at once never count a tile twice.
            val removed = db.transaction {
                var n = 0
                for (v in meta.oldest(256)) {
                    if (sizeBytes <= targetBytes) break
                    db.deleteTile(v.id)
                    meta.delete(v.id)
                    synchronized(this) { total -= v.bytes }
                    n++
                }
                n
            }
            if (removed == 0) break
        }
    }

    /** Writes recorded reads to disk. */
    fun flush() {
        val batch = synchronized(this) {
            if (pendingAccess.isEmpty()) return
            HashMap(pendingAccess).also { pendingAccess.clear() }
        }
        db.transaction { for ((k, t) in batch) meta.touch(TileKey.unpack(k), t) }
    }

    /** Removes every tile. */
    fun clear() {
        synchronized(this) { pendingAccess.clear() }
        db.transaction {
            db.withConnection { it.execSQL("DELETE FROM tiles") }
            meta.clear()
            synchronized(this) { total = 0 }
        }
    }

    /** A value kept with the cache (the resolved TileJSON lives here). */
    fun note(key: String): String? = db.metadata("atlas.$key")

    fun note(key: String, value: String) = db.putMetadata("atlas.$key", value)

    override fun close() {
        runCatching { flush() }
        db.close()
    }

    private fun touch(id: TileId) {
        val flushNow = synchronized(this) {
            pendingAccess[TileKey.pack(id)] = clock()
            pendingAccess.size >= FLUSH_EVERY
        }
        if (flushNow) flush()
    }

    private companion object {
        const val FLUSH_EVERY = 256
    }
}

/** A cached tile: its stored (gzipped) bytes and what is known about it. */
class CacheEntry(val id: TileId, val stored: ByteArray, val meta: TileMeta?) {
    fun raw(): ByteArray = Gzip.unwrap(stored)
    val etag: String? get() = meta?.etag
    val version: String? get() = meta?.version
}

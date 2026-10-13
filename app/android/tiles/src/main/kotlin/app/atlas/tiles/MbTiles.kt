package app.atlas.tiles

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.SQLiteStatement
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.driver.bundled.SQLITE_OPEN_CREATE
import androidx.sqlite.driver.bundled.SQLITE_OPEN_READONLY
import androidx.sqlite.driver.bundled.SQLITE_OPEN_READWRITE
import androidx.sqlite.execSQL
import app.atlas.geo.BBox
import app.atlas.geo.TileId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * An MBTiles 1.3 file of vector tiles, read and (when [writable]) written through the bundled SQLite.
 *
 * Rows use the standard schema with the TMS row flip. Stored tiles may be gzip-compressed (the `compression`
 * metadata says so, and the reader sniffs each blob anyway); [tile] always hands out raw MVT bytes.
 * One connection serves all callers in turn; every call is safe from any thread.
 */
class MbTiles(val file: File, val writable: Boolean = false) : TileSource {
    constructor(path: String, writable: Boolean = false) : this(File(path), writable)

    private val lock = ReentrantLock()
    private val conn: SQLiteConnection
    private val statements = HashMap<String, SQLiteStatement>()

    // Replaced whole under the lock, read without it.
    @Volatile
    private var meta: Map<String, String>
    @Volatile
    private var closed = false

    init {
        if (!writable && !file.isFile) throw java.io.FileNotFoundException(file.path)
        file.absoluteFile.parentFile?.mkdirs()
        val flags = if (writable) SQLITE_OPEN_READWRITE or SQLITE_OPEN_CREATE else SQLITE_OPEN_READONLY
        conn = driver.open(file.path, flags)
        try {
            conn.execSQL("PRAGMA busy_timeout = 5000")
            if (writable) {
                // Refuse before changing anything: switching to WAL is persistent and would alter a file we will not write.
                if (tableType("tiles") == "view") throw UnsupportedOperationException("${file.name} keeps deduplicated tiles behind a view; it can only be read")
                conn.execSQL("PRAGMA journal_mode = WAL")
                conn.execSQL("PRAGMA synchronous = NORMAL")
                conn.execSQL("CREATE TABLE IF NOT EXISTS metadata (name TEXT, value TEXT)")
                conn.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS name ON metadata (name)")
                conn.execSQL("CREATE TABLE IF NOT EXISTS tiles (zoom_level INTEGER, tile_column INTEGER, tile_row INTEGER, tile_data BLOB)")
                conn.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS tile_index ON tiles (zoom_level, tile_column, tile_row)")
            } else if (tableType("tiles") == null) {
                throw TileFormatException("${file.name} is not an MBTiles file (no tiles table)")
            }
            meta = readMetadata()
        } catch (e: Throwable) {
            conn.close()
            throw e
        }
    }

    /** All metadata rows. */
    val metadata: Map<String, String> get() = meta

    fun metadata(key: String): String? = meta[key]

    override val minZoom: Int get() = meta["minzoom"]?.toIntOrNull() ?: 0
    override val maxZoom: Int get() = meta["maxzoom"]?.toIntOrNull() ?: 14
    override val attribution: String get() = meta["attribution"] ?: ""
    override val bounds: BBox
        get() = meta["bounds"]?.split(',')?.mapNotNull { it.trim().toDoubleOrNull() }?.takeIf { it.size == 4 }
            ?.let { BBox(it[0], it[1], it[2], it[3]) } ?: BBox.WORLD

    /** True when this file says its tiles are stored gzip-compressed. */
    val isGzipped: Boolean get() = meta["compression"] == "gzip"

    override suspend fun tile(id: TileId): ByteArray? = withContext(Dispatchers.IO) { readTile(id) }

    /** The raw MVT bytes of [id], blocking. */
    fun readTile(id: TileId): ByteArray? = readStored(id)?.let(Gzip::unwrap)

    /** The bytes of [id] exactly as stored (often gzip-compressed), blocking. */
    fun readStored(id: TileId): ByteArray? {
        if (!id.isValid()) return null
        return locked {
            val st = statement("SELECT tile_data FROM tiles WHERE zoom_level = ? AND tile_column = ? AND tile_row = ?")
            try {
                st.bindLong(1, id.z.toLong())
                st.bindLong(2, id.x.toLong())
                st.bindLong(3, tmsRow(id).toLong())
                if (st.step() && !st.isNull(0)) st.getBlob(0) else null
            } finally {
                st.reset()
            }
        }
    }

    fun contains(id: TileId): Boolean = locked {
        val st = statement("SELECT 1 FROM tiles WHERE zoom_level = ? AND tile_column = ? AND tile_row = ?")
        try {
            st.bindLong(1, id.z.toLong()); st.bindLong(2, id.x.toLong()); st.bindLong(3, tmsRow(id).toLong())
            st.step()
        } finally {
            st.reset()
        }
    }

    /** Stores [data] for [id] as given (gzip it first to save space), replacing any older tile. */
    fun putTile(id: TileId, data: ByteArray) = putTiles(listOf(id to data))

    /** Stores many tiles in one transaction. */
    fun putTiles(tiles: Collection<Pair<TileId, ByteArray>>) {
        checkWritable()
        if (tiles.isEmpty()) return
        transaction {
            val st = statement("INSERT OR REPLACE INTO tiles (zoom_level, tile_column, tile_row, tile_data) VALUES (?, ?, ?, ?)")
            for ((id, data) in tiles) {
                require(id.isValid()) { "no such tile $id" }
                try {
                    st.bindLong(1, id.z.toLong()); st.bindLong(2, id.x.toLong()); st.bindLong(3, tmsRow(id).toLong())
                    st.bindBlob(4, data)
                    st.step()
                } finally {
                    st.reset()
                }
            }
        }
    }

    fun deleteTile(id: TileId) {
        checkWritable()
        locked {
            val st = statement("DELETE FROM tiles WHERE zoom_level = ? AND tile_column = ? AND tile_row = ?")
            try {
                st.bindLong(1, id.z.toLong()); st.bindLong(2, id.x.toLong()); st.bindLong(3, tmsRow(id).toLong())
                st.step()
            } finally {
                st.reset()
            }
        }
    }

    fun putMetadata(key: String, value: String) = putMetadata(mapOf(key to value))

    fun putMetadata(values: Map<String, String>) {
        checkWritable()
        transaction {
            val del = statement("DELETE FROM metadata WHERE name = ?")
            val ins = statement("INSERT INTO metadata (name, value) VALUES (?, ?)")
            for ((k, v) in values) {
                try { del.bindText(1, k); del.step() } finally { del.reset() }
                try { ins.bindText(1, k); ins.bindText(2, v); ins.step() } finally { ins.reset() }
            }
            meta = readMetadata()
        }
    }

    /** Tile ids present at zoom [z], packed as `x shl 32 or y` (XYZ rows). */
    fun tileKeys(z: Int): LongArray = locked {
        val st = conn.prepare("SELECT tile_column, tile_row FROM tiles WHERE zoom_level = ?")
        try {
            st.bindLong(1, z.toLong())
            var out = LongArray(256)
            var n = 0
            val top = (1 shl z) - 1
            while (st.step()) {
                if (n == out.size) out = out.copyOf(n * 2)
                out[n++] = (st.getLong(0) shl 32) or (top - st.getLong(1))
            }
            out.copyOf(n).also { it.sort() }
        } finally {
            st.close()
        }
    }

    /** Number of tiles stored. */
    fun count(): Long = scalar("SELECT COUNT(*) FROM tiles")

    /** Bytes of tile data stored. */
    fun dataBytes(): Long = scalar("SELECT COALESCE(SUM(LENGTH(tile_data)), 0) FROM tiles")

    /** Runs [block] in one transaction (nested calls join the outer one). */
    fun <T> transaction(block: () -> T): T = locked {
        if (conn.inTransaction()) return@locked block()
        conn.execSQL("BEGIN IMMEDIATE")
        try {
            block().also { conn.execSQL("COMMIT") }
        } catch (e: Throwable) {
            runCatching { conn.execSQL("ROLLBACK") }
            throw e
        }
    }

    /**
     * Folds the write-ahead log into the file so it stands alone (for a finished offline pack, which may then be
     * opened read-only or copied), and optionally rebuilds it without free pages.
     */
    fun compact(vacuum: Boolean = false) {
        checkWritable()
        locked {
            conn.execSQL("PRAGMA wal_checkpoint(TRUNCATE)")
            conn.execSQL("PRAGMA journal_mode = DELETE")
            if (vacuum) conn.execSQL("VACUUM")
            conn.execSQL("ANALYZE")
        }
    }

    /** Runs [block] with the connection held: for tables of other Atlas classes kept in the same file. */
    internal fun <T> withConnection(block: (SQLiteConnection) -> T): T = locked { block(conn) }

    /** A prepared statement kept for reuse; call only with the lock held, and reset it after use. */
    internal fun statement(sql: String): SQLiteStatement = statements.getOrPut(sql) { conn.prepare(sql) }

    override fun close() {
        lock.withLock {
            if (closed) return
            closed = true
            statements.values.forEach { runCatching { it.close() } }
            statements.clear()
            conn.close()
        }
    }

    private inline fun <T> locked(block: () -> T): T = lock.withLock {
        check(!closed) { "${file.name} is closed" }
        block()
    }

    private fun scalar(sql: String): Long = locked {
        val st = conn.prepare(sql)
        try { if (st.step()) st.getLong(0) else 0L } finally { st.close() }
    }

    private fun tableType(name: String): String? {
        val st = conn.prepare("SELECT type FROM sqlite_master WHERE name = ?")
        try {
            st.bindText(1, name)
            return if (st.step()) st.getText(0) else null
        } finally {
            st.close()
        }
    }

    private fun readMetadata(): Map<String, String> {
        if (tableType("metadata") == null) return emptyMap()
        val st = conn.prepare("SELECT name, value FROM metadata")
        try {
            val m = LinkedHashMap<String, String>()
            while (st.step()) if (!st.isNull(0) && !st.isNull(1)) m[st.getText(0)] = st.getText(1)
            return m
        } finally {
            st.close()
        }
    }

    private fun checkWritable() = check(writable) { "${file.name} is open read-only" }

    companion object {
        internal val driver by lazy { BundledSQLiteDriver() }

        /** MBTiles count rows from the south (TMS). */
        fun tmsRow(id: TileId): Int = (1 shl id.z) - 1 - id.y

        /** Standard metadata for a vector tile pack; [vectorLayers] fill the `json` row MBTiles 1.3 asks of pbf files. */
        fun vectorMetadata(
            name: String,
            bbox: BBox,
            minZoom: Int,
            maxZoom: Int,
            attribution: String,
            gzipped: Boolean,
            vectorLayers: List<String> = emptyList(),
        ): Map<String, String> {
            val c = bbox.center
            return buildMap {
                put("name", name)
                put("format", "pbf")
                put("type", "baselayer")
                put("version", "1")
                put("minzoom", minZoom.toString())
                put("maxzoom", maxZoom.toString())
                put("bounds", String.format(java.util.Locale.ROOT, "%.6f,%.6f,%.6f,%.6f", bbox.west, bbox.south, bbox.east, bbox.north))
                put("center", String.format(java.util.Locale.ROOT, "%.6f,%.6f,%d", c.lon, c.lat, maxZoom))
                put("attribution", attribution)
                put("json", Json.write(mapOf("vector_layers" to vectorLayers.map { mapOf("id" to it, "fields" to emptyMap<String, String>()) })))
                if (gzipped) put("compression", "gzip")
            }
        }
    }
}

package app.atlas.tiles

import androidx.sqlite.execSQL
import app.atlas.geo.TileId

/** What Atlas knows about a stored tile beyond its bytes: where it came from, when, and when it was last drawn. */
data class TileMeta(
    val id: TileId,
    /** The server's ETag, for conditional requests. */
    val etag: String?,
    /** The data release the tile belongs to (OpenFreeMap's version path). */
    val version: String?,
    /** Epoch milliseconds of the download or the last confirmation that it is current. */
    val fetchedAt: Long,
    /** Epoch milliseconds of the last read, for least-recently-used eviction. */
    val lastAccess: Long,
    /** Stored size in bytes. */
    val bytes: Int,
) {
    fun age(now: Long): Long = now - fetchedAt
}

/**
 * The extra table `atlas_tile_meta` that cache and offline packs keep next to the standard MBTiles tables:
 * one row per tile, keyed like `tiles` (TMS rows).
 */
internal class TileMetaTable(private val db: MbTiles) {
    init {
        db.withConnection {
            it.execSQL(
                "CREATE TABLE IF NOT EXISTS atlas_tile_meta (zoom_level INTEGER NOT NULL, tile_column INTEGER NOT NULL, " +
                    "tile_row INTEGER NOT NULL, etag TEXT, version TEXT, fetched_at INTEGER NOT NULL, " +
                    "last_access INTEGER NOT NULL, bytes INTEGER NOT NULL, PRIMARY KEY (zoom_level, tile_column, tile_row))",
            )
            it.execSQL("CREATE INDEX IF NOT EXISTS atlas_tile_meta_access ON atlas_tile_meta (last_access)")
        }
    }

    fun get(id: TileId): TileMeta? = db.withConnection {
        val st = db.statement(
            "SELECT etag, version, fetched_at, last_access, bytes FROM atlas_tile_meta " +
                "WHERE zoom_level = ? AND tile_column = ? AND tile_row = ?",
        )
        try {
            bindId(st, id)
            if (!st.step()) null
            else TileMeta(
                id,
                if (st.isNull(0)) null else st.getText(0),
                if (st.isNull(1)) null else st.getText(1),
                st.getLong(2), st.getLong(3), st.getLong(4).toInt(),
            )
        } finally {
            st.reset()
        }
    }

    /** Writes [m]; call inside a transaction of [db] when writing many. */
    fun put(m: TileMeta) = db.withConnection {
        val st = db.statement(
            "INSERT OR REPLACE INTO atlas_tile_meta (zoom_level, tile_column, tile_row, etag, version, fetched_at, last_access, bytes) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
        )
        try {
            bindId(st, m.id)
            if (m.etag != null) st.bindText(4, m.etag) else st.bindNull(4)
            if (m.version != null) st.bindText(5, m.version) else st.bindNull(5)
            st.bindLong(6, m.fetchedAt)
            st.bindLong(7, m.lastAccess)
            st.bindLong(8, m.bytes.toLong())
            st.step()
        } finally {
            st.reset()
        }
    }

    fun touch(id: TileId, time: Long) = db.withConnection {
        val st = db.statement("UPDATE atlas_tile_meta SET last_access = ? WHERE zoom_level = ? AND tile_column = ? AND tile_row = ?")
        try {
            st.bindLong(1, time)
            st.bindLong(2, id.z.toLong()); st.bindLong(3, id.x.toLong()); st.bindLong(4, MbTiles.tmsRow(id).toLong())
            st.step()
        } finally {
            st.reset()
        }
    }

    fun delete(id: TileId) = db.withConnection {
        val st = db.statement("DELETE FROM atlas_tile_meta WHERE zoom_level = ? AND tile_column = ? AND tile_row = ?")
        try {
            bindId(st, id)
            st.step()
        } finally {
            st.reset()
        }
    }

    /** Up to [limit] tiles, least recently used first. */
    fun oldest(limit: Int): List<TileMeta> = db.withConnection {
        val st = it.prepare(
            "SELECT zoom_level, tile_column, tile_row, etag, version, fetched_at, last_access, bytes " +
                "FROM atlas_tile_meta ORDER BY last_access ASC LIMIT ?",
        )
        try {
            st.bindLong(1, limit.toLong())
            val out = ArrayList<TileMeta>()
            while (st.step()) {
                val z = st.getLong(0).toInt()
                val id = TileId(z, st.getLong(1).toInt(), (1 shl z) - 1 - st.getLong(2).toInt())
                out += TileMeta(
                    id,
                    if (st.isNull(3)) null else st.getText(3),
                    if (st.isNull(4)) null else st.getText(4),
                    st.getLong(5), st.getLong(6), st.getLong(7).toInt(),
                )
            }
            out
        } finally {
            st.close()
        }
    }

    fun totalBytes(): Long = db.withConnection {
        val st = it.prepare("SELECT COALESCE(SUM(bytes), 0) FROM atlas_tile_meta")
        try { if (st.step()) st.getLong(0) else 0L } finally { st.close() }
    }

    fun clear() = db.withConnection { it.execSQL("DELETE FROM atlas_tile_meta") }

    private fun bindId(st: androidx.sqlite.SQLiteStatement, id: TileId) {
        st.bindLong(1, id.z.toLong())
        st.bindLong(2, id.x.toLong())
        st.bindLong(3, MbTiles.tmsRow(id).toLong())
    }
}

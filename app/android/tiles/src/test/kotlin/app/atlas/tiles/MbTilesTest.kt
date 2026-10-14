package app.atlas.tiles

import androidx.sqlite.execSQL
import app.atlas.geo.BBox
import app.atlas.geo.TileId
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class MbTilesTest {
    private val dir = tempDir("mbtiles")

    @Test
    fun writesGzippedAndReadsRawBack() = runBlocking {
        val file = File(dir, "ams.mbtiles")
        MbTiles(file, writable = true).use { db ->
            db.putMetadata(MbTiles.vectorMetadata("Amsterdam", BBox(4.85, 52.36, 4.92, 52.39), 0, 14, OpenFreeMapSource.ATTRIBUTION, gzipped = true))
            db.putTiles(Fixtures.all.map { it to Gzip.compress(Fixtures.bytes(it)) })
            assertEquals(Fixtures.all.size.toLong(), db.count())
            assertTrue(db.dataBytes() < Fixtures.all.sumOf { Fixtures.file(it).length() })
            db.compact()
        }
        MbTiles(file).use { db ->
            assertFalse(db.writable)
            assertTrue(db.isGzipped)
            assertEquals(0, db.minZoom)
            assertEquals(14, db.maxZoom)
            assertEquals(OpenFreeMapSource.ATTRIBUTION, db.attribution)
            assertEquals(4.85, db.bounds.west, 1e-9)
            assertEquals("pbf", db.metadata("format"))
            for (id in Fixtures.all) {
                assertArrayEquals("$id", Fixtures.bytes(id), db.tile(id))
                assertTrue(Gzip.isGzip(db.readStored(id)!!))
                assertTrue(db.contains(id))
            }
            assertNull(db.tile(TileId(14, 0, 0)))
            assertNull(db.tile(TileId(3, 9, 9)))
            assertFalse(db.contains(TileId(14, 1, 1)))
            val keys = db.tileKeys(14)
            assertEquals(9, keys.size)
            assertEquals((8413L shl 32) or 5383L, keys.first())
            assertThrows(IllegalStateException::class.java) { db.putTile(TileId(0, 0, 0), ByteArray(1)) }
            Unit
        }
    }

    @Test
    fun rowsAreFlippedLikeTms() {
        val file = File(dir, "tms.mbtiles")
        MbTiles(file, writable = true).use { db ->
            db.putTile(TileId(14, 8414, 5384), byteArrayOf(1, 2, 3))
            val row = db.withConnection { c ->
                c.prepare("SELECT tile_row FROM tiles WHERE zoom_level = 14").use { if (it.step()) it.getLong(0) else -1 }
            }
            assertEquals((1L shl 14) - 1 - 5384, row)
            assertArrayEquals(byteArrayOf(1, 2, 3), db.readTile(TileId(14, 8414, 5384)))
            // Replacing keeps one row.
            db.putTile(TileId(14, 8414, 5384), byteArrayOf(4))
            assertEquals(1L, db.count())
            db.deleteTile(TileId(14, 8414, 5384))
            assertEquals(0L, db.count())
        }
    }

    @Test
    fun readsDeduplicatedFilesBehindAView() = runBlocking {
        val file = File(dir, "dedup.mbtiles")
        val conn = MbTiles.driver.open(file.path)
        conn.execSQL("CREATE TABLE metadata (name TEXT, value TEXT)")
        conn.execSQL("INSERT INTO metadata VALUES ('minzoom', '0'), ('maxzoom', '14'), ('format', 'pbf')")
        conn.execSQL("CREATE TABLE map (zoom_level INTEGER, tile_column INTEGER, tile_row INTEGER, tile_id TEXT)")
        conn.execSQL("CREATE TABLE images (tile_data BLOB, tile_id TEXT)")
        conn.execSQL("CREATE VIEW tiles AS SELECT map.zoom_level, map.tile_column, map.tile_row, images.tile_data FROM map JOIN images ON images.tile_id = map.tile_id")
        val raw = Fixtures.bytes(TileId(0, 0, 0))
        conn.prepare("INSERT INTO images VALUES (?, 'a')").use { it.bindBlob(1, Gzip.compress(raw)); it.step() }
        conn.execSQL("INSERT INTO map VALUES (0, 0, 0, 'a'), (1, 0, 1, 'a')")
        conn.close()
        MbTiles(file).use { db ->
            assertArrayEquals(raw, db.tile(TileId(0, 0, 0)))
            assertArrayEquals(raw, db.tile(TileId(1, 0, 0)))
        }
        assertThrows(UnsupportedOperationException::class.java) { MbTiles(file, writable = true) }
        // Refusing to write must not have changed the file: it is still in its own journal mode.
        val mode = MbTiles.driver.open(file.path).let { c ->
            try { c.prepare("PRAGMA journal_mode").use { it.step(); it.getText(0) } } finally { c.close() }
        }
        assertEquals("delete", mode)
        Unit
    }

    @Test
    fun missingOrForeignFilesFail() {
        assertThrows(java.io.FileNotFoundException::class.java) { MbTiles(File(dir, "nope.mbtiles")) }
        val other = File(dir, "other.db")
        MbTiles.driver.open(other.path).also { it.execSQL("CREATE TABLE x (a)") }.close()
        assertThrows(TileFormatException::class.java) { MbTiles(other) }
    }

    @Test
    fun transactionsRollBack() {
        MbTiles(File(dir, "tx.mbtiles"), writable = true).use { db ->
            runCatching {
                db.transaction {
                    db.putTile(TileId(1, 0, 0), byteArrayOf(1))
                    error("boom")
                }
            }
            assertEquals(0L, db.count())
        }
    }
}

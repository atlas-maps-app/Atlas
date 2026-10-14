package app.atlas.tiles

import app.atlas.geo.TileId
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class TileCacheTest {
    private val dir = tempDir("cache")
    private var now = 1_000_000_000_000L

    @Test
    fun storesGzippedAndServesRaw() = runBlocking {
        TileCache(File(dir, "a.mbtiles"), clock = { now }).use { cache ->
            val raw = Fixtures.bytes(Fixtures.CENTRE)
            cache.put(Fixtures.CENTRE, raw, etag = "\"e1\"", version = "v1")
            val e = cache.entry(Fixtures.CENTRE)!!
            assertTrue(Gzip.isGzip(e.stored))
            assertArrayEquals(raw, e.raw())
            assertEquals("\"e1\"", e.etag)
            assertEquals("v1", e.version)
            assertArrayEquals(raw, cache.tile(Fixtures.CENTRE))
            assertEquals(e.stored.size.toLong(), cache.sizeBytes)
            assertNull(cache.tile(TileId(14, 1, 1)))
            // Already gzipped input is kept as it is.
            val gz = Gzip.compress(Fixtures.bytes(TileId(0, 0, 0)))
            cache.put(TileId(0, 0, 0), gz)
            assertArrayEquals(gz, cache.entry(TileId(0, 0, 0))!!.stored)
        }
    }

    @Test
    fun freshnessByAgeAndRelease() {
        TileCache(File(dir, "b.mbtiles"), maxAgeMillis = 1000, clock = { now }).use { cache ->
            cache.put(Fixtures.CENTRE, byteArrayOf(1, 2), version = "v1")
            val e = cache.entry(Fixtures.CENTRE)!!
            assertTrue(cache.isFresh(e, "v1"))
            assertTrue(cache.isFresh(e))
            assertFalse(cache.isFresh(e, "v2"))
            now += 1500
            assertFalse(cache.isFresh(cache.entry(Fixtures.CENTRE)!!, "v1"))
            cache.markFresh(Fixtures.CENTRE, etag = "\"x\"", version = "v2")
            val again = cache.entry(Fixtures.CENTRE)!!
            assertTrue(cache.isFresh(again, "v2"))
            assertEquals("\"x\"", again.etag)
        }
    }

    @Test
    fun evictsLeastRecentlyUsedPastTheCap() {
        val tiles = Fixtures.z14
        val sizes = tiles.associateWith { Gzip.compress(Fixtures.bytes(it)).size.toLong() }
        val cap = sizes.values.sum() - 1 // One tile too many.
        TileCache(File(dir, "c.mbtiles"), maxBytes = cap, clock = { now }).use { cache ->
            for (id in tiles) { now += 1000; cache.put(id, Fixtures.bytes(id)) }
            // The first tile went in first and was never read: it is the first to go.
            assertNull(cache.entry(tiles[0]))
            assertTrue(cache.sizeBytes <= cap * 9 / 10 + 1)
            val kept = tiles.filter { cache.entry(it) != null }
            assertTrue(kept.isNotEmpty())
            assertTrue(kept.last() == tiles.last())
        }
    }

    @Test
    fun readsProtectTilesFromEviction() {
        val a = TileId(14, 8413, 5383)
        val b = TileId(14, 8413, 5384)
        val c = TileId(14, 8413, 5385)
        val sizeA = Gzip.compress(Fixtures.bytes(a)).size
        val sizeB = Gzip.compress(Fixtures.bytes(b)).size
        val sizeC = Gzip.compress(Fixtures.bytes(c)).size
        // Room for all three, but nine tenths of the cap holds only the two that matter.
        TileCache(File(dir, "d.mbtiles"), maxBytes = (sizeA + sizeB + sizeC).toLong() - 1, clock = { now }).use { cache ->
            now += 1; cache.put(a, Fixtures.bytes(a))
            now += 1; cache.put(b, Fixtures.bytes(b))
            now += 1; assertNotNull(cache.entry(a)) // a is now more recent than b
            cache.flush()
            now += 1; cache.put(c, Fixtures.bytes(c))
            assertNull("b was least recently used", cache.entry(b))
            assertNotNull(cache.entry(c))
        }
    }

    @Test
    fun concurrentTrimsKeepTheSizeTrue() {
        val file = File(dir, "race.mbtiles")
        val rnd = kotlin.random.Random(11)
        // Incompressible tiles of 6 KB: the cap of 120 KB is passed again and again, from eight threads at once.
        val blobs = List(64) { rnd.nextBytes(6_000) }
        val counted = TileCache(file, maxBytes = 120_000).use { cache ->
            val pool = java.util.concurrent.Executors.newFixedThreadPool(8)
            val jobs = (0 until 8).map { t ->
                pool.submit {
                    repeat(150) { i ->
                        val id = TileId(14, t * 1000 + i, 7)
                        cache.put(id, blobs[(t * 7 + i) % blobs.size])
                        if (i % 5 == 0) cache.remove(TileId(14, t * 1000 + i - 3, 7))
                    }
                }
            }
            jobs.forEach { it.get() }
            pool.shutdown()
            assertTrue(cache.sizeBytes <= 120_000)
            cache.sizeBytes
        }
        // Reopening counts the bytes afresh from the table: the running total must not have drifted.
        TileCache(file, maxBytes = 120_000).use { assertEquals(it.sizeBytes, counted) }
    }

    @Test
    fun survivesReopeningAndClears() {
        val file = File(dir, "e.mbtiles")
        TileCache(file, clock = { now }).use { cache ->
            cache.put(Fixtures.CENTRE, Fixtures.bytes(Fixtures.CENTRE), version = "v9")
            cache.note("probe", "hello")
        }
        TileCache(file, clock = { now }).use { cache ->
            assertTrue(cache.sizeBytes > 100_000)
            assertEquals(1L, cache.count())
            assertEquals("v9", cache.entry(Fixtures.CENTRE)!!.version)
            assertEquals("hello", cache.note("probe"))
            cache.remove(Fixtures.CENTRE)
            assertEquals(0L, cache.sizeBytes)
            cache.put(Fixtures.CENTRE, byteArrayOf(1))
            cache.clear()
            assertEquals(0L, cache.count())
            assertEquals(0L, cache.sizeBytes)
        }
        // A cache file is an ordinary MBTiles file.
        MbTiles(file).use { assertEquals("gzip", it.metadata("compression")) }
    }
}

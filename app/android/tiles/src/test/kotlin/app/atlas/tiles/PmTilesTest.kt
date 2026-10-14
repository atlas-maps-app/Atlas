package app.atlas.tiles

import app.atlas.geo.TileId
import kotlinx.coroutines.runBlocking
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okio.Buffer
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.random.Random

class PmTilesTest {
    private val dir = tempDir("pmtiles")

    /** The fixtures, plus a run of identical tiles (deduplicated, run-length encoded) and scattered copies. */
    private fun archiveTiles(): Map<TileId, ByteArray> {
        val m = LinkedHashMap<TileId, ByteArray>()
        for (id in Fixtures.all) m[id] = Fixtures.bytes(id)
        val sea = MvtBuilder().layer("water") { feature(3, Geom.rings(Geom.square(-64, -64, 4224))) }.build()
        // Sixteen consecutive Hilbert ids at z12: a run.
        val start = PmTiles.tileId(12, 2000, 1300)
        for (k in 0 until 16) m[PmTiles.tileOf(start + k)] = sea
        m[TileId(14, 0, 0)] = sea
        return m
    }

    @Test
    fun hilbertIdsMatchTheSpec() {
        assertEquals(0L, PmTiles.tileId(0, 0, 0))
        assertEquals(1L, PmTiles.tileId(1, 0, 0))
        assertEquals(2L, PmTiles.tileId(1, 0, 1))
        assertEquals(3L, PmTiles.tileId(1, 1, 1))
        assertEquals(4L, PmTiles.tileId(1, 1, 0))
        assertEquals(5L, PmTiles.tileId(2, 0, 0))
        assertEquals(19078479L, PmTiles.tileId(12, 3423, 1763))
        assertEquals(TileId(12, 3423, 1763), PmTiles.tileOf(19078479L))
        val rnd = Random(7)
        repeat(2000) {
            val z = rnd.nextInt(0, 27)
            val id = TileId(z, rnd.nextInt(1 shl z), rnd.nextInt(1 shl z))
            assertEquals(id, PmTiles.tileOf(PmTiles.tileId(id)))
        }
        // Ids are dense: every id at z3 maps to a distinct tile.
        val z3 = (0 until 64).map { PmTiles.tileOf(PmTiles.tileId(3, 0, 0) + it) }.toSet()
        assertEquals(64, z3.size)
        assertThrows(IllegalArgumentException::class.java) { PmTiles.tileId(2, 4, 0) }
    }

    @Test
    fun readsARootOnlyArchive() = runBlocking {
        val tiles = archiveTiles()
        val file = File(dir, "root.pmtiles")
        PmTilesWriter.write(file, tiles)
        PmTiles.open(file).use { pm ->
            assertEquals(3, pm.header.specVersion)
            assertEquals(0, pm.minZoom)
            assertEquals(14, pm.maxZoom)
            assertTrue(pm.isVector)
            assertEquals("test attribution", pm.attribution)
            assertEquals(4.8, pm.bounds.west, 1e-9)
            assertEquals(53.0, pm.bounds.north, 1e-9)
            assertEquals(tiles.size.toLong(), pm.header.addressedTiles)
            assertTrue(pm.header.tileEntries < tiles.size)
            for ((id, raw) in tiles) assertArrayEquals("$id", raw, pm.tile(id))
            assertNull(pm.tile(TileId(14, 1, 1)))
            assertNull(pm.tile(TileId(15, 0, 0)))
            assertEquals(0, pm.cachedDirectories)
        }
    }

    @Test
    fun readsLeafDirectories() = runBlocking {
        val tiles = archiveTiles()
        val file = File(dir, "leaves.pmtiles")
        PmTilesWriter.write(file, tiles, leafSize = 3)
        PmTiles.open(file).use { pm ->
            for ((id, raw) in tiles) assertArrayEquals("$id", raw, pm.tile(id))
            assertTrue(pm.cachedDirectories > 1)
            assertNull(pm.tile(TileId(13, 0, 0)))
            assertNull(pm.tile(TileId(12, 100, 100)))
        }
    }

    @Test
    fun uncompressedArchive() = runBlocking {
        val tiles = archiveTiles()
        val file = File(dir, "plain.pmtiles")
        PmTilesWriter.write(file, tiles, leafSize = 5, tileCompression = PmTiles.COMPRESSION_NONE, internalCompression = PmTiles.COMPRESSION_NONE)
        PmTiles.open(file).use { pm -> for ((id, raw) in tiles) assertArrayEquals(raw, pm.tile(id)) }
    }

    @Test
    fun brotliAndZstdFailClearly() = runBlocking {
        val tiles = mapOf(TileId(0, 0, 0) to Fixtures.bytes(TileId(0, 0, 0)))
        for (c in listOf(PmTiles.COMPRESSION_BROTLI, PmTiles.COMPRESSION_ZSTD)) {
            val file = File(dir, "c$c.pmtiles")
            PmTilesWriter.write(file, tiles, tileCompression = c)
            val e = runCatching { PmTiles.open(file) }.exceptionOrNull()
            assertTrue("$e", e is UnsupportedCompressionException)
        }
        val notPm = File(dir, "not.pmtiles").also { it.writeBytes(ByteArray(200)) }
        assertTrue(runCatching { PmTiles.open(notPm) }.exceptionOrNull() is TileFormatException)
    }

    @Test
    fun readsOverHttpRanges() = runBlocking {
        val tiles = archiveTiles()
        val file = File(dir, "remote.pmtiles")
        PmTilesWriter.write(file, tiles, leafSize = 4)
        val bytes = file.readBytes()
        val ranges = java.util.concurrent.atomic.AtomicInteger()
        MockWebServer().use { server ->
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    val m = Regex("bytes=(\\d+)-(\\d+)").matchEntire(request.headers["Range"] ?: "")
                        ?: return MockResponse.Builder().code(200).body(Buffer().write(bytes)).build()
                    ranges.incrementAndGet()
                    val a = m.groupValues[1].toInt()
                    val b = minOf(m.groupValues[2].toInt(), bytes.size - 1)
                    return MockResponse.Builder().code(206).setHeader("ETag", "\"v1\"")
                        .setHeader("Content-Range", "bytes $a-$b/${bytes.size}")
                        .body(Buffer().write(bytes, a, b - a + 1)).build()
                }
            }
            server.start()
            val pm = PmTiles.open(HttpRangeSource(server.url("/ams.pmtiles").toString()))
            assertEquals(14, pm.maxZoom)
            for ((id, raw) in tiles) assertArrayEquals(raw, pm.tile(id))
            val afterFirstPass = ranges.get()
            for ((id, raw) in tiles) assertArrayEquals(raw, pm.tile(id))
            // Directories are cached: the second pass only fetches tile bytes.
            assertEquals(afterFirstPass + tiles.size, ranges.get())
            val ua = server.takeRequest().headers["User-Agent"]
            assertEquals(Http.USER_AGENT, ua)
        }
    }

    @Test
    fun aRemoteArchiveChangingUnderTheReaderFails() = runBlocking {
        val etag = java.util.concurrent.atomic.AtomicReference("\"v1\"")
        MockWebServer().use { server ->
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse =
                    MockResponse.Builder().code(206).setHeader("ETag", etag.get()).body(Buffer().write(ByteArray(16))).build()
            }
            server.start()
            val src = HttpRangeSource(server.url("/a.pmtiles").toString())
            assertEquals(16, src.read(0, 16).size)
            etag.set("\"v2\"")
            val e = runCatching { src.read(16, 16) }.exceptionOrNull()
            assertTrue("$e", e is java.io.IOException && e.message!!.contains("changed"))
            assertEquals(0, src.read(5, 0).size)
        }
    }
}

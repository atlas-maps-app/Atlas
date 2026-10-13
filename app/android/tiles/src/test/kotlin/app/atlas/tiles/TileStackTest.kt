package app.atlas.tiles

import app.atlas.geo.BBox
import app.atlas.geo.TileId
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class TileStackTest {
    /** A source over a map of tiles that counts its reads and can be switched off. */
    private class MapSource(
        val tiles: Map<TileId, ByteArray>,
        override val maxZoom: Int = 14,
        override val bounds: BBox = BBox.WORLD,
        override val attribution: String = "map",
    ) : TileSource {
        override val minZoom = 0
        var reads = 0
        var down = false
        override suspend fun tile(id: TileId): ByteArray? {
            reads++
            if (down) throw IOException("offline")
            return tiles[id]
        }
    }

    private val a = Fixtures.CENTRE
    private val fixtures = Fixtures.all.associateWith { Fixtures.bytes(it) }

    @Test
    fun firstHitWins() = runBlocking {
        val pack = MapSource(mapOf(a to byteArrayOf(1)), attribution = "pack")
        val online = MapSource(fixtures, attribution = "online")
        val stack = TileStack(pack, online)
        assertArrayEquals(byteArrayOf(1), stack.tile(a))
        assertEquals(0, online.reads)
        assertArrayEquals(fixtures[TileId(13, 4207, 2692)], stack.tile(TileId(13, 4207, 2692)))
        assertEquals("pack · online", stack.attribution)
        val hit = stack.bestTile(TileId(13, 4207, 2692))!!
        assertEquals(1, hit.sourceIndex)
        assertTrue(hit.isExact)
        assertTrue(hit.isFinal)
    }

    @Test
    fun overzoomsPastTheLastZoom() = runBlocking {
        val stack = TileStack(MapSource(fixtures))
        val target = TileId(16, 8414 * 4 + 1, 5384 * 4 + 3)
        val hit = stack.bestTile(target)!!
        assertEquals(a, hit.source)
        assertEquals(target, hit.target)
        assertFalse(hit.isExact)
        assertTrue(hit.isFinal)
        assertEquals(2, hit.transform.dz)
        assertEquals(4.0, hit.transform.scale, 0.0)
        assertEquals(0.25, hit.transform.left, 0.0)
        assertEquals(0.75, hit.transform.top, 0.0)
        assertArrayEquals(doubleArrayOf(1024.0, 3072.0, 2048.0, 4096.0), hit.transform.clip(4096), 0.0)
        assertEquals(0.0, hit.transform.x(1024.0, 4096), 0.0)
        assertEquals(4096.0, hit.transform.y(4096.0, 4096), 0.0)
        assertTrue(hit.decode(setOf("building"))["building"]!!.size > 0)
    }

    @Test
    fun fallsBackToTheNearestAncestor() = runBlocking {
        val src = MapSource(fixtures)
        val stack = TileStack(src)
        // z14 tiles next to the fixture block are missing; their z13 parent is not.
        val missing = TileId(14, 8414, 5386)
        assertNull(stack.tile(missing))
        val hit = stack.bestTile(missing)!!
        assertEquals(TileId(13, 4207, 2693).let { if (fixtures.containsKey(it)) it else TileId(12, 2103, 1346) }, hit.source)
        assertTrue(hit.isFinal)
        // Nothing within reach: no tile.
        assertNull(TileStack(listOf(src), maxFallback = 1).bestTile(TileId(14, 100, 100)))
    }

    @Test
    fun failuresMakeAHitProvisional() = runBlocking {
        val pack = MapSource(mapOf(TileId(12, 2103, 1346) to fixtures[TileId(12, 2103, 1346)]!!))
        val online = MapSource(fixtures).also { it.down = true }
        val stack = TileStack(pack, online)
        val hit = stack.bestTile(a)!!
        assertEquals(TileId(12, 2103, 1346), hit.source)
        assertFalse(hit.isFinal)
        // The failed source is asked once per lookup, not once per zoom.
        assertEquals(1, online.reads)
        online.down = false
        val better = stack.bestTile(a)!!
        assertEquals(a, better.source)
        assertTrue(better.isFinal)
    }

    @Test
    fun aBrokenSourceDoesNotBreakTheMap() = runBlocking {
        // A pack closed (or corrupt) under the stack throws something other than an IOException.
        val broken = object : TileSource {
            override val minZoom = 0
            override val maxZoom = 14
            override val attribution = ""
            override suspend fun tile(id: TileId): ByteArray? = throw IllegalStateException("pack.mbtiles is closed")
        }
        val stack = TileStack(broken, MapSource(fixtures))
        val hit = stack.bestTile(a)!!
        assertEquals(1, hit.sourceIndex)
        assertFalse(hit.isFinal)
        assertArrayEquals(fixtures[a], stack.tile(a))
    }

    @Test
    fun cancellationIsNotMistakenForAFailure() = runBlocking {
        val cancelled = object : TileSource {
            override val minZoom = 0
            override val maxZoom = 14
            override val attribution = ""
            override suspend fun tile(id: TileId): ByteArray? = throw kotlinx.coroutines.CancellationException("view closed")
        }
        val other = MapSource(fixtures)
        val e = runCatching { TileStack(cancelled, other).bestTile(a) }.exceptionOrNull()
        assertTrue("$e", e is kotlinx.coroutines.CancellationException)
        assertEquals(0, other.reads)
    }

    @Test
    fun remembersTilesAndMisses() = runBlocking {
        val src = MapSource(fixtures)
        val stack = TileStack(src)
        repeat(16) { i -> stack.bestTile(TileId(16, 8414 * 4 + i % 4, 5384 * 4 + i / 4)) }
        assertEquals(1, src.reads)
        stack.tile(TileId(14, 3, 3))
        stack.tile(TileId(14, 3, 3))
        assertEquals(2, src.reads)
        stack.clearMemory()
        stack.tile(a)
        assertEquals(3, src.reads)
    }

    @Test
    fun memoryIsBounded() = runBlocking {
        val src = MapSource(fixtures)
        val stack = TileStack(listOf(src), memoryBytes = 1_000_000)
        for (id in Fixtures.z14) stack.tile(id)
        val before = src.reads
        for (id in Fixtures.z14) stack.tile(id)
        assertTrue(src.reads > before)
    }

    @Test
    fun respectsSourceZoomsAndBounds() = runBlocking {
        val elsewhere = MapSource(fixtures, bounds = BBox(10.0, 10.0, 11.0, 11.0))
        val low = MapSource(fixtures, maxZoom = 10)
        val stack = TileStack(elsewhere, low)
        val hit = stack.bestTile(a)!!
        assertEquals(0, elsewhere.reads)
        assertEquals(1, hit.sourceIndex)
        assertEquals(TileId(10, 525, 336), hit.source)
        assertEquals(14, stack.maxZoom)
    }
}

package app.atlas.tiles

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.time.Instant

class WorldIndexTest {
    private val date = Instant.parse("2026-10-31T21:20:00Z")

    private fun hash(seed: Int) = ByteArray(WorldIndex.HASH_BYTES) { (seed * 31 + it).toByte() }

    private fun fine(x: Int, y: Int, offset: Long, length: Long) =
        WorldIndex.Entry(WorldIndex.FINE_BLOCK, x, y, 9, 11, 0, offset, length, 40, intArrayOf(486_000_000, 33_750_000, 524_000_000, 39_375_000), hash(x + y))

    private fun coarse(x: Int, y: Int) =
        WorldIndex.Entry(WorldIndex.COARSE_BLOCK, x, y, 6, 8, 1, 0, 1_000, 21, intArrayOf(486_000_000, 0, 558_000_000, 112_500_000), hash(-x - y))

    private fun bytes(index: WorldIndex) = ByteArrayOutputStream().also { index.write(it) }.toByteArray()

    private val index = WorldIndex(
        date,
        listOf(WorldIndex.Part("world-mid.000", 3_000), WorldIndex.Part("world-coarse.000", 1_000)),
        // out of order, as the packs are written: the index sorts them by block level, x and y
        listOf(fine(33, 21, 2_000, 1_000), coarse(4, 2), fine(32, 21, 0, 1_200), fine(32, 20, 1_200, 800)),
    )

    @Test
    fun theIndexReadsBackAsWrittenItsPacksInOrder() {
        val b = bytes(index)
        assertEquals("ATWX", String(b, 0, 4, Charsets.US_ASCII))
        assertEquals(4 + 2 + 2 + 8 + 2 + (1 + 13 + 8) + (1 + 16 + 8) + 4 + 4 * WorldIndex.ENTRY_BYTES, b.size)
        val back = WorldIndex.read(b)
        assertEquals(date, back.date)
        assertEquals(listOf("world-mid.000" to 3_000L, "world-coarse.000" to 1_000L), back.parts.map { it.name to it.bytes })
        assertEquals(listOf(Triple(3, 4, 2), Triple(6, 32, 20), Triple(6, 32, 21), Triple(6, 33, 21)), back.entries.map { Triple(it.z, it.x, it.y) })
        for ((a, e) in index.entries.zip(back.entries)) {
            assertEquals(listOf(a.firstLevel, a.lastLevel, a.part, a.tiles), listOf(e.firstLevel, e.lastLevel, e.part, e.tiles))
            assertEquals(a.offset to a.length, e.offset to e.length)
            assertArrayEquals(a.box, e.box)
            assertArrayEquals(a.sha256, e.sha256)
        }
        assertArrayEquals(b, bytes(back))
    }

    @Test
    fun aTileIsHeldByItsL6BlocksFinePackOrItsL3BlocksCoarsePack() {
        val back = WorldIndex.read(bytes(index))
        // Amsterdam's tiles: L9 262/168, L10 525/336, L11 1050/673 lie in L6 32/21; L6 to L8 in L3 4/2
        for ((z, x, y) in listOf(Triple(9, 262, 168), Triple(10, 525, 336), Triple(11, 1050, 673))) {
            val e = back.holding(z, x, y)!!
            assertEquals(Triple(6, 32, 21), Triple(e.z, e.x, e.y))
            assertEquals(0L, e.offset)
        }
        for ((z, x, y) in listOf(Triple(6, 32, 21), Triple(7, 65, 42), Triple(8, 131, 84))) {
            assertEquals(Triple(3, 4, 2), back.holding(z, x, y)!!.let { Triple(it.z, it.x, it.y) })
        }
        assertSame(back.find(6, 32, 20), back.holding(11, 1050, 670))
        assertNull(back.holding(9, 300, 168)) // a block not built
        assertNull(back.holding(12, 2100, 1346)) // a level no world pack holds: the countries' and the windows'
        assertNull(back.holding(5, 16, 10)) // the globe's
    }

    @Test
    fun anIndexCutShortOfAnotherKindOrALaterVersionOrWithAPackTwiceIsRefused() {
        val b = bytes(index)
        assertThrows(IllegalArgumentException::class.java) { WorldIndex.read(b.copyOf(b.size - 1)) }
        assertThrows(IllegalArgumentException::class.java) { WorldIndex.read(b + byteArrayOf(0)) }
        assertThrows(IllegalArgumentException::class.java) { WorldIndex.read(b.copyOf().also { it[0] = 'B'.code.toByte() }) }
        assertThrows(IllegalArgumentException::class.java) { WorldIndex.read(b.copyOf().also { it[4] = 2 }) }
        assertThrows(IllegalArgumentException::class.java) { WorldIndex(date, index.parts, listOf(coarse(4, 2), coarse(4, 2))) }
        assertThrows(IllegalArgumentException::class.java) { WorldIndex(date, index.parts.take(1), listOf(coarse(4, 2))) }
    }
}

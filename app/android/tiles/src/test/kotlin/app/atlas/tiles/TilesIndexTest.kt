package app.atlas.tiles

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.time.Instant

class TilesIndexTest {
    private val date = Instant.parse("2026-10-26T20:21:24Z")
    private val version = "core-p8 s1.1-0123456789abcdef"
    private val style = "{the country's style}".toByteArray()

    private fun hash(seed: Int) = ByteArray(32) { (seed * 31 + it).toByte() }

    private fun entry(x: Int, y: Int, offset: Long, length: Int, z: Int = 14) =
        TilesIndex.Entry(x, y, offset, length, if (length == 0) ByteArray(32) else hash(x xor y), z)

    private fun bytes(index: TilesIndex) = ByteArrayOutputStream().also { index.write(it) }.toByteArray()

    @Test
    fun theIndexReadsBackAsWrittenWithItsWindowsInOrder() {
        // given out of order, as a build's workers finish them; the sea's window with nothing to draw
        val index = TilesIndex.of(
            14, date, 1_000L, version, style,
            listOf(entry(8414, 5384, 600, 400), entry(8413, 5385, 200, 400), entry(8413, 5384, 0, 200), entry(8400, 5300, 0, 0)),
        )
        val b = bytes(index)
        assertEquals(TilesIndex.HEAD_BYTES + version.length + TilesIndex.STYLE_BYTES + style.size + 4 * TilesIndex.ENTRY_BYTES, b.size)
        assertEquals("ATTI", String(b, 0, 4, Charsets.US_ASCII))
        val back = TilesIndex.read(b)
        assertEquals(14, back.level)
        assertEquals(date, back.date)
        assertEquals(1_000L, back.tilesBytes)
        assertEquals(version, back.version)
        assertArrayEquals(style, back.style)
        assertEquals(
            listOf(8400 to 5300, 8413 to 5384, 8413 to 5385, 8414 to 5384),
            (0 until back.size).map { back[it].x to back[it].y },
        )
        val e = back.find(8413, 5385)!!
        assertEquals(200L, e.offset)
        assertEquals(400, e.length)
        assertTrue(e.drawn)
        assertArrayEquals(hash(8413 xor 5385), e.sha256)
        assertFalse(back.find(8400, 5300)!!.drawn)
        assertNull(back.find(8413, 5386))
        assertArrayEquals(b, bytes(back))
    }

    @Test
    fun anIndexThatDoesNotHoldIsRefused() {
        val good = bytes(TilesIndex.of(14, date, 1_000L, version, style, listOf(entry(1, 1, 0, 10), entry(1, 2, 10, 10))))
        val first = TilesIndex.HEAD_BYTES + version.length + TilesIndex.STYLE_BYTES + style.size
        fun refused(edit: (ByteBuffer) -> Unit, size: Int = good.size) {
            val b = good.copyOf(size)
            edit(ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN))
            assertThrows(IllegalArgumentException::class.java) { TilesIndex.read(b) }
        }
        refused({ it.put(0, 'X'.code.toByte()) })
        // another format's: version 1 had no style, version 2 no levels
        refused({ it.putShort(4, 1) })
        refused({ it.putShort(4, 2) })
        // a style longer than the index holds
        refused({ it.putInt(TilesIndex.HEAD_BYTES + version.length, style.size + 1) })
        // cut short, or a window more than it counts
        refused({}, good.size - 1)
        refused({ it.putInt(8, 3) })
        // out of order, or twice
        refused({ it.putInt(first + TilesIndex.ENTRY_BYTES + 5, 1) })
        // beyond the file
        refused({ it.putLong(20, 15) })
        // beyond the level, or a level finer than the windows'
        refused({ it.putInt(first + TilesIndex.ENTRY_BYTES + 1, 1 shl 14) })
        refused({ it.put(first + TilesIndex.ENTRY_BYTES, 15) })
        // no version, or windows to draw without the country's style; the sea alone needs none
        assertThrows(IllegalArgumentException::class.java) { TilesIndex.of(14, date, 0L, "", style, emptyList()) }
        assertThrows(IllegalArgumentException::class.java) { TilesIndex.of(14, date, 10L, version, ByteArray(0), listOf(entry(1, 1, 0, 10))) }
        assertEquals(1, TilesIndex.of(14, date, 0L, version, ByteArray(0), listOf(entry(1, 1, 0, 0))).size)
        assertTrue(TilesIndex.read(good).size == 2)
    }

    @Test
    fun aFrameCellsLevelsAreListedBesideItsWindowsEachFoundByItsLevel() {
        // ENGINE.md one pack per country, step 7: the cell's L13 and L12 tiles after its windows, given in any order
        val index = TilesIndex.of(
            14, date, 2_000L, version, style,
            listOf(
                entry(2103, 1346, 1_600, 400, z = 12), entry(4206, 2692, 1_000, 600, z = 13), entry(8413, 5385, 200, 400),
                entry(8413, 5384, 0, 200), entry(4206, 2693, 0, 0, z = 13),
            ),
        )
        val b = bytes(index)
        assertEquals(TilesIndex.HEAD_BYTES + version.length + TilesIndex.STYLE_BYTES + style.size + 5 * TilesIndex.ENTRY_BYTES, b.size)
        val back = TilesIndex.read(b)
        // the windows first, then each coarser level, each by x, then y
        assertEquals(
            listOf(Triple(14, 8413, 5384), Triple(14, 8413, 5385), Triple(13, 4206, 2692), Triple(13, 4206, 2693), Triple(12, 2103, 1346)),
            (0 until back.size).map { Triple(back[it].z, back[it].x, back[it].y) },
        )
        // a window by its place, a level's tile by its level
        assertEquals(200L, back.find(8413, 5385)!!.offset)
        assertEquals(1_000L, back.find(13, 4206, 2692)!!.offset)
        assertEquals(1_600L, back.find(12, 2103, 1346)!!.offset)
        assertFalse(back.find(13, 4206, 2693)!!.drawn)
        // the same column and row at another level is another tile
        assertNull(back.find(12, 4206, 2692))
        assertNull(back.find(4206, 2692))
        assertArrayEquals(b, bytes(back))
        // a level's tile beyond its level
        assertThrows(IllegalArgumentException::class.java) {
            TilesIndex.of(14, date, 10L, version, style, listOf(entry(1 shl 13, 1, 0, 10, z = 13)))
        }
    }
}

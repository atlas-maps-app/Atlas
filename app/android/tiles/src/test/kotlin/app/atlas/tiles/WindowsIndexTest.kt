package app.atlas.tiles

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.time.Instant

class WindowsIndexTest {
    private val date = Instant.parse("2026-10-26T20:21:24Z")

    private fun hash(seed: Int) = ByteArray(32) { (seed * 31 + it).toByte() }

    private fun entry(x: Int, y: Int, offset: Long, length: Int) = WindowsIndex.Entry(x, y, offset, length, hash(x xor y))

    private fun bytes(index: WindowsIndex) = ByteArrayOutputStream().also { index.write(it) }.toByteArray()

    @Test
    fun theIndexReadsBackAsWrittenWithItsWindowsInOrder() {
        // given out of order, as a build's workers finish them
        val index = WindowsIndex.of(
            14, WindowsIndex.QUERY, date, 1_000L,
            listOf(entry(8414, 5384, 600, 400), entry(8413, 5385, 200, 400), entry(8413, 5384, 0, 200)),
        )
        val b = bytes(index)
        assertEquals(WindowsIndex.HEAD_BYTES + 3 * WindowsIndex.ENTRY_BYTES, b.size)
        assertEquals("ATWI", String(b, 0, 4, Charsets.US_ASCII))
        val back = WindowsIndex.read(b)
        assertEquals(14, back.level)
        assertEquals(WindowsIndex.QUERY, back.query)
        assertEquals(date, back.date)
        assertEquals(1_000L, back.dataBytes)
        assertEquals(listOf(8413 to 5384, 8413 to 5385, 8414 to 5384), (0 until back.size).map { back[it].x to back[it].y })
        val e = back.find(8413, 5385)!!
        assertEquals(200L, e.offset)
        assertEquals(400, e.length)
        assertArrayEquals(hash(8413 xor 5385), e.sha256)
        assertNull(back.find(8413, 5386))
        assertNull(back.find(0, 0))
        assertArrayEquals(b, bytes(back))
    }

    @Test
    fun anEmptyRegionHasAnIndexToo() {
        val back = WindowsIndex.read(bytes(WindowsIndex.of(14, WindowsIndex.QUERY, date, 0L, emptyList())))
        assertEquals(0, back.size)
        assertNull(back.find(1, 1))
    }

    @Test
    fun anIndexThatDoesNotHoldIsRefused() {
        val good = bytes(WindowsIndex.of(14, WindowsIndex.QUERY, date, 1_000L, listOf(entry(1, 1, 0, 10), entry(1, 2, 10, 10))))
        fun refused(edit: (ByteBuffer) -> Unit, size: Int = good.size) {
            val b = good.copyOf(size)
            edit(ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN))
            assertThrows(IllegalArgumentException::class.java) { WindowsIndex.read(b) }
        }
        refused({ it.put(0, 'X'.code.toByte()) })
        // a later format's
        refused({ it.putShort(4, 2) })
        // cut short, or a window more than it counts
        refused({}, good.size - 1)
        refused({ it.putInt(8, 3) })
        // out of order, or twice
        refused({ it.putInt(WindowsIndex.HEAD_BYTES + WindowsIndex.ENTRY_BYTES + 4, 1) })
        // beyond the file, or empty
        refused({ it.putLong(24, 15) })
        refused({ it.putInt(WindowsIndex.HEAD_BYTES + 16, 0) })
        // beyond the level
        refused({ it.putInt(WindowsIndex.HEAD_BYTES + WindowsIndex.ENTRY_BYTES, 1 shl 14) })
        assertTrue(WindowsIndex.read(good).size == 2)
    }

    @Test
    fun theListNamesARegionsWindowsDataAndTheirIndex() {
        val base = "https://github.com/atlas-maps-app/Atlas/releases/download/packs-20261102"
        val list = PackCatalog.parse(
            """
            {"packs": [
              {"id": "netherlands", "bbox": [2.9, 50.7, 7.2, 54.0], "osm_timestamp": "2026-11-01T20:21:24Z", "files": [
                {"kind": "windows", "name": "netherlands.windows", "bytes": 3000, "sha256": "aa", "parts": [
                  {"url": "$base/netherlands.windows.000", "bytes": 2000},
                  {"url": "$base/netherlands.windows.001", "bytes": 1000}]},
                {"kind": "windows-index", "name": "netherlands.windows.idx", "bytes": 84, "sha256": "bb",
                 "url": "$base/netherlands.windows.idx"}]},
              {"id": "half", "bbox": [5.7, 49.4, 6.5, 50.2], "files": [
                {"kind": "windows", "name": "half.windows", "bytes": 10, "url": "$base/half.windows"}]}
            ]}
            """.trimIndent(),
        )
        val nl = list.packs[0]
        assertEquals(listOf(2000L, 1000L), nl.windows!!.parts.map { it.bytes })
        assertEquals("$base/netherlands.windows.idx", nl.windowsIndex!!.url)
        assertEquals("bb", nl.windowsIndex!!.sha256)
        // data without its index is of no use
        assertNull(list.packs[1].windows)
    }
}

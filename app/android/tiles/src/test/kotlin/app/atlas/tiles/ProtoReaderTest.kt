package app.atlas.tiles

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ProtoReaderTest {
    @Test
    fun varintsOfEverySize() {
        val values = longArrayOf(0, 1, 127, 128, 300, 16_383, 16_384, Int.MAX_VALUE.toLong(), 1L shl 35, Long.MAX_VALUE, -1, Long.MIN_VALUE)
        val w = ProtoWriter()
        for (v in values) w.varint(v)
        val r = ProtoReader(w.toByteArray())
        for (v in values) assertEquals(v, r.varint())
        assertFalse(r.hasMore)
    }

    @Test
    fun negativeInt32IsTenBytesAndReadsBack() {
        val b = ProtoWriter().varint(-5L).toByteArray()
        assertEquals(10, b.size)
        assertEquals(-5, ProtoReader(b).varint32())
    }

    @Test
    fun zigzagBothWays() {
        assertEquals(0, ProtoReader.zigzag(0))
        assertEquals(-1, ProtoReader.zigzag(1))
        assertEquals(1, ProtoReader.zigzag(2))
        assertEquals(-2, ProtoReader.zigzag(3))
        assertEquals(Int.MAX_VALUE, ProtoReader.zigzag(-2))
        assertEquals(Int.MIN_VALUE, ProtoReader.zigzag(-1))
        assertEquals(-123_456_789_012L, ProtoReader.zigzag((-123_456_789_012L shl 1) xor (-123_456_789_012L shr 63)))
        val b = ProtoWriter().sint(1, -42).sint(2, 42).toByteArray()
        val r = ProtoReader(b)
        assertTrue(r.next()); assertEquals(-42, r.sint32())
        assertTrue(r.next()); assertEquals(42L, r.sint64())
    }

    @Test
    fun fixedFloatsAndDoubles() {
        val b = ProtoWriter().fixed32(1, -7).fixed64(2, 0x0102030405060708L).float(3, 1.5f).double(4, -2.25).toByteArray()
        val r = ProtoReader(b)
        r.next(); assertEquals(ProtoReader.WIRE_FIXED32, r.wireType); assertEquals(-7, r.fixed32())
        r.next(); assertEquals(ProtoReader.WIRE_FIXED64, r.wireType); assertEquals(0x0102030405060708L, r.fixed64())
        r.next(); assertEquals(1.5f, r.float())
        r.next(); assertEquals(-2.25, r.double(), 0.0)
        assertFalse(r.next())
    }

    @Test
    fun nestedMessagesWithLimits() {
        val inner = ProtoWriter().string(1, "naam").uint(2, 7)
        val b = ProtoWriter().message(5, inner).uint(6, 99).toByteArray()
        val r = ProtoReader(b)
        assertTrue(r.next())
        assertEquals(5, r.field)
        val outer = r.pushLimit(r.length())
        r.next(); assertEquals("naam", r.string())
        r.next(); assertEquals(7, r.varint32())
        assertFalse(r.next())
        r.popLimit(outer)
        assertTrue(r.next())
        assertEquals(6, r.field)
        assertEquals(99, r.varint32())
    }

    @Test
    fun skipsEveryWireType() {
        val b = ProtoWriter().uint(1, 300).fixed64(2, 5).bytes(3, ByteArray(10)).fixed32(4, 1).uint(9, 1).toByteArray()
        val r = ProtoReader(b)
        repeat(4) { assertTrue(r.next()); r.skip() }
        assertTrue(r.next())
        assertEquals(9, r.field)
    }

    @Test
    fun packedFields() {
        val values = intArrayOf(0, 1, 2, 300, 70_000, Int.MAX_VALUE)
        val b = ProtoWriter().packed(4, values).toByteArray()
        val r = ProtoReader(b)
        r.next()
        assertArrayEquals(values, r.packedUInt32())
        val r2 = ProtoReader(b)
        r2.next()
        val got = ArrayList<Long>()
        r2.packedVarints { got += it }
        assertEquals(values.map { it.toLong() }, got)
    }

    @Test
    fun malformedInputFailsClearly() {
        assertThrows(TileFormatException::class.java) { ProtoReader(byteArrayOf(0x80.toByte())).varint() }
        assertThrows(TileFormatException::class.java) {
            val r = ProtoReader(ProtoWriter().tag(1, 2).varint(50).toByteArray())
            r.next(); r.string()
        }
        assertThrows(TileFormatException::class.java) { ProtoReader(byteArrayOf(0x0b)).also { it.next() }.skip() } // wire type 3
        assertThrows(TileFormatException::class.java) { ProtoReader(byteArrayOf(0x00)).next() } // field 0
        assertThrows(TileFormatException::class.java) { ProtoReader(ByteArray(11) { 0xff.toByte() }).varint() }
    }
}

package app.atlas.tiles

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.zip.GZIPOutputStream

class GzipJsonTest {
    @Test
    fun roundTripsARealTile() {
        val raw = Fixtures.bytes(Fixtures.CENTRE)
        val gz = Gzip.compress(raw)
        assertTrue(Gzip.isGzip(gz))
        assertFalse(Gzip.isGzip(raw))
        assertTrue(gz.size < raw.size * 0.7)
        assertArrayEquals(raw, Gzip.decompress(gz))
        assertArrayEquals(raw, Gzip.unwrap(gz))
        assertSame(raw, Gzip.unwrap(raw))
        assertSame(gz, Gzip.wrap(gz))
    }

    @Test
    fun readsJavaStreamsWithHeaderFieldsAndConcatenatedMembers() {
        val a = "first member ".repeat(50).toByteArray()
        val b = "second".toByteArray()
        val out = ByteArrayOutputStream()
        GZIPOutputStream(out).use { it.write(a) }
        // A member with FNAME and FCOMMENT set, written by hand around a raw deflate stream.
        val deflated = ByteArrayOutputStream().also { o -> java.util.zip.DeflaterOutputStream(o, java.util.zip.Deflater(9, true)).use { it.write(b) } }.toByteArray()
        val crc = java.util.zip.CRC32().also { it.update(b) }.value.toInt()
        out.write(byteArrayOf(0x1f, 0x8b.toByte(), 8, (8 or 16).toByte(), 0, 0, 0, 0, 0, 3))
        out.write("name.pbf\u0000a comment\u0000".toByteArray())
        out.write(deflated)
        out.write(byteArrayOf(crc.toByte(), (crc shr 8).toByte(), (crc shr 16).toByte(), (crc shr 24).toByte()))
        out.write(byteArrayOf(b.size.toByte(), 0, 0, 0))
        assertArrayEquals(a + b, Gzip.decompress(out.toByteArray()))
    }

    @Test
    fun corruptDataFails() {
        val gz = Gzip.compress("hello tiles".repeat(20).toByteArray())
        val badCrc = gz.copyOf().also { it[it.size - 8] = (it[it.size - 8] + 1).toByte() }
        assertThrows(TileFormatException::class.java) { Gzip.decompress(badCrc) }
        assertThrows(TileFormatException::class.java) { Gzip.decompress(gz.copyOf(gz.size - 12)) }
        assertThrows(TileFormatException::class.java) { Gzip.decompress("not gzip at all, really".toByteArray()) }
    }

    @Test
    fun emptyInput() {
        assertArrayEquals(ByteArray(0), Gzip.decompress(Gzip.compress(ByteArray(0))))
    }

    @Test
    fun jsonReadsAndWrites() {
        val text = """{"a":1,"b":-2.5e3,"c":"q\"uo\\te\né","d":[true,false,null,{"e":[]}],"f":{},"g":12345678901234}"""
        val m = Json.parseObject(text)
        assertEquals(1L, m["a"])
        assertEquals(-2500.0, m["b"])
        assertEquals("q\"uo\\te\né", m["c"])
        assertEquals(listOf(true, false, null, mapOf("e" to emptyList<Any>())), m["d"])
        assertEquals(emptyMap<String, Any>(), m["f"])
        assertEquals(12345678901234L, m["g"])
        assertEquals(m, Json.parseObject(Json.write(m)))
        assertEquals("""{"x":[1,2.5,"\u0001"],"y":null}""", Json.write(linkedMapOf("x" to listOf(1, 2.5, "\u0001"), "y" to null)))
        assertEquals("3.0", Json.write(3.0))
        assertEquals("[4.85,52]", Json.write(listOf(4.85, 52L)))
        assertNull(Json.parse(" null "))
    }

    @Test
    fun jsonRejectsBrokenText() {
        for (bad in listOf("{", "[1,]", "{\"a\" 1}", "tru", "\"open", "{} x", "[1 2]")) {
            assertThrows(bad, TileFormatException::class.java) { Json.parse(bad) }
        }
    }
}

class GzipLargeInputTest {
    @Test
    fun inflatesInputsOfSeveralMegabytes() {
        // Incompressible data: the gzip stream is as big as the data, past the size where 1032 × input overflows an Int.
        val raw = kotlin.random.Random(3).nextBytes(2_300_000)
        val gz = Gzip.compress(raw, level = 1)
        assertTrue(gz.size > 2_100_000)
        assertArrayEquals(raw, Gzip.decompress(gz))
    }

    @Test
    fun refusesToInflatePastTheCap() {
        val bomb = Gzip.compress(ByteArray(4_000_000))
        assertTrue(bomb.size < 10_000)
        assertThrows(TileFormatException::class.java) { Gzip.decompress(bomb, maxSize = 1_000_000) }
        assertEquals(4_000_000, Gzip.decompress(bomb).size)
    }
}

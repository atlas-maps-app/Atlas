package app.atlas.tiles

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class JsonTest {
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

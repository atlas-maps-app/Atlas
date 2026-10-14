package app.atlas.providers

import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException
import java.io.StringReader

class JsonStreamTest {
    private fun js(s: String) = JsonStream(StringReader(s))

    @Test
    fun readsNestedValuesEscapesAndNumbers() {
        val j = js("""﻿ { "a": [1, -2.5e3, true, null, "x\"y\\zé\n"], "b": {"c": {}} , "d": [] } """)
        j.beginObject()
        assertEquals("a", j.nextName())
        j.beginArray()
        assertEquals(1L, j.nextLong())
        assertEquals(-2500.0, j.nextDouble(), 0.0)
        assertTrue(j.nextBoolean())
        j.nextNull()
        assertEquals("x\"y\\zé\n", j.nextString())
        j.endArray()
        assertEquals("b", j.nextName())
        j.skipValue()
        assertEquals("d", j.nextName())
        j.beginArray()
        j.endArray()
        j.endObject()
        assertEquals(JsonStream.Token.END, j.peek())
    }

    @Test
    fun readTreeAndStringMap() {
        val tree = js("""{"n": 3, "s": "t", "l": [1, 2.5], "o": {"k": null}}""").readTree().asObject()!!
        assertEquals(3, tree.int("n"))
        assertEquals(2.5, tree.arr("l")[1].asDouble()!!, 0.0)
        val tags = js("""{"name": "Café", "level": 2, "x": null, "nested": {"skip": 1}}""").readStringMap()
        assertEquals(mapOf("name" to "Café", "level" to "2"), tags)
        assertEquals(JsonPrimitive("t"), tree["s"])
    }

    @Test
    fun numbersAsStringsAreAccepted() {
        val j = js("""["52.3745", "12"]""")
        j.beginArray()
        assertEquals(52.3745, j.nextDouble(), 0.0)
        assertEquals(12L, j.nextLong())
    }

    @Test
    fun malformedInputFailsClearly() {
        // Every malformed input is an IOException, so callers that fail over on I/O errors (Overpass) catch it.
        for (bad in listOf("""{"a" 1}""", """[1,,2]""", """{"a":1,}""", """["open""", """{"a":tru}""", """[1.2.3]""", """[-]""", """[12abc]""", """[01]""", """[1d]""")) {
            try {
                js(bad).readTree()
                fail("accepted $bad")
            } catch (_: IOException) {
            }
        }
        for (read in listOf<(JsonStream) -> Unit>({ it.nextDouble() }, { it.nextLong() })) {
            val j = js("""[5.2.1]""")
            j.beginArray()
            try {
                read(j)
                fail("accepted 5.2.1")
            } catch (_: IOException) {
            }
        }
    }

    @Test
    fun malformedLiteralsFailOnEveryPathNotJustTheTypedOnes() {
        // Tags are read as text and skipped values are not read at all: a garbled answer must still fail, so Overpass
        // fails over to another server instead of handing out "tru" as a tag value.
        val bad = listOf("tru", "nul", "12abc", "1d", "0x10", "-Infinity", "01", "1.", "-", "1e", ".5")
        for (lit in bad) {
            for (read in listOf<(JsonStream) -> Any?>({ it.readStringMap() }, { it.beginObject(); it.nextName(); it.skipValue() }, { it.beginObject(); it.nextName(); it.nextScalarOrNull() })) {
                try {
                    read(js("""{"a": $lit}"""))
                    fail("accepted $lit")
                } catch (_: IOException) {
                }
            }
        }
        for (good in listOf("0", "-0", "12", "-2.5e3", "1E+2", "0.25", "true", "false")) {
            assertEquals(good, js("""{"a": $good}""").readStringMap()["a"])
        }
    }

    @Test
    fun longStringsCrossTheBuffer() {
        val long = "é".repeat(40_000)
        val j = js("""{"k":"$long"}""")
        j.beginObject()
        j.nextName()
        assertEquals(long, j.nextString())
    }
}

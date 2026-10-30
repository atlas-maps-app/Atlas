package app.atlas.tiles

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.util.zip.CRC32

/**
 * A pack as the core lays one out, every section stored: the magic, a zero byte, format 3, two zero bytes, the
 * manifest's and the directory's lengths, the manifest, the directory (each name, then its offset, stored and raw
 * lengths, CRC-32 and method), the sections end to end.
 */
internal fun storedPack(manifest: String, sections: List<Pair<String, ByteArray>>): ByteArray {
    val m = manifest.toByteArray()
    fun directory(base: Long): ByteArray {
        val d = ByteArrayOutputStream()
        var offset = base
        for ((name, b) in sections) {
            val n = name.toByteArray()
            d.write(n.size)
            d.write(n)
            d.write(ByteBuffer.allocate(29).putLong(offset).putLong(b.size.toLong()).putLong(b.size.toLong()).putInt(CRC32().also { it.update(b) }.value.toInt()).put(0).array())
            offset += b.size
        }
        return d.toByteArray()
    }
    val d = directory(20L + m.size + directory(0).size)
    val out = ByteArrayOutputStream()
    out.write("ATLASPCK".toByteArray())
    out.write(byteArrayOf(0, 3, 0, 0))
    out.write(ByteBuffer.allocate(8).putInt(m.size).putInt(d.size).array())
    out.write(m)
    out.write(d)
    for ((_, b) in sections) out.write(b)
    return out.toByteArray()
}

/** A window's style as a country's windows differ: the same tables but for the region record. */
internal fun windowStyle(x: Int, y: Int): ByteArray =
    ("{keep ladder, render tags, the style file's tables} ".repeat(20) + "{scene: region 14/$x/$y, view, R} {tradition, state}").toByteArray()

class CountryStyleTest {
    private val country = windowStyle(8414, 5384)

    @Test
    fun aStyleComesBackFromWhatDiffersFromTheCountrys() {
        fun back(template: ByteArray, raw: ByteArray): ByteArray = CountryStyle.apply(template, CountryStyle.difference(template, raw))
        val styles = listOf(
            country, windowStyle(8413, 5385), windowStyle(1, 2), ByteArray(0), "x".toByteArray(), country.copyOf(country.size - 3),
            byteArrayOf(9) + country, country + byteArrayOf(9), "aa".toByteArray(), "aaaa".toByteArray(), ByteArray(300) { (it * 7).toByte() },
        )
        for (template in styles + listOf("aaa".toByteArray())) for (raw in styles) assertArrayEquals(raw, back(template, raw))
        // the country's own style costs its length's two varint bytes and an empty suffix's; a window's, its region record's
        assertEquals(3, CountryStyle.difference(country, country).size)
        assertTrue(CountryStyle.difference(country, windowStyle(8413, 5385)).size < 12)
    }

    @Test
    fun aWindowsPackGoesIntoTheTilesAndComesBackAsTheCoreReadsIt() {
        val style = windowStyle(8413, 5385)
        val pack = storedPack("{\"window\":\"14/8413/5385\"}", listOf("tiles" to ByteArray(47) { 1 }, "chunks" to ByteArray(500) { (it % 13).toByte() }, "style" to style))
        val stored = CountryStyle.store(pack, style, country)
        assertEquals(pack.size - style.size + CountryStyle.difference(country, style).size, stored.size)
        // the directory says how: the country's method, the difference's length and CRC-32, the style's raw length
        val at = stored.size - CountryStyle.difference(country, style).size
        assertArrayEquals(pack.copyOf(at), stored.copyOf(at).also { patchStyleEntry(it, pack) })
        assertArrayEquals(pack, CountryStyle.unpack(stored, country))
        // stored again from the pack the phone wrote: the same bytes
        assertArrayEquals(stored, CountryStyle.store(CountryStyle.unpack(stored, country), style, country))
    }

    /** [stored]'s directory entry of the style made [pack]'s, so what precedes the style's bytes can be compared. */
    private fun patchStyleEntry(stored: ByteArray, pack: ByteArray) {
        val entry = 20 + ByteBuffer.wrap(pack).getInt(12) + ByteBuffer.wrap(pack).getInt(16) - 29 + 8
        pack.copyInto(stored, entry, entry, entry + 21)
    }

    @Test
    fun whatIsNotAWindowsPackAsTheTilesHoldItIsRefused() {
        val style = windowStyle(8413, 5385)
        val pack = storedPack("{}", listOf("tiles" to ByteArray(4), "style" to style))
        val stored = CountryStyle.store(pack, style, country)
        fun refused(b: ByteArray, template: ByteArray = country) {
            assertThrows(IllegalArgumentException::class.java) { CountryStyle.unpack(b, template) }
        }
        // not a pack; cut short; a byte more after the style
        refused(stored.copyOf().also { it[0] = 'X'.code.toByte() })
        refused(stored.copyOf(stored.size - 1))
        refused(stored + byteArrayOf(0))
        // a style the core stores, not a country's
        refused(pack)
        // the style not the last section
        val turned = storedPack("{}", listOf("style" to style, "tiles" to ByteArray(4)))
        assertThrows(IllegalArgumentException::class.java) { CountryStyle.store(turned, style, country) }
        // a style the pack does not say, or a country's style shorter than the window's shares
        assertThrows(IllegalArgumentException::class.java) { CountryStyle.store(pack, style + byteArrayOf(1), country) }
        refused(stored, country.copyOf(10))
        assertArrayEquals(pack, CountryStyle.unpack(stored, country))
    }
}

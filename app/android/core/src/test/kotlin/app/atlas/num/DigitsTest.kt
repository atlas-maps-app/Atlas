package app.atlas.num

import app.atlas.geo.CoordinateParser
import app.atlas.geo.LatLon
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.regex.Pattern

/**
 * Numbers in any script's digits (the reviewer, 17 November, finding 12): read as their writer meant, never passed on
 * to a conversion that reads 0 to 9 alone.
 */
class DigitsTest {
    /** Every decimal digit Unicode knows, in every plane, with its value. */
    private val everyDigit: List<Pair<String, Int>> =
        (0..Character.MAX_CODE_POINT).filter { Character.getType(it) == Character.DECIMAL_DIGIT_NUMBER.toInt() }
            .map { String(Character.toChars(it)) to Character.digit(it, 10) }

    @Test
    fun theFactsTheFixRestsOn() {
        // Android's `\d` is ICU's, any script's digit: the JVM's own takes it so with UNICODE_CHARACTER_CLASS
        val icuLike = Pattern.compile("""^\d+$""", Pattern.UNICODE_CHARACTER_CLASS)
        assertTrue(icuLike.matcher("٥").matches())
        assertTrue(icuLike.matcher("५").matches())
        assertTrue(!Pattern.compile("""^\d+$""").matcher("٥").matches())
        // and `toDouble` reads 0 to 9 alone, where `toInt` reads any script's: a double was where it threw
        assertThrows(NumberFormatException::class.java) { "٥".toDouble() }
        assertEquals(5, "٥".toInt())
    }

    @Test
    fun everyScriptsDigitsArePlain() {
        assertTrue("Unicode's digits: ${everyDigit.size}", everyDigit.size >= 600)
        for ((d, value) in everyDigit) assertEquals("U+%04X".format(d.codePointAt(0)), "$value", Digits.ascii(d))
        assertEquals("52.37", Digits.ascii("٥٢٫٣٧"))
        assertEquals("1404", Digits.ascii("۱۴۰۴"))
        assertEquals("Mo-Fr 09:00", Digits.ascii("Mo-Fr ٠٩:٠٠"))
        // text without other scripts is the same text
        val plain = "capacity>5"
        assertSame(plain, Digits.ascii(plain))
        assertEquals("Café 12", Digits.ascii("Café ١٢"))
    }

    @Test
    fun coordinatesTypedInArabicDigits() {
        val at = CoordinateParser.parse("٥٢٫٣٧٣١، ٤٫٨٩٢٦")
        assertNotNull(at)
        assertEquals(52.3731, at!!.lat, 1e-9)
        assertEquals(4.8926, at.lon, 1e-9)
        val delhi = CoordinateParser.parse("२८.६१३९, ७७.२०९")
        assertNotNull(delhi)
        assertEquals(LatLon(28.6139, 77.209).lat, delhi!!.lat, 1e-9)
        assertEquals(77.209, delhi.lon, 1e-9)
        // every digit of every script reads, never throws
        for ((d, value) in everyDigit) {
            val p = CoordinateParser.parse("$d.5, $d$d.25")
            assertNotNull("U+%04X".format(d.codePointAt(0)), p)
            assertEquals(d, value + 0.5, p!!.lat, 1e-9)
            assertEquals(d, value * 11 + 0.25, p.lon, 1e-9)
        }
    }
}

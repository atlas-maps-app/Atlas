package app.atlas.search

import app.atlas.places.CompareOp
import app.atlas.places.TagFilter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * A comparison typed in any script's digits is read (the reviewer, 17 November, finding 12): "capacity>٥" passed
 * Android's `\d` and threw from `toDouble` past the power parser, which catches only the filter's own refusal; on the
 * JVM it read as words. Now it is capacity over 5 on both, and no digit throws.
 */
class TypedNumbersTest {
    @Test
    fun aComparisonInArabicDigits() {
        val p = QueryParser.parse("capacity>٥", "en")
        assertEquals(listOf<Query>(Query.Tags(TagFilter.Compare("capacity", CompareOp.GT, 5.0))), p.steps)
        assertTrue(p.unknown.toString(), p.unknown.isEmpty())
    }

    @Test
    fun noDigitThrows() {
        for (cp in 0..Character.MAX_CODE_POINT) {
            if (Character.getType(cp) != Character.DECIMAL_DIGIT_NUMBER.toInt()) continue
            val d = String(Character.toChars(cp))
            for (text in listOf("capacity>$d", "maxheight<$d.$d m", "height<$d'$d\"", "$d$d.$d, $d.$d")) {
                try { QueryParser.parse(text, "en") } catch (e: Exception) { fail("U+%04X '$text': $e".format(cp)) }
            }
        }
    }
}

package app.atlas.places

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * A number written in any script's digits is that number (the reviewer, 17 November, finding 12): a comparison typed
 * on an Arabic keyboard ("capacity>٥") passed Android's `\d`, ICU's, and threw from `toDouble`, which the search's
 * power parser does not catch; on the JVM it read as no number. The same in a value's units and in its years.
 */
class NumbersInAnyScriptTest {
    private val everyDigit: List<Pair<String, Int>> =
        (0..Character.MAX_CODE_POINT).filter { Character.getType(it) == Character.DECIMAL_DIGIT_NUMBER.toInt() }
            .map { String(Character.toChars(it)) to Character.digit(it, 10) }

    @Test
    fun aTypedComparisonInAnyScriptsDigits() {
        assertEquals(TagFilter.Compare("capacity", CompareOp.GT, 5.0), TagFilter.parse("capacity>٥"))
        assertEquals(TagFilter.Compare("capacity", CompareOp.GE, 12.0), TagFilter.parse("capacity>=۱۲"))
        assertEquals(TagFilter.Compare("maxheight", CompareOp.LT, 4.5, "m"), TagFilter.parse("maxheight<٤٫٥ m"))
        assertEquals(TagFilter.Compare("height", CompareOp.LT, 3.81, "m"), TagFilter.parse("height<١٢'٦\""))
        for ((d, value) in everyDigit) {
            val f = try { TagFilter.parse("capacity>$d$d") } catch (e: Exception) { fail("U+%04X: $e".format(d.codePointAt(0))); null }
            assertEquals(d, TagFilter.Compare("capacity", CompareOp.GT, value * 11.0), f)
        }
        // what is no number is refused as the parser refuses, never with another exception
        for (text in listOf("capacity>٥e", "capacity>٥.٥.٥", "capacity>٫", "capacity>e٥")) {
            try { TagFilter.parse(text) } catch (e: TagFilterException) { continue } catch (e: Exception) { fail("'$text': $e") }
        }
        assertTrue(TagFilter.parse("capacity>٥").matches(mapOf("capacity" to "6")))
    }

    @Test
    fun aValuesUnitsAndYearsInAnyScriptsDigits() {
        assertEquals(Measure(3.81, Dimension.LENGTH).value, Units.parse("١٢'٦\"")!!.value, 1e-9)
        assertEquals(Measure(5.5, Dimension.LENGTH), Units.parse("٥٫٥ m"))
        assertEquals(Measure(22.0, Dimension.NONE), Units.parse("٢٢"))
        assertEquals(1920.0 to 1929.0, Years.of("١٩٢٠s"))
        assertEquals(1890.0 to 1890.0, Years.of("۱۸۹۰"))
        for ((d, value) in everyDigit) {
            assertEquals(d, value * 11.0, Units.parse("$d$d")!!.value, 1e-9)
            assertEquals(d, (value * 111 * 10.0).let { it to it + 9 }, Years.of("$d$d${d}0s"))
        }
    }
}

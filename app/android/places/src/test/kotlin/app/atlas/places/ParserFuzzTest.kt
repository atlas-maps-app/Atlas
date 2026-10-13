package app.atlas.places

import org.junit.Assert.fail
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import kotlin.random.Random

/**
 * Random text built from each grammar's own pieces: the parsers may refuse it, but only with their own exception,
 * and whatever parses must evaluate without throwing.
 */
class ParserFuzzTest {
    private fun noise(r: Random, pieces: List<String>, n: Int) = buildString { repeat(r.nextInt(1, n)) { append(pieces[r.nextInt(pieces.size)]) } }

    @Test
    fun openingHours() {
        val pieces = listOf(
            "Mo", "Tu-Fr", "Sa", "Su", "PH", "SH", " ", " ", ";", ",", "||", ":", "-", "+", "/", "[", "]", "(", ")", "08:00", "18:30",
            "24:00", "26:00", "9", "12", "2026", "Jan", "Dec", "easter", "week", "01", "53", "1", "-1", "day", "days", "off", "open",
            "unknown", "\"note\"", "sunrise", "sunset", "dawn", "dusk", "24/7", "am", "pm", "h", ".", "99999999999", "0",
        )
        val ctx = OpeningHoursContext(publicHolidays = PublicHolidays.forCountry("NL"), schoolHolidays = HolidayCalendar { d -> if (d.dayOfMonth == 13) Holiday(d, "school") else null })
        val r = Random(20261013)
        var parsed = 0
        repeat(6000) {
            val text = noise(r, pieces, 14)
            val oh = try { OpeningHours.parse(text) } catch (e: OpeningHoursException) { null } catch (e: Exception) { fail("'$text': $e"); null }
            if (oh != null) {
                parsed++
                try {
                    oh.status(LocalDateTime.of(2026, 10, 13, 12, 0), ctx)
                    oh.spans(LocalDate.of(2026, 12, 25), ctx)
                    oh.weekTable()
                    oh.weekTable(LocalDate.of(2026, 12, 28), ctx)
                } catch (e: Exception) {
                    fail("'$text' parsed but failed to evaluate: $e")
                }
            }
        }
        println("opening hours fuzz: $parsed of 6000 random values parsed, all evaluated")
    }

    @Test
    fun tagFilters() {
        val pieces = listOf(
            "amenity", "=", "cafe", " ", " and ", " or ", "not ", "!", "(", ")", "~", "!=", "!~", ">=", "<", "4.2", " m", "kW", "\"",
            "'", "*", "|", "||", "&&", "^(a|b)$", "[", "a", ";", "12'6\"", "1e5",
        )
        val tags = mapOf("amenity" to "cafe", "a" to "b;c", "maxheight" to "4.5 m", "m" to "12")
        val r = Random(7)
        repeat(6000) {
            val text = noise(r, pieces, 10)
            val f = try { TagFilter.parse(text) } catch (e: TagFilterException) { null } catch (e: Exception) { fail("'$text': $e"); null }
            if (f != null) {
                try {
                    f.matches(tags)
                    f.toOverpass()
                    val again = TagFilter.parse(f.toText())
                    if (again != f) fail("'$text' wrote '${f.toText()}', which reads back as $again, not $f")
                } catch (e: TagFilterException) {
                    fail("'$text' wrote '${f.toText()}', which does not parse: ${e.message}")
                }
            }
        }
    }

    @Test
    fun svgPaths() {
        val pieces = listOf("M", "m", "L", "l", "H", "v", "C", "s", "Q", "t", "A", "a", "Z", "z", " ", ",", "-", ".", "1", "0", "5.5", "e", "1e2", "-.5")
        val r = Random(11)
        repeat(6000) {
            val text = noise(r, pieces, 24)
            try {
                SvgPath.parse(text).bounds()
            } catch (e: SvgPathException) {
                // refused, as it may be
            } catch (e: Exception) {
                fail("'$text': $e")
            }
        }
    }
}

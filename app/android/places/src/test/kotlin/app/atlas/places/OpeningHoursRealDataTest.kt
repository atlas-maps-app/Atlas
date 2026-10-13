package app.atlas.places

import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime

/** Every opening_hours value in the prototype's three extracts: parse rate, and that evaluation never fails. */
class OpeningHoursRealDataTest {
    private val keys = setOf("opening_hours", "opening_hours:kitchen", "opening_hours:drive_through", "collection_times", "service_times")

    @Test
    fun parsesNearlyEveryRealValue() {
        val values = LinkedHashMap<String, Int>()
        for (name in listOf("ams", "eat", "errands")) {
            for (e in Fixtures.elements(name)) for ((k, v) in e.place.tags) if (k in keys) values.merge(v, 1, Int::plus)
        }
        val failures = LinkedHashMap<String, String>()
        var ok = 0
        val ctx = OpeningHoursContext(publicHolidays = PublicHolidays.forCountry("NL"))
        val moments = listOf(LocalDateTime.of(2026, 10, 13, 12, 0), LocalDateTime.of(2026, 12, 25, 23, 30), LocalDateTime.of(2027, 4, 27, 3, 15))
        for ((v, n) in values) {
            try {
                val oh = OpeningHours.parse(v)
                ok += n
                for (at in moments) oh.status(at, ctx)
                oh.spans(LocalDate.of(2026, 10, 13), ctx)
                oh.weekTable()
            } catch (e: OpeningHoursException) {
                failures[v] = e.message ?: "?"
            }
        }
        val total = values.values.sum()
        val share = ok.toDouble() / total
        println("opening hours: ${values.size} distinct values, $total uses; parsed $ok (${"%.2f".format(share * 100)} %)")
        for ((v, why) in failures) println("  not read: \"$v\" ($why)")
        assertTrue("parse share ${share * 100} %", share >= 0.98)
    }
}

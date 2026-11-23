package app.atlas.places

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime

/**
 * Every opening_hours value in the prototype's three extracts: parse rate, that evaluation never fails, and what a
 * handful of them say at given moments.
 */
class OpeningHoursRealDataTest {
    private val keys = setOf("opening_hours", "opening_hours:kitchen", "opening_hours:drive_through", "collection_times", "service_times")

    /** The values and how often each is used. */
    private val values: Map<String, Int> by lazy {
        val out = LinkedHashMap<String, Int>()
        for (name in listOf("ams", "eat", "errands")) {
            for (e in Fixtures.elements(name)) for ((k, v) in e.place.tags) if (k in keys) out.merge(v, 1, Int::plus)
        }
        out
    }

    @Test
    fun parsesNearlyEveryRealValue() {
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
                oh.weekTable(weekOf = null, context = Nowhere)
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

    /**
     * What the extracts' own values say, not only that they read (the reviewer, 17 November, finding 12: the share
     * alone let "Tu-Su 16:00-02:00+" read open on its closed Monday, finding 6): Monday 12 to Sunday 18 October 2026,
     * Christmas, 21 September and King's Day 2027, in the Netherlands.
     */
    @Test
    fun realValuesSayWhatTheyMean() {
        val nl = OpeningHoursContext(publicHolidays = PublicHolidays.forCountry("NL"))
        val late = "Mo-Th 15:00-01:00, Fr,Sa 12:00-03:00, Su 12:00-01:00"
        val sunday = "Mo-Sa 10:00-18:00; Su 12:00-17:00; PH off"
        val museum = "Mo-Su,PH 09:00-22:00 open \"Online tickets only\"; Jan 01 12:00-22:00; Apr 27 09:00-17:00; May 04 09:00-17:00; " +
            "2026 Sep 20 09:00-17:00; 2026 Sep 21 off; 2026 Nov 07 09:00-18:30; Dec 25 09:00-17:00; Dec 31 09:00-17:00"
        val checks = listOf(
            // a bar's open end past midnight: its closed Monday stays closed, its night ends in the morning
            Triple("Tu-Su 16:00-02:00+", "2026-10-12T12:00", false),
            Triple("Tu-Su 16:00-02:00+", "2026-10-13T01:00", false),
            Triple("Tu-Su 16:00-02:00+", "2026-10-13T17:00", true),
            Triple("Tu-Su 16:00-02:00+", "2026-10-14T01:30", true),
            Triple("Tu-Su 16:00-02:00+", "2026-10-14T12:00", false),
            Triple("Mo-Su 21:00+", "2026-10-13T23:00", true),
            Triple("Mo-Su 21:00+", "2026-10-14T12:00", false),
            // late hours belong to the day they start on
            Triple(late, "2026-10-17T02:30", true),
            Triple(late, "2026-10-12T00:30", true),
            Triple(late, "2026-10-12T14:00", false),
            // public holidays
            Triple(sunday, "2026-10-13T12:00", true),
            Triple(sunday, "2027-04-27T12:00", false),
            Triple("Mo-Su, PH 11:00-23:00; Jan 01 off; Apr 27 off; Dec 25-26 off; Dec 31 off", "2026-12-25T12:00", false),
            Triple("Mo-Su, PH 11:00-23:00; Jan 01 off; Apr 27 off; Dec 25-26 off; Dec 31 off", "2026-12-24T12:00", true),
            Triple(museum, "2026-12-25T12:00", true),
            Triple(museum, "2026-12-25T18:00", false),
            Triple(museum, "2026-09-21T12:00", false),
            Triple(museum, "2026-09-22T21:00", true),
            // comments say nothing certain where they stand for the times
            Triple("\"by appointment\"", "2026-10-13T12:00", null),
            Triple("Tu-Fr \"on request\"; Sa-Su 13:00-17:00", "2026-10-13T12:00", null),
            Triple("Tu-Fr \"on request\"; Sa-Su 13:00-17:00", "2026-10-17T14:00", true),
            Triple("Tu-Fr \"on request\"; Sa-Su 13:00-17:00", "2026-10-12T12:00", false),
            Triple("24/7", "2026-12-25T03:00", true),
        )
        for ((v, at, open) in checks) {
            assertTrue("'$v' is the extracts' own", v in values)
            assertEquals("'$v' at $at", open, OpeningHours.parse(v).isOpen(LocalDateTime.parse(at), nl))
        }
    }
}

package app.atlas.places

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class HolidaysTest {
    private fun d(s: String) = LocalDate.parse(s)
    private fun dates(country: String, year: Int, sub: String? = null) = PublicHolidays.list(country, year, sub).map { it.date.toString() }

    @Test
    fun easterSundays() {
        val known = mapOf(2024 to "2024-03-31", 2025 to "2025-04-20", 2026 to "2026-04-05", 2027 to "2027-03-28", 2028 to "2028-04-16", 2038 to "2038-04-25", 2285 to "2285-03-22")
        for ((y, date) in known) assertEquals(d(date), Easter.sunday(y))
    }

    @Test
    fun netherlands() {
        assertEquals(
            listOf("2026-01-01", "2026-04-03", "2026-04-05", "2026-04-06", "2026-04-27", "2026-05-05", "2026-05-14", "2026-05-24", "2026-05-25", "2026-12-25", "2026-12-26"),
            dates("NL", 2026),
        )
        // King's Day moves to Saturday when 27 April is a Sunday (2025)
        assertTrue("2025-04-26" in dates("NL", 2025))
        assertTrue("2027-04-27" in dates("NL", 2027))
        assertTrue("2028-05-25" in dates("NL", 2028)) // Ascension 2028
        assertEquals("Koningsdag", PublicHolidays.forCountry("nl")!!.holiday(d("2026-04-27"))!!.localName)
    }

    @Test
    fun germanyAndItsStates() {
        assertEquals(
            listOf("2026-01-01", "2026-04-03", "2026-04-06", "2026-05-01", "2026-05-14", "2026-05-25", "2026-10-03", "2026-12-25", "2026-12-26"),
            dates("DE", 2026),
        )
        val bavaria = dates("DE", 2026, "DE-BY")
        assertTrue("2026-01-06" in bavaria && "2026-06-04" in bavaria && "2026-11-01" in bavaria)
        assertFalse("2026-10-31" in bavaria)
        assertTrue("2026-10-31" in dates("DE", 2026, "SN"))
        assertTrue("2026-11-18" in dates("DE", 2026, "SN")) // Buß- und Bettag
        assertTrue("2027-11-17" in dates("DE", 2027, "SN"))
        assertTrue("2026-03-08" in dates("DE", 2026, "BE"))
    }

    @Test
    fun franceAndBelgium() {
        assertEquals(
            listOf("2026-01-01", "2026-04-06", "2026-05-01", "2026-05-08", "2026-05-14", "2026-05-25", "2026-07-14", "2026-08-15", "2026-11-01", "2026-11-11", "2026-12-25"),
            dates("FR", 2026),
        )
        assertEquals(
            listOf("2027-01-01", "2027-03-29", "2027-05-01", "2027-05-06", "2027-05-17", "2027-07-21", "2027-08-15", "2027-11-01", "2027-11-11", "2027-12-25"),
            dates("BE", 2027),
        )
        assertTrue("2026-12-26" in dates("FR", 2026, "FR-67"))
    }

    @Test
    fun britainWithSubstituteDays() {
        val e = dates("GB", 2026)
        for (x in listOf("2026-01-01", "2026-04-03", "2026-04-06", "2026-05-04", "2026-05-25", "2026-08-31", "2026-12-25", "2026-12-26", "2026-12-28")) assertTrue(x, x in e)
        // 2027: Christmas on a Saturday, Boxing Day on a Sunday: Monday 27 and Tuesday 28 are off
        val y27 = dates("GB", 2027)
        assertTrue("2027-12-27" in y27 && "2027-12-28" in y27)
        val scotland = dates("GB", 2026, "GB-SCT")
        assertTrue("2026-01-02" in scotland && "2026-08-03" in scotland && "2026-11-30" in scotland)
        assertFalse("2026-04-06" in scotland)
        assertTrue("2026-03-17" in dates("GB", 2026, "GB-NIR"))
        assertTrue("2026-07-13" in dates("GB", 2026, "GB-NIR")) // the Boyne on a Sunday, observed Monday
        assertEquals(dates("GB", 2026), dates("UK", 2026))
    }

    @Test
    fun unitedStatesWithObservedDays() {
        val e = dates("US", 2026)
        for (x in listOf("2026-01-01", "2026-01-19", "2026-02-16", "2026-05-25", "2026-06-19", "2026-07-03", "2026-07-04", "2026-09-07", "2026-10-12", "2026-11-11", "2026-11-26", "2026-12-25")) {
            assertTrue(x, x in e)
        }
        assertTrue("2027-07-05" in dates("US", 2027))
        // New Year 2028 is a Saturday: observed on Friday 31 December 2027, found through the calendar
        val us = PublicHolidays.forCountry("US")!!
        assertTrue(us.isHoliday(d("2027-12-31")))
        assertEquals("2028-11-23", PublicHolidays.list("US", 2028).first { it.name == "Thanksgiving Day" }.date.toString())
    }

    @Test
    fun unknownCountriesAndCalendars() {
        assertNull(PublicHolidays.forCountry("XX"))
        assertTrue(PublicHolidays.list("XX", 2026).isEmpty())
        assertFalse(HolidayCalendar.None.isHoliday(d("2026-12-25")))
        val custom = HolidayCalendar.of(listOf(Holiday(d("2026-10-19"), "Autumn")))
        assertTrue(custom.isHoliday(d("2026-10-19")))
        assertFalse(custom.isHoliday(d("2026-10-20")))
    }
}

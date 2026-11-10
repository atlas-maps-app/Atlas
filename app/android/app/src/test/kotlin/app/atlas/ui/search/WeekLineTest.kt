package app.atlas.ui.search

import app.atlas.search.DaySpread
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek
import java.time.DayOfWeek.FRIDAY
import java.time.DayOfWeek.MONDAY
import java.time.DayOfWeek.SATURDAY
import java.time.DayOfWeek.SUNDAY
import java.time.DayOfWeek.THURSDAY
import java.time.DayOfWeek.TUESDAY
import java.time.DayOfWeek.WEDNESDAY
import java.util.Locale

/** The hours card's week as its differences: days alike in a row on one line, the usual line the one the rest differ from. */
class WeekLineTest {
    private fun h(hours: Int) = hours * 60

    private fun week(of: (DayOfWeek) -> DaySpread) = DayOfWeek.entries.map(of)

    private fun open(d: DayOfWeek, n: Int, first: Int, last: Int, most: Int = first, mostTill: Int = last) = DaySpread(d, n, first, last, most, mostTill)

    private fun closed(d: DayOfWeek) = DaySpread(d, 0, null, null, null, null)

    /** Albert Heijn's two branches near: the week days alike, Saturday later the first to open, Sunday later still. */
    @Test
    fun daysAlikeInARowShareALine() {
        val lines = WeekLine.of(week { d ->
            when (d) {
                SATURDAY -> open(d, 2, h(8), h(22))
                SUNDAY -> open(d, 2, h(8), h(22), most = h(9))
                else -> open(d, 2, h(7), h(22), most = h(8))
            }
        })
        assertEquals(listOf(MONDAY to FRIDAY, SATURDAY to SATURDAY, SUNDAY to SUNDAY), lines.map { it.first to it.last })
        assertEquals(listOf("Mon–Fri", "Sat", "Sun"), lines.map { it.label(Locale.ENGLISH) })
        assertEquals(lines[0], WeekLine.usual(lines))
        assertTrue(WEDNESDAY in lines[0])
        assertFalse(SATURDAY in lines[0])
        // what the line says in figures: when most are open
        assertEquals(h(8) to h(22), lines[0].opens to lines[0].closes)
        assertEquals(h(9) to h(22), lines[2].opens to lines[2].closes)
    }

    /** Days alike but not in a row keep lines of their own, alike: the card fades the later one whole. */
    @Test
    fun daysAlikeApartKeepTheirLines() {
        val lines = WeekLine.of(week { d ->
            when (d) {
                MONDAY, SUNDAY -> closed(d)
                THURSDAY -> open(d, 1, h(10), h(21))
                else -> open(d, 1, h(10), h(18))
            }
        })
        assertEquals(listOf(MONDAY to MONDAY, TUESDAY to WEDNESDAY, THURSDAY to THURSDAY, FRIDAY to SATURDAY, SUNDAY to SUNDAY), lines.map { it.first to it.last })
        assertEquals(lines[1], WeekLine.usual(lines))
        assertTrue(lines[3].alike(lines[1]))
        assertFalse(lines[2].alike(lines[1]))
        assertEquals(null, lines[0].opens)
    }

    /** The usual line is an open one, even where the closed days are more. */
    @Test
    fun theUsualLineIsOpen() {
        val lines = WeekLine.of(week { d -> if (d == SATURDAY || d == SUNDAY) open(d, 3, h(9), h(17)) else closed(d) })
        assertEquals(listOf(MONDAY to FRIDAY, SATURDAY to SUNDAY), lines.map { it.first to it.last })
        assertEquals(lines[1], WeekLine.usual(lines))
        assertEquals("Sat–Sun", lines[1].label(Locale.ENGLISH))
    }
}

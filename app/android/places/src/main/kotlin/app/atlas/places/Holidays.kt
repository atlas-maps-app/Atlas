package app.atlas.places

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.Month
import java.time.temporal.TemporalAdjusters
import java.util.concurrent.ConcurrentHashMap

/** One holiday: its date, its English name and its name in the country's language. */
data class Holiday(val date: LocalDate, val name: String, val localName: String = name)

/** A source of holidays for the `PH` and `SH` selectors of opening_hours. */
fun interface HolidayCalendar {
    /** The holiday on [date], or null on an ordinary day. */
    fun holiday(date: LocalDate): Holiday?

    fun isHoliday(date: LocalDate): Boolean = holiday(date) != null

    companion object {
        /** A calendar without holidays. */
        val None = HolidayCalendar { null }

        /** A calendar of fixed dates, e.g. school holidays from an offline pack. */
        fun of(holidays: Collection<Holiday>): HolidayCalendar {
            val byDate = holidays.associateBy { it.date }
            return HolidayCalendar { byDate[it] }
        }
    }
}

/** Western Easter Sunday by the anonymous Gregorian algorithm (Meeus/Jones/Butcher). */
object Easter {
    fun sunday(year: Int): LocalDate {
        val a = year % 19
        val b = year / 100
        val c = year % 100
        val d = b / 4
        val e = b % 4
        val f = (b + 8) / 25
        val g = (b - f + 1) / 3
        val h = (19 * a + b - d - g + 15) % 30
        val i = c / 4
        val k = c % 4
        val l = (32 + 2 * e + 2 * i - h - k) % 7
        val m = (a + 11 * h + 22 * l) / 451
        val month = (h + l - 7 * m + 114) / 31
        val day = (h + l - 7 * m + 114) % 31 + 1
        return LocalDate.of(year, month, day)
    }
}

/**
 * Public holidays computed by rule, Easter-based ones included, for the Netherlands, Germany (with its states),
 * France, Belgium, the United Kingdom (England and Wales, Scotland, Northern Ireland) and the United States
 * (federal, with observed days). The rules hold for the years Atlas cares about; tests check 2026–2028.
 */
object PublicHolidays {
    /** Countries with rules, as ISO 3166-1 alpha-2 codes. */
    val countries: Set<String> = setOf("NL", "DE", "FR", "BE", "GB", "US")

    private val cache = ConcurrentHashMap<String, List<Holiday>>()

    /** The calendar for [country] (and an ISO 3166-2 [subdivision] such as "DE-BY" or "GB-SCT"), or null if unknown. */
    fun forCountry(country: String, subdivision: String? = null): HolidayCalendar? {
        val c = normalise(country) ?: return null
        val sub = subdivision?.uppercase()?.let { if ('-' in it) it else "$c-$it" }
        // a holiday observed on a date of the year before (1 January on a Saturday) sits in the next year's list
        return HolidayCalendar { date ->
            list(c, date.year, sub).firstOrNull { it.date == date } ?: list(c, date.year + 1, sub).firstOrNull { it.date == date }
        }
    }

    /** Every public holiday of [country] in [year], in date order. Empty for a country without rules. */
    fun list(country: String, year: Int, subdivision: String? = null): List<Holiday> {
        val c = normalise(country) ?: return emptyList()
        val sub = subdivision?.uppercase()?.let { if ('-' in it) it else "$c-$it" }
        return cache.getOrPut("$c/$sub/$year") { compute(c, year, sub).sortedBy { it.date }.distinctBy { it.date to it.name } }
    }

    private fun normalise(country: String): String? = when (val c = country.uppercase()) {
        "UK" -> "GB"
        else -> c.takeIf { it in countries }
    }

    private fun compute(country: String, year: Int, sub: String?): List<Holiday> {
        val easter = Easter.sunday(year)
        val out = ArrayList<Holiday>()
        fun add(date: LocalDate, en: String, local: String = en) = out.add(Holiday(date, en, local))
        fun day(m: Int, d: Int) = LocalDate.of(year, m, d)
        when (country) {
            "NL" -> {
                add(day(1, 1), "New Year's Day", "Nieuwjaarsdag")
                add(easter.minusDays(2), "Good Friday", "Goede Vrijdag")
                add(easter, "Easter Sunday", "Eerste Paasdag")
                add(easter.plusDays(1), "Easter Monday", "Tweede Paasdag")
                val kings = day(4, 27).let { if (it.dayOfWeek == DayOfWeek.SUNDAY) it.minusDays(1) else it }
                add(kings, "King's Day", "Koningsdag")
                add(day(5, 5), "Liberation Day", "Bevrijdingsdag")
                add(easter.plusDays(39), "Ascension Day", "Hemelvaartsdag")
                add(easter.plusDays(49), "Whit Sunday", "Eerste Pinksterdag")
                add(easter.plusDays(50), "Whit Monday", "Tweede Pinksterdag")
                add(day(12, 25), "Christmas Day", "Eerste Kerstdag")
                add(day(12, 26), "Boxing Day", "Tweede Kerstdag")
            }
            "DE" -> {
                add(day(1, 1), "New Year's Day", "Neujahr")
                add(easter.minusDays(2), "Good Friday", "Karfreitag")
                add(easter.plusDays(1), "Easter Monday", "Ostermontag")
                add(day(5, 1), "Labour Day", "Tag der Arbeit")
                add(easter.plusDays(39), "Ascension Day", "Christi Himmelfahrt")
                add(easter.plusDays(50), "Whit Monday", "Pfingstmontag")
                add(day(10, 3), "German Unity Day", "Tag der Deutschen Einheit")
                add(day(12, 25), "Christmas Day", "1. Weihnachtstag")
                add(day(12, 26), "St. Stephen's Day", "2. Weihnachtstag")
                val state = sub?.removePrefix("DE-")
                if (state != null) {
                    if (state in setOf("BW", "BY", "ST")) add(day(1, 6), "Epiphany", "Heilige Drei Könige")
                    if (state in setOf("BE", "MV")) add(day(3, 8), "International Women's Day", "Internationaler Frauentag")
                    if (state == "BB") {
                        add(easter, "Easter Sunday", "Ostersonntag")
                        add(easter.plusDays(49), "Whit Sunday", "Pfingstsonntag")
                    }
                    if (state in setOf("BW", "BY", "HE", "NW", "RP", "SL")) add(easter.plusDays(60), "Corpus Christi", "Fronleichnam")
                    if (state == "SL") add(day(8, 15), "Assumption Day", "Mariä Himmelfahrt")
                    if (state == "TH") add(day(9, 20), "World Children's Day", "Weltkindertag")
                    if (state in setOf("BB", "HB", "HH", "MV", "NI", "SN", "ST", "SH", "TH")) add(day(10, 31), "Reformation Day", "Reformationstag")
                    if (state in setOf("BW", "BY", "NW", "RP", "SL")) add(day(11, 1), "All Saints' Day", "Allerheiligen")
                    if (state == "SN") {
                        // Repentance and Prayer: the Wednesday before 23 November
                        add(day(11, 22).with(TemporalAdjusters.previousOrSame(DayOfWeek.WEDNESDAY)), "Repentance and Prayer Day", "Buß- und Bettag")
                    }
                }
            }
            "FR" -> {
                add(day(1, 1), "New Year's Day", "Jour de l'an")
                add(easter.plusDays(1), "Easter Monday", "Lundi de Pâques")
                add(day(5, 1), "Labour Day", "Fête du Travail")
                add(day(5, 8), "Victory in Europe Day", "Victoire 1945")
                add(easter.plusDays(39), "Ascension Day", "Ascension")
                add(easter.plusDays(50), "Whit Monday", "Lundi de Pentecôte")
                add(day(7, 14), "Bastille Day", "Fête nationale")
                add(day(8, 15), "Assumption Day", "Assomption")
                add(day(11, 1), "All Saints' Day", "Toussaint")
                add(day(11, 11), "Armistice Day", "Armistice 1918")
                add(day(12, 25), "Christmas Day", "Noël")
                if (sub in setOf("FR-57", "FR-67", "FR-68", "FR-6AE")) {
                    add(easter.minusDays(2), "Good Friday", "Vendredi saint")
                    add(day(12, 26), "St. Stephen's Day", "Saint-Étienne")
                }
            }
            "BE" -> {
                add(day(1, 1), "New Year's Day", "Nieuwjaar")
                add(easter.plusDays(1), "Easter Monday", "Paasmaandag")
                add(day(5, 1), "Labour Day", "Dag van de Arbeid")
                add(easter.plusDays(39), "Ascension Day", "Onze-Lieve-Heer-Hemelvaart")
                add(easter.plusDays(50), "Whit Monday", "Pinkstermaandag")
                add(day(7, 21), "National Day", "Nationale feestdag")
                add(day(8, 15), "Assumption Day", "Onze-Lieve-Vrouw-Hemelvaart")
                add(day(11, 1), "All Saints' Day", "Allerheiligen")
                add(day(11, 11), "Armistice Day", "Wapenstilstand")
                add(day(12, 25), "Christmas Day", "Kerstmis")
            }
            "GB" -> britain(year, easter, sub, ::add)
            "US" -> unitedStates(year, ::add)
        }
        return out
    }

    private fun britain(year: Int, easter: LocalDate, sub: String?, add: (LocalDate, String, String) -> Boolean) {
        fun day(m: Int, d: Int) = LocalDate.of(year, m, d)
        fun firstMonday(m: Int) = day(m, 1).with(TemporalAdjusters.firstInMonth(DayOfWeek.MONDAY))
        fun lastMonday(m: Int) = day(m, 1).with(TemporalAdjusters.lastInMonth(DayOfWeek.MONDAY))
        // A fixed holiday on a weekend gives the next free weekday instead
        fun substitute(date: LocalDate, taken: Set<LocalDate> = emptySet()): LocalDate {
            var d = date
            while (d.dayOfWeek == DayOfWeek.SATURDAY || d.dayOfWeek == DayOfWeek.SUNDAY || d in taken) d = d.plusDays(1)
            return d
        }
        // The day itself stays a holiday too: shops keep to the date, offices to the substitute
        fun fixed(date: LocalDate, name: String, taken: Set<LocalDate> = emptySet()): LocalDate {
            add(date, name, name)
            return substitute(date, taken).also { if (it != date) add(it, "$name (substitute day)", "$name (substitute day)") }
        }
        val scotland = sub == "GB-SCT"
        val northernIreland = sub == "GB-NIR"
        val newYear = fixed(day(1, 1), "New Year's Day")
        if (scotland) fixed(day(1, 2), "2nd January", setOf(newYear))
        if (northernIreland) fixed(day(3, 17), "St Patrick's Day")
        add(easter.minusDays(2), "Good Friday", "Good Friday")
        if (!scotland) add(easter.plusDays(1), "Easter Monday", "Easter Monday")
        // 2020 moved the early May holiday for VE Day; later years keep the first Monday
        add(firstMonday(5), "Early May bank holiday", "Early May bank holiday")
        add(lastMonday(5), "Spring bank holiday", "Spring bank holiday")
        if (northernIreland) fixed(day(7, 12), "Battle of the Boyne")
        if (scotland) add(firstMonday(8), "Summer bank holiday", "Summer bank holiday")
        else add(lastMonday(8), "Summer bank holiday", "Summer bank holiday")
        if (scotland) fixed(day(11, 30), "St Andrew's Day")
        val christmas = fixed(day(12, 25), "Christmas Day")
        fixed(day(12, 26), "Boxing Day", setOf(christmas))
    }

    private fun unitedStates(year: Int, add: (LocalDate, String, String) -> Boolean) {
        fun day(m: Int, d: Int) = LocalDate.of(year, m, d)
        fun nth(m: Month, n: Int, dow: DayOfWeek) = LocalDate.of(year, m, 1).with(TemporalAdjusters.dayOfWeekInMonth(n, dow))
        fun last(m: Month, dow: DayOfWeek) = LocalDate.of(year, m, 1).with(TemporalAdjusters.lastInMonth(dow))
        // A fixed federal holiday on a Saturday is observed on the Friday before, on a Sunday on the Monday after
        fun fixed(date: LocalDate, name: String) {
            add(date, name, name)
            when (date.dayOfWeek) {
                DayOfWeek.SATURDAY -> add(date.minusDays(1), "$name (observed)", "$name (observed)")
                DayOfWeek.SUNDAY -> add(date.plusDays(1), "$name (observed)", "$name (observed)")
                else -> {}
            }
        }
        fixed(day(1, 1), "New Year's Day")
        add(nth(Month.JANUARY, 3, DayOfWeek.MONDAY), "Martin Luther King Jr. Day", "Martin Luther King Jr. Day")
        add(nth(Month.FEBRUARY, 3, DayOfWeek.MONDAY), "Washington's Birthday", "Washington's Birthday")
        add(last(Month.MAY, DayOfWeek.MONDAY), "Memorial Day", "Memorial Day")
        fixed(day(6, 19), "Juneteenth")
        fixed(day(7, 4), "Independence Day")
        add(nth(Month.SEPTEMBER, 1, DayOfWeek.MONDAY), "Labor Day", "Labor Day")
        add(nth(Month.OCTOBER, 2, DayOfWeek.MONDAY), "Columbus Day", "Columbus Day")
        fixed(day(11, 11), "Veterans Day")
        add(nth(Month.NOVEMBER, 4, DayOfWeek.THURSDAY), "Thanksgiving Day", "Thanksgiving Day")
        fixed(day(12, 25), "Christmas Day")
    }
}

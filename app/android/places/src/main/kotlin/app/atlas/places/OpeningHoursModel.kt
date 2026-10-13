package app.atlas.places

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.IsoFields
import java.time.temporal.TemporalAdjusters

/** How a rule joins the rules before it: `;` (normal), `,` (additional) or `||` (fallback). */
internal enum class RuleKind { NORMAL, ADDITIONAL, FALLBACK }

/** What a rule says about the times it selects. */
enum class OpenState { OPEN, CLOSED, UNKNOWN }

/** The sun events opening_hours can name. */
internal enum class SunEvent { DAWN, SUNRISE, SUNSET, DUSK }

/** Selects dates while evaluating; [generic] evaluates a plain week, where dated selectors and holidays never match. */
internal class DateContext(val context: OpeningHoursContext, val generic: Boolean)

/** `2026`, `2026-2028`, `2026-2030/2`, `2026+`. */
internal class YearRange(val from: Int, val to: Int, val step: Int) {
    fun matches(year: Int): Boolean = year in from..to && (year - from) % step == 0
}

/** `week 01-53/2`: ISO week numbers. */
internal class WeekRange(val from: Int, val to: Int, val step: Int) {
    fun matches(date: LocalDate): Boolean {
        val w = date.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR)
        return if (from <= to) w in from..to && (w - from) % step == 0 else (w >= from || w <= to)
    }
}

/**
 * One end of a date range: `[year] month day`, `[year] easter`, with an optional weekday shift (`+Su`: the next
 * Sunday on or after) and a day shift (`+2 days`).
 */
internal class DateSpec(
    val year: Int?,
    val month: Int,
    val day: Int,
    val easter: Boolean = false,
    val weekdayShift: Int = 0,
    val dayShift: Int = 0,
) {
    fun resolve(y: Int): LocalDate? {
        var d = if (easter) Easter.sunday(y) else {
            if (month == 2 && day == 29 && !java.time.Year.isLeap(y.toLong())) return null
            LocalDate.of(y, month, day.coerceAtMost(LocalDate.of(y, month, 1).lengthOfMonth()))
        }
        if (weekdayShift != 0) {
            val dow = DayOfWeek.of(kotlin.math.abs(weekdayShift))
            d = d.with(if (weekdayShift > 0) TemporalAdjusters.nextOrSame(dow) else TemporalAdjusters.previousOrSame(dow))
        }
        return if (dayShift != 0) d.plusDays(dayShift.toLong()) else d
    }
}

/** A selector of calendar dates. */
internal sealed class DateRange {
    abstract fun matches(date: LocalDate): Boolean

    /** `Jan-Mar`, `Dec-Feb` (wrapping), `2026 Jan-2027 Mar`, `Aug`. */
    class Months(val fromYear: Int?, val from: Int, val toYear: Int?, val to: Int) : DateRange() {
        override fun matches(date: LocalDate): Boolean {
            val m = date.monthValue
            if (fromYear != null) {
                val ty = toYear ?: if (to >= from) fromYear else fromYear + 1
                val v = date.year * 12 + m
                return v >= fromYear * 12 + from && v <= ty * 12 + to
            }
            return if (from <= to) m in from..to else (m >= from || m <= to)
        }
    }

    /** `Dec 25`, `Dec 24-26`, `Dec 24-Jan 02`, `easter -2 days-easter +1 day`, `Mar 01+`. */
    class Days(val from: DateSpec, val to: DateSpec?, val openEnded: Boolean) : DateRange() {
        override fun matches(date: LocalDate): Boolean {
            if (from.year != null) {
                val a = from.resolve(from.year) ?: return false
                if (openEnded) return !date.isBefore(a)
                if (to == null) return date == a
                var b = to.resolve(to.year ?: from.year) ?: return false
                if (to.year == null && b.isBefore(a)) b = to.resolve(from.year + 1) ?: return false
                return !date.isBefore(a) && !date.isAfter(b)
            }
            for (y in intArrayOf(date.year, date.year - 1)) {
                val a = from.resolve(y) ?: continue
                if (openEnded) {
                    if (y == date.year && !date.isBefore(a)) return true
                    continue
                }
                if (to == null) {
                    if (date == a) return true
                    continue
                }
                var b = to.resolve(y) ?: continue
                if (b.isBefore(a)) b = to.resolve(y + 1) ?: continue
                if (!date.isBefore(a) && !date.isAfter(b)) return true
            }
            return false
        }
    }
}

/**
 * `Mo-Fr`, `Su[1]`, `Mo[1,-1]`, `Sa[-1] +1 day`. Days are 0 (Monday) to 6 (Sunday); [nth] lists the allowed
 * occurrences in the month (1..5 from the start, -1..-5 from the end); [shift] moves the selected day.
 */
internal class WeekdayRange(val from: Int, val to: Int, val nth: List<IntRange>?, val shift: Int) {
    fun matches(date: LocalDate, generic: Boolean): Boolean {
        if (nth != null && generic) return false
        val d = if (shift != 0) date.minusDays(shift.toLong()) else date
        val dow = d.dayOfWeek.value - 1
        val inRange = if (from <= to) dow in from..to else (dow >= from || dow <= to)
        if (!inRange) return false
        if (nth == null) return true
        val fromStart = (d.dayOfMonth - 1) / 7 + 1
        val fromEnd = -((d.lengthOfMonth() - d.dayOfMonth) / 7 + 1)
        return nth.any { fromStart in it || fromEnd in it }
    }
}

/** `PH`, `SH`, `PH +1 day`. */
internal class HolidaySelector(val school: Boolean, val shift: Int) {
    fun matches(date: LocalDate, ctx: DateContext): Boolean {
        if (ctx.generic) return false
        val cal = (if (school) ctx.context.schoolHolidays else ctx.context.publicHolidays) ?: return false
        return cal.isHoliday(if (shift != 0) date.minusDays(shift.toLong()) else date)
    }
}

/** Weekdays and holidays: any item matches (`Mo-Fr,PH`), or holiday and weekday both (`SH Mo-Fr`, when [both]). */
internal class DaySelector(val weekdays: List<WeekdayRange>, val holidays: List<HolidaySelector>, val both: Boolean) {
    fun matches(date: LocalDate, ctx: DateContext): Boolean {
        val w = weekdays.any { it.matches(date, ctx.generic) }
        val h = holidays.any { it.matches(date, ctx) }
        return if (both) w && h else w || h
    }
}

/** A time of day: fixed minutes (up to 48:00), or a sun event moved by [offset] minutes. */
internal class TimePoint(val minutes: Int, val event: SunEvent? = null, val offset: Int = 0) {
    fun resolve(date: LocalDate, sun: SunClock): Int? {
        if (event == null) return minutes
        val t = sun.times(date) ?: return null
        val at = when (event) {
            SunEvent.DAWN -> t.dawn
            SunEvent.SUNRISE -> t.sunrise
            SunEvent.SUNSET -> t.sunset
            SunEvent.DUSK -> t.dusk
        } ?: return null
        return at.hour * 60 + at.minute + offset
    }
}

/**
 * `08:00-12:00`, `22:00-02:00` (past midnight), `17:00+` (open end), `10:00-16:00/01:30` (periodic, read as the
 * span), `17:00` (a point in time, one minute).
 */
internal class TimeSpan(val from: TimePoint, val to: TimePoint?, val openEnd: Boolean) {
    /** Start and end minutes on [date] (end may pass 1440) and whether the span itself is open-ended; null when a sun event is missing. */
    fun resolve(date: LocalDate, sun: SunClock): Pair<Int, Int>? {
        val a = from.resolve(date, sun) ?: return null
        if (to == null) return if (openEnd) a to a + openEndGuess(a) else a to a + 1
        var b = to.resolve(date, sun) ?: return null
        if (b <= a) b += 1440
        return a to b.coerceAtMost(a + 2880).coerceAtMost(2880)
    }

    companion object {
        /** How long an open end ("17:00+") is assumed to last: to midnight by day, five hours in the evening, eight at night. */
        fun openEndGuess(start: Int): Int = when {
            start < 17 * 60 -> (1440 - start).coerceAtLeast(60)
            start < 22 * 60 -> 300
            else -> 480
        }
    }
}

/** One rule of an opening_hours value: its selectors, what it says, and how it joins the rules before it. */
internal class Rule(
    val kind: RuleKind,
    val years: List<YearRange>?,
    val dates: List<DateRange>?,
    val weeks: List<WeekRange>?,
    val days: DaySelector?,
    val times: List<TimeSpan>?,
    val state: OpenState,
    val comment: String?,
    val alwaysOpen: Boolean,
) {
    val hasDateSelector: Boolean get() = years != null || dates != null || weeks != null || days != null

    /** True when the rule repeats week after week: no years, dates, weeks, holidays, nth weekdays or sun times. */
    val isWeekly: Boolean
        get() = years == null && dates == null && weeks == null &&
            (days == null || (days.holidays.isEmpty() && days.weekdays.none { it.nth != null })) &&
            (times == null || times.none { it.from.event != null || it.to?.event != null })
    private val wide: Boolean get() = years != null || dates != null || weeks != null

    fun matchesDate(date: LocalDate, ctx: DateContext): Boolean {
        if (ctx.generic && wide) return false
        if (years != null && years.none { it.matches(date.year) }) return false
        if (dates != null && dates.none { it.matches(date) }) return false
        if (weeks != null && weeks.none { it.matches(date) }) return false
        if (days != null && !days.matches(date, ctx)) return false
        return true
    }
}

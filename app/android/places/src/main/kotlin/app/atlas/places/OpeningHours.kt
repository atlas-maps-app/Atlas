package app.atlas.places

import app.atlas.geo.Sun
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters
import java.util.concurrent.ConcurrentHashMap

/** One day's sun as opening_hours reads it: local times, null when the event does not happen (polar day or night). */
data class DaySun(val dawn: LocalTime?, val sunrise: LocalTime?, val sunset: LocalTime?, val dusk: LocalTime?)

/** Sun times by date, for `sunrise`, `sunset`, `dawn` and `dusk` in opening hours. */
fun interface SunClock {
    fun times(date: LocalDate): DaySun?

    companion object {
        /** Fixed times for when the place is not known: dawn 05:30, sunrise 06:00, sunset 18:00, dusk 18:30. */
        val Fixed: SunClock = DaySun(LocalTime.of(5, 30), LocalTime.of(6, 0), LocalTime.of(18, 0), LocalTime.of(18, 30)).let { s -> SunClock { s } }

        /** The real sun at a place, from :core's NOAA calculator, with civil dawn and dusk; cached per date. */
        fun at(lat: Double, lon: Double, zone: ZoneId): SunClock {
            val cache = ConcurrentHashMap<LocalDate, DaySun>()
            return SunClock { date ->
                cache.getOrPut(date) {
                    val t = Sun.times(date, lat, lon, zone)
                    DaySun(t.civilDawn?.toLocalTime(), t.sunrise?.toLocalTime(), t.sunset?.toLocalTime(), t.civilDusk?.toLocalTime())
                }
            }
        }
    }
}

/** What opening hours are read against: public holidays (`PH`), school holidays (`SH`) and the sun. */
data class OpeningHoursContext(
    val publicHolidays: HolidayCalendar? = null,
    val schoolHolidays: HolidayCalendar? = null,
    val sun: SunClock = SunClock.Fixed,
) {
    companion object {
        /** No holidays (a `PH` rule never applies) and fixed sun times. */
        val Default = OpeningHoursContext()

        /** The public holidays of [country] (when Atlas has its rules) and, with a position, the real sun. */
        fun forPlace(country: String?, zone: ZoneId, lat: Double? = null, lon: Double? = null, subdivision: String? = null) =
            OpeningHoursContext(
                publicHolidays = country?.let { PublicHolidays.forCountry(it, subdivision) },
                sun = if (lat != null && lon != null) SunClock.at(lat, lon, zone) else SunClock.Fixed,
            )
    }
}

/** What happens next at a place: it opens, it closes, or its state becomes unknown. */
enum class ChangeKind { OPENS, CLOSES, BECOMES_UNKNOWN }

/**
 * The state at a moment: [isOpen], when it next changes ([changesAt], null when it never does within a year) and how.
 * [unknown] means the hours do not say (a comment such as "by appointment", or a rule marked `unknown`).
 * [openEnd] means the place is open with no stated closing time (`17:00+`): [changesAt] is then a guess.
 */
data class OpenStatus(
    val isOpen: Boolean,
    val changesAt: LocalDateTime?,
    val nextChangeKind: ChangeKind?,
    val unknown: Boolean,
    val comment: String? = null,
    val openEnd: Boolean = false,
) {
    companion object {
        /** The status of hours that cannot be read. */
        val Unknown = OpenStatus(isOpen = false, changesAt = null, nextChangeKind = null, unknown = true)
    }
}

/**
 * A stretch of a day that is open (or unknown), in minutes since midnight: [start] in 0..1440, [end] after it. In a
 * [OpeningHours.weekTable] a span past midnight stays with the day it starts on, so [end] can pass 1440.
 */
data class OpenSpan(val start: Int, val end: Int, val state: OpenState, val comment: String? = null, val openEnd: Boolean = false) {
    val startTime: LocalTime get() = LocalTime.of((start / 60) % 24, start % 60)
    val endTime: LocalTime get() = LocalTime.of((end / 60) % 24, end % 60)
    val minutes: Int get() = end - start
}

/**
 * An OSM opening_hours value, parsed: `Mo-Fr 08:00-18:00; Sa 10:00-17:00; PH off`, with everything the
 * specification allows (years, months, dates and Easter, weeks, nth weekdays, holidays, times past midnight, sun
 * times, open ends, additional and fallback rules, comments). Rules are evaluated as opening_hours.js does: a later
 * dated rule replaces earlier ones for its days, `,` adds to them, `off` closes only what it selects, and `||`
 * fills only what no earlier rule covered. Hours that run past midnight belong to the day they start on.
 */
class OpeningHours private constructor(val raw: String, internal val rules: List<Rule>) {
    private val comments: List<String> = rules.mapNotNull { it.comment }.distinct()

    /** True for `24/7` and its equivalents with nothing else. */
    val isAlwaysOpen: Boolean
        get() = rules.size == 1 && rules[0].let { it.state == OpenState.OPEN && it.alwaysOpen && it.comment == null && !it.hasDateSelector }

    /** How many days ahead [status] looks for a change: a plain weekly pattern repeats after seven. */
    private val horizon: Int = if (rules.all { it.isWeekly }) 8 else MAX_DAYS

    /** Open at [at]: true or false, null when the hours do not say. */
    fun isOpen(at: LocalDateTime, context: OpeningHoursContext = OpeningHoursContext.Default): Boolean? =
        status(at, context).let { if (it.unknown) null else it.isOpen }

    /** The state at [at] and its next change, looking up to a year ahead. */
    fun status(at: LocalDateTime, context: OpeningHoursContext = OpeningHoursContext.Default): OpenStatus {
        val eval = Evaluation(DateContext(context, generic = false))
        var date = at.toLocalDate()
        var view = eval.view(date)
        var m = at.hour * 60 + at.minute
        val code = view.state[m].toInt()
        val state = stateOf(code)
        val comment = view.comment(m)
        var openEnd = code and OPEN_END != 0
        m++
        for (day in 0..horizon) {
            while (m < 1440) {
                val c = view.state[m].toInt()
                val s = stateOf(c)
                if (s != state) {
                    val kind = when (s) {
                        OpenState.OPEN -> ChangeKind.OPENS
                        OpenState.CLOSED -> ChangeKind.CLOSES
                        OpenState.UNKNOWN -> ChangeKind.BECOMES_UNKNOWN
                    }
                    return OpenStatus(state == OpenState.OPEN, date.atStartOfDay().plusMinutes(m.toLong()), kind,
                        state == OpenState.UNKNOWN, comment ?: unknownComment(state), openEnd && state == OpenState.OPEN)
                }
                if (c and OPEN_END != 0) openEnd = true
                m++
            }
            date = date.plusDays(1)
            view = eval.view(date)
            m = 0
        }
        return OpenStatus(state == OpenState.OPEN, null, null, state == OpenState.UNKNOWN, comment ?: unknownComment(state), openEnd)
    }

    private fun unknownComment(state: OpenState): String? = if (state == OpenState.UNKNOWN) comments.firstOrNull() else null

    /** The open and unknown stretches of [date] from midnight to midnight, the night before's late hours included: the clock dial. */
    fun spans(date: LocalDate, context: OpeningHoursContext = OpeningHoursContext.Default): List<OpenSpan> {
        val view = Evaluation(DateContext(context, generic = false)).view(date)
        return runs(view.state, view.commentIndex, 0, 1440)
    }

    /**
     * Seven lists, Monday to Sunday, of open and unknown spans, each owned by the day it starts on (ends may pass
     * 1440). Without [weekOf] it is the plain weekly pattern: dated rules, holidays and nth weekdays left out, as a
     * door plaque shows it. With [weekOf] it is that real week (Monday to Sunday), holidays and dates included.
     */
    fun weekTable(weekOf: LocalDate? = null, context: OpeningHoursContext = OpeningHoursContext.Default): List<List<OpenSpan>> {
        val eval = Evaluation(DateContext(context, generic = weekOf == null))
        val monday = (weekOf ?: GENERIC_MONDAY).with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
        return (0 until 7).map { d ->
            val paint = eval.paint(monday.plusDays(d.toLong()))
            runs(paint.state, paint.commentIndex, 0, 2880)
        }
    }

    override fun toString() = raw

    // ---------------------------------------------------------------------------------------------------- evaluation

    /** A day's own painting: a state code per minute from 00:00 to 48:00, and a comment index (0: none). */
    internal class Paint(val state: ByteArray, val commentIndex: ByteArray, val comments: List<String>) {
        fun comment(m: Int): String? = commentIndex[m].toInt().let { if (it == 0) null else comments[it - 1] }
    }

    private inner class Evaluation(val ctx: DateContext) {
        // a status walks forward day by day and needs only the latest few days
        private val paints = object : LinkedHashMap<LocalDate, Paint>(8, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<LocalDate, Paint>?) = size > 4
        }

        fun paint(date: LocalDate): Paint = paints.getOrPut(date) { paintDay(date) }

        /** [date] from midnight to midnight: its own hours, and the hours of the day before that run past midnight. */
        fun view(date: LocalDate): Paint {
            val own = paint(date)
            val prev = paint(date.minusDays(1))
            val st = ByteArray(1440)
            val cm = ByteArray(1440)
            for (m in 0 until 1440) {
                val o = own.state[m].toInt()
                val s = prev.state[m + 1440].toInt()
                if (s != 0 && (o == 0 || o and WEAK != 0)) {
                    st[m] = s.toByte(); cm[m] = prev.commentIndex[m + 1440]
                } else {
                    st[m] = o.toByte(); cm[m] = own.commentIndex[m]
                }
            }
            return Paint(st, cm, comments)
        }

        private fun paintDay(date: LocalDate): Paint {
            val st = ByteArray(2880)
            val cm = ByteArray(2880)
            for (rule in rules) {
                if (!rule.matchesDate(date, ctx)) continue
                val code = codeOf(rule.state)
                val ci = (rule.comment?.let { comments.indexOf(it) + 1 } ?: 0).toByte()
                val fallback = rule.kind == RuleKind.FALLBACK
                if (rule.kind == RuleKind.NORMAL && rule.hasDateSelector && rule.state != OpenState.CLOSED) {
                    st.fill(0); cm.fill(0)
                }
                fun fill(a: Int, b: Int, c: Int) {
                    val from = a.coerceIn(0, 2880)
                    val to = b.coerceIn(0, 2880)
                    for (m in from until to) {
                        if (fallback && st[m].toInt() != 0) continue
                        st[m] = (c or (if (fallback) WEAK else 0)).toByte(); cm[m] = ci
                    }
                }
                val times = rule.times
                when {
                    rule.alwaysOpen -> fill(0, 1440, code)
                    times == null -> {
                        // a whole day off ("Dec 25 off") also takes back that day's own hours past midnight
                        if (rule.state == OpenState.CLOSED && !fallback) {
                            st.fill(0, 1440, 2880); cm.fill(0, 1440, 2880)
                        }
                        fill(0, 1440, code or WEAK)
                    }
                    else -> for (span in times) {
                        val (a, b) = span.resolve(date, ctx.context.sun) ?: continue
                        if (span.openEnd && span.to == null) {
                            fill(a, b, code or OPEN_END)
                        } else {
                            fill(a, b, code)
                            if (span.openEnd) fill(b, b + TimeSpan.openEndGuess(b % 1440), code or OPEN_END)
                        }
                    }
                }
            }
            return Paint(st, cm, comments)
        }
    }

    private fun runs(state: ByteArray, commentIndex: ByteArray, from: Int, to: Int): List<OpenSpan> {
        val out = ArrayList<OpenSpan>()
        var m = from
        while (m < to) {
            val c = state[m].toInt()
            val s = stateOf(c)
            if (s == OpenState.CLOSED) { m++; continue }
            val start = m
            val key = c and (STATE_MASK or OPEN_END)
            val ci = commentIndex[m]
            while (m < to && (state[m].toInt() and (STATE_MASK or OPEN_END)) == key && commentIndex[m] == ci) m++
            val comment = ci.toInt().let { if (it == 0) null else comments[it - 1] }
            out += OpenSpan(start, m, s, comment, key and OPEN_END != 0)
        }
        return out
    }

    companion object {
        private const val STATE_MASK = 3
        private const val OPEN_END = 4
        private const val WEAK = 8
        private const val MAX_DAYS = 400
        private val GENERIC_MONDAY: LocalDate = LocalDate.of(2024, 1, 1)

        private fun codeOf(s: OpenState): Int = when (s) {
            OpenState.OPEN -> 1
            OpenState.CLOSED -> 2
            OpenState.UNKNOWN -> 3
        }

        private fun stateOf(code: Int): OpenState = when (code and STATE_MASK) {
            1 -> OpenState.OPEN
            3 -> OpenState.UNKNOWN
            else -> OpenState.CLOSED
        }

        /** Parses [text]; throws [OpeningHoursException] saying where it stopped making sense. */
        fun parse(text: String): OpeningHours {
            val rules = OpeningHoursParser(text.trim()).parse()
            if (rules.mapNotNull { it.comment }.distinct().size > 120) throw OpeningHoursException("too many comments", 0)
            return OpeningHours(text, rules)
        }

        fun parseOrNull(text: String?): OpeningHours? =
            if (text.isNullOrBlank()) null else try { parse(text) } catch (e: OpeningHoursException) { null }

        /** The status of a raw value; [OpenStatus.Unknown] when it cannot be read. */
        fun statusOf(text: String?, at: LocalDateTime, context: OpeningHoursContext = OpeningHoursContext.Default): OpenStatus =
            parseOrNull(text)?.status(at, context) ?: OpenStatus.Unknown
    }
}

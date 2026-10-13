package app.atlas.places

/** An opening_hours value that cannot be read; [index] is the character where reading stopped. */
class OpeningHoursException(message: String, val index: Int) : IllegalArgumentException(message)

/**
 * Reads the OSM opening_hours syntax (https://wiki.openstreetmap.org/wiki/Key:opening_hours/specification) into
 * rules. It accepts the common slips of real data too: any letter case, English, German and Dutch day and month
 * names, spaces around dashes, `08.30`, `8h30` and `9am-5pm`, a colon after the days, missing `;` between rules, a
 * stray trailing `;`, `Su PH` for `Su,PH`, `24/7` after other selectors (`Jun-Aug 24/7`), and typographic dashes and
 * quotes.
 */
internal class OpeningHoursParser(private val text: String) {
    private enum class T { NUM, WORD, COMMENT, SYM, END }

    private class Token(val type: T, val text: String, val at: Int, val space: Boolean) {
        val num: Int get() = text.toInt()
        override fun toString() = text
    }

    private val tokens = tidy(tokenize(text))
    private var p = 0

    fun parse(): List<Rule> {
        val rules = ArrayList<Rule>()
        var kind = RuleKind.NORMAL
        while (true) {
            while (isSym(";") || (rules.isEmpty() && isSym(","))) p++
            if (peek().type == T.END) break
            val before = p
            rules += rule(kind)
            if (p == before) fail("rule expected")
            val t = peek()
            kind = when {
                t.type == T.END -> break
                isSym(";") -> { p++; RuleKind.NORMAL }
                isSym("||") -> { p++; RuleKind.FALLBACK }
                isSym(",") -> { p++; RuleKind.ADDITIONAL }
                startsSelector(t) -> RuleKind.NORMAL // a missing ";" between two rules
                else -> fail("unexpected '${t.text}'")
            }
        }
        if (rules.isEmpty()) fail("empty opening hours")
        return rules
    }

    // ------------------------------------------------------------------------------------------------------- rules

    private fun rule(kind: RuleKind): Rule {
        var years: MutableList<YearRange>? = null
        var dates: MutableList<DateRange>? = null
        var weeks: MutableList<WeekRange>? = null
        var days: DaySelector? = null
        var times: List<TimeSpan>? = null
        var always = false
        if (isAlwaysAhead(0)) {
            p += 3
            always = true
        } else {
            // wide range selectors: years, then month-days, then weeks, in any order the data gives them
            while (true) {
                when {
                    isYearStart() -> (years ?: ArrayList<YearRange>().also { years = it }).addAll(yearRanges())
                    isDateStart() -> (dates ?: ArrayList<DateRange>().also { dates = it }).addAll(dateRanges())
                    isWord("week") -> { p++; (weeks ?: ArrayList<WeekRange>().also { weeks = it }).addAll(weekRanges()) }
                    else -> break
                }
            }
            if ((years != null || dates != null || weeks != null) && isSym(":")) p++
            days = daySelector()
            if (days != null && isSym(":") && (isTimeStart(1) || isAlwaysAhead(1))) p++
            if ((years != null || dates != null || weeks != null || days != null) && isAlwaysAhead(0)) {
                // "Jun-Aug 24/7", "Mo-Fr 24/7": all day on the selected days
                p += 3
                always = true
            } else {
                times = timeSelector()
            }
        }
        var state = OpenState.OPEN
        var stated = false
        var comment: String? = null
        when {
            isWord("open") -> { p++; stated = true }
            isWord("closed") || isWord("off") || isWord("geschlossen") || isWord("gesloten") -> { p++; state = OpenState.CLOSED; stated = true }
            isWord("unknown") -> { p++; state = OpenState.UNKNOWN; stated = true }
        }
        if (peek().type == T.COMMENT) comment = tokens[p++].text
        // a comment instead of times ("by appointment", Tu-Fr "on request") says nothing certain
        if (!stated && comment != null && times == null && !always) state = OpenState.UNKNOWN
        return Rule(kind, years, dates, weeks, days, times, state, comment, always)
    }

    /** `24/7` at [k] tokens ahead. */
    private fun isAlwaysAhead(k: Int): Boolean =
        peek(k).type == T.NUM && peek(k).text == "24" && isSym("/", k + 1) && peek(k + 2).type == T.NUM && peek(k + 2).text == "7"

    private fun startsSelector(t: Token): Boolean =
        weekday(t) != null || holiday(t) != null || month(t) != null || t.type == T.COMMENT ||
            (t.type == T.WORD && t.text == "week") || isTimeStartAt(p) || isYearStart()

    // ------------------------------------------------------------------------------------------------------ years

    private fun isYearStart(): Boolean {
        val t = peek()
        return t.type == T.NUM && t.text.length == 4 && t.num in 1900..2200 && month(peek(1)) == null && !isWord("easter", 1)
    }

    private fun yearRanges(): List<YearRange> {
        val out = ArrayList<YearRange>()
        do {
            val a = number(4)
            var b = a
            var step = 1
            if (isSym("+")) { p++; b = Int.MAX_VALUE } else if (isSym("-") && peek(1).type == T.NUM && peek(1).text.length == 4) {
                p++
                b = number(4)
                if (isSym("/")) { p++; step = number() }
            }
            if (b < a) fail("year range runs backwards")
            out += YearRange(a, b, step.coerceAtLeast(1))
        } while (isSym(",") && peek(1).type == T.NUM && peek(1).text.length == 4 && month(peek(2)) == null && advance())
        return out
    }

    // ----------------------------------------------------------------------------------------------- month-days

    private fun isDateStart(): Boolean {
        val t = peek()
        if (month(t) != null || isWord("easter")) return true
        return t.type == T.NUM && t.text.length == 4 && (month(peek(1)) != null || isWord("easter", 1))
    }

    private fun dateRanges(): List<DateRange> {
        val out = ArrayList<DateRange>()
        var lastMonth = 0
        var lastYear: Int? = null
        while (true) {
            val r = dateRange(lastMonth, lastYear)
            out += r.first
            lastMonth = r.second
            lastYear = r.third
            if (!isSym(",")) break
            val n = peek(1)
            val more = month(n) != null || (n.type == T.WORD && n.text == "easter") ||
                (n.type == T.NUM && n.text.length == 4 && month(peek(2)) != null) ||
                (n.type == T.NUM && n.text.length <= 2 && lastMonth != 0 && !isTimeStartAt(p + 1))
            if (!more) break
            p++
        }
        return out
    }

    /** One item of a month-day list; [prevMonth] serves a bare day after a comma ("Dec 25,26"). */
    private fun dateRange(prevMonth: Int, prevYear: Int?): Triple<DateRange, Int, Int?> {
        val from: DateSpec
        if (peek().type == T.NUM && peek().text.length <= 2 && prevMonth != 0) {
            from = DateSpec(prevYear, prevMonth, day())
        } else {
            val year = if (peek().type == T.NUM && peek().text.length == 4) number(4) else null
            if (isWord("easter")) {
                p++
                from = shifted(DateSpec(year, 0, 0, easter = true))
            } else {
                val m = month(peek()) ?: fail("month expected")
                p++
                if (!isDayNumber()) {
                    // a month range: "Jan-Mar", "Dec-Feb", "2026 Jan-2027 Mar"
                    var toYear: Int? = null
                    var to = m
                    if (isSym("-") && (month(peek(1)) != null || (peek(1).type == T.NUM && peek(1).text.length == 4 && month(peek(2)) != null))) {
                        p++
                        if (peek().type == T.NUM) toYear = number(4)
                        to = month(peek()) ?: fail("month expected")
                        p++
                        if (isDayNumber()) {
                            // "Jan-Mar 15" is not a month range but "Jan 01-Mar 15" written loosely: read as days
                            val d = day()
                            return Triple(DateRange.Days(DateSpec(year, m, 1), DateSpec(toYear, to, d), false), to, toYear ?: year)
                        }
                    }
                    return Triple(DateRange.Months(year, m, toYear, to), to, toYear ?: year)
                }
                from = shifted(DateSpec(year, m, day()))
            }
        }
        if (isSym("+") && !isShiftAhead()) {
            p++
            return Triple(DateRange.Days(from, null, true), from.month, from.year)
        }
        if (isSym("-") && !isShiftAhead() && isDateEndAhead()) {
            p++
            val toYear = if (peek().type == T.NUM && peek().text.length == 4) number(4) else null
            val to = when {
                isWord("easter") -> { p++; shifted(DateSpec(toYear, 0, 0, easter = true)) }
                month(peek()) != null -> {
                    val m = month(peek())!!
                    p++
                    shifted(DateSpec(toYear, m, day()))
                }
                else -> shifted(DateSpec(toYear ?: from.year, if (from.easter) fail("month expected") else from.month, day()))
            }
            return Triple(DateRange.Days(from, to, false), if (to.easter) from.month else to.month, to.year ?: from.year)
        }
        return Triple(DateRange.Days(from, null, false), from.month, from.year)
    }

    private fun isDayNumber(): Boolean {
        val t = peek()
        return t.type == T.NUM && t.text.length <= 2 && t.num in 1..31 && !isTimeStartAt(p) && !isAlwaysAhead(0)
    }

    private fun day(): Int {
        if (!isDayNumber()) fail("day of month expected")
        return tokens[p++].num
    }

    /** After "-": a day, a month, a year or easter, not a time. */
    private fun isDateEndAhead(): Boolean {
        val n = peek(1)
        return month(n) != null || (n.type == T.WORD && n.text == "easter") ||
            (n.type == T.NUM && n.text.length == 4 && (month(peek(2)) != null || (peek(2).type == T.WORD && peek(2).text == "easter"))) ||
            (n.type == T.NUM && n.text.length <= 2 && n.num in 1..31 && !isTimeStartAt(p + 1))
    }

    /** "+Su", "-Sa", "+2 days", "-1 day" after a date. */
    private fun isShiftAhead(): Boolean {
        if (!isSym("+") && !isSym("-")) return false
        val n = peek(1)
        if (weekday(n) != null && !n.space) return true
        return n.type == T.NUM && peek(2).type == T.WORD && (peek(2).text == "day" || peek(2).text == "days")
    }

    private fun shifted(base: DateSpec): DateSpec {
        var wd = 0
        var dd = 0
        while (isShiftAhead()) {
            val sign = if (tokens[p].text == "-") -1 else 1
            p++
            val w = weekday(peek())
            if (w != null) {
                p++
                wd = sign * (w + 1)
            } else {
                dd += sign * number()
                p++ // "day" or "days"
            }
        }
        return if (wd == 0 && dd == 0) base else DateSpec(base.year, base.month, base.day, base.easter, wd, dd)
    }

    // ------------------------------------------------------------------------------------------------------ weeks

    private fun weekRanges(): List<WeekRange> {
        val out = ArrayList<WeekRange>()
        do {
            val a = number()
            var b = a
            var step = 1
            if (isSym("-") && peek(1).type == T.NUM) {
                p++
                b = number()
                if (isSym("/")) { p++; step = number() }
            }
            if (a !in 1..53 || b !in 1..53) fail("week number out of range")
            out += WeekRange(a, b, step.coerceAtLeast(1))
        } while (isSym(",") && peek(1).type == T.NUM && !isTimeStartAt(p + 1) && advance())
        return out
    }

    // ---------------------------------------------------------------------------------------- weekdays, holidays

    private fun daySelector(): DaySelector? {
        val weekdays = ArrayList<WeekdayRange>()
        val holidays = ArrayList<HolidaySelector>()
        var both = false
        while (true) {
            val t = peek()
            val h = holiday(t)
            val w = weekday(t)
            if (h != null) {
                p++
                holidays += HolidaySelector(h, dayShift())
            } else if (w != null) {
                p++
                var to = w
                if (isSym("-") && weekday(peek(1)) != null) {
                    p++
                    to = weekday(peek())!!
                    p++
                }
                var nth: List<IntRange>? = null
                if (isSym("[")) {
                    p++
                    nth = nthList()
                    if (!isSym("]")) fail("']' expected")
                    p++
                }
                weekdays += WeekdayRange(w, to, nth, dayShift())
            } else {
                break
            }
            if (isSym(",") && (weekday(peek(1)) != null || holiday(peek(1)) != null)) {
                p++
                continue
            }
            // "SH Mo-Fr": holidays then weekdays with only a space between them must both hold
            if (holidays.isNotEmpty() && weekdays.isEmpty() && weekday(peek()) != null) {
                both = true
                continue
            }
            // weekdays then a holiday with only a space: "Su PH" is "Su,PH" with the comma left out, while "Mo-Fr SH"
            // is "SH Mo-Fr" written the other way round
            val next = holiday(peek())
            if (weekdays.isNotEmpty() && holidays.isEmpty() && next != null) {
                if (next) both = true
                continue
            }
            break
        }
        if (weekdays.isEmpty() && holidays.isEmpty()) return null
        return DaySelector(weekdays, holidays, both)
    }

    private fun nthList(): List<IntRange> {
        val out = ArrayList<IntRange>()
        do {
            val a = signedNumber()
            var b = a
            if (isSym("-") && peek(1).type == T.NUM && a > 0) {
                p++
                b = number()
            }
            if (a == 0 || b == 0 || kotlin.math.abs(a) > 5 || kotlin.math.abs(b) > 5) fail("weekday occurrence out of range")
            out += if (a <= b) a..b else b..a
        } while (isSym(",") && advance())
        return out
    }

    /** " +1 day" after a weekday or holiday. */
    private fun dayShift(): Int {
        if ((isSym("+") || isSym("-")) && peek(1).type == T.NUM && (isWord("day", 2) || isWord("days", 2))) {
            val sign = if (tokens[p].text == "-") -1 else 1
            p++
            val n = number()
            p++
            return sign * n
        }
        return 0
    }

    // ------------------------------------------------------------------------------------------------------ times

    private fun timeSelector(): List<TimeSpan>? {
        if (!isTimeStartAt(p)) return null
        val out = ArrayList<TimeSpan>()
        while (true) {
            out += timeSpan()
            if (isSym(",") && isTimeStartAt(p + 1)) { p++; continue }
            if (isTimeStartAt(p)) continue // "09:00-12:00 13:00-18:00"
            break
        }
        return out
    }

    private fun timeSpan(): TimeSpan {
        val from = time(extended = false)
        if (isSym("+") && !isTimeStartAt(p + 1)) {
            p++
            return TimeSpan(from, null, openEnd = true)
        }
        if (!isSym("-")) return TimeSpan(from, null, openEnd = false)
        p++
        val to = time(extended = true)
        var openEnd = false
        if (isSym("+") && !isTimeStartAt(p + 1)) { p++; openEnd = true }
        if (isSym("/")) {
            // a period between repeats ("/01:30" or "/90"): the span stays the span
            p++
            if (peek().type == T.NUM && isSym(":", 1)) time(extended = false) else number()
        }
        return TimeSpan(from, to, openEnd)
    }

    private fun time(extended: Boolean): TimePoint {
        val t = peek()
        if (t.type == T.SYM && t.text == "(") {
            p++
            val e = sunEvent(peek()) ?: fail("sunrise, sunset, dawn or dusk expected")
            p++
            val sign = when {
                isSym("+") -> 1
                isSym("-") -> -1
                else -> fail("'+' or '-' expected")
            }
            p++
            val off = clock(48)
            if (!isSym(")")) fail("')' expected")
            p++
            return TimePoint(0, e, sign * off)
        }
        sunEvent(t)?.let { p++; return TimePoint(0, it) }
        return TimePoint(clock(if (extended) 48 else 24))
    }

    /** "08:00", "8:00", "08.00", "8h30", "8h", "9am", "5:30 pm": minutes since midnight, at most [maxHours] hours. */
    private fun clock(maxHours: Int): Int {
        val h = peek()
        if (h.type != T.NUM || h.text.length > 2) fail("time expected")
        p++
        var m = 0
        if (isSym(":") || isSym(".")) {
            p++
            val mt = peek()
            if (mt.type != T.NUM || mt.text.length != 2) fail("minutes expected")
            m = mt.num
            p++
        } else if (isWord("h")) {
            p++
            if (peek().type == T.NUM && peek().text.length == 2 && !peek().space) m = tokens[p++].num
        } else if (!isMeridiem(peek())) {
            fail("':' expected")
        }
        if (isWord("h") && !peek().space) p++ // "18:00h"
        var hours = h.num
        if (isMeridiem(peek())) {
            // 12 am is midnight, 12 pm noon
            val pm = isWord("pm")
            p++
            if (hours !in 1..12) fail("hour out of range for am/pm")
            hours = hours % 12 + if (pm) 12 else 0
        }
        val total = hours * 60 + m
        if (m > 59 || total > maxHours * 60) fail("time out of range")
        return total
    }

    private fun isTimeStart(offset: Int): Boolean = isTimeStartAt(p + offset)

    private fun isTimeStartAt(i: Int): Boolean {
        val t = at(i)
        if (t.type == T.WORD && sunEvent(t) != null) return true
        if (t.type == T.SYM && t.text == "(" && sunEvent(at(i + 1)) != null) return true
        if (t.type != T.NUM || t.text.length > 2) return false
        val n = at(i + 1)
        if (n.type == T.SYM && (n.text == ":" || n.text == ".") && !n.space) return at(i + 2).type == T.NUM && at(i + 2).text.length == 2
        return (n.type == T.WORD && n.text == "h" && !n.space) || isMeridiem(n)
    }

    private fun isMeridiem(t: Token): Boolean = t.type == T.WORD && (t.text == "am" || t.text == "pm")

    // ------------------------------------------------------------------------------------------------ vocabulary

    private fun weekday(t: Token): Int? = if (t.type == T.WORD) WEEKDAYS[t.text] else null
    private fun holiday(t: Token): Boolean? = if (t.type != T.WORD) null else when (t.text) {
        "ph" -> false
        "sh" -> true
        else -> null
    }

    private fun month(t: Token): Int? = if (t.type == T.WORD) MONTHS[t.text] else null
    private fun sunEvent(t: Token): SunEvent? = if (t.type != T.WORD) null else when (t.text) {
        "sunrise" -> SunEvent.SUNRISE
        "sunset" -> SunEvent.SUNSET
        "dawn" -> SunEvent.DAWN
        "dusk" -> SunEvent.DUSK
        else -> null
    }

    // ---------------------------------------------------------------------------------------------------- helpers

    private fun at(i: Int): Token = if (i < tokens.size) tokens[i] else tokens.last()
    private fun peek(k: Int = 0): Token = at(p + k)
    private fun isSym(s: String, k: Int = 0): Boolean = peek(k).let { it.type == T.SYM && it.text == s }
    private fun isWord(w: String, k: Int = 0): Boolean = peek(k).let { it.type == T.WORD && it.text == w }
    private fun advance(): Boolean { p++; return true }

    private fun number(maxLength: Int = 3): Int {
        val t = peek()
        if (t.type != T.NUM || t.text.length > maxLength) fail("number expected")
        p++
        return t.num
    }

    private fun signedNumber(): Int {
        val neg = isSym("-")
        if (neg) p++
        val n = number()
        return if (neg) -n else n
    }

    private fun fail(message: String): Nothing {
        val t = peek()
        throw OpeningHoursException(if (t.type == T.END) "$message at the end" else "$message at '${t.text}'", t.at)
    }

    /** Drops what real data adds but the grammar does not have: "Mo." dots, a spoken "Uhr", a closing full stop. */
    private fun tidy(raw: List<Token>): List<Token> {
        val out = ArrayList<Token>(raw.size)
        for ((i, t) in raw.withIndex()) {
            if (t.type == T.SYM && t.text == "." && i > 0) {
                val prev = raw[i - 1]
                val next = raw[i + 1]
                if (prev.type == T.WORD && (WEEKDAYS.containsKey(prev.text) || MONTHS.containsKey(prev.text))) continue
                if (next.type == T.END) continue
            }
            if (t.type == T.WORD && t.text == "uhr" && i > 0 && raw[i - 1].type == T.NUM) continue
            out += t
        }
        return out
    }

    private fun tokenize(s: String): List<Token> {
        val out = ArrayList<Token>()
        var i = 0
        var space = false
        while (i < s.length) {
            val c = s[i]
            when {
                c.isWhitespace() -> { space = true; i++; continue }
                c == '"' || c == '“' || c == '”' || c == '„' -> {
                    val close = s.indexOfAny(charArrayOf('"', '”', '“'), i + 1)
                    if (close < 0) throw OpeningHoursException("unclosed comment", i)
                    out += Token(T.COMMENT, s.substring(i + 1, close), i, space)
                    i = close + 1
                }
                c in '0'..'9' -> {
                    val start = i
                    while (i < s.length && s[i] in '0'..'9') i++
                    out += Token(T.NUM, s.substring(start, i), start, space)
                }
                c.isLetter() -> {
                    val start = i
                    while (i < s.length && s[i].isLetter()) i++
                    out += Token(T.WORD, s.substring(start, i).lowercase(), start, space)
                }
                c == '|' && i + 1 < s.length && s[i + 1] == '|' -> { out += Token(T.SYM, "||", i, space); i += 2 }
                c in "-–—‐‑−" -> { out += Token(T.SYM, "-", i, space); i++ }
                c == '：' -> { out += Token(T.SYM, ":", i, space); i++ }
                c in ";,:+/[]()." -> { out += Token(T.SYM, c.toString(), i, space); i++ }
                c == '|' -> { out += Token(T.SYM, "||", i, space); i++ }
                else -> throw OpeningHoursException("unexpected '$c'", i)
            }
            space = false
        }
        out += Token(T.END, "", s.length, space)
        return out
    }

    companion object {
        private val WEEKDAYS: Map<String, Int> = buildMap {
            val names = listOf(
                "mo mon monday ma maandag montag",
                "tu tue tues tuesday di dinsdag dienstag",
                "we wed weds wednesday mi wo woensdag mittwoch",
                "th thu thur thurs thursday do donderdag donnerstag",
                "fr fri friday vr vrijdag freitag",
                "sa sat saturday za zaterdag samstag",
                "su sun sunday so zo zondag sonntag",
            )
            names.forEachIndexed { i, s -> s.split(' ').forEach { put(it, i) } }
        }
        private val MONTHS: Map<String, Int> = buildMap {
            val names = listOf(
                "jan january januar januari", "feb february februar februari", "mar march mär mrz märz maerz mrt maart",
                "apr april", "may mai mei", "jun june juni", "jul july juli", "aug august augustus",
                "sep sept september", "oct october okt oktober", "nov november", "dec december dez dezember",
            )
            names.forEachIndexed { i, s -> s.split(' ').forEach { put(it, i + 1) } }
        }
    }
}

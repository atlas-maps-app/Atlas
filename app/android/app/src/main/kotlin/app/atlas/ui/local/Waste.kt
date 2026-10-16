package app.atlas.ui.local

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.time.temporal.TemporalAdjusters
import java.util.Locale

/** What is collected: the streams of household waste, with the colour of their bins on the Collect board. */
enum class Stream(val colour: Long, val symbol: String) {
    REST(0xFF2B2F36, "bin.trash"),
    PAPER(0xFF2E7D4A, "bin.paper.2"),
    PACKAGING(0xFF1B5FA8, "bin.plastic"),
    GLASS(0xFF3A8FA8, "bin.recycling"),
    ORGANIC(0xFF8C5A2B, "bin.organic"),
    TEXTILE(0xFF8A4FB8, "bin.textile"),
    HAZARDOUS(0xFFF2B705, "bin.hazardous"),
    BULKY(0xFF6B45B8, "bin.bulky"),
    OTHER(0xFF676D7C, "bin.empty");

    /** True for the streams a calendar marks as a special day, not a bin out at the kerb. */
    val special: Boolean get() = this == HAZARDOUS || this == BULKY
}

/** A collection on a day: the streams, with the calendar's own words for them. */
class Pickup(val date: LocalDate, val streams: List<Stream>, val labels: List<String>)

/** A calendar read from an `.ics` file: its name and its events, ready to be unrolled into days. */
class WasteCalendar(val name: String?, private val events: List<Event>) {
    /** One calendar entry: a first day, how it repeats, the days it skips. */
    class Event(val summary: String, val stream: Stream, val first: LocalDate, val rule: Rule?, val skip: Set<LocalDate>, val extra: Set<LocalDate>)

    /** An iCalendar RRULE, the parts a waste calendar uses. */
    class Rule(
        val freq: String,
        val interval: Int,
        val count: Int?,
        val until: LocalDate?,
        val weekdays: List<Pair<Int?, DayOfWeek>>,
        val monthDay: Int?,
        val months: List<Int>,
    )

    val size: Int get() = events.size

    /** The collections from [from] to [to], both included, one entry a day, in date order. */
    fun pickups(from: LocalDate, to: LocalDate): List<Pickup> {
        val byDay = java.util.TreeMap<LocalDate, MutableList<Event>>()
        for (e in events) for (d in occurrences(e, from, to)) byDay.getOrPut(d) { ArrayList() } += e
        return byDay.map { (d, list) -> Pickup(d, list.map { it.stream }.distinct(), list.map { it.summary }) }
    }

    /** The next collection on or after [day], within a year; null when the calendar has none. */
    fun next(day: LocalDate): Pickup? = pickups(day, day.plusDays(400)).firstOrNull()

    private fun occurrences(e: Event, from: LocalDate, to: LocalDate): List<LocalDate> {
        val out = ArrayList<LocalDate>()
        val rule = e.rule
        if (rule == null) {
            if (e.first in from..to) out += e.first
        } else {
            var emitted = 0
            val limit = rule.until ?: to
            fun add(d: LocalDate): Boolean {
                if (d.isBefore(e.first)) return true
                if (rule.count != null && emitted >= rule.count) return false
                if (d.isAfter(limit)) return false
                emitted++
                if (d !in e.skip && d in from..to) out += d
                return !d.isAfter(to)
            }
            when (rule.freq) {
                "DAILY" -> {
                    var d = e.first
                    // A calendar that began years ago: start at the day just before the window, not at the first day.
                    if (rule.count == null && d.isBefore(from)) d = d.plusDays(ChronoUnit.DAYS.between(d, from) / rule.interval * rule.interval)
                    var guard = 0
                    while (guard++ < 5000 && add(d)) d = d.plusDays(rule.interval.toLong())
                }
                "WEEKLY" -> {
                    val days = rule.weekdays.map { it.second }.ifEmpty { listOf(e.first.dayOfWeek) }.sortedBy { it.value }
                    var weekStart = e.first.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
                    var guard = 0
                    var go = true
                    while (go && guard++ < 3000) {
                        for (dow in days) if (go) go = add(weekStart.plusDays((dow.value - 1).toLong()))
                        weekStart = weekStart.plusWeeks(rule.interval.toLong())
                    }
                }
                "MONTHLY" -> {
                    var month = e.first.withDayOfMonth(1)
                    var guard = 0
                    var go = true
                    while (go && guard++ < 1500) {
                        val days = monthlyDays(month, rule, e.first)
                        for (d in days) if (go) go = add(d)
                        month = month.plusMonths(rule.interval.toLong())
                        if (month.isAfter(limit.plusMonths(1))) go = false
                    }
                }
                "YEARLY" -> {
                    var year = e.first.year
                    var guard = 0
                    var go = true
                    while (go && guard++ < 200) {
                        val months = rule.months.ifEmpty { listOf(e.first.monthValue) }
                        for (m in months) {
                            val first = LocalDate.of(year, m, 1)
                            for (d in monthlyDays(first, rule, e.first)) if (go) go = add(d)
                        }
                        year += rule.interval
                        if (year > limit.year + 1) go = false
                    }
                }
            }
        }
        for (d in e.extra) if (d in from..to && d !in e.skip) out += d
        return out.distinct()
    }

    private fun monthlyDays(month: LocalDate, rule: Rule, first: LocalDate): List<LocalDate> {
        val out = ArrayList<LocalDate>()
        if (rule.weekdays.isNotEmpty()) {
            for ((nth, dow) in rule.weekdays) {
                if (nth == null) {
                    var d = month.with(TemporalAdjusters.firstInMonth(dow))
                    while (d.month == month.month) { out += d; d = d.plusWeeks(1) }
                } else if (nth > 0) {
                    val d = month.with(TemporalAdjusters.dayOfWeekInMonth(nth, dow))
                    if (d.month == month.month) out += d
                } else {
                    val d = month.with(TemporalAdjusters.lastInMonth(dow)).minusWeeks((-nth - 1).toLong())
                    if (d.month == month.month) out += d
                }
            }
        } else {
            val day = rule.monthDay ?: first.dayOfMonth
            if (day > 0 && day <= month.lengthOfMonth()) out += month.withDayOfMonth(day)
            else if (day < 0 && -day <= month.lengthOfMonth()) out += month.withDayOfMonth(month.lengthOfMonth() + day + 1)
        }
        return out.sorted()
    }

    companion object {
        /** Reads an iCalendar text. Null when it holds no events at all. */
        fun parse(text: String, zone: ZoneId = ZoneOffset.UTC): WasteCalendar? {
            val lines = unfold(text)
            var name: String? = null
            val events = ArrayList<Event>()
            var i = 0
            while (i < lines.size) {
                val line = lines[i]
                if (line.startsWith("X-WR-CALNAME", ignoreCase = true)) name = line.substringAfter(':').trim().ifEmpty { null }
                if (line.equals("BEGIN:VEVENT", ignoreCase = true)) {
                    val body = ArrayList<String>()
                    i++
                    while (i < lines.size && !lines[i].equals("END:VEVENT", ignoreCase = true)) { body += lines[i]; i++ }
                    event(body, zone)?.let { events += it }
                }
                i++
            }
            return if (events.isEmpty()) null else WasteCalendar(name, events)
        }

        private fun unfold(text: String): List<String> {
            val out = ArrayList<String>()
            for (raw in text.replace("\r\n", "\n").replace('\r', '\n').split('\n')) {
                if ((raw.startsWith(" ") || raw.startsWith("\t")) && out.isNotEmpty()) out[out.lastIndex] = out.last() + raw.substring(1)
                else if (raw.isNotEmpty()) out += raw
            }
            return out
        }

        private fun prop(line: String): Triple<String, Map<String, String>, String>? {
            val colon = line.indexOf(':')
            if (colon < 0) return null
            val head = line.substring(0, colon).split(';')
            val params = head.drop(1).mapNotNull { p -> p.indexOf('=').takeIf { it > 0 }?.let { p.substring(0, it).uppercase() to p.substring(it + 1) } }.toMap()
            return Triple(head[0].uppercase(), params, line.substring(colon + 1))
        }

        private fun event(body: List<String>, zone: ZoneId): Event? {
            var summary = ""
            var first: LocalDate? = null
            var rule: Rule? = null
            val skip = HashSet<LocalDate>()
            val extra = HashSet<LocalDate>()
            for (line in body) {
                val (name, params, value) = prop(line) ?: continue
                when (name) {
                    "SUMMARY" -> summary = value.replace("\\,", ",").replace("\\;", ";").replace("\\n", " ").trim()
                    "DTSTART" -> first = date(value, params, zone)
                    "EXDATE" -> value.split(',').forEach { v -> date(v.trim(), params, zone)?.let { skip += it } }
                    "RDATE" -> value.split(',').forEach { v -> date(v.trim(), params, zone)?.let { extra += it } }
                    "RRULE" -> rule = rule(value)
                }
            }
            val day = first ?: return null
            if (summary.isEmpty()) return null
            return Event(summary, classify(summary), day, rule, skip, extra)
        }

        private val DATE = DateTimeFormatter.ofPattern("yyyyMMdd")

        private fun date(value: String, params: Map<String, String>, zone: ZoneId): LocalDate? = try {
            val v = value.trim()
            when {
                v.length == 8 -> LocalDate.parse(v, DATE)
                v.endsWith("Z") -> LocalDateTime.parse(v.dropLast(1), DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss")).atOffset(ZoneOffset.UTC).atZoneSameInstant(zone).toLocalDate()
                v.length >= 15 -> LocalDate.parse(v.substring(0, 8), DATE)
                else -> null
            }
        } catch (e: java.time.format.DateTimeParseException) {
            null
        }

        private fun rule(value: String): Rule? {
            val parts = value.split(';').mapNotNull { p -> p.indexOf('=').takeIf { it > 0 }?.let { p.substring(0, it).uppercase() to p.substring(it + 1) } }.toMap()
            val freq = parts["FREQ"]?.uppercase() ?: return null
            if (freq !in setOf("DAILY", "WEEKLY", "MONTHLY", "YEARLY")) return null
            val days = parts["BYDAY"]?.split(',')?.mapNotNull { d ->
                val m = Regex("""^([+-]?\d+)?(MO|TU|WE|TH|FR|SA|SU)$""").matchEntire(d.trim().uppercase()) ?: return@mapNotNull null
                m.groupValues[1].toIntOrNull() to DayOfWeek.of(listOf("MO", "TU", "WE", "TH", "FR", "SA", "SU").indexOf(m.groupValues[2]) + 1)
            }.orEmpty()
            return Rule(
                freq, parts["INTERVAL"]?.toIntOrNull()?.coerceAtLeast(1) ?: 1, parts["COUNT"]?.toIntOrNull(),
                parts["UNTIL"]?.let { date(it, emptyMap(), ZoneOffset.UTC) }, days, parts["BYMONTHDAY"]?.split(',')?.firstOrNull()?.toIntOrNull(),
                parts["BYMONTH"]?.split(',')?.mapNotNull { it.toIntOrNull() }.orEmpty(),
            )
        }

        private val WORDS: List<Pair<Stream, List<String>>> = listOf(
            Stream.HAZARDOUS to listOf("kca", "chemo", "chemisch", "hazard", "gevaarlijk", "sonderabfall", "schadstoff", "toxi", "gift", "klein chemisch", "paint", "batter"),
            Stream.BULKY to listOf("grof", "sperr", "encombrant", "bulky", "bulk"),
            Stream.TEXTILE to listOf("textiel", "textil", "textile", "kleding", "clothes", "altkleider"),
            Stream.GLASS to listOf("glas", "glass", "verre"),
            Stream.ORGANIC to listOf("gft", "groente", "organic", "organisch", "bio", "compost", "tuinafval", "garden", "green waste", "déchets verts", "dechets verts", "biologisch", "keukenafval", "food"),
            Stream.PAPER to listOf("papier", "paper", "karton", "carton", "pappe"),
            Stream.PACKAGING to listOf("pmd", "plastic", "verpakking", "kunststof", "metaal", "blik", "gelbe", "gele ", "packag", "recycl", "emballage", "jaune", "wertstoff", "tri"),
            Stream.REST to listOf("rest", "grijs", "grey", "gray", "black", "residual", "general", "household", "trash", "garbage", "refuse", "landfill", "schwarz", "ordures", "ménag", "menag", "gris", "huisvuil", "rubbish"),
        )

        /** The stream a calendar entry is about, from the words of its title in Dutch, German, French and English. */
        fun classify(summary: String): Stream {
            val s = summary.lowercase(Locale.ROOT)
            return WORDS.firstOrNull { (_, words) -> words.any { mentions(s, it) } }?.first ?: Stream.OTHER
        }

        /** True when [word] is in [text]; a short one only at the start of a word ("tri" is a sorting in French, not the end of "district"). */
        private fun mentions(text: String, word: String): Boolean {
            var i = text.indexOf(word)
            while (i >= 0) {
                if (word.length > 4 || i == 0 || !text[i - 1].isLetter()) return true
                i = text.indexOf(word, i + 1)
            }
            return false
        }

        /** Days between two dates, as the calendar counts them. */
        fun daysBetween(a: LocalDate, b: LocalDate): Long = ChronoUnit.DAYS.between(a, b)
    }
}

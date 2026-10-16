package app.atlas.ui.local

import app.atlas.geo.LatLon
import java.time.LocalDate

/** The seasonal calendars' regions: each has its own table of when foods are in season. */
enum class FoodRegion(val country: String?, val group: Group) {
    NL("NL", Group.NW), DE("DE", Group.NW), FR("FR", Group.FR), GB("GB", Group.GB), US("US", Group.US), OTHER(null, Group.NW);

    /** Which column of the food table a region reads. GB and the rest of the north-west share one. */
    enum class Group { NW, FR, GB, US }

    companion object {
        /** The region a place is in, by the rough outlines of the five countries the calendar is written for. */
        fun of(at: LatLon): FoodRegion = when {
            at.lat in 50.7..53.6 && at.lon in 3.3..7.3 -> NL
            at.lat in 47.2..55.1 && at.lon in 5.8..15.1 -> DE
            at.lat in 41.3..51.2 && at.lon in -5.3..9.7 -> FR
            at.lat in 49.8..60.9 && at.lon in -8.7..1.8 -> GB
            at.lat in 24.4..49.5 && at.lon in -125.0..-66.9 -> US
            else -> OTHER
        }
    }
}

/** A food's season: [start] to [end] (months 1-12, wrapping the year when end is before start) and its peak. */
data class Span(val start: Int, val end: Int, val peakStart: Int, val peakEnd: Int) {
    private fun within(m: Int, s: Int, e: Int) = if (s <= e) m in s..e else m >= s || m <= e

    fun inSeason(month: Int) = within(month, start, end)
    fun atPeak(month: Int) = inSeason(month) && within(month, peakStart, peakEnd)

    /** The season's first month: "new". */
    fun isNew(month: Int) = month == start

    /** The season's last month: the last chance. */
    fun isLast(month: Int) = month == end && inSeason(month)

    /** Months until the season starts (0 when it is on now), counting forward through the year. */
    fun monthsUntilStart(month: Int): Int = if (inSeason(month)) 0 else (start - month + 12) % 12

    /** The season as positions on the year: start of [start] to end of [end], in months from 1 January, end after start. */
    val arc: Pair<Double, Double>
        get() {
            val a = (start - 1).toDouble()
            var b = end.toDouble()
            if (b <= a) b += 12.0
            return a to b
        }

    companion object {
        /** Reads "4-6/5-6"; null for "-" (not here). */
        fun parse(text: String): Span? {
            if (text == "-") return null
            val m = Regex("""^(\d{1,2})-(\d{1,2})/(\d{1,2})-(\d{1,2})$""").find(text.trim()) ?: return null
            val (s, e, ps, pe) = m.destructured
            val months = listOf(s, e, ps, pe).map { it.toInt() }
            return if (months.all { it in 1..12 }) Span(months[0], months[1], months[2], months[3]) else null
        }
    }

    /** The same season six months later: the southern hemisphere's calendar. */
    fun flipped(): Span = Span((start + 5) % 12 + 1, (end + 5) % 12 + 1, (peakStart + 5) % 12 + 1, (peakEnd + 5) % 12 + 1)
}

/** A food in a region's calendar. [glyph] is a symbol or a drawing of this package, [colour] its ARGB colour. */
data class FoodItem(val id: String, val glyph: String, val colour: Long, val span: Span)

/** What is in season, now and soon, for one region and date. */
class SeasonNow(
    val foods: List<FoodItem>,
    val month: Int,
    /** In season and at its peak or new this month, peak first. */
    val best: List<FoodItem>,
    /** In season for the last month. */
    val last: List<FoodItem>,
    /** Out of season now, starting within two months. */
    val coming: List<FoodItem>,
    /** The foods the wheel shows, outermost ring first: what is best, what ends, what comes, then the far side of the year. */
    val wheel: List<FoodItem>,
)

/** The seasonal calendar: ~60 foods, for the Netherlands, Germany, France, Great Britain and the United States. */
object FoodCalendar {
    private class Row(val id: String, val glyph: String, val colour: Long, val nw: Span?, val fr: Span?, val us: Span?)

    private val rows: List<Row> by lazy {
        FoodData.TABLE.trim().lines().filter { it.isNotBlank() }.map { line ->
            val c = line.split('|')
            val nw = Span.parse(c[3])
            Row(c[0], c[1], 0xFF000000L or c[2].toLong(16), nw, if (c[4].isEmpty()) nw else Span.parse(c[4]), if (c[5].isEmpty()) nw else Span.parse(c[5]))
        }
    }

    /** Every food that has a season in [region], the southern hemisphere's six months later. */
    fun foods(region: FoodRegion, southern: Boolean = false): List<FoodItem> = rows.mapNotNull { r ->
        val span = when (region.group) {
            FoodRegion.Group.FR -> r.fr
            FoodRegion.Group.US -> r.us
            else -> r.nw
        } ?: return@mapNotNull null
        FoodItem(r.id, r.glyph, r.colour, if (southern) span.flipped() else span)
    }

    fun now(region: FoodRegion, date: LocalDate, southern: Boolean = false): SeasonNow {
        val foods = foods(region, southern)
        val m = date.monthValue
        val inSeason = foods.filter { it.span.inSeason(m) }
        val best = inSeason.filter { it.span.atPeak(m) || it.span.isNew(m) }.sortedWith(compareByDescending<FoodItem> { it.span.atPeak(m) }.thenBy { priority(it.id) })
        val last = inSeason.filter { it.span.isLast(m) && it !in best }.sortedBy { priority(it.id) }
        val coming = foods.filter { !it.span.inSeason(m) && it.span.monthsUntilStart(m) in 1..2 }.sortedWith(compareBy<FoodItem> { it.span.monthsUntilStart(m) }.thenBy { priority(it.id) })
        val wheel = LinkedHashSet<FoodItem>()
        wheel += best.take(5)
        wheel += last.take(2)
        wheel += coming.take(2)
        // The far side of the year, so the wheel shows the whole year: the foods whose peak is furthest from now.
        val far = foods.filter { it !in wheel }.sortedByDescending { distance(it.span, m) }
        for (f in far) { if (wheel.size >= 8) break; wheel += f }
        return SeasonNow(foods, m, best, last, coming, wheel.take(8))
    }

    /** The foods people look for first, in the order they are listed when several are at their best. */
    private val PRIORITY = listOf(
        "strawberries", "asparagus", "cherries", "apples", "pumpkin", "mushrooms", "chestnuts", "pears", "plums", "tomatoes", "raspberries",
        "figs", "peaches", "citrus", "kale", "parsnips", "grapes", "blueberries", "sweetcorn", "courgette", "mussels", "oysters", "herring",
    )

    private fun priority(id: String): Int = PRIORITY.indexOf(id).let { if (it < 0) PRIORITY.size else it }

    /** How far the middle of a season's peak is from [month], in months round the year. */
    private fun distance(span: Span, month: Int): Int {
        val mid = (span.peakStart + (if (span.peakEnd >= span.peakStart) span.peakEnd else span.peakEnd + 12)) / 2
        val d = Math.floorMod(mid - month, 12)
        return minOf(d, 12 - d)
    }

    /** The first day of a food's next season: the first of its start month after [today]. */
    fun nextStart(span: Span, today: LocalDate): LocalDate {
        var d = LocalDate.of(today.year, span.start, 1)
        while (!d.isAfter(today)) d = d.plusYears(1)
        return d
    }
}

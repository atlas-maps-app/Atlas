package app.atlas.ui.local

import app.atlas.nav.Dest
import java.time.LocalDate

/** Where an activity of the year stands on a date. */
enum class Phase { LAST, PEAK, ON, SOON }

/** One thing the year offers to do: swimming, leaves turning, mushrooms, the birds' migration, stars, skiing, blossom, festivals. */
class Activity(val id: String, val letter: String, val colour: Long, private val nw: Span, private val fr: Span, private val us: Span, val dest: Dest?) {
    fun span(group: FoodRegion.Group, southern: Boolean): Span {
        val s = when (group) { FoodRegion.Group.FR -> fr; FoodRegion.Group.US -> us; else -> nw }
        return if (southern) s.flipped() else s
    }
}

/** An activity with its season for a region, and where it stands on a date. */
class ActivityNow(val activity: Activity, val span: Span, val phase: Phase?, val monthsToStart: Int)

/** The year's calendar of things to do: general seasons for the northern temperate belt, a little earlier or later in France and the US. */
object YearCalendar {
    val all: List<Activity> = listOf(
        Activity("swim", "S", 0xFF2E9CDB, Span(6, 9, 7, 8), Span(6, 9, 7, 8), Span(6, 9, 7, 8), Dest.Swim()),
        Activity("foliage", "F", 0xFFE8762C, Span(9, 11, 10, 10), Span(9, 11, 10, 10), Span(9, 11, 10, 10), Dest.Nature("trees")),
        Activity("mushrooms", "M", 0xFF8C6A4A, Span(8, 11, 9, 10), Span(8, 11, 9, 10), Span(8, 11, 9, 10), Dest.Season),
        Activity("birds", "B", 0xFF5A7ACA, Span(9, 11, 10, 10), Span(9, 11, 10, 10), Span(8, 11, 9, 10), Dest.Nature("birds")),
        Activity("stars", "S", 0xFF14203A, Span(9, 3, 11, 1), Span(9, 3, 11, 1), Span(9, 3, 11, 1), Dest.Sky),
        Activity("ski", "S", 0xFF9FB6E8, Span(12, 3, 1, 2), Span(12, 4, 1, 3), Span(12, 3, 1, 2), null),
        Activity("blossom", "B", 0xFFE4579A, Span(3, 5, 4, 4), Span(3, 4, 3, 4), Span(3, 5, 4, 4), Dest.Nature("trees")),
        Activity("festivals", "F", 0xFF7B4DFF, Span(5, 9, 6, 8), Span(5, 9, 6, 8), Span(5, 9, 6, 8), null),
    )

    fun now(region: FoodRegion, date: LocalDate, southern: Boolean = false): List<ActivityNow> = all.map { a ->
        val span = a.span(region.group, southern)
        val m = date.monthValue
        val phase = when {
            span.inSeason(m) && span.isLast(m) -> Phase.LAST
            span.atPeak(m) -> Phase.PEAK
            span.inSeason(m) -> Phase.ON
            span.monthsUntilStart(m) in 1..3 -> Phase.SOON
            else -> null
        }
        ActivityNow(a, span, phase, span.monthsUntilStart(m))
    }

    /** The up-to-four cards of "right now and next": what is ending, what is at its peak, what is on, then what comes. */
    fun highlights(now: List<ActivityNow>): List<ActivityNow> {
        val order = compareBy<ActivityNow>({ when (it.phase) { Phase.LAST -> 0; Phase.PEAK -> 1; Phase.ON -> 2; Phase.SOON -> 3; null -> 9 } }, { it.monthsToStart })
        return now.filter { it.phase != null }.sortedWith(order).take(4)
    }

    /** "Late Sep": which third of the month a date is in. */
    fun third(date: LocalDate): Int = ((date.dayOfMonth - 1) * 3) / date.lengthOfMonth()
}

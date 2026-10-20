package app.atlas.ui.eat

import app.atlas.places.OpenState
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.temporal.TemporalAdjusters

/** A price tag on the market's string, the Market board's eight: a kind of stall or fresh food shop. */
enum class Stall {
    PRODUCE, BUTCHER, FISH, CHEESE, DELI, BREAD, WINE, FARM;

    fun has(p: EatPlace): Boolean = when (this) {
        PRODUCE -> p.kind == EatKind.GREENGROCER
        BUTCHER -> p.kind == EatKind.BUTCHER
        FISH -> p.kind == EatKind.FISH
        CHEESE -> p.kind == EatKind.CHEESE
        DELI -> p.kind == EatKind.DELI
        BREAD -> p.kind == EatKind.BAKERY
        WINE -> p.kind == EatKind.WINE
        FARM -> p.kind == EatKind.FARM
    }
}

/** The checkboxes. */
enum class MarketCheck {
    ORGANIC, SUNDAY, JAR, CARD;

    /** [sunday] is the coming Sunday's noon. */
    fun has(p: EatPlace, sunday: LocalDateTime, area: EatArea): Boolean = when (this) {
        ORGANIC -> p.tags["organic"]?.let { it == "yes" || it == "only" } == true
        SUNDAY -> p.openness(sunday, area.hours).isOpen
        JAR -> p.yes("zero_waste") || p.yes("bulk_purchase") || p.tags["reusable_packaging:accept"] == "yes"
        CARD -> p.takesCards
    }
}

/** A market on one day: when it is on, and what it is called that day (`open "Biologische Boerenmarkt"`). */
data class MarketSession(val place: EatPlace, val from: LocalTime, val to: LocalTime, val what: String?)

/** One day of the week strip: its date, whether a market is on, and the sessions. */
data class MarketDay(val date: LocalDate, val sessions: List<MarketSession>)

/** A row of the list: a stall, open or not at the time, and when it next opens if closed. */
data class StallRow(val place: EatPlace, val openness: Openness, val opens: LocalDateTime?)

/** What the market screen shows. */
data class MarketBoard(
    val count: Int,
    val countsOpen: Boolean,
    val stalls: Map<Stall, Int>,
    val week: List<MarketDay>,
    val checks: Map<MarketCheck, Int>,
    val rows: List<StallRow>,
)

/** Markets and fresh food: stalls and shops by kind, the week's market days from `opening_hours`, and the list. */
object MarketModel {
    fun board(area: EatArea, at: LocalDateTime, stall: Stall?, checks: Set<MarketCheck>): MarketBoard {
        val all = EatSets.market(area.places)
        val countsOpen = all.any { it.hasHours }
        val shops = all.filter { it.kind != EatKind.MARKETPLACE }
        val ofStall = if (stall == null) all else all.filter { stall.has(it) }
        val sunday = at.toLocalDate().with(TemporalAdjusters.nextOrSame(DayOfWeek.SUNDAY)).atTime(12, 0)
        val chosen = ofStall.filter { p -> checks.all { it.has(p, sunday, area) } }
        val rank = { o: Openness -> if (o.isOpen) 0 else if (o == Openness.UNKNOWN) 1 else 2 }
        val rows = chosen.map { p -> StallRow(p, p.openness(at, area.hours), p.opensAt(at, area.hours)) }
            .sortedWith(compareBy<StallRow> { rank(it.openness) }.thenBy { it.place.distanceM })
        return MarketBoard(
            count = if (countsOpen) all.count { it.openness(at, area.hours).isOpen } else all.size,
            countsOpen = countsOpen,
            stalls = Stall.entries.associateWith { s -> shops.count { s.has(it) } },
            week = week(all.filter { it.kind == EatKind.MARKETPLACE }, area, at.toLocalDate()),
            checks = MarketCheck.entries.associateWith { c -> ofStall.count { c.has(it, sunday, area) } },
            rows = rows,
        )
    }

    /** Monday to Sunday of the week of [day]: the markets on each day, from their real week's opening hours. */
    fun week(markets: List<EatPlace>, area: EatArea, day: LocalDate): List<MarketDay> {
        val monday = day.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
        val byDay = List(7) { ArrayList<MarketSession>() }
        for (m in markets) {
            val table = m.hours?.weekTable(monday, area.hours) ?: continue
            table.forEachIndexed { d, spans ->
                for (s in spans) if (s.state == OpenState.OPEN) byDay[d] += MarketSession(m, s.startTime, s.endTime, s.comment)
            }
        }
        return (0 until 7).map { d -> MarketDay(monday.plusDays(d.toLong()), byDay[d].sortedBy { it.from }.distinctBy { it.place.name to it.from }) }
    }
}

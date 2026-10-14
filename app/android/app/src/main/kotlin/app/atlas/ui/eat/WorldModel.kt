package app.atlas.ui.eat

import java.time.LocalDateTime
import kotlin.math.abs
import kotlin.math.roundToInt

/** The round toggles over a cuisine world's machine. */
enum class WorldToggle(val symbol: String) {
    LATE("ui-moon"), VEGAN("ui-leaf"), TAKEAWAY("ui-shopping-bag"), CARD("ui-card"), STEP_FREE("ui-wheelchair");

    /** True when [p] has it; late means still open at 23:00 on the night of [at]. */
    fun has(p: EatPlace, at: LocalDateTime, area: EatArea): Boolean = when (this) {
        LATE -> p.openness(EatSets.nightOf(at).atTime(23, 0), area.hours).isOpen
        VEGAN -> p.diet("vegan")
        TAKEAWAY -> p.takeaway
        CARD -> p.takesCards
        STEP_FREE -> p.stepFree
    }
}

/**
 * One button of the ticket machine: a shop, its state at the machine's time (the LED), when it closes or opens,
 * its rating as 0–5 dots if anyone rated it, and its marks.
 */
data class MachineButton(
    val place: EatPlace,
    val openness: Openness,
    val closes: LocalDateTime?,
    val opens: LocalDateTime?,
    /** Whole dots of five, rounded from the stars; null when nobody rated it. */
    val dots: Int?,
    val vegan: Boolean,
    val cashOnly: Boolean,
)

/** The machine for one plank of a world: counts per plank, the buttons, and how many are open. */
data class Machine(
    val world: CuisineWorld,
    val plank: Plank,
    val plankCounts: Map<String, Int>,
    val buttons: List<MachineButton>,
    val open: Int,
    val countsOpen: Boolean,
    val toggleCounts: Map<WorldToggle, Int>,
)

/** A printed meal ticket: the shop, its number, closing time, how it takes money and the walk. */
data class MealTicket(val number: String, val button: MachineButton, val walkMinutes: Int, val pays: Pays) {
    enum class Pays { CASH_ONLY, CARD, CASH, UNKNOWN }
}

/** A cuisine world's ticket machine, from real tags, opening hours and ratings. */
object WorldModel {
    /** The plank [requested] (an index, or -1 for "the one with most places"), within [world]'s planks. */
    fun plankIndex(world: CuisineWorld, area: EatArea, requested: Int): Int {
        if (requested in world.planks.indices) return requested
        val counts = world.planks.map { world.places(area, it).size }
        return counts.indices.maxByOrNull { counts[it] } ?: 0
    }

    fun machine(world: CuisineWorld, plankIndex: Int, area: EatArea, at: LocalDateTime, toggles: Set<WorldToggle>, ratings: AreaRatings): Machine {
        val plank = world.planks[plankIndex.coerceIn(world.planks.indices)]
        val ofPlank = world.places(area, plank)
        val chosen = ofPlank.filter { p -> toggles.all { it.has(p, at, area) } }
        val rank = { o: Openness -> when (o) { Openness.OPEN -> 0; Openness.CLOSING_SOON -> 1; Openness.UNKNOWN -> 2; Openness.CLOSED -> 3 } }
        val buttons = chosen.map { p ->
            val o = p.openness(at, area.hours)
            MachineButton(
                place = p,
                openness = o,
                closes = p.closesAt(at, area.hours),
                opens = p.opensAt(at, area.hours),
                dots = ratings.stars(p.key)?.let { it.roundToInt().coerceIn(0, 5) },
                vegan = p.diet("vegan"),
                cashOnly = p.cashOnly,
            )
        }.sortedWith(compareBy<MachineButton> { rank(it.openness) }.thenBy { it.place.distanceM })
        val countsOpen = !area.sketchOnly && (ofPlank.any { it.hasHours } || (ofPlank.isEmpty() && area.places.any { it.hasHours }))
        return Machine(
            world = world,
            plank = plank,
            plankCounts = world.planks.associate { it.id to world.places(area, it).size },
            buttons = buttons,
            open = if (countsOpen) ofPlank.count { it.openness(at, area.hours).isOpen } else ofPlank.size,
            countsOpen = countsOpen,
            toggleCounts = WorldToggle.entries.associateWith { t -> ofPlank.count { t.has(it, at, area) } },
        )
    }

    /** The ticket the machine prints for [b] at [at]: a number of four figures that stays the same for the day. */
    fun ticket(b: MachineButton, at: LocalDateTime): MealTicket {
        val n = abs((b.place.key + at.toLocalDate()).hashCode()) % 10_000
        val pays = when {
            b.cashOnly -> MealTicket.Pays.CASH_ONLY
            b.place.takesCards -> MealTicket.Pays.CARD
            b.place.takesCash -> MealTicket.Pays.CASH
            else -> MealTicket.Pays.UNKNOWN
        }
        return MealTicket(String.format(java.util.Locale.ROOT, "%04d", n), b, b.place.walkMinutes, pays)
    }
}

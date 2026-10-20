package app.atlas.ui.eat

import java.time.LocalDateTime

/**
 * A neon tile of the late-night wall, the Street board's nine: a kind of late bite, the cuisines it gathers, its tube
 * colour and its glow (the board's `stroke` and `drop-shadow` colours).
 */
enum class Bite(val tube: Long, val glow: Long, val cuisines: Set<String>) {
    TACOS(0xFFFFD6F2, 0xFFFF4FD8, setOf("mexican", "tex-mex", "burrito", "taco", "tacos")),
    SLICE(0xFFFFE7B0, 0xFFFFB020, setOf("pizza", "italian_pizza")),
    BURGER(0xFFFFD2B0, 0xFFFF7A2A, setOf("burger")),
    KEBAB(0xFFFFF0B8, 0xFFFFD23F, setOf("kebab", "shawarma", "doner", "turkish", "gyros", "lebanese", "middle_eastern")),
    FALAFEL(0xFFD4FFC9, 0xFF5BE36B, setOf("falafel", "hummus")),
    DUMPLING(0xFFE2FCFF, 0xFF36E3FF, setOf("dumpling", "chinese", "asian", "noodle", "thai", "japanese", "ramen", "vietnamese", "korean", "wok", "dim_sum")),
    HOT_DOG(0xFFFFD2B0, 0xFFFF7A2A, setOf("hot_dog", "sausage")),
    CHICKEN(0xFFFFE7B0, 0xFFFFB020, setOf("chicken", "wings", "fried_chicken")),
    TRUCKS(0xFFE2FCFF, 0xFF36E3FF, emptySet());

    /** True when [p] is this bite: by cuisine, or a street vendor for the trucks. */
    fun has(p: EatPlace): Boolean = when (this) {
        TRUCKS -> p.yes("street_vendor") || p.tags["vending"] == "food" || p.tags["mobile"] == "yes"
        else -> p.cuisines.any { it in cuisines }
    }
}

/** The chips under the wall. */
enum class LateChip(val symbol: String) {
    /** Still open at two in the night, not just past midnight. */
    TWO("ui-moon"),
    ALWAYS("ui-clock"),
    DRIVE_THROUGH("ui-car"),
    DELIVERY("ui-bike"),
    CASH("ui-cash");

    fun has(p: EatPlace, night: LocalDateTime, area: EatArea): Boolean = when (this) {
        TWO -> p.openness(night.toLocalDate().atTime(2, 0), area.hours).isOpen
        ALWAYS -> p.alwaysOpen
        DRIVE_THROUGH -> p.yes("drive_through")
        DELIVERY -> p.tags["delivery"]?.let { it == "yes" || it == "only" } == true
        CASH -> p.takesCash
    }
}

/** How the late list is ordered. */
enum class LateSort { NEAREST, LATEST }

/** A late place: open past midnight, until when (null for 24/7 or an open end). */
data class LateSpot(val place: EatPlace, val until: LocalDateTime?, val bites: List<Bite>)

/** The late-night wall at a time: the count, the last closing, the board's nine tiles with their counts and the list. */
data class LateWall(
    val count: Int,
    val hoursKnown: Boolean,
    val till: LocalDateTime?,
    val tiles: List<Pair<Bite, Int>>,
    val chipCounts: Map<LateChip, Int>,
    val spots: List<LateSpot>,
)

/**
 * Late bites, as on the Street board: kitchens whose `opening_hours` keep them open past midnight on the night of the
 * table's time, by kind of bite.
 */
object LateModel {
    /** The night's half past midnight, when "late" is judged. */
    fun night(at: LocalDateTime): LocalDateTime = EatSets.lateMoment(at)

    fun wall(area: EatArea, at: LocalDateTime, bite: Bite?, chips: Set<LateChip>, sort: LateSort): LateWall {
        val night = night(at)
        val late = EatSets.lateNight(area, at)
        val hoursKnown = area.places.any { it.hasHours }
        val tiles = Bite.entries.map { b -> b to late.count { b.has(it) } }
        val ofBite = if (bite == null) late else late.filter { bite.has(it) }
        val chosen = ofBite.filter { p -> chips.all { it.has(p, night, area) } }
        val spots = chosen.map { p -> LateSpot(p, p.closesAt(night, area.hours), Bite.entries.filter { it.has(p) }) }
        val sorted = when (sort) {
            LateSort.NEAREST -> spots.sortedBy { it.place.distanceM }
            LateSort.LATEST -> spots.sortedWith(compareByDescending<LateSpot> { if (it.place.alwaysOpen) LocalDateTime.MAX else it.until ?: night }.thenBy { it.place.distanceM })
        }
        return LateWall(
            count = late.size,
            hoursKnown = hoursKnown,
            till = EatSets.lastCall(late, area, night),
            tiles = tiles,
            chipCounts = LateChip.entries.associateWith { c -> ofBite.count { c.has(it, night, area) } },
            spots = sorted,
        )
    }
}

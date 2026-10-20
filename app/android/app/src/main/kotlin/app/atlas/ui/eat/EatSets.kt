package app.atlas.ui.eat

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import kotlin.random.Random

/**
 * Which places each object on the table gathers, and what it counts. Every rule reads real tags; counts read the
 * opening hours at the table's time.
 */
object EatSets {
    /** Cuisines of sweet things, wherever they are served. */
    val SWEET_CUISINES = setOf(
        "ice_cream", "gelato", "frozen_yogurt", "dessert", "cake", "donut", "crepe", "pancake", "poffertjes", "waffle", "chocolate", "pastry", "candy", "churros",
    )

    /** Every restaurant: the carte. */
    fun carte(places: List<EatPlace>): List<EatPlace> = places.filter { it.kind.isMeal }

    /** Coffee, tea, bakeries with a counter: the chalkboard. */
    fun cafes(places: List<EatPlace>): List<EatPlace> =
        places.filter { it.kind == EatKind.CAFE || it.kind == EatKind.BAKERY || it.kind == EatKind.TEA_SHOP || it.kind == EatKind.COFFEE_SHOP }

    /** Bars, pubs, beer gardens and clubs: the Art Deco card. */
    fun bars(places: List<EatPlace>): List<EatPlace> = places.filter { it.kind.isDrinks }

    /** Ice cream, pastry, sweets and chocolate, and kitchens that serve them: the ticket stub. */
    fun sweets(places: List<EatPlace>): List<EatPlace> = places.filter { p ->
        p.kind == EatKind.ICE_CREAM || p.kind == EatKind.PASTRY || p.kind == EatKind.CONFECTIONERY || p.kind == EatKind.CHOCOLATE ||
            p.kind == EatKind.BAKERY || (p.kind != EatKind.BAR && p.cuisines.any { it in SWEET_CUISINES })
    }

    /** Markets, fresh food shops and bakers: the kraft tag. */
    fun market(places: List<EatPlace>): List<EatPlace> = places.filter { it.kind.isFresh || it.kind == EatKind.BAKERY }

    /** The night a moment belongs to: before 5:00 it is still the night before. */
    fun nightOf(at: LocalDateTime): LocalDate = if (at.toLocalTime().isBefore(LocalTime.of(5, 0))) at.toLocalDate().minusDays(1) else at.toLocalDate()

    /** Half past midnight of the night [at] belongs to: open then means open late. */
    fun lateMoment(at: LocalDateTime): LocalDateTime = nightOf(at).plusDays(1).atTime(0, 30)

    /** Kitchens open past midnight on the night of [at] (`opening_hours` says so): the neon. */
    fun lateNight(area: EatArea, at: LocalDateTime): List<EatPlace> {
        val late = lateMoment(at)
        return area.places.filter { p -> (p.kind.isMeal || p.kind == EatKind.ICE_CREAM) && p.openness(late, area.hours).isOpen }
    }

    /**
     * The last closing time among [places] after [from], for "last call 4:00" and "till 3:00"; null when none says
     * when it closes. A place open 24/7 never closes, so it does not count.
     */
    fun lastCall(places: List<EatPlace>, area: EatArea, from: LocalDateTime): LocalDateTime? =
        places.mapNotNull { it.closesAt(from, area.hours) }.maxOrNull()

    /** What the six objects show at [at]. */
    data class Counts(
        val carte: EatArea.Count,
        val cafe: EatArea.Count,
        val late: EatArea.Count,
        val lateUntil: LocalDateTime?,
        val bar: EatArea.Count,
        val sweets: EatArea.Count,
        val market: EatArea.Count,
    )

    fun counts(area: EatArea, at: LocalDateTime): Counts {
        val p = area.places
        val late = lateNight(area, at)
        val sketch = p.none { it.hasHours }
        return Counts(
            carte = area.count(carte(p), at),
            cafe = area.count(cafes(p), at),
            late = if (sketch) EatArea.Count(0, open = false) else EatArea.Count(late.size, open = true),
            lateUntil = if (sketch) null else lastCall(late, area, lateMoment(at)),
            bar = area.count(bars(p), at),
            sweets = area.count(sweets(p), at),
            market = area.count(market(p), at),
        )
    }

    /**
     * "Surprise me": a place to eat or drink open at [at] (by its hours) within [maxWalkMinutes], at random; places
     * rated 4 stars or more by someone are twice as likely. Null when nothing qualifies.
     */
    fun surprise(area: EatArea, at: LocalDateTime, ratings: AreaRatings, random: Random = Random.Default, maxWalkMinutes: Int = 12): EatPlace? {
        val candidates = area.places.filter { p ->
            (p.kind.isMeal || p.kind == EatKind.CAFE || p.kind.isDrinks) && p.walkMinutes <= maxWalkMinutes && p.openness(at, area.hours) == Openness.OPEN
        }
        if (candidates.isEmpty()) return null
        val weights = candidates.map { if ((ratings.stars(it.key) ?: 0.0) >= 4.0) 2 else 1 }
        var pick = random.nextInt(weights.sum())
        for (i in candidates.indices) {
            pick -= weights[i]
            if (pick < 0) return candidates[i]
        }
        return candidates.last()
    }
}

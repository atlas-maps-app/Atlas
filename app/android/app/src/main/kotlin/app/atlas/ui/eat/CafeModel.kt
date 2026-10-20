package app.atlas.ui.eat

import app.atlas.providers.Freshness
import app.atlas.providers.Trends
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

/** The chalkboard's words: what kind of place, from real tags. */
enum class CafeKind {
    COFFEE, TEA, BUBBLE_TEA, BAKERY, BRUNCH;

    fun has(p: EatPlace): Boolean = when (this) {
        COFFEE -> !CafeModel.cannabis(p) && (p.kind == EatKind.COFFEE_SHOP || "coffee_shop" in p.cuisines ||
            (p.kind == EatKind.CAFE && p.cuisines.none { it == "tea" || it == "bubble_tea" }))
        TEA -> p.kind == EatKind.TEA_SHOP || "tea" in p.cuisines || p.tags["cafe"] == "tea"
        BUBBLE_TEA -> "bubble_tea" in p.cuisines
        BAKERY -> p.kind == EatKind.BAKERY
        BRUNCH -> p.cuisines.any { it == "breakfast" || it == "brunch" } || p.yes("breakfast") || p.yes("brunch")
    }
}

/** The chalk icons: things a café has. */
enum class CafeToggle {
    WIFI, QUIET, TERRACE, VEGAN, EARLY, TAKEAWAY;

    /** True when [p] has it; [morning] is the morning "early" asks about. */
    fun has(p: EatPlace, morning: LocalDateTime, area: EatArea): Boolean = when (this) {
        WIFI -> p.wifi
        QUIET -> p.tags["music"] == "no" || p.tags["background_music"] == "no" || p.tags["noise"] == "quiet" || p.yes("quiet")
        TERRACE -> p.outside
        VEGAN -> p.diet("vegan")
        EARLY -> p.openness(morning, area.hours).isOpen
        TAKEAWAY -> p.takeaway
    }
}

/** A line of the chalkboard: the place, what it has, and its state at the board's time. */
data class CafeLine(val place: EatPlace, val openness: Openness, val has: Set<CafeToggle>, val isNew: Boolean)

/** "Today's special": a place worth the walk now, and why (brunch until, or open until). */
data class Special(val place: EatPlace, val brunch: Boolean, val until: LocalDateTime?)

/** What the chalkboard shows at a time. */
data class CafeBoard(
    val open: Int,
    val countsOpen: Boolean,
    val kindCounts: Map<CafeKind, Int>,
    val toggleCounts: Map<CafeToggle, Int>,
    val lines: List<CafeLine>,
    val total: Int,
    val special: Special?,
)

/** The café chalkboard's logic, over real tags and opening hours. */
object CafeModel {
    /** Dutch "coffeeshops" are something else: they never count as coffee. */
    internal fun cannabis(p: EatPlace): Boolean =
        "soft_drugs" in p.cuisines || p.tags["drug:cannabis"] == "yes" || p.tags["cannabis"] == "yes" || p.tags["shop"] == "cannabis"

    /** The morning "early" means: 7:30 of the day of [at], or of the next day once it is past eleven. */
    fun morningOf(at: LocalDateTime): LocalDateTime {
        val day: LocalDate = if (at.toLocalTime().isAfter(LocalTime.of(11, 0))) at.toLocalDate().plusDays(1) else at.toLocalDate()
        return day.atTime(7, 30)
    }

    /** The board for [kind] (null: every café) and [toggles] (all must hold): open first, then nearest. */
    fun board(area: EatArea, at: LocalDateTime, kind: CafeKind?, toggles: Set<CafeToggle>, today: LocalDate = at.toLocalDate(), limit: Int = 40): CafeBoard {
        val all = EatSets.cafes(area.places)
        val morning = morningOf(at)
        val countsOpen = all.any { it.hasHours }
        val ofKind = if (kind == null) all else all.filter { kind.has(it) }
        val chosen = ofKind.filter { p -> toggles.all { it.has(p, morning, area) } }
        val rank = { o: Openness -> if (o.isOpen) 0 else if (o == Openness.UNKNOWN) 1 else 2 }
        val lines = chosen.map { p ->
            CafeLine(
                p, p.openness(at, area.hours),
                CafeToggle.entries.filterTo(HashSet()) { it.has(p, morning, area) },
                Trends.freshness(p.place, today)?.kind.let { it == Freshness.Kind.NEW || it == Freshness.Kind.OPENING_SOON },
            )
        }.sortedWith(compareBy<CafeLine> { rank(it.openness) }.thenBy { it.place.distanceM })
        return CafeBoard(
            open = if (countsOpen) all.count { it.openness(at, area.hours).isOpen } else all.size,
            countsOpen = countsOpen,
            kindCounts = CafeKind.entries.associateWith { k -> all.count { k.has(it) } },
            toggleCounts = CafeToggle.entries.associateWith { t -> ofKind.count { t.has(it, morning, area) } },
            lines = lines.take(limit),
            total = lines.size,
            special = special(all, area, at),
        )
    }

    /**
     * Today's special: before half past two, the nearest brunch place open at [at] and still for an hour, with when it
     * closes; else the café within ten minutes that stays open latest. Null when the hours say nothing.
     */
    fun special(cafes: List<EatPlace>, area: EatArea, at: LocalDateTime): Special? {
        val openLong = cafes.filter { p ->
            p.openness(at, area.hours) == Openness.OPEN && p.closesAt(at, area.hours)?.let { it.isAfter(at.plusMinutes(59)) } != false
        }
        // Brunch is a morning-to-mid-afternoon thing, whatever a place's evening hours say.
        if (at.toLocalTime() in LocalTime.of(7, 0)..LocalTime.of(14, 30)) {
            openLong.filter { CafeKind.BRUNCH.has(it) && it.walkMinutes <= 15 }.minByOrNull { it.distanceM }?.let {
                return Special(it, brunch = true, until = it.closesAt(at, area.hours))
            }
        }
        val late = openLong.filter { it.walkMinutes <= 10 && it.closesAt(at, area.hours) != null }
            .maxWithOrNull(compareBy<EatPlace> { it.closesAt(at, area.hours) }.thenByDescending { it.distanceM })
        return late?.let { Special(it, brunch = false, until = it.closesAt(at, area.hours)) }
    }
}

package app.atlas.ui.eat

import java.time.LocalDateTime

/**
 * A tub in the gelato case, the Sweets board's eight: a kind of sweet, with the colour of what is heaped in it and
 * the three tones of its scoop avatar (light, the scoop, its shadow), as the board fills them.
 */
enum class Tub(val fill: Long, val scoop: Triple<Long, Long, Long>) {
    GELATO(0xFFB8D98A, Triple(0xFFD6ECB4, 0xFFB8D98A, 0xFF9CC06D)),
    BAKERY(0xFFD9A45E, Triple(0xFFF2E7D2, 0xFFE3D2A8, 0xFFCDB783)),
    PASTRY(0xFFF6E7C1, Triple(0xFFFFF6E3, 0xFFF6E7C1, 0xFFE2CC94)),
    CHOCO(0xFF6B3A22, Triple(0xFFA5694A, 0xFF6B3A22, 0xFF4E2715)),
    DONUTS(0xFFF59CB5, Triple(0xFFF8C4D2, 0xFFF59CB5, 0xFFE07894)),
    CREPES(0xFFEBC56A, Triple(0xFFF6DD99, 0xFFEBC56A, 0xFFC99A3A)),
    FROYO(0xFFFBF3F7, Triple(0xFFFFFFFF, 0xFFFBF3F7, 0xFFE5D5EC)),
    CANDY(0xFFFFF3EE, Triple(0xFFFFFFFF, 0xFFFFF3EE, 0xFFF2D3C6));

    fun has(p: EatPlace): Boolean = when (this) {
        GELATO -> p.kind == EatKind.ICE_CREAM || p.cuisines.any { it == "ice_cream" || it == "gelato" }
        BAKERY -> p.kind == EatKind.BAKERY
        PASTRY -> p.kind == EatKind.PASTRY || p.cuisines.any { it == "pastry" || it == "cake" || it == "dessert" }
        CHOCO -> p.kind == EatKind.CHOCOLATE || "chocolate" in p.cuisines
        DONUTS -> "donut" in p.cuisines
        CREPES -> p.cuisines.any { it == "crepe" || it == "pancake" || it == "waffle" || it == "poffertjes" }
        FROYO -> "frozen_yogurt" in p.cuisines
        CANDY -> p.kind == EatKind.CONFECTIONERY || p.cuisines.any { it == "candy" || it == "churros" }
    }
}

/** The pills under the case. */
enum class SweetPill {
    VEGAN, GLUTEN_FREE, LATE, SEATS, KIDS;

    fun has(p: EatPlace, at: LocalDateTime, area: EatArea): Boolean = when (this) {
        VEGAN -> p.diet("vegan")
        GLUTEN_FREE -> p.diet("gluten_free")
        LATE -> p.openness(at.toLocalDate().atTime(22, 0), area.hours).isOpen
        SEATS -> p.yes("indoor_seating") || p.outside
        KIDS -> p.yes("kids_area") || p.yes("highchair") || p.yes("changing_table")
    }
}

/** A sweet place: its first tub (the avatar's colour), state, closing time and whether it is all vegan. */
data class SweetSpot(val place: EatPlace, val tub: Tub, val openness: Openness, val closes: LocalDateTime?, val vegan: Boolean, val allVegan: Boolean)

/** What the sweets screen shows: the open count, the tubs' counts and the list. */
data class SweetCase(val open: Int, val countsOpen: Boolean, val tubs: Map<Tub, Int>, val pills: Map<SweetPill, Int>, val spots: List<SweetSpot>)

/** Ice cream, bakeries, pastry, chocolate and sweets, from real tags and opening hours. */
object SweetsModel {
    fun case(area: EatArea, at: LocalDateTime, tub: Tub?, pills: Set<SweetPill>): SweetCase {
        val all = EatSets.sweets(area.places)
        val countsOpen = !area.sketchOnly && all.any { it.hasHours }
        val ofTub = if (tub == null) all else all.filter { tub.has(it) }
        val chosen = ofTub.filter { p -> pills.all { it.has(p, at, area) } }
        val rank = { o: Openness -> if (o.isOpen) 0 else if (o == Openness.UNKNOWN) 1 else 2 }
        val spots = chosen.map { p ->
            SweetSpot(
                p, tub ?: Tub.entries.firstOrNull { it.has(p) } ?: Tub.PASTRY, p.openness(at, area.hours), p.closesAt(at, area.hours),
                vegan = p.diet("vegan"), allVegan = p.tags["diet:vegan"] == "only",
            )
        }.sortedWith(compareBy<SweetSpot> { rank(it.openness) }.thenBy { it.place.distanceM })
        return SweetCase(
            open = if (countsOpen) all.count { it.openness(at, area.hours).isOpen } else all.size,
            countsOpen = countsOpen,
            tubs = Tub.entries.associateWith { t -> all.count { t.has(it) } },
            pills = SweetPill.entries.associateWith { s -> ofTub.count { s.has(it, at, area) } },
            spots = spots,
        )
    }
}

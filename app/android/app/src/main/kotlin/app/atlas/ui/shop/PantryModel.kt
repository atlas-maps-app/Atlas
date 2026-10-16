package app.atlas.ui.shop

import java.time.LocalDateTime

/**
 * What each shelf of the pantry counts around you: the shops open now (the tiles, which carry no hours, count shops
 * instead and say so), the open total of the header, and the places of each shelf for the aisle screen.
 */
internal class PantryCounts(
    val byShelf: Map<Shelf, ShopArea.Count>,
    /** Distinct places on any shelf: open ones, or all of them when no hours are known. */
    val total: ShopArea.Count,
) {
    companion object {
        /** The places of [shelf] in [area] at [at]: LATE is any food shop open past 22:00 that day. */
        fun placesOf(shelf: Shelf, area: ShopArea, at: LocalDateTime): List<ShopPlace> = when (shelf) {
            Shelf.LATE -> area.places.filter { p ->
                Shelf.LATE.filter.matches(p.tags) && p.openAfter(at.toLocalDate(), LATE_HOUR, area.hours)
            }
            else -> area.places.filter { shelf.filter.matches(it.tags) }
        }

        const val LATE_HOUR = 22

        /** A count nobody can know yet (shown as a dash). */
        const val UNKNOWN = -1

        fun of(area: ShopArea, at: LocalDateTime): PantryCounts {
            val counts = HashMap<Shelf, ShopArea.Count>()
            for (shelf in Shelf.entries) {
                counts[shelf] = if (shelf == Shelf.LATE) {
                    // Without readable hours nobody can say who is open late: unknown (-1), not none.
                    if (area.sketchOnly || area.places.none { it.hasHours }) ShopArea.Count(UNKNOWN, open = true)
                    else ShopArea.Count(placesOf(shelf, area, at).size, open = true)
                } else {
                    area.count(area.places.filter { shelf.filter.matches(it.tags) }, at)
                }
            }
            val all = area.places.filter { Shelf.all.matches(it.tags) }
            return PantryCounts(counts, area.count(all, at))
        }
    }
}

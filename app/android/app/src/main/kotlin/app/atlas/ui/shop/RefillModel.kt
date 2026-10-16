package app.atlas.ui.shop

import java.time.Duration
import java.time.LocalDateTime

/** The Refill board's four filters. [OWN] keeps places that take your own container, [NOW] those not closed. */
internal enum class RefillFilter { OWN, NOW, ORGANIC, CARD }

/** One place on the Refill list: how long it stays open today decides how full its jar is drawn. */
internal class RefillRow(val place: ShopPlace, val kind: RefillKind, val fill: Float, val closes: LocalDateTime?, val always: Boolean, val tagline: String?, val organic: Boolean = false)

/** What the Refill screen shows for one choice of container. */
internal class RefillView(
    /** Places of each kind that are not known to be closed now. */
    val counts: Map<RefillKind, Int>,
    val total: Int,
    val rows: List<RefillRow>,
    val filterCounts: Map<RefillFilter, Int>,
)

internal object Refills {
    /** A day's opening, in minutes, that draws a full jar: twelve hours. */
    const val FULL_MINUTES = 12 * 60

    fun view(kind: RefillKind, area: ShopArea, at: LocalDateTime, filters: Set<RefillFilter>, lang: String?): RefillView {
        val notClosed = { p: ShopPlace -> p.openness(at, area.hours) != Open.CLOSED }
        val byKind = RefillKind.entries.associateWith { k -> area.places.filter { k.filter.matches(it.tags) } }
        val counts = byKind.mapValues { (_, ps) -> ps.count(notClosed) }
        val mine = byKind.getValue(kind)
        fun holds(f: RefillFilter, p: ShopPlace): Boolean = when (f) {
            RefillFilter.OWN -> kind == RefillKind.WATER || kind == RefillKind.VEND || kind == RefillKind.RETURN || takesOwn(p)
            RefillFilter.NOW -> notClosed(p)
            RefillFilter.ORGANIC -> p.tags["organic"] == "yes" || p.tags["organic"] == "only"
            RefillFilter.CARD -> takesCard(p)
        }
        val kept = mine.filter { p -> filters.all { holds(it, p) } }
        val rows = kept.sortedBy { it.distanceM }.map { p -> row(p, kind, area, at, lang) }
        return RefillView(counts, counts.values.sum(), rows, RefillFilter.entries.associateWith { f -> mine.count { holds(f, it) } })
    }

    private fun row(p: ShopPlace, kind: RefillKind, area: ShopArea, at: LocalDateTime, lang: String?): RefillRow {
        val always = p.alwaysOpen || (!p.hasHours && kind == RefillKind.WATER)
        val closes = if (always) null else p.closesAt(at, area.hours)
        val fill = when {
            always -> 1f
            !p.hasHours -> 0.5f
            p.openness(at, area.hours) == Open.CLOSED -> 0f
            closes == null -> 1f
            else -> (Duration.between(at, closes).toMinutes().toFloat() / FULL_MINUTES).coerceIn(0.06f, 1f)
        }
        val organic = p.tags["organic"] == "yes" || p.tags["organic"] == "only"
        val tagline = p.kindName(lang)?.takeIf { p.tags["shop"] != null || p.tags["amenity"] == "vending_machine" }
        return RefillRow(p, kind, fill, closes, always, tagline, organic)
    }

    /** True when the place says it takes your own jars: bulk and zero-waste shops, or reusable packaging accepted. */
    fun takesOwn(p: ShopPlace): Boolean =
        p.yes("bulk_purchase") || p.yes("zero_waste") || p.yes("reusable_packaging:accept") || p.tags["refill"]?.let { it != "no" } == true

    fun takesCard(p: ShopPlace): Boolean = ShopPlace.CARD_KEYS.any { p.yes("payment:$it") }
}

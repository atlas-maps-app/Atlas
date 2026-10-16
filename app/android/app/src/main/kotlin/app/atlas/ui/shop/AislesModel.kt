package app.atlas.ui.shop

import app.atlas.places.Brand
import java.time.LocalDateTime

/** How the shelf-edge labels of an aisle are ordered. Cheapest needs prices and is offered only when there are some. */
internal enum class AisleSort { NEAREST, BIGGEST, OPEN_LATEST, CHEAPEST }

/** The toggles of an aisle: each keeps only the shops the map says have it. */
internal enum class AisleFilter {
    SUNDAY, SELF_CHECKOUT, DELIVERY, PARKING, STEP_FREE;

    fun holds(p: ShopPlace, area: ShopArea, at: LocalDateTime): Boolean = when (this) {
        SUNDAY -> p.openSunday(at.toLocalDate(), area.hours) == true
        SELF_CHECKOUT -> p.selfCheckout
        DELIVERY -> p.delivery
        PARKING -> p.parking
        STEP_FREE -> p.stepFree
    }
}

/** A chain (or "independent") with its shops in the aisle: a logo chip with a count. [brand] null for the odd chain NSI lacks. */
internal class BrandChip(val key: String, val name: String, val brand: Brand?, val count: Int, val independent: Boolean = false)

/**
 * What an aisle lists: one shelf of the pantry, or one family of the high street. [places] picks its shops out of an area.
 */
internal class AisleSpec(
    val id: String,
    val name: Int,
    /** The number on the hanging sign. */
    val number: Int,
    val colour: androidx.compose.ui.graphics.Color,
    /** The symbol on a card whose shop is not a chain. */
    val glyph: String,
    val places: (ShopArea, LocalDateTime) -> List<ShopPlace>,
)

/** An aisle as the screen shows it. */
internal class AisleView(
    val spec: AisleSpec,
    /** Every place of the aisle, before filters and brand choice. */
    val all: List<ShopPlace>,
    val shown: List<ShopPlace>,
    val brands: List<BrandChip>,
    /** How many of [all] each toggle would keep, to dim the ones that keep none. */
    val filterCounts: Map<AisleFilter, Int>,
    val openCount: ShopArea.Count,
)

internal object Aisles {
    /** The key chips group by: the matched brand, else the `brand` tag, else null (independent). */
    fun brandKey(p: ShopPlace): String? = p.brand?.id ?: p.tags["brand"]?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }

    fun view(
        spec: AisleSpec,
        area: ShopArea,
        at: LocalDateTime,
        brand: String? = null,
        filters: Set<AisleFilter> = emptySet(),
        sort: AisleSort = AisleSort.NEAREST,
        /** What the shopping list would cost at each shop (by place key), when known. */
        baskets: Map<String, Double> = emptyMap(),
        /** Only shops that are not chains. */
        indie: Boolean = false,
    ): AisleView {
        val all = spec.places(area, at).let { ps -> if (indie) ps.filter { it.independent } else ps }
        val chips = all.groupBy { brandKey(it) }.map { (key, ps) ->
            if (key == null) BrandChip(INDEPENDENT, "", null, ps.size, independent = true)
            else {
                val first = ps.first()
                BrandChip(key, first.brand?.let { brandLabel(it) } ?: first.tags["brand"].orEmpty(), first.brand, ps.size)
            }
        }.sortedWith(compareByDescending<BrandChip> { it.count }.thenBy { it.independent }.thenBy { it.name })
        val picked = if (brand == null) all else all.filter { (brandKey(it) ?: INDEPENDENT) == brand }
        val kept = picked.filter { p -> filters.all { it.holds(p, area, at) } }
        val shown = kept.sortedWith(comparator(sort, area, at, baskets))
        val counts = AisleFilter.entries.associateWith { f -> all.count { f.holds(it, area, at) } }
        return AisleView(spec, all, shown, chips, counts, area.count(all, at))
    }

    /** The name a brand trades under ("Jumbo"), without the index's region ("Jumbo (Europe)"). */
    fun brandLabel(b: Brand): String = b.brand.ifBlank { b.name }

    const val INDEPENDENT = "~independent"

    private fun comparator(sort: AisleSort, area: ShopArea, at: LocalDateTime, baskets: Map<String, Double>): Comparator<ShopPlace> {
        val byDistance = compareBy<ShopPlace> { it.distanceM }
        return when (sort) {
            AisleSort.NEAREST -> byDistance
            AisleSort.BIGGEST -> compareByDescending<ShopPlace> { it.footprintM2 ?: -1 }.then(byDistance)
            AisleSort.OPEN_LATEST -> compareByDescending<ShopPlace> { closingRank(it, area, at) }.then(byDistance)
            AisleSort.CHEAPEST -> compareBy<ShopPlace> { baskets[it.key] ?: Double.MAX_VALUE }.then(byDistance)
        }
    }

    /** Minutes after [at] it stays open today: 24/7 shops first, closed or unknown last. */
    private fun closingRank(p: ShopPlace, area: ShopArea, at: LocalDateTime): Long {
        if (p.alwaysOpen) return Long.MAX_VALUE
        if (!p.openness(at, area.hours).isOpen) return -1
        val c = p.closesAt(at, area.hours) ?: return Long.MAX_VALUE - 1
        return java.time.Duration.between(at, c).toMinutes()
    }
}

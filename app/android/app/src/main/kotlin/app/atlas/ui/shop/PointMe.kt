package app.atlas.ui.shop

import app.atlas.geo.Geo
import app.atlas.geo.LatLon
import app.atlas.places.TagFilter
import java.time.LocalDateTime

/** The needs the compass screens point at. */
internal enum class NeedKind {
    TOILET, WATER, FOOD;

    companion object {
        fun of(kind: String): NeedKind = when (kind.lowercase()) {
            "water" -> WATER
            "toilet", "toilets", "wc" -> TOILET
            else -> FOOD
        }
    }
}

/** The filter chips at the top of a point-me screen. */
internal enum class NeedFilter {
    FREE, WHEELCHAIR, BABY, BOTTLE, CHEAP, VEGAN, HALAL;

    companion object {
        fun of(kind: NeedKind): List<NeedFilter> = when (kind) {
            NeedKind.TOILET -> listOf(FREE, WHEELCHAIR, BABY)
            NeedKind.WATER -> listOf(BOTTLE, FREE)
            NeedKind.FOOD -> listOf(FREE, CHEAP, VEGAN, HALAL)
        }

        /** The filters a kind starts with, as the boards show them pressed. */
        fun initial(kind: NeedKind): Set<NeedFilter> = when (kind) {
            NeedKind.TOILET -> setOf(FREE)
            NeedKind.WATER -> setOf(BOTTLE, FREE)
            NeedKind.FOOD -> emptySet()
        }
    }
}

/** A small fact on the detail card: what it is, from the tags. */
internal enum class Fact { FREE, PAID, NO_BOTTLE, WHEELCHAIR, BABY, ALL_GENDER, WATER, DRINKABLE, BOTTLE, SEASONAL, CUSTOMERS, CHEAP, VEGAN, HALAL, READY }

/** One place the compass can point at, with what the tags say about it. */
internal class Target(
    val place: ShopPlace,
    val distanceM: Double,
    /** Compass bearing from where you are. */
    val bearing: Double,
    val facts: Set<Fact>,
    /** "off 1 Nov": when a seasonal source is turned off, when the hours say so. */
    val offOn: LocalDateTime?,
    /** The kind of place, for a label such as "FREE REFILL" or "customers". */
    val refill: Boolean,
    val open: Open,
) {
    val key: String get() = place.key
    val walkMinutes: Int get() = maxOf(1, Math.round(app.atlas.ui.kit.WalkTime.seconds(distanceM) / 60.0).toInt())
}

/**
 * What the toilet, water and food compass screens look for around you and how they rank it: the nearest that is usable
 * (open or unknown hours, not customers only) first, then the rest by distance; each with the facts its tags give.
 */
internal object PointMe {
    private val FOOD_PLACES = "amenity~^(restaurant|cafe|fast_food|food_court|food_sharing|fridge|marketplace)$ or " +
        "shop~^(bakery|pastry|deli|convenience|supermarket|greengrocer|butcher|seafood)$ or (amenity=give_box and give_box~food)"

    fun filterOf(kind: NeedKind): TagFilter = when (kind) {
        NeedKind.TOILET -> Need.TOILET.filter
        NeedKind.WATER -> Need.WATER.filter
        NeedKind.FOOD -> TagFilter.parse(FOOD_PLACES)
    }

    /** The target facts of [p]. */
    fun facts(kind: NeedKind, p: ShopPlace): Set<Fact> = buildSet {
        val t = p.tags
        if (t["access"] == "customers") add(Fact.CUSTOMERS)
        when (kind) {
            NeedKind.TOILET -> {
                // "Free" only where the map says so: a toilet nobody tagged a fee on is not claimed to be free.
                if (t["fee"] == "no") add(Fact.FREE)
                if (t["fee"] == "yes") add(Fact.PAID)
                if (t["wheelchair"] == "yes" || t["wheelchair"] == "designated" || p.yes("toilets:wheelchair")) add(Fact.WHEELCHAIR)
                if (p.yes("changing_table") || t["changing_table"] == "yes" || p.yes("diaper")) add(Fact.BABY)
                if (p.yes("unisex")) add(Fact.ALL_GENDER)
                if (t["drinking_water"] == "yes") add(Fact.WATER)
                if (t["amenity"] != "toilets") add(Fact.CUSTOMERS)
            }
            NeedKind.WATER -> {
                if (t["fee"] != "yes") add(Fact.FREE)
                if (t["drinking_water"] != "no") add(Fact.DRINKABLE)
                // A bottle filler only where the map says so ("bubbler" is a jet to drink from, not to fill a bottle at).
                val noBottle = t["bottle"] == "no" || (t["fountain"] == "bubbler" && t["bottle"] != "yes")
                if (noBottle) add(Fact.NO_BOTTLE)
                else if (t["bottle"] == "yes" || t["fountain"] in setOf("bottle", "bottle_refill") || t["drinking_water:refill"] == "yes" || t["refill"] == "yes") add(Fact.BOTTLE)
                if (t["seasonal"]?.let { it != "no" } == true) add(Fact.SEASONAL)
                if (t["shop"] != null || t["amenity"] in setOf("cafe", "restaurant", "fast_food")) add(Fact.CUSTOMERS)
            }
            NeedKind.FOOD -> {
                if (t["amenity"] in setOf("food_sharing", "fridge") || t["give_box"] != null || t["community_fridge"] == "yes") add(Fact.FREE)
                if (isCheap(t)) add(Fact.CHEAP)
                if (t["diet:vegan"] in setOf("yes", "only") || t["cuisine"]?.contains("vegan") == true) add(Fact.VEGAN)
                if (t["diet:halal"] in setOf("yes", "only") || t["cuisine"]?.contains("halal") == true) add(Fact.HALAL)
            }
        }
    }

    /** "$": quick, plain places (fast food, bakeries, market stalls, convenience stores), by their tags: OSM has no prices. */
    fun isCheap(t: Map<String, String>): Boolean =
        t["amenity"] in setOf("fast_food", "food_court", "marketplace", "food_sharing", "fridge") || t["shop"] in setOf("bakery", "convenience", "supermarket", "greengrocer", "pastry")

    fun holds(f: NeedFilter, facts: Set<Fact>, kind: NeedKind? = null): Boolean = when (f) {
        // For toilets "free" keeps everything not known to cost money (most are not tagged), and the card says "free" only where it is tagged.
        NeedFilter.FREE -> if (kind == NeedKind.TOILET) Fact.PAID !in facts else Fact.FREE in facts
        NeedFilter.WHEELCHAIR -> Fact.WHEELCHAIR in facts
        NeedFilter.BABY -> Fact.BABY in facts
        // For water "bottle" keeps everything not known to be a drinking jet only (most taps are not tagged), and the card says "bottle filler" only where it is tagged.
        NeedFilter.BOTTLE -> if (kind == NeedKind.WATER) Fact.NO_BOTTLE !in facts else Fact.BOTTLE in facts
        NeedFilter.CHEAP -> Fact.CHEAP in facts
        NeedFilter.VEGAN -> Fact.VEGAN in facts
        NeedFilter.HALAL -> Fact.HALAL in facts
    }

    fun targets(kind: NeedKind, area: ShopArea, here: LatLon, filters: Set<NeedFilter>, at: LocalDateTime): List<Target> {
        val f = filterOf(kind)
        val all = area.places.filter { f.matches(it.tags) && notClosedForGood(kind, it) }.map { p ->
            val facts = facts(kind, p)
            val d = Geo.distance(here, p.location)
            val off = if (kind == NeedKind.WATER && p.hasHours && !p.alwaysOpen) {
                p.hours?.status(at, area.hours)?.takeIf { it.isOpen && it.nextChangeKind == app.atlas.places.ChangeKind.CLOSES }?.changesAt?.takeIf { it.isAfter(at.plusDays(1)) }
            } else null
            Target(p, d, Geo.bearing(here, p.location), facts, off, refill = p.tags["amenity"] != "drinking_water" && kind == NeedKind.WATER, open = p.openness(at, area.hours))
        }
        val kept = all.filter { t -> filters.all { holds(it, t.facts, kind) } }
        fun usable(t: Target) = t.open != Open.CLOSED && Fact.CUSTOMERS !in t.facts
        return kept.sortedWith(compareBy<Target> { if (usable(it)) 0 else 1 }.thenBy { it.distanceM })
    }

    /** Places mapped as not for the public at all (`access=private|no`) are left out. */
    private fun notClosedForGood(kind: NeedKind, p: ShopPlace): Boolean {
        val a = p.tags["access"]
        if (a == "private" || a == "no") return false
        if (kind == NeedKind.WATER && p.tags["drinking_water"] == "no") return false
        if (kind == NeedKind.WATER && p.tags["amenity"] == "drinking_water" && p.tags["disused"] == "yes") return false
        return true
    }

    /** The angle to turn the arrow by: where the place lies relative to the way the phone faces (north up when unknown). */
    fun relative(bearing: Double, heading: Double?): Double = Geo.turnAngle(heading ?: 0.0, bearing)
}

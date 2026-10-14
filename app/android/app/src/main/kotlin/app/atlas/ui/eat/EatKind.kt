package app.atlas.ui.eat

import app.atlas.places.TagFilter

/**
 * What a place to eat or drink is, from its main tag: the six objects on the table each gather some of these.
 * [UNKNOWN] is never returned by [of] for a place that passes [EatKind.FILTER].
 */
enum class EatKind {
    RESTAURANT, FAST_FOOD, FOOD_COURT, CAFE, BAR, PUB, BIERGARTEN, NIGHTCLUB, ICE_CREAM, BAKERY, PASTRY, CONFECTIONERY,
    CHOCOLATE, MARKETPLACE, GREENGROCER, BUTCHER, FISH, CHEESE, DELI, WINE, FARM, TEA_SHOP, COFFEE_SHOP, UNKNOWN;

    /** Where you sit down for a meal: the carte's places. */
    val isMeal: Boolean get() = this == RESTAURANT || this == FAST_FOOD || this == FOOD_COURT

    /** Where you go for a drink: the Art Deco card's places. */
    val isDrinks: Boolean get() = this == BAR || this == PUB || this == BIERGARTEN || this == NIGHTCLUB

    /** A stall or shop of fresh food: the market tag's places. */
    val isFresh: Boolean get() = this in FRESH

    companion object {
        private val FRESH = setOf(MARKETPLACE, GREENGROCER, BUTCHER, FISH, CHEESE, DELI, WINE, FARM)

        private val AMENITIES = mapOf(
            "restaurant" to RESTAURANT, "fast_food" to FAST_FOOD, "food_court" to FOOD_COURT, "cafe" to CAFE, "bar" to BAR,
            "pub" to PUB, "biergarten" to BIERGARTEN, "nightclub" to NIGHTCLUB, "ice_cream" to ICE_CREAM, "marketplace" to MARKETPLACE,
        )
        private val SHOPS = mapOf(
            "bakery" to BAKERY, "pastry" to PASTRY, "confectionery" to CONFECTIONERY, "chocolate" to CHOCOLATE,
            "ice_cream" to ICE_CREAM, "greengrocer" to GREENGROCER, "farm" to FARM, "butcher" to BUTCHER, "seafood" to FISH,
            "cheese" to CHEESE, "deli" to DELI, "wine" to WINE, "tea" to TEA_SHOP, "coffee" to COFFEE_SHOP,
        )

        /** Every place an eat & drink screen may show: the one rule the place store, the tiles and Overpass are asked. */
        val FILTER: TagFilter = TagFilter.Or(
            listOf(
                TagFilter.Matches("amenity", "^(" + AMENITIES.keys.joinToString("|") + ")$"),
                TagFilter.Matches("shop", "^(" + SHOPS.keys.joinToString("|") + ")$"),
            ),
        )

        /** The kind of a place from its tags; [UNKNOWN] when it is not a place to eat or drink. */
        fun of(tags: Map<String, String>): EatKind {
            tags["amenity"]?.let { a -> AMENITIES[a]?.let { return it } }
            tags["shop"]?.let { s -> SHOPS[s]?.let { return it } }
            return UNKNOWN
        }

        /** The amenity values of [FILTER], for Overpass. */
        val amenityValues: Set<String> get() = AMENITIES.keys

        /** The shop values of [FILTER], for Overpass. */
        val shopValues: Set<String> get() = SHOPS.keys
    }
}

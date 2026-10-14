package app.atlas.ui.shop

import app.atlas.places.TagFilter

/**
 * The kinds of shop and service the shop screens look for, each by its OpenStreetMap tags: what an item on a shopping
 * list is bought at ([ShopItems]), what an index card of Find stands for, what a stop of a run is. [tags] is the rule
 * in `:places`' filter language; [tagLine] the tags as the Find card types them.
 */
internal enum class ShopKind(val tags: String, val tagLine: String, val colour: Long) {
    SUPERMARKET("shop=supermarket", "shop=supermarket", 0xFF2E5BFF),
    CONVENIENCE("shop~^(convenience|kiosk)$", "shop=convenience · kiosk", 0xFF2E5BFF),
    GREENGROCER("shop~^(greengrocer|farm)$ or amenity=marketplace", "shop=greengrocer · farm", 0xFF2F6B34),
    BAKERY("shop~^(bakery|pastry)$", "shop=bakery · pastry", 0xFFA96F2E),
    BUTCHER("shop=butcher", "shop=butcher", 0xFFB03A3A),
    SEAFOOD("shop~^(seafood|fish)$", "shop=seafood", 0xFF1B6F8A),
    CHEESE("shop~^(cheese|dairy)$", "shop=cheese · dairy", 0xFFB8861B),
    DELI("shop=deli", "shop=deli", 0xFF8C6A3E),
    WORLD("shop=spices or (shop~^(supermarket|convenience|deli|greengrocer|butcher)$ and (cuisine or origin))", "shop=spices · cuisine=*", 0xFF1B6F8A),
    FARM("shop=farm or (amenity=vending_machine and vending~(milk|eggs|food|cheese))", "shop=farm · vending=milk", 0xFF8C6A3E),
    DRINKS("shop~^(alcohol|wine|beverages)$", "shop=alcohol · wine · beverages", 0xFF8E2B24),
    SWEETS("shop~^(confectionery|chocolate)$", "shop=confectionery · chocolate", 0xFFD2457F),
    COFFEE("shop~^(coffee|tea)$", "shop=coffee · tea", 0xFF6B4A2A),
    HEALTH_FOOD("shop~^(health_food|organic)$ or (shop=supermarket and organic~^(only|yes)$)", "shop=health_food · organic", 0xFF2F6B34),
    FROZEN("shop=frozen_food", "shop=frozen_food", 0xFF1F5F99),
    BULK("bulk_purchase~^(yes|only)$ or zero_waste~^(yes|only)$", "bulk_purchase=yes", 0xFFE8913A),
    CHEMIST("shop~^(chemist|drugstore)$", "shop=chemist", 0xFFD2457F),
    PHARMACY("amenity=pharmacy or healthcare=pharmacy", "amenity=pharmacy", 0xFF1E9E5A),
    FLORIST("shop~^(florist|garden_centre)$", "shop=florist", 0xFFD93A5A),
    NEWSAGENT("shop~^(newsagent|kiosk|tobacco)$", "shop=newsagent · kiosk", 0xFF5A6070),
    PET("shop=pet", "shop=pet", 0xFF12A9A6),
    HARDWARE("shop~^(hardware|doityourself)$", "shop=hardware · doityourself", 0xFFC98A2B),
    VARIETY("shop~^(variety_store|department_store|houseware)$", "shop=variety_store · department_store", 0xFFC8322A);

    /** The rule, parsed once. */
    val filter: TagFilter by lazy { TagFilter.parse(tags) }

    fun matches(tags: Map<String, String>): Boolean = filter.matches(tags)

    companion object {
        private val byName = entries.associateBy { it.name.lowercase() }

        /** The kind named [id] ("bakery", "supermarket"); null when unknown. */
        fun of(id: String): ShopKind? = byName[id.lowercase()]

        /** The kinds [tags] belong to, most specific first. */
        fun kindsOf(tags: Map<String, String>): List<ShopKind> = entries.filter { it.matches(tags) }.sortedBy { it.ordinal == 0 }
    }
}

/**
 * The pantry's twelve shelf items, in the board's order: three wooden shelves of four. LATE is not a kind of shop but
 * any food shop open past 22:00 today; SHARE is community food (fridges, food banks, food sharing).
 */
internal enum class Shelf(val tags: String) {
    SUPER("shop=supermarket"),
    CORNER("shop=convenience"),
    FRESH("shop=greengrocer"),
    BAKERY("shop~^(bakery|pastry)$"),
    MEAT_FISH("shop~^(butcher|seafood|fish)$"),
    DELI("shop~^(deli|cheese)$"),
    WORLD("shop=spices or (shop~^(supermarket|convenience|deli|greengrocer|butcher)$ and (cuisine or origin))"),
    REFILL("bulk_purchase~^(yes|only)$ or zero_waste~^(yes|only)$"),
    DRINKS("shop~^(alcohol|beverages|wine)$"),
    FARM("shop=farm or (amenity=vending_machine and vending~(milk|eggs))"),
    LATE(FOOD_SHOPS),
    SHARE(COMMUNITY_FOOD);

    val filter: TagFilter by lazy { TagFilter.parse(tags) }

    /** The route kind of the shelf for `Dest.Groceries(kind)`. */
    val id: String get() = name.lowercase()

    companion object {
        fun of(id: String?): Shelf? = entries.firstOrNull { it.id == id?.lowercase() }

        /** Every place any shelf shows, as one rule: one query fills the whole pantry. */
        val all: TagFilter by lazy { TagFilter.or(*entries.map { it.filter }.toTypedArray()) }
    }
}

/** Food shops: what the LATE shelf counts when they are open past 22:00. */
internal const val FOOD_SHOPS =
    "shop~^(supermarket|convenience|greengrocer|bakery|pastry|butcher|seafood|fish|deli|cheese|spices|alcohol|beverages|wine|farm|confectionery|chocolate|frozen_food|health_food|dairy)$"

/** Free food: community fridges and food sharing, food banks and soup kitchens. */
internal const val COMMUNITY_FOOD =
    "amenity~^(food_sharing|fridge|food_bank|soup_kitchen)$ or social_facility~^(food_bank|soup_kitchen)$ or (amenity=give_box and give_box~food) or community_fridge=yes"

/**
 * The high street's twelve trade signs, in the board's order: each a family of `shop=*` values (and a few amenities
 * for errands), counted around you.
 */
internal enum class Family(val tags: String) {
    FASHION("shop~^(clothes|boutique|fashion|fashion_accessories|bag|jewelry|watches|hat)$"),
    SHOES("shop~^(shoes|leather)$"),
    BOOKS("shop~^(books|music|stationery|newsagent|art|musical_instrument|games|video_games|comic|anime)$"),
    TECH("shop~^(electronics|mobile_phone|computer|hifi|camera|photo|telecommunication|electrical|appliance)$"),
    HOME("shop~^(furniture|interior_decoration|houseware|kitchen|hardware|doityourself|paint|lighting|bed|curtain|carpet|frame|candles|antiques|tiles|bathroom_furnishing|household_linen)$"),
    FLOWERS("shop~^(florist|garden_centre|plant)$"),
    HEALTH("shop~^(chemist|cosmetics|beauty|perfumery|optician|hairdresser|hearing_aids|medical_supply|herbalist|nutrition_supplements|massage)$ or amenity=pharmacy"),
    SPORT("shop~^(sports|outdoor|bicycle|scuba_diving|fishing|ski|surf|golf|swimming_pool)$"),
    KIDS("shop~^(toys|baby_goods|model|craft|party|hobby)$"),
    PETS("shop~^(pet|pet_grooming|animal_feed)$"),
    VINTAGE("shop~^(second_hand|charity|vintage|antiques|collector)$ or second_hand~^(yes|only)$"),
    ERRANDS("shop~^(laundry|dry_cleaning|copyshop|shoe_repair|tailor|key_cutter|locksmith|travel_agency|ticket|money_lender|pawnbroker|storage_rental|repair)$ or amenity~^(post_office|bank|atm|parcel_locker)$ or craft~^(key_cutter|shoemaker|tailor)$");

    val filter: TagFilter by lazy { TagFilter.parse(tags) }

    companion object {
        /** Every family as one rule. */
        val all: TagFilter by lazy { TagFilter.or(*entries.map { it.filter }.toTypedArray()) }

        /** The first family [tags] belong to; null for none. */
        fun of(tags: Map<String, String>): Family? = entries.firstOrNull { it.filter.matches(tags) }
    }
}

/** An errand of the Errands board: where it is done, and the colour of its fob on the key ring. */
internal enum class Errand(val tags: String, val colour: Long, val minutes: Int) {
    PHARMACY("amenity=pharmacy or healthcare=pharmacy", 0xFF1E9E5A, 5),
    PARCEL("amenity=parcel_locker or (amenity=vending_machine and vending=parcel_pickup)", 0xFFC98A2B, 2),
    CASH("amenity=atm or (amenity=bank and atm=yes)", 0xFF1F5F99, 2),
    CLEANING("shop=dry_cleaning", 0xFF7B4DFF, 4),
    POST("amenity=post_office or post_office~^(post_partner|yes|bureau)$", 0xFFD93A3A, 6),
    LAUNDRY("shop=laundry", 0xFF2E5BFF, 5),
    KEYS("craft=key_cutter or shop~^(locksmith|key_cutter)$ or service:key_cutting=yes", 0xFF8C6A3E, 8),
    PRINTING("shop=copyshop or amenity=print_shop", 0xFF464C5A, 6),
    SHOE_REPAIR("shop=shoe_repair or craft=shoemaker", 0xFF8C5A2B, 4),
    TAILOR("craft=tailor or shop=tailor", 0xFF7A2233, 4),
    RECYCLING("amenity=recycling and recycling_type=container", 0xFF2E7D4A, 2);

    val filter: TagFilter by lazy { TagFilter.parse(tags) }

    companion object {
        /** The fobs on the key ring, as on the board; the others are the "more errands" buttons. */
        val ring = listOf(PHARMACY, PARCEL, CASH, CLEANING, POST)
        val more = listOf(LAUNDRY, KEYS, PRINTING, SHOE_REPAIR, TAILOR, RECYCLING)
        val all: TagFilter by lazy { TagFilter.or(*entries.map { it.filter }.toTypedArray()) }
    }
}

/** The Help board's four tiles of free food and water. */
internal enum class HelpKind(val tags: String) {
    FRIDGE("amenity~^(food_sharing|fridge)$ or (amenity=give_box and give_box~food) or community_fridge=yes"),
    PANTRY("amenity=food_bank or social_facility=food_bank"),
    MEALS("amenity=soup_kitchen or social_facility=soup_kitchen or (amenity=social_facility and social_facility:for~(homeless|underprivileged) and food~yes)"),
    WATER("(amenity=drinking_water or (man_made=water_tap and drinking_water=yes)) and access!~^(private|no|customers)$");

    val filter: TagFilter by lazy { TagFilter.parse(tags) }

    companion object {
        val all: TagFilter by lazy { TagFilter.or(*entries.map { it.filter }.toTypedArray()) }
        fun of(tags: Map<String, String>): HelpKind? = entries.firstOrNull { it.filter.matches(tags) }
    }
}

/** The "point me" needs: what each looks for. */
internal enum class Need(val tags: String) {
    TOILET("amenity=toilets or toilets=yes"),
    WATER("amenity=drinking_water or (man_made=water_tap and drinking_water=yes) or drinking_water:refill=yes or refill=yes"),
    FOOD("shop~^(bakery|deli|convenience|supermarket|pastry)$ or amenity~^(fast_food|food_sharing|fridge|food_court)$ or (amenity=give_box and give_box~food)");

    val filter: TagFilter by lazy { TagFilter.parse(tags) }

    companion object {
        fun of(kind: String): Need = when (kind.lowercase()) {
            "water" -> WATER
            "toilet", "toilets", "wc" -> TOILET
            else -> FOOD
        }
    }
}

/**
 * A waste stream of the Bins and Drop boards, with the board's stream colour and the `recycling:*` keys that take it
 * (any one is enough). [RUBBISH] is the plain waste basket.
 */
internal enum class Stream(val colour: Long, val keys: List<String>) {
    RUBBISH(0xFF2B2F36, emptyList()),
    MIXED(0xFF1B5FA8, listOf("cans", "glass_bottles", "glass", "plastic", "plastic_packaging", "plastic_bottles", "beverage_cartons", "pmd")),
    PAPER(0xFF2E7D4A, listOf("paper", "cardboard", "paper_packaging", "newspaper", "magazines")),
    ORGANIC(0xFF8C5A2B, listOf("organic", "food_waste", "green_waste", "compost")),
    BATTERIES(0xFFC8322A, listOf("batteries", "car_batteries")),
    CLOTHES(0xFF6B45B8, listOf("clothes", "shoes", "textiles")),
    ELECTRONICS(0xFF3E4A56, listOf("electrical_appliances", "small_appliances", "electrical_items", "mobile_phones", "computers", "low_energy_bulbs", "light_bulbs", "fluorescent_tubes")),
    OIL(0xFFD9A82B, listOf("cooking_oil", "engine_oil")),
    HAZARDOUS(0xFFF2B705, listOf("hazardous_waste", "paint", "chemicals", "waste_oil"));

    /** True when the place at [tags] takes this stream. */
    fun takes(tags: Map<String, String>): Boolean = when (this) {
        RUBBISH -> tags["amenity"] == "waste_basket" || tags["amenity"] == "waste_disposal"
        else -> keys.any { k -> tags["recycling:$k"]?.let { it == "yes" || it == "only" } == true } ||
            (this == HAZARDOUS && tags["amenity"] == "recycling" && tags["recycling_type"] == "centre" && keys.any { tags["recycling:$it"] != "no" })
    }

    companion object {
        /** Bins and drop-offs: waste baskets, recycling points and centres. */
        val all: TagFilter by lazy { TagFilter.parse("amenity~^(waste_basket|waste_disposal|recycling)$") }

        fun of(id: String?): Stream? = entries.firstOrNull { it.name.equals(id, ignoreCase = true) }
    }
}

/** The Refill board's five containers on the tile wall. */
internal enum class RefillKind(val tags: String) {
    BULK("bulk_purchase~^(yes|only)$ or zero_waste~^(yes|only)$"),
    SOAP("refill~(soap|detergent|shampoo|cleaning) or (shop~^(chemist|organic|health_food|houseware)$ and bulk_purchase~^(yes|only)$)"),
    WATER("amenity=drinking_water or drinking_water:refill=yes or refill:drinking_water=yes"),
    RETURN("amenity=vending_machine and vending~(bottle_return|bottles|deposit) or recycling:bottle_return=yes or (amenity=recycling and recycling:cans=yes and deposit=yes)"),
    VEND("amenity=vending_machine and vending~(milk|eggs|food|cheese|potatoes|meat|bread)");

    val filter: TagFilter by lazy { TagFilter.parse(tags) }

    companion object {
        val all: TagFilter by lazy { TagFilter.or(*entries.map { it.filter }.toTypedArray()) }
    }
}

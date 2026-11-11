package app.atlas.search

import app.atlas.places.CategoryNode
import app.atlas.places.CategoryTree
import app.atlas.places.NodeKind
import app.atlas.places.OpenStatus
import java.time.LocalDateTime

/**
 * How a kind's view is laid out, by what one goes to such a place for (the user, 3 November: "Purpose. A multitude of
 * different generated category screens with generated layouts"): the view is made from its purpose, not drawn once
 * for every kind.
 */
enum class Layout {
    /** The nearest one is the point (a toilet, a cash machine, a defibrillator): the way to it leads, the next two after. */
    NEAREST,

    /** One chooses among many (a restaurant, a hotel, a museum): the best few lead, the rows say what sets each apart. */
    BEST,

    /** Power is the point (a charger): the rows lead with kilowatts and sockets. */
    POWER,

    /** Out of doors, the weather decides (a park, a beach, a lake, a view): the hours ahead lead. */
    WINDOW,

    /** A run to make before they close (groceries, the post, laundry): what closes soon leads. */
    ERRAND,

    /** Everything else: the places, nearest and best first. */
    LIST,
}

/** The orders a kind's results can take. */
enum class Order(val symbol: String) {
    /** The kind's own ranking ([Ranking]): open, near, liked, suiting the moment, each as much as the kind weighs it. */
    FIT("ui-sparkles"),
    NEAREST("ui-navigation"),

    /** Open ones first, those open longest first: a night out. */
    OPEN_LONGEST("ui-clock"),
    RATING("ui-star"),

    /** Free first, then the cheapest. */
    CHEAPEST("ui-coins"),
    POWER("ui-plug-zap"),
    STARS("ui-star"),

    /** The most room first (spaces, docks). */
    ROOM("ui-parking"),

    /** The largest first, by the ground its outline's box covers, as the typed "largest" sorts (a park, a wood, a reserve). */
    LARGEST("ui-cube"),

    /** As typed words asked ("oldest first", "closing latest"): the query's own order. */
    ASKED("ui-arrow-down");

    fun label(lang: String): String = (LABELS[this] ?: LABELS.getValue(FIT)).let { it[lang] ?: it.getValue("en") }

    private companion object {
        fun l(en: String, nl: String, de: String, fr: String) = mapOf("en" to en, "nl" to nl, "de" to de, "fr" to fr)
        val LABELS = mapOf(
            FIT to l("Best now", "Beste nu", "Jetzt am besten", "Le mieux maintenant"),
            NEAREST to l("Nearest", "Dichtstbij", "Am nächsten", "Le plus proche"),
            OPEN_LONGEST to l("Open longest", "Langst open", "Am längsten offen", "Ouvert le plus tard"),
            RATING to l("Best rated", "Best beoordeeld", "Am besten bewertet", "Mieux noté"),
            CHEAPEST to l("Cheapest", "Goedkoopst", "Am günstigsten", "Le moins cher"),
            POWER to l("Most power", "Meeste vermogen", "Meiste Leistung", "Le plus puissant"),
            STARS to l("Most stars", "Meeste sterren", "Meiste Sterne", "Le plus d'étoiles"),
            ROOM to l("Most room", "Meeste plaats", "Meiste Plätze", "Le plus de places"),
            LARGEST to l("Largest", "Grootst", "Am größten", "Le plus grand"),
            ASKED to l("As asked", "Zoals gevraagd", "Wie gefragt", "Comme demandé"),
        )
    }
}

/**
 * What a kind of place is for, and so how its view is made: its [layout]; what its rows say of each place besides how
 * long it takes to reach ([tells], the first that a place maps; the user, 3 November: "Places results always show whats
 * useful and omit whats not"); the filters worth a word of their own before those its places suggest ([words], facet
 * ids "toilets: free, step-free, changing table"); the [orders] its results can take, its own first; and the intent
 * whose certainty parts its results once chosen ([parts]: lakes one may swim in, those where it is unclear, those where
 * one may not).
 */
data class Purpose(
    val layout: Layout,
    val tells: List<TellKey>,
    val words: List<String> = emptyList(),
    val orders: List<Order> = listOf(Order.FIT, Order.NEAREST),
    val parts: String? = null,
) {
    /** The kind's own order. */
    val order: Order get() = orders.first()

    /** Out of doors: the hours ahead belong on its view. */
    val outdoors: Boolean get() = layout == Layout.WINDOW
}

/** Every kind's purpose: by its preset where that has one of its own (a lake, a cash machine), else its category's or shelf's. */
object Purposes {
    /** [node]'s purpose: the nearest of it and its ancestors that has one, else a plain list. */
    fun of(node: CategoryNode): Purpose {
        for (n in CategoryTree.pathOf(node.id).asReversed()) {
            n.preset?.id?.let { pid -> PRESETS.entries.filter { pid == it.key || pid.startsWith(it.key + "/") }.maxByOrNull { it.key.length }?.let { return it.value } }
            when (n.kind) {
                NodeKind.CATEGORY -> CATEGORIES[n.category.id]?.let { return it }
                NodeKind.SHELF -> SHELVES[n.id.removePrefix("shelf:")]?.let { return it }
                else -> Unit
            }
        }
        return LIST
    }

    private fun t(vararg keys: TellKey) = keys.toList()

    private val NEAR = listOf(Order.FIT, Order.NEAREST)
    private val FOOD = listOf(Order.FIT, Order.NEAREST, Order.OPEN_LONGEST, Order.RATING)
    private val OUT = listOf(Order.FIT, Order.NEAREST, Order.RATING)
    // the greens' (the user, 9 November: "Is it somewhere possible to see the largest green areas in your country or
    // around you?")
    private val GREEN = listOf(Order.FIT, Order.NEAREST, Order.LARGEST, Order.RATING)

    val LIST = Purpose(Layout.LIST, t(TellKey.FEE, TellKey.WHEELCHAIR, TellKey.WIKI))

    private val EAT = Purpose(Layout.BEST, t(TellKey.CUISINE, TellKey.DIET, TellKey.OUTDOOR, TellKey.TAKEAWAY), listOf("@open=now", "outdoor_seating=~yes", "diet:vegetarian=~yes", "takeaway=~yes"), FOOD)
    private val DRINK = Purpose(Layout.BEST, t(TellKey.OUTDOOR, TellKey.WHEELCHAIR, TellKey.WIFI), listOf("@open=now", "@open=late", "outdoor_seating=~yes"), listOf(Order.FIT, Order.OPEN_LONGEST, Order.NEAREST, Order.RATING))
    private val CULTURE = Purpose(Layout.BEST, t(TellKey.FEE, TellKey.WHEELCHAIR, TellKey.WIKI), listOf("@open=now", "@price=free", "wheelchair=~yes"), OUT)
    private val STAY = Purpose(Layout.BEST, t(TellKey.STARS, TellKey.WIFI, TellKey.WHEELCHAIR, TellKey.DOG), listOf("internet_access=wlan", "wheelchair=~yes"), listOf(Order.FIT, Order.NEAREST, Order.STARS, Order.RATING))
    private val GROCERIES = Purpose(Layout.ERRAND, t(TellKey.ORGANIC, TellKey.DIET, TellKey.WHEELCHAIR), listOf("@open=now", "organic=~yes", "wheelchair=~yes"), listOf(Order.FIT, Order.NEAREST, Order.OPEN_LONGEST))
    private val SHOPS = Purpose(Layout.ERRAND, t(TellKey.SECOND_HAND, TellKey.ORGANIC, TellKey.WHEELCHAIR), listOf("@open=now", "second_hand=~yes", "wheelchair=~yes"), listOf(Order.FIT, Order.NEAREST, Order.OPEN_LONGEST))
    private val CHARGE = Purpose(Layout.POWER, t(TellKey.POWER, TellKey.SOCKETS, TellKey.FEE, TellKey.CAPACITY), listOf("socket:type2_combo=*", "@price=free", "@open=now"), listOf(Order.FIT, Order.POWER, Order.NEAREST, Order.CHEAPEST))
    // the fuel family's own view, its gas stations and chargers together: the nearest open, each telling what it fills
    // up with, its fuels or its power and plugs
    private val FILL_UP = Purpose(Layout.NEAREST, t(TellKey.FUEL, TellKey.POWER, TellKey.SOCKETS, TellKey.SELF_SERVICE), listOf("@open=now", "@open=247"), NEAR)
    private val OUTSIDE = Purpose(Layout.WINDOW, t(TellKey.LAP, TellKey.DOG, TellKey.TOILETS, TellKey.LIT, TellKey.WIKI), listOf("dog=yes", "wheelchair=~yes"), GREEN)
    private val WATER = Purpose(Layout.WINDOW, t(TellKey.SWIM, TellKey.ADVISORY, TellKey.QUALITY, TellKey.FEE, TellKey.DOG, TellKey.TOILETS), listOf("@price=free", "dog=yes"), OUT, parts = "swim")
    private val HEALTH = Purpose(Layout.NEAREST, t(TellKey.DISPENSING, TellKey.WHEELCHAIR), listOf("@open=now", "wheelchair=~yes"), NEAR)
    private val ON_THE_WAY = Purpose(Layout.NEAREST, t(TellKey.ROUTES, TellKey.COVERED, TellKey.WHEELCHAIR, TellKey.BACKREST), listOf("shelter=~yes", "wheelchair=~yes"), NEAR)
    private val SERVICE = Purpose(Layout.LIST, t(TellKey.OPERATOR, TellKey.WHEELCHAIR, TellKey.FEE), listOf("@open=now", "wheelchair=~yes"), NEAR)
    private val SPORT = Purpose(Layout.LIST, t(TellKey.SPORT, TellKey.INDOOR, TellKey.LIT, TellKey.FEE), listOf("@open=now", "lit=~yes"), NEAR)

    private val CATEGORIES: Map<String, Purpose> = mapOf(
        "restaurants" to EAT,
        "drinks" to DRINK,
        "museums" to CULTURE,
        "heritage" to CULTURE,
        "sights" to Purpose(Layout.BEST, t(TellKey.WIKI, TellKey.FEE, TellKey.WHEELCHAIR), listOf("@price=free", "wheelchair=~yes"), OUT),
        "stage" to Purpose(Layout.BEST, t(TellKey.WHEELCHAIR, TellKey.WIKI), listOf("@open=now", "wheelchair=~yes"), OUT),
        "cinema" to Purpose(Layout.BEST, t(TellKey.WHEELCHAIR), listOf("@open=now", "wheelchair=~yes"), OUT),
        "worship" to Purpose(Layout.LIST, t(TellKey.RELIGION, TellKey.WIKI, TellKey.WHEELCHAIR), emptyList(), NEAR),
        "library" to Purpose(Layout.ERRAND, t(TellKey.WIFI, TellKey.WHEELCHAIR), listOf("@open=now", "internet_access=wlan", "wheelchair=~yes"), NEAR),
        "book_swap" to Purpose(Layout.NEAREST, t(TellKey.COVERED), emptyList(), NEAR),
        "hotels" to STAY,
        "camping" to Purpose(Layout.BEST, t(TellKey.FEE, TellKey.DOG, TellKey.TOILETS, TellKey.POWER), listOf("dog=yes", "@price=free"), OUT),
        "coworking" to Purpose(Layout.BEST, t(TellKey.WIFI, TellKey.FEE), listOf("@open=now", "internet_access=wlan"), FOOD),
        "food_shops" to GROCERIES,
        "shopping" to SHOPS,
        "post" to Purpose(Layout.ERRAND, t(TellKey.COLLECTION, TellKey.OPERATOR), listOf("@open=now"), NEAR),
        "laundry" to Purpose(Layout.ERRAND, t(TellKey.SELF_SERVICE, TellKey.FEE), listOf("@open=now", "self_service=~yes"), NEAR),
        "car_care" to Purpose(Layout.ERRAND, t(TellKey.SELF_SERVICE, TellKey.OPERATOR), listOf("@open=now", "self_service=~yes"), NEAR),
        "recycling" to Purpose(Layout.NEAREST, t(TellKey.RECYCLES), emptyList(), NEAR),
        "vending" to Purpose(Layout.NEAREST, t(TellKey.VENDS, TellKey.FEE), emptyList(), NEAR),
        "charging" to CHARGE,
        "fuel" to Purpose(Layout.NEAREST, t(TellKey.FUEL, TellKey.SELF_SERVICE, TellKey.TOILETS), listOf("@open=now", "@open=247"), NEAR),
        "parking" to Purpose(Layout.NEAREST, t(TellKey.FEE, TellKey.CAPACITY, TellKey.COVERED), listOf("@price=free", "covered=~yes", "wheelchair=~yes"), listOf(Order.FIT, Order.NEAREST, Order.CHEAPEST, Order.ROOM)),
        "toilets" to Purpose(Layout.NEAREST, t(TellKey.FEE, TellKey.WHEELCHAIR, TellKey.CHANGING), listOf("@price=free", "wheelchair=~yes", "changing_table=~yes"), NEAR),
        "showers" to Purpose(Layout.NEAREST, t(TellKey.FEE, TellKey.WHEELCHAIR), listOf("@price=free"), NEAR),
        "drinking_water" to Purpose(Layout.NEAREST, t(TellKey.DRINKABLE, TellKey.BOTTLE), listOf("bottle=~yes"), NEAR),
        "defibrillator" to Purpose(Layout.NEAREST, t(TellKey.WHERE, TellKey.INDOOR, TellKey.LEVEL), listOf("@open=now"), NEAR),
        "money" to Purpose(Layout.NEAREST, t(TellKey.CASH_IN, TellKey.INDOOR, TellKey.OPERATOR), listOf("@open=now", "cash_in=~yes"), NEAR),
        "health" to HEALTH,
        "transit" to ON_THE_WAY,
        "street_furniture" to Purpose(Layout.NEAREST, t(TellKey.BACKREST, TellKey.COVERED), listOf("backrest=~yes", "covered=~yes"), NEAR),
        "picnic" to Purpose(Layout.WINDOW, t(TellKey.COVERED, TellKey.TOILETS, TellKey.DOG), listOf("covered=~yes", "dog=yes"), NEAR),
        "bike_parking" to Purpose(Layout.NEAREST, t(TellKey.COVERED, TellKey.CAPACITY, TellKey.FEE), listOf("covered=~yes", "@price=free"), listOf(Order.FIT, Order.NEAREST, Order.ROOM)),
        "community" to SERVICE,
        "rentals" to Purpose(Layout.NEAREST, t(TellKey.CAPACITY, TellKey.OPERATOR, TellKey.FEE), emptyList(), listOf(Order.FIT, Order.NEAREST, Order.ROOM)),
        "parks" to OUTSIDE,
        "nature_reserve" to OUTSIDE,
        "playground" to Purpose(Layout.WINDOW, t(TellKey.COVERED, TellKey.WHEELCHAIR, TellKey.TOILETS), listOf("wheelchair=~yes"), NEAR),
        "beach" to WATER,
        "boating" to Purpose(Layout.WINDOW, t(TellKey.FEE, TellKey.OPERATOR), emptyList(), OUT),
        "viewpoint" to Purpose(Layout.WINDOW, t(TellKey.ELEVATION, TellKey.WIKI), emptyList(), OUT),
        "nature_spots" to Purpose(Layout.WINDOW, t(TellKey.ELEVATION, TellKey.WIKI), emptyList(), OUT),
        "zoo" to Purpose(Layout.WINDOW, t(TellKey.FEE, TellKey.WHEELCHAIR), listOf("@open=now", "@price=free"), OUT),
        "theme_park" to Purpose(Layout.WINDOW, t(TellKey.FEE, TellKey.WIKI), listOf("@open=now"), OUT),
        "skate" to Purpose(Layout.WINDOW, t(TellKey.LIT, TellKey.COVERED, TellKey.FEE), listOf("lit=~yes"), NEAR),
        "sports" to SPORT,
        "stadium" to Purpose(Layout.LIST, t(TellKey.SPORT, TellKey.CAPACITY), emptyList(), NEAR),
        "climbing" to Purpose(Layout.BEST, t(TellKey.INDOOR, TellKey.FEE), listOf("@open=now", "indoor=~yes"), OUT),
        "gym" to Purpose(Layout.BEST, t(TellKey.FEE, TellKey.WHEELCHAIR), listOf("@open=now", "@open=247"), listOf(Order.FIT, Order.NEAREST, Order.OPEN_LONGEST)),
        "swim" to Purpose(Layout.BEST, t(TellKey.INDOOR, TellKey.FEE, TellKey.WHEELCHAIR), listOf("@open=now", "indoor=~yes"), OUT),
        "sauna_spa" to Purpose(Layout.BEST, t(TellKey.FEE, TellKey.WHEELCHAIR), listOf("@open=now"), FOOD),
        "games" to Purpose(Layout.BEST, t(TellKey.FEE, TellKey.WHEELCHAIR), listOf("@open=now"), FOOD),
        "education" to Purpose(Layout.LIST, t(TellKey.OPERATOR, TellKey.WHEELCHAIR), emptyList(), NEAR),
        "support" to SERVICE,
        "offices" to SERVICE,
        "lost_property" to SERVICE,
        "airport" to Purpose(Layout.LIST, t(TellKey.OPERATOR, TellKey.WIKI), emptyList(), NEAR),
    )

    /** The shelves', for their own views: a shelf's purpose is that of what most go there for. */
    private val SHELVES: Map<String, Purpose> = mapOf(
        "eat" to EAT, "groceries" to GROCERIES, "shops" to SHOPS, "stay" to STAY, "charge" to FILL_UP, "nature" to OUTSIDE,
        "fun" to CULTURE, "water" to WATER, "culture" to CULTURE, "sport" to SPORT, "health" to HEALTH, "service" to SERVICE,
        "transport" to ON_THE_WAY,
    )

    /** Kinds with a purpose of their own, by id-tagging-schema preset id; a preset's narrower presets share it. */
    private val PRESETS: Map<String, Purpose> = mapOf(
        "natural/water" to Purpose(Layout.WINDOW, t(TellKey.SWIM, TellKey.ADVISORY, TellKey.QUALITY, TellKey.ELEVATION, TellKey.WIKI), emptyList(), OUT, parts = "swim"),
        "leisure/swimming_area" to WATER,
        "leisure/bathing_place" to WATER,
        "natural/beach" to WATER,
        "natural/peak" to Purpose(Layout.WINDOW, t(TellKey.ELEVATION, TellKey.WIKI), emptyList(), OUT),
        "natural/volcano" to Purpose(Layout.WINDOW, t(TellKey.ELEVATION, TellKey.WIKI), emptyList(), OUT),
        "tourism/viewpoint" to Purpose(Layout.WINDOW, t(TellKey.ELEVATION, TellKey.WIKI), emptyList(), OUT),
        "amenity/atm" to Purpose(Layout.NEAREST, t(TellKey.CASH_IN, TellKey.INDOOR, TellKey.OPERATOR), listOf("cash_in=~yes"), NEAR),
        "amenity/pharmacy" to Purpose(Layout.NEAREST, t(TellKey.DISPENSING, TellKey.WHEELCHAIR), listOf("@open=now", "dispensing=~yes"), NEAR),
        "amenity/post_box" to Purpose(Layout.NEAREST, t(TellKey.COLLECTION, TellKey.OPERATOR), emptyList(), NEAR),
        "amenity/bench" to Purpose(Layout.NEAREST, t(TellKey.BACKREST, TellKey.COVERED), listOf("backrest=~yes"), NEAR),
        "amenity/bicycle_parking" to Purpose(Layout.NEAREST, t(TellKey.CAPACITY, TellKey.COVERED, TellKey.FEE), listOf("covered=~yes"), listOf(Order.FIT, Order.NEAREST, Order.ROOM)),
        "amenity/cafe" to Purpose(Layout.BEST, t(TellKey.OUTDOOR, TellKey.WIFI, TellKey.DIET), listOf("@open=now", "outdoor_seating=~yes", "internet_access=wlan"), FOOD),
        "amenity/ice_cream" to Purpose(Layout.BEST, t(TellKey.DIET, TellKey.OUTDOOR), listOf("@open=now", "diet:vegan=~yes"), FOOD),
        "amenity/drinking_water" to Purpose(Layout.NEAREST, t(TellKey.DRINKABLE, TellKey.BOTTLE), listOf("bottle=~yes"), NEAR),
        "man_made/water_tap" to Purpose(Layout.NEAREST, t(TellKey.DRINKABLE, TellKey.BOTTLE), listOf("drinking_water=~yes"), NEAR),
        "amenity/recycling" to Purpose(Layout.NEAREST, t(TellKey.RECYCLES), emptyList(), NEAR),
        "amenity/vending_machine" to Purpose(Layout.NEAREST, t(TellKey.VENDS, TellKey.FEE), emptyList(), NEAR),
        "leisure/picnic_table" to Purpose(Layout.NEAREST, t(TellKey.COVERED), listOf("covered=~yes"), NEAR),
        "emergency/defibrillator" to Purpose(Layout.NEAREST, t(TellKey.WHERE, TellKey.INDOOR, TellKey.LEVEL), listOf("@open=now"), NEAR),
        "highway/bus_stop" to ON_THE_WAY,
        "public_transport/platform" to ON_THE_WAY,
        "tourism/camp_site" to Purpose(Layout.BEST, t(TellKey.FEE, TellKey.DOG, TellKey.TOILETS, TellKey.POWER), listOf("dog=yes"), OUT),
    )
}

/**
 * The results of a kind in [order] ([Order.FIT] keeps the ranking). Equal ones keep the ranking's order, those that
 * say nothing of what is ordered by (an unrated place) after those that do.
 */
fun List<ResultHit>.inOrder(order: Order, now: LocalDateTime): List<ResultHit> {
    fun <T : Comparable<T>> by(desc: Boolean, f: (ResultHit) -> T?): List<ResultHit> {
        val (known, unknown) = withIndex().partition { f(it.value) != null }
        val c = compareBy<IndexedValue<ResultHit>> { f(it.value)!! }.let { if (desc) it.reversed() else it }.thenBy { it.index }
        return known.sortedWith(c).map { it.value } + unknown.map { it.value }
    }
    return when (order) {
        Order.FIT, Order.ASKED -> this
        Order.NEAREST -> sortedWith(compareBy<ResultHit> { it.minutes }.thenBy { it.metres })
        Order.OPEN_LONGEST -> by(desc = true) { h -> openFor(h.open, now) }
        Order.RATING -> by(desc = true) { it.record.place.extra["rating"]?.toDoubleOrNull() }
        Order.CHEAPEST -> by(desc = false) { h -> Tells.price(h.record.tags) }
        Order.POWER -> by(desc = true) { Power.maxKw(it.record.tags) }
        Order.STARS -> by(desc = true) { it.record.tags["stars"]?.takeWhile { c -> c.isDigit() }?.toIntOrNull() }
        Order.ROOM -> by(desc = true) { it.record.tags["capacity"]?.trim()?.toIntOrNull() }
        Order.LARGEST -> by(desc = true) { Laps.ground(it.record.place.bbox) }
    }
}

/** Minutes a place stays open from [now]: long for one that does not close ahead; null for one closed or not known. */
private fun openFor(s: OpenStatus?, now: LocalDateTime): Long? {
    if (s == null || !s.isOpen) return null
    val at = s.changesAt ?: return Long.MAX_VALUE
    return java.time.Duration.between(now, at).toMinutes()
}

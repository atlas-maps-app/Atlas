package app.atlas.places

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import java.util.concurrent.ConcurrentHashMap

/**
 * The colour families of places, from the prototype's poi_style.py (food, grocery, shop, beauty, culture, stay,
 * health, service, sport) and the boards' Map home (nature, fun, water, charge, transport). Colours are opaque ARGB.
 */
enum class PlaceFamily(val colour: Int) {
    FOOD(0xFFE07A1F.toInt()),
    GROCERY(0xFF2E9E5B.toInt()),
    SHOP(0xFF8E5BD0.toInt()),
    BEAUTY(0xFFD2508F.toInt()),
    CULTURE(0xFFB8326A.toInt()),
    STAY(0xFF2E6FD8.toInt()),
    HEALTH(0xFFD23C3C.toInt()),
    SERVICE(0xFF5A6478.toInt()),
    SPORT(0xFF1D8FA0.toInt()),
    NATURE(0xFF4C8C3A.toInt()),
    FUN(0xFF7B4FD6.toInt()),
    WATER(0xFF1E7FB8.toInt()),
    CHARGE(0xFF1F9D55.toInt()),
    TRANSPORT(0xFF2E5BFF.toInt()),
}

/** Named colours outside the families. */
object Palette {
    val SERVICE: Int = PlaceFamily.SERVICE.colour

    /** The neutral sign of a chain without a colour of its own (poi_style.py). */
    val NEUTRAL_SIGN: Int = 0xFF26282C.toInt()
    val WHITE: Int = 0xFFFFFFFF.toInt()
}

/**
 * Every symbol Atlas draws, offline: CC0 map symbols (`maki-*`, `temaki-*`, `pinhead-*`) for each preset, category and
 * cuisine; Atlas's UI set (`ui-*`, Lucide style, stroked); and brand glyphs (`logo-*`, simple-icons). Each set is a
 * bundled file read on first use; icons are built once and kept.
 */
object Symbols {
    private val sets = ConcurrentHashMap<String, Map<String, JsonElement>>()
    private val icons = ConcurrentHashMap<String, Icon>()

    private fun fileFor(id: String): String = when {
        id.startsWith("ui-") -> "ui.json"
        id.startsWith("logo-") -> "logos.json"
        else -> "symbols.json"
    }

    private fun set(file: String): Map<String, JsonElement> =
        sets.getOrPut(file) { (Resources.jsonOrNull(file) as? JsonObject) ?: emptyMap() }

    /** The icon [id] ("maki-cafe", "ui-wheelchair", "logo-albertheijn"), or null when Atlas has no such symbol. */
    fun icon(id: String): Icon? {
        icons[id]?.let { return it }
        val e = set(fileFor(id))[id]?.arr ?: return null
        val vb = ViewBox.parse(e[0].str ?: return null)
        val paths = e[1].arr?.mapNotNull { p ->
            val a = p.arr ?: return@mapNotNull null
            val flags = a[0].str ?: ""
            IconPath(a[1].str ?: return@mapNotNull null, stroke = 's' in flags, fill = 'f' in flags, evenOdd = 'e' in flags)
        } ?: return null
        return Icon(id, vb, paths).also { icons[id] = it }
    }

    fun has(id: String): Boolean = set(fileFor(id)).containsKey(id)

    /** Every id in one set: "symbols" (map symbols), "ui" or "logos". */
    fun ids(set: String = "symbols"): Set<String> = set("$set.json").keys

    /**
     * The map symbol for a feature: its id-tagging-schema preset's icon (or the nearest parent preset's), else its
     * category's, else a generic one for its main key, else a plain marker.
     */
    fun symbolFor(tags: Map<String, String>, geometry: Geometry = Geometry.POINT): String {
        Presets.match(tags, geometry)?.let { p -> Presets.iconOf(p)?.let { if (has(it)) return it } }
        Categories.of(tags)?.let { if (has(it.symbol)) return it.symbol }
        for ((key, symbol) in KEY_SYMBOLS) if (key in tags && has(symbol)) return symbol
        return MARKER
    }

    /** The place's colour family, by its tags (poi_style.py's tables), else by its category. */
    fun familyOf(tags: Map<String, String>): PlaceFamily? {
        tags["amenity"]?.let { AMENITY[it] }?.let { return it }
        tags["shop"]?.let { return SHOP[it] ?: PlaceFamily.SHOP }
        tags["tourism"]?.let { TOURISM[it] }?.let { return it }
        tags["leisure"]?.let { LEISURE[it] }?.let { return it }
        if ("office" in tags) return PlaceFamily.SERVICE
        if (!tags["healthcare"].isNullOrEmpty()) return PlaceFamily.HEALTH
        val c = Categories.of(tags) ?: return null
        return FAMILIES[c.parentId ?: c.family]
    }

    /** The place's colour (opaque ARGB): its family's, else the neutral service grey. */
    fun colourFor(tags: Map<String, String>): Int = familyOf(tags)?.colour ?: Palette.SERVICE

    /** The plain marker, when nothing better fits. */
    const val MARKER = "maki-marker"

    /** The plain restaurant symbol, for a cuisine without its own. */
    const val DEFAULT_FOOD = "maki-restaurant"

    private val KEY_SYMBOLS = listOf(
        "shop" to "maki-shop", "office" to "temaki-briefcase", "craft" to "temaki-tools", "historic" to "maki-monument",
        "tourism" to "maki-attraction", "leisure" to "maki-park", "amenity" to MARKER,
    )

    private val FAMILIES = mapOf(
        "eat" to PlaceFamily.FOOD, "groceries" to PlaceFamily.GROCERY, "shops" to PlaceFamily.SHOP, "stay" to PlaceFamily.STAY,
        "charge" to PlaceFamily.CHARGE, "nature" to PlaceFamily.NATURE, "fun" to PlaceFamily.FUN, "water" to PlaceFamily.WATER,
        "culture" to PlaceFamily.CULTURE, "health" to PlaceFamily.HEALTH, "service" to PlaceFamily.SERVICE,
        "sport" to PlaceFamily.SPORT, "transport" to PlaceFamily.TRANSPORT,
    )

    private fun table(family: PlaceFamily, values: String) = values.split(' ').associateWith { family }

    private val AMENITY: Map<String, PlaceFamily> =
        table(PlaceFamily.FOOD, "restaurant cafe pub bar fast_food ice_cream nightclub biergarten food_court") +
            table(PlaceFamily.HEALTH, "pharmacy doctors dentist hospital clinic") +
            table(PlaceFamily.CULTURE, "theatre arts_centre art_gallery events_venue public_bookcase library concert_hall") +
            table(PlaceFamily.SERVICE, "atm bank post_box post_office bicycle_rental") +
            table(PlaceFamily.CHARGE, "charging_station")

    private val SHOP: Map<String, PlaceFamily> =
        table(PlaceFamily.CULTURE, "art") +
            table(PlaceFamily.SERVICE, "ticket copyshop tailor rental vacant laundry dry_cleaning") +
            table(PlaceFamily.HEALTH, "optician chemist medical_supply hearing_aids") +
            table(PlaceFamily.BEAUTY, "hairdresser beauty cosmetics massage tattoo perfumery") +
            table(
                PlaceFamily.GROCERY,
                "supermarket convenience bakery pastry confectionery cheese alcohol wine tea butcher greengrocer deli " +
                    "seafood coffee chocolate health_food organic farm frozen_food dairy spices nuts pasta food beverages water",
            )

    private val TOURISM: Map<String, PlaceFamily> =
        table(PlaceFamily.STAY, "hotel guest_house apartment hostel motel chalet") +
            table(PlaceFamily.CULTURE, "museum gallery attraction artwork")

    private val LEISURE: Map<String, PlaceFamily> =
        table(PlaceFamily.SPORT, "fitness_centre sports_centre pitch stadium track sports_hall swimming_pool ice_rink golf_course horse_riding") +
            table(PlaceFamily.NATURE, "park garden nature_reserve") +
            table(PlaceFamily.WATER, "marina slipway beach_resort")
}

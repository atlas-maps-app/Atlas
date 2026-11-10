package app.atlas.places

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import java.util.concurrent.ConcurrentHashMap

/**
 * The colour families of places: the Map home row's eight (food, grocery, shop, stay, charge, nature, fun, water) and
 * the search's shelves past it (culture and sights, sport, health, service, transport). A place's family is its
 * category's, the first rule of categories.json it passes, so a place wears one colour wherever Atlas draws it: on the
 * row, the map's pins, the search's rows and cards, its own page (README decision 12). Which kind it is within its
 * family its symbol says, never a colour of its own: the prototype's poi_style.py tables, which gave hairdressers pink
 * under the Shops button, are gone (the owner, 5 November: "What to do with all the different subcolors"). The baked
 * tiles' 3D awnings (render PoiStyle, mapcore PoiStyle.cpp) still keep them. Fun is days out, and sport a family of
 * its own (the owner, 5 November: "Are the categories good as they are?").
 */
enum class PlaceFamily(val id: String) {
    FOOD("eat"),
    GROCERY("groceries"),
    SHOP("shops"),
    STAY("stay"),
    CHARGE("charge"),
    NATURE("nature"),
    FUN("fun"),
    WATER("water"),
    CULTURE("culture"),
    SPORT("sport"),
    HEALTH("health"),
    SERVICE("service"),
    TRANSPORT("transport"),
    ;

    /** Opaque ARGB: categories.json's colour for the family's group on the row, else for its shelf. */
    val colour: Int get() = (Categories.byId(id)?.takeIf { it.isGroup } ?: Categories.shelf(id))?.colour ?: Palette.SERVICE
}

/** Named colours outside the families. */
object Palette {
    /** The services' slate (categories.json's), for a category or family categories.json gives no colour. */
    val SERVICE: Int = 0xFF5A6478.toInt()

    /**
     * The sign of a brand whose own colour is not known, the boards' ink (the Brands board's sign without a logo): never
     * a colour made up for it, which would tell of a brand what is not so.
     */
    val NEUTRAL_SIGN: Int = 0xFF14161B.toInt()
    val WHITE: Int = 0xFFFFFFFF.toInt()

    /**
     * How far a closed place's mark goes toward the paper or the day's map under it: lighter, so the open places stand
     * out (the owner, 6 November, of lighter pins that looked closed: "Lighter for closed is a good idea"). Half, the
     * least at which no family's closed mark is taken for another family's open one, by any eye (FamilyColoursTest).
     */
    const val CLOSED_FADE = 0.5f

    /** [argb]'s colour on a closed place, [CLOSED_FADE] of the way to white: the family's still, lighter. */
    fun closed(argb: Int): Int {
        fun channel(shift: Int): Int {
            val c = (argb shr shift) and 0xFF
            return ((c + (0xFF - c) * CLOSED_FADE) + 0.5f).toInt() shl shift
        }
        return (0xFF shl 24) or channel(16) or channel(8) or channel(0)
    }
}

/**
 * Every symbol Atlas draws, offline. The boards' own symbols ([BoardSymbols]: `ui.search`, `cat.eat`, `mode.walk`) come
 * first: [icon] returns the board drawing for a board id or for any older id it stands in for. Behind them are the CC0
 * map symbols (`maki-*`, `temaki-*`, `pinhead-*`) for each preset, category and cuisine, Atlas's own filled symbols in
 * their style for the kinds no set draws (`atlas-*`, places/tools/atlas_icons.txt), the Lucide-style UI set (`ui-*`,
 * stroked), and brand glyphs (`logo-*`, simple-icons). Each set is a bundled file read on first use; icons are
 * built once and kept.
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

    /**
     * The icon [id] ("ui.search", "maki-cafe", "ui-wheelchair", "logo-albertheijn"), or null when Atlas has no such
     * symbol. The boards' drawing wins wherever the boards have one for [id].
     */
    fun icon(id: String): Icon? = BoardSymbols.icon(id) ?: original(id)

    /**
     * The icon [id] from the bundled sets only (Maki, Temaki, Pinhead, Atlas's own `atlas-*`, Lucide, simple-icons),
     * never the boards'.
     */
    fun original(id: String): Icon? {
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

    fun has(id: String): Boolean = BoardSymbols.has(id) || set(fileFor(id)).containsKey(id)

    /** True when [id] draws one of the boards' own symbols (itself a board id, or an id one stands in for). */
    fun isBoard(id: String): Boolean = BoardSymbols.has(id)

    /** The board id that draws [id] ("ui-search" -> "ui.search"), or null when the boards have no symbol for it. */
    fun boardId(id: String): String? = BoardSymbols.resolve(id)?.id

    /** Every id in one set: "board" (the boards' symbols), "symbols" (map symbols), "ui" or "logos". */
    fun ids(set: String = "symbols"): Set<String> = if (set == "board") BoardSymbols.ids() else set("$set.json").keys

    /**
     * The map symbol for a feature: its kind's from the symbols database ([Kinds], its id-tagging-schema preset's icon
     * or the nearest parent preset's until refined), else its category's, else a generic one for its main key, else a
     * plain marker.
     */
    fun symbolFor(tags: Map<String, String>, geometry: Geometry = Geometry.POINT): String {
        Presets.match(tags, geometry)?.let { p -> Kinds.symbolOf(p)?.let { return it } }
        Categories.of(tags)?.let { if (has(it.symbol)) return it.symbol }
        for ((key, symbol) in KEY_SYMBOLS) if (key in tags && has(symbol)) return symbol
        return MARKER
    }

    /**
     * What a place's mark shows on its family's colour, on a pin, a row's disc, a card's badge or its page: its brand's
     * logo when the brand has one, so a chain is known at a glance, else its kind's symbol. The brand's own colours stay
     * on its sign (the owner, 5 November: "think about how should brands be featured everywhere").
     */
    fun markFor(tags: Map<String, String>, geometry: Geometry = Geometry.POINT, country: String? = null): String =
        Brands.forTags(tags, country)?.logoSlug?.let { "logo-$it" }?.takeIf { has(it) } ?: symbolFor(tags, geometry)

    /** The place's colour family: its category's, or null for what Atlas does not list. */
    fun familyOf(tags: Map<String, String>): PlaceFamily? = Categories.of(tags)?.let(::familyOf)

    /** The family of category [c]: its group's on the Map home's row, else its shelf's. */
    fun familyOf(c: Category): PlaceFamily? = FAMILIES[c.parentId ?: c.family]

    /**
     * The place's colour (opaque ARGB): its category's, which is its family's; for what no category takes (a tree, a
     * building), the colour of the search's shelf of everything else on the map, as the search draws it there.
     */
    fun colourFor(tags: Map<String, String>): Int = Categories.of(tags)?.colour ?: other

    private val other: Int by lazy { Categories.shelf(CategoryTree.OSM)?.colour ?: Palette.SERVICE }

    /** The plain marker, when nothing better fits. */
    const val MARKER = "maki-marker"

    /** The plain restaurant symbol, for a cuisine without its own. */
    const val DEFAULT_FOOD = "maki-restaurant"

    private val KEY_SYMBOLS = listOf(
        "shop" to "maki-shop", "office" to "temaki-briefcase", "craft" to "temaki-tools", "historic" to "maki-monument",
        "tourism" to "maki-attraction", "leisure" to "maki-park", "amenity" to MARKER,
    )

    private val FAMILIES = PlaceFamily.entries.associateBy { it.id }
}

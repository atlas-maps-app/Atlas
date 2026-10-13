package app.atlas.places

/**
 * A category of places: one of the Map home's groups (eat, groceries, shops, stay, charge, nature, fun, water), or a
 * leaf with the tag [rule] that puts a place in it. Leaves belong to a group ([parentId]) or to a colour [family]
 * (culture, health, service, sport, transport). [legacyId] is the plan's id (c00..c48, eat, heritage, other).
 */
class Category internal constructor(
    val id: String,
    val isGroup: Boolean,
    val parentId: String?,
    val family: String,
    /** A symbol id for [Symbols.icon]: a map symbol for leaves, a UI symbol for groups. */
    val symbol: String,
    /** Opaque ARGB. */
    val colour: Int,
    /** The tag rule a place must pass; null for groups. */
    val rule: TagFilter?,
    val legacyId: String?,
    private val names: Map<String, String>,
) {
    /** The name in [lang], else in English. */
    fun name(lang: String? = "en"): String = names[Langs.resolve(lang)] ?: names["en"] ?: id

    val parent: Category? get() = parentId?.let { Categories.byId(it) }

    /** The group this category shows under on the Map home, if any: itself for a group. */
    val group: Category? get() = if (isGroup) this else parent

    override fun toString() = id
}

/**
 * Atlas's categories as data (from tools/categories.py): the prototype's ordered rules, where the first match wins,
 * merged with the boards' categories. Loaded on first use from `categories.json`.
 */
object Categories {
    private class Data(val all: List<Category>, val leaves: List<Category>, val byId: Map<String, Category>, val notAPlace: TagFilter)

    private val data: Data by lazy { load() }

    private fun load(): Data {
        val root = Resources.json("categories.json").obj ?: error("categories.json")
        val notAPlace = TagFilter.parse(root["notAPlace"]?.str ?: "")
        val all = root["categories"]?.arr?.mapNotNull { e ->
            val o = e.obj ?: return@mapNotNull null
            Category(
                id = o["id"]?.str ?: return@mapNotNull null,
                isGroup = o["group"] != null,
                parentId = o["parent"]?.str,
                family = o["family"]?.str ?: o["id"]?.str ?: "",
                symbol = o["symbol"]?.str ?: "maki-marker",
                colour = parseColour(o["colour"]?.str) ?: Palette.SERVICE,
                rule = o["rule"]?.str?.let { TagFilter.parse(it) },
                legacyId = o["legacy"]?.str,
                names = o["names"].stringMap(),
            )
        } ?: emptyList()
        return Data(all, all.filter { !it.isGroup }, all.associateBy { it.id }, notAPlace)
    }

    /** Groups first (in the Map home's order), then every leaf in rule order. */
    fun all(): List<Category> = data.all

    /** The Map home's row: eat, groceries, shops, stay, charge, nature, fun, water. */
    fun groups(): List<Category> = data.all.filter { it.isGroup }

    /** The leaves in rule order. */
    fun leaves(): List<Category> = data.leaves

    fun byId(id: String): Category? = data.byId[id]

    /** The leaves under [group], in rule order. */
    fun children(group: String): List<Category> = data.leaves.filter { it.parentId == group }

    /** The leaves that carry the plan's id [legacy] ("c19" has two: food shops and other shops). */
    fun byLegacyId(legacy: String): List<Category> = data.leaves.filter { it.legacyId == legacy }

    /** True unless the tags describe something that is not a place to go (a parking space, a vacant shop, a siren). */
    fun isPlace(tags: Map<String, String>): Boolean = tags.isNotEmpty() && !data.notAPlace.matches(tags)

    /** The first leaf whose rule [tags] pass, or null when it is not a place Atlas lists. */
    fun of(tags: Map<String, String>): Category? {
        if (!isPlace(tags)) return null
        for (c in data.leaves) if (c.rule?.matches(tags) == true) return c
        return null
    }

    /** The Map home group of [tags], if its category has one. */
    fun groupOf(tags: Map<String, String>): Category? = of(tags)?.group
}

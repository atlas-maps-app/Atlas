package app.atlas.search

/**
 * The tags the store's lookup table holds, as pairs of dictionary ids: the keys that make a kind of place (amenity,
 * shop, highway…), and the facets searches narrow by (cuisine, diet, wheelchair, terrace, wifi, sockets, brand…). A
 * `;` list is held by its parts too. Opening hours are held as present (and "24/7" by its value). Other tags are
 * found by reading the places of a box, a kind, a brand or a text match.
 */
internal object TagIndex {
    /** The value id of a row that only says the key is there (a long value, or a key held without values). */
    const val PRESENT = 0

    /** Longest value held by its id; a longer one is held as [PRESENT] and checked on the place. */
    const val MAX_VALUE = StoreSchema.MAX_INDEXED_VALUE

    private val VALUE_KEYS: Set<String> = buildSet {
        addAll(Selection.POI_KEYS)
        addAll(
            listOf(
                // kinds
                "natural", "railway", "highway", "place", "boundary", "landuse", "waterway", "water", "aerialway", "route",
                "barrier", "power", "building", "military", "playground", "information", "vending", "parking",
                "recycling_type", "religion", "denomination", "memorial", "artwork_type", "station", "attraction",
                "bicycle_rental", "admin_level",
                // facets
                "cuisine", "wheelchair", "toilets:wheelchair", "outdoor_seating", "internet_access", "brand:wikidata",
                "takeaway", "delivery", "organic", "dog", "smoking", "fee", "power_supply", "beer_garden", "garden",
                "kids_area", "highchair", "changing_table", "live_music", "drive_through", "self_service",
            ),
        )
    }

    private val VALUE_PREFIXES = listOf("diet:", "socket:", "fuel:")

    private const val HOURS = "opening_hours"

    /** Opening hours values held by their id besides being present. */
    private val HOURS_VALUES = setOf("24/7")

    /** True when [key]'s values are held by id. */
    fun byValue(key: String): Boolean = key in VALUE_KEYS || VALUE_PREFIXES.any { key.startsWith(it) }

    /** True when the table holds [key] at all (by value or as present). */
    fun holds(key: String): Boolean = byValue(key) || key == HOURS

    /** True when [key]=[value] has rows of its own (else it is found among the key's [PRESENT] rows). */
    fun holdsValue(key: String, value: String): Boolean =
        if (key == HOURS) value in HOURS_VALUES else byValue(key) && value.length <= MAX_VALUE

    /** The value ids [key]=[value] is held under: its own, its `;` parts', or [PRESENT]. Interns what is new. */
    fun valueIds(key: String, value: String, dict: Dictionaries): IntArray {
        if (key == HOURS) return if (value in HOURS_VALUES) intArrayOf(PRESENT, dict.internValue(value)) else intArrayOf(PRESENT)
        if (!byValue(key)) return IntArray(0)
        val out = IntArrayList(2)
        out.addDistinct(if (value.length > MAX_VALUE) PRESENT else dict.internValue(value))
        if (';' in value) for (part in value.split(';')) {
            val t = part.trim()
            if (t.isNotEmpty() && t != value) out.addDistinct(if (t.length > MAX_VALUE) PRESENT else dict.internValue(t))
        }
        return out.toArray()
    }
}

/** A small list of ints without boxing. */
internal class IntArrayList(capacity: Int = 8) {
    private var a = IntArray(capacity)

    var size = 0
        private set

    operator fun get(i: Int) = a[i]

    fun add(v: Int) {
        if (size == a.size) a = a.copyOf(maxOf(8, size * 2))
        a[size++] = v
    }

    fun addDistinct(v: Int) {
        for (i in 0 until size) if (a[i] == v) return
        add(v)
    }

    fun clear() {
        size = 0
    }

    fun toArray(): IntArray = a.copyOf(size)
}

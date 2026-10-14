package app.atlas.places

/**
 * Cuisine values (`cuisine=italian;pizza`): a CC0 symbol for each common value, the country of a national cuisine,
 * and names in Atlas's 18 languages from id-tagging-schema's cuisine field.
 */
object Cuisine {
    private class Entry(val symbol: String, val country: String?)

    private val table: Map<String, Entry> by lazy {
        (Resources.json("cuisine.json").obj ?: emptyMap()).mapValues { (_, v) ->
            val a = v.arr
            Entry(a?.getOrNull(0)?.str ?: Symbols.DEFAULT_FOOD, a?.getOrNull(1)?.str)
        }
    }

    /** The values Atlas has symbols for. */
    val known: Set<String> get() = table.keys

    /** The cuisines of a place, in mapped order: "Italian; pizza " -> [italian, pizza]. */
    fun values(tags: Map<String, String>): List<String> = parse(tags["cuisine"])

    /** Splits a cuisine value into normalised parts. */
    fun parse(value: String?): List<String> {
        if (value.isNullOrBlank()) return emptyList()
        return value.split(';').map { it.trim().lowercase().replace(' ', '_') }.filter { it.isNotEmpty() }.distinct()
    }

    /** The symbol for [value], or the plain restaurant symbol. */
    fun symbol(value: String): String = table[norm(value)]?.symbol ?: Symbols.DEFAULT_FOOD

    /** The ISO country of a national cuisine ("italian" -> "IT"), or null. */
    fun country(value: String): String? = table[norm(value)]?.country

    /** The cuisine's name in [lang] (else English), or the value made readable ("fish_and_chips" -> "Fish and chips"). */
    fun name(value: String, lang: String? = "en"): String {
        val v = norm(value)
        return I18n.fieldOption("cuisine", v, lang) ?: v.replace('_', ' ').replaceFirstChar { it.uppercase() }
    }

    private fun norm(v: String) = v.trim().lowercase().replace(' ', '_')
}

package app.atlas.places

/**
 * What a kind is called wherever Atlas names it: what it sells, short ("Clothes", not "Clothing Store"), from [FILE]
 * beside kinds.tsv. The owner, 6 November, of the shops' chips: "instead of clothing store and gift shop only show
 * Clothes, Gifts?", then "Yes and apply that everywhere?". [Presets.name] reads it first; id-tagging-schema's own name
 * ([Presets.fullName]) still names a place that has none of its own, and the search still knows the kind by it.
 */
object KindNames {
    const val FILE = "kind_names.tsv"

    /** The languages of the file's columns after the kind's: the app's own. */
    val LANGS = listOf("en", "nl", "de", "fr")

    private val table: Map<String, List<String?>> by lazy { parse(Resources.text(FILE).orEmpty()) }

    /** Every kind the file names (a preset id), with its names in [LANGS]: null where id-tagging-schema's stays. */
    fun all(): Map<String, List<String?>> = table

    /** The name of kind [preset] in [lang], or null where id-tagging-schema's stands (no line, a `-`, another language). */
    fun of(preset: String, lang: String?): String? {
        val at = LANGS.indexOf(Langs.resolve(lang)).takeIf { it >= 0 } ?: return null
        return table[preset]?.getOrNull(at)
    }

    /**
     * [of], where typed words are to be read as the kind it names: not a narrower kind's ("Tweedehands kleding", its
     * parent's name with a word before it), which reads better as the parent with that word as a filter, taking the
     * shops that sell some second-hand clothes as well as those that sell only those.
     */
    fun searched(preset: String, lang: String?): String? = of(preset, lang)?.takeIf { preset.count { it == '/' } < 2 }

    /** The file's lines, `kind<TAB>en<TAB>nl<TAB>de<TAB>fr`; a line starting with # is a note. */
    private fun parse(text: String): Map<String, List<String?>> = text.lines().filter { it.isNotBlank() && !it.startsWith("#") }
        .associate { line ->
            val cells = line.split('\t').map { it.trim() }
            cells[0] to cells.drop(1).map { c -> c.takeIf { it.isNotEmpty() && it != "-" } }
        }
}

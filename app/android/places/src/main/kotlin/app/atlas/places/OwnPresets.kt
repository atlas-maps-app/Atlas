package app.atlas.places

/**
 * The kinds Atlas lists that id-tagging-schema keeps no preset for, as presets of Atlas's own, from [FILE] beside
 * presets.json: a national park, which id-tagging-schema draws only as a relation (the owner, 9 November: "Yes, do it"
 * to "Give national parks a kind of their own, so their rows say "National park"?"). [Presets.all] takes them after its
 * own and [I18n] their names, so a row, a kind's chip, a place's page and the search name them as any other kind.
 */
internal object OwnPresets {
    const val FILE = "own_presets.tsv"

    /** The languages of the file's name columns: the app's own. */
    val LANGS = listOf("en", "nl", "de", "fr")

    /** One line: a kind [id] made by [tags], drawn as [geometry], asking for the facts of preset [facts]; its names by language. */
    class Line(val id: String, val tags: Map<String, String>, val geometry: Set<Geometry>, val facts: String?, val text: Map<String, I18n.PresetText>)

    val lines: List<Line> by lazy { parse(Resources.text(FILE).orEmpty()) }

    /**
     * The presets of [lines], none of an id [bundled] (id-tagging-schema's, by id) holds: each asks for the facts of the
     * bundled preset its line names.
     */
    fun presets(bundled: Map<String, Preset>): List<Preset> = lines.filter { it.id !in bundled }.map { line ->
        val like = line.facts?.let { bundled[it] }
        Preset(
            id = line.id, tags = line.tags, addTags = emptyMap(), geometry = line.geometry, icon = null,
            fields = like?.fields.orEmpty(), moreFields = like?.moreFields.orEmpty(), matchScore = 1.0, searchable = true,
            countries = emptySet(), excludedCountries = emptySet(),
        )
    }

    /** The file's lines, `kind<TAB>tags<TAB>drawn<TAB>facts<TAB>en<TAB>nl<TAB>de<TAB>fr`; blank lines and `#` notes skipped. */
    internal fun parse(text: String): List<Line> = text.lineSequence().filter { it.isNotBlank() && !it.startsWith("#") }.map { raw ->
        val c = raw.split('\t').map { it.trim() }
        require(c.size == 4 + LANGS.size) { "$FILE: ${c.size} columns in $raw" }
        val tags = c[1].split(',').associate { kv -> kv.substringBefore('=').trim() to kv.substringAfter('=', "").trim() }
        val text = LANGS.zip(c.drop(4)).associate { (lang, cell) ->
            val terms = cell.substringAfter(" | ", "").split(',').map { it.trim() }.filter { it.isNotEmpty() }
            lang to I18n.PresetText(cell.substringBefore(" | ").trim(), terms, emptyList())
        }
        Line(c[0], tags, c[2].mapNotNull { Geometry.of(it) }.toSet(), c[3].takeIf { it != "-" }, text)
    }.toList()
}

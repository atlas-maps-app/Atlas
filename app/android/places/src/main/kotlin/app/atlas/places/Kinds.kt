package app.atlas.places

/**
 * The symbols database, `kinds.tsv`: the symbol Atlas draws for each kind of place it lists, one kind a line, so each can
 * be refined on its own (the owner, 5 November: "an icons and symbols database. To make it easier to refine each one
 * further"). A line names the kind (an id-tagging-schema preset), its symbol (any id [Symbols.icon] draws: the boards'
 * own drawings first, then Maki, Temaki, Pinhead, Atlas's own filled `atlas-*` and the UI set), how far it is refined
 * and why. [Symbols.symbolFor]
 * and the category tree draw from it; KindsTest keeps it whole (every kind Atlas lists has its line, every symbol
 * draws) and draws every line, in its family's colour, onto a sheet to look at.
 */
object Kinds {
    /** How far a kind's symbol is refined: the third column. */
    enum class State(val word: String) {
        /** Not looked at yet: the preset's own icon, or its nearest ancestor's. */
        NEW(""),

        /** Looked at, and right. */
        OK("ok"),

        /** Wants a better symbol; the note says why. */
        REDO("redo"),

        /** One of the boards' own drawings, in Atlas's hand. */
        DRAWN("drawn"),
    }

    /** One line: [kind] (a preset id) is drawn with [symbol]. */
    class Line(val kind: String, val symbol: String, val state: State, val note: String)

    private val lines: Map<String, Line> by lazy { parse(Resources.text(FILE).orEmpty()) }

    const val FILE = "kinds.tsv"

    /** The line of [kind] (a preset id), or null. */
    fun line(kind: String): Line? = lines[kind]

    /** Every line, in the file's order. */
    fun lines(): Collection<Line> = lines.values

    /** The symbol of [preset]: its line's, else its own icon or its nearest ancestor's; null when none of them draws. */
    fun symbolOf(preset: Preset): String? =
        lines[preset.id]?.symbol?.takeIf { Symbols.has(it) } ?: Presets.iconOf(preset)?.takeIf { Symbols.has(it) }

    /**
     * The kinds Atlas lists, each a line: the searchable presets a category takes, drawn as a point or an area, by the
     * category tree's own rule, and the wildcard ones ("Shop", any shop) that stand for the rest of their key.
     */
    fun listed(): List<Preset> = Presets.all.filter { p ->
        p.searchable && p.tags.isNotEmpty() && p.geometry.any { it == Geometry.POINT || it == Geometry.AREA || it == Geometry.VERTEX } &&
            Categories.of(sample(p)) != null
    }

    /** The tags a place of [preset] has, a wildcard read as any value, and a name ([named]). */
    fun sample(preset: Preset): Map<String, String> = named((preset.tags + preset.addTags).mapValues { (_, v) -> if (v == "*") "yes" else v })

    /**
     * [tags] with a name when they have none: a kind is listed as its named places are. An old building is a sight by
     * its name; a nameless one is one more house in the street, and not in the list.
     */
    fun named(tags: Map<String, String>): Map<String, String> = if ("name" in tags) tags else tags + ("name" to "…")

    /** The lines of [text] by kind, the first of a kind kept; blank lines and `#` comments skipped. */
    internal fun parse(text: String): Map<String, Line> {
        val out = LinkedHashMap<String, Line>()
        for (raw in text.lineSequence()) {
            if (raw.isBlank() || raw.startsWith("#")) continue
            val c = raw.split('\t')
            val kind = c[0].trim()
            val symbol = c.getOrNull(1)?.trim().orEmpty()
            if (kind.isEmpty() || symbol.isEmpty() || kind in out) continue
            val word = c.getOrNull(2)?.trim().orEmpty()
            out[kind] = Line(kind, symbol, State.entries.firstOrNull { it.word == word } ?: State.NEW, c.getOrNull(3)?.trim().orEmpty())
        }
        return out
    }
}

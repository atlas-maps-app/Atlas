package app.atlas.places

/**
 * A kind's own colour: its thing's, where everyone knows one (the owner, 6 November: "A bread is brown to me and cheese
 * yellow ... If the category symbols carry the real thing in color they would be very recognizable", then "Real colors
 * look good"). The table, [FILE] beside kinds.tsv (a line `kind #RRGGBB` each), names the foods' and drinks' kinds: a
 * bakery's bread brown, a cheese shop's yellow, a pub's beer. A kind it names wears its colour wherever Atlas draws a
 * place: the map's pins, the rows, the cards, a place's page and the kinds' chips. A kind under one it names takes that
 * one's (a coffee shop a cafe's); every other keeps its family's. Colour still says the family wherever it says
 * anything (README decision 12): FamilyColoursTest holds each kind's colour apart from every other family's, open or
 * closed, for every eye. A table to try in its place, [TRIAL] (`-Patlas.kindColours=<file>` puts one there for a test
 * run), is read instead; an empty one draws every kind in its family's colour.
 */
object KindColours {
    const val FILE = "kind_colours.tsv"
    const val TRIAL = "kind_colours_trial.tsv"

    private val table: Map<String, Int> by lazy { parse(Resources.text(TRIAL) ?: Resources.text(FILE).orEmpty()) }

    /** Every kind the table names (a preset id), with its colour (opaque ARGB). */
    fun all(): Map<String, Int> = table

    /** The colour of [preset]'s kind, or of its nearest ancestor's the table names; null where the family's holds. */
    fun of(preset: Preset): Int? {
        if (table.isEmpty()) return null
        var p: Preset? = preset
        while (p != null) {
            table[p.id]?.let { return it }
            p = p.broader
        }
        return null
    }

    /** The colour of the kind of a place with [tags], or null where the family's holds. */
    fun of(tags: Map<String, String>): Int? = if (table.isEmpty()) null else Presets.match(tags)?.let(::of)

    /** A table's lines, `kind #RRGGBB`; a line starting with # is a note. */
    private fun parse(text: String): Map<String, Int> = text.lines().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }
        .associate { line ->
            val (kind, hex) = line.split(Regex("\\s+"))
            kind to (hex.removePrefix("#").toLong(16).toInt() or (0xFF shl 24))
        }
}

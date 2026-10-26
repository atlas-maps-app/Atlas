package app.atlas.ui.search

import app.atlas.providers.OsmType
import app.atlas.providers.TagEdit

/**
 * The FillIn board's questions about one building (canvas/project/FillIn.dc.html's script): the colour of its front and
 * the shape of its roof, each asked only where the building's tags leave it a guess, as the renders' gaps count them
 * (render/amsterdam.py: a colour is mapped where `building:colour` or `building:material` is). An answer adds the board's
 * tags; "Can't tell" adds nothing. The edit keeps what the user saw of each key it sets, so it never overwrites what
 * someone mapped meanwhile.
 */
object FillIn {
    enum class Ask { COLOUR, ROOF }

    /** A colour of the front: its swatch, its tag value, and the material it implies. */
    data class Colour(val id: String, val hex: String, val material: String)

    /** A roof's shape as seen from the street, its `roof:shape` value. */
    data class Roof(val shape: String)

    /** The board's swatches, in its order: brown brick, red brick, painted white, sandstone, painted black. */
    val COLOURS = listOf(
        Colour("brown", "#5C3A30", "brick"),
        Colour("red", "#94503C", "brick"),
        Colour("white", "#EAE5DA", "plaster"),
        Colour("sand", "#CDB892", "stone"),
        Colour("black", "#2C2C2E", "plaster"),
    )

    /** The board's roofs: a gable to the street (step, neck and spout gables too), hipped, flat. */
    val ROOFS = listOf(Roof("gabled"), Roof("hipped"), Roof("flat"))

    /** What the building's [tags] leave a guess, in the board's order. */
    fun asks(tags: Map<String, String>): List<Ask> = buildList {
        if (tags["building:colour"].isNullOrBlank() && tags["building:material"].isNullOrBlank()) add(Ask.COLOUR)
        if (tags["roof:shape"].isNullOrBlank()) add(Ask.ROOF)
    }

    /** The tags the answers add, as the board lists them: the colour (lower case) and its material, then the roof. */
    fun adds(colour: Colour?, roof: Roof?): List<Pair<String, String>> = buildList {
        if (colour != null) {
            add("building:colour" to colour.hex.lowercase())
            add("building:material" to colour.material)
        }
        if (roof != null) add("roof:shape" to roof.shape)
    }

    /**
     * The edit of the answers on element [type] [id], whose tags the user saw as [seen]; null when the answers add
     * nothing (both "Can't tell").
     */
    fun edit(type: OsmType, id: Long, seen: Map<String, String>, colour: Colour?, roof: Roof?): TagEdit? {
        val adds = adds(colour, roof)
        if (adds.isEmpty()) return null
        return TagEdit(type, id, adds.toMap(), adds.associate { (k, _) -> k to seen[k] })
    }
}

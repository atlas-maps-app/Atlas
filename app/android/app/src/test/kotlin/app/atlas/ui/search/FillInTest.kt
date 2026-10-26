package app.atlas.ui.search

import app.atlas.providers.OsmType
import app.atlas.providers.TagEdit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FillInTest {
    /** Leliegracht 49 as OpenStreetMap has it (the board's building). */
    private val leliegracht = mapOf("building" to "commercial", "height" to "16.1", "start_date" to "1673")

    @Test
    fun aBuildingIsAskedWhatItsTagsLeaveAGuess() {
        assertEquals(listOf(FillIn.Ask.COLOUR, FillIn.Ask.ROOF), FillIn.asks(leliegracht))
        // a material alone maps the colour, as the renders' gaps count it
        assertEquals(listOf(FillIn.Ask.ROOF), FillIn.asks(leliegracht + ("building:material" to "brick")))
        assertEquals(listOf(FillIn.Ask.COLOUR), FillIn.asks(leliegracht + ("roof:shape" to "gabled")))
        assertEquals(emptyList<FillIn.Ask>(), FillIn.asks(leliegracht + ("building:colour" to "white") + ("roof:shape" to "flat")))
    }

    @Test
    fun theAnswersAddTheBoardsTags() {
        val brown = FillIn.COLOURS.first { it.id == "brown" }
        val gabled = FillIn.ROOFS.first()
        assertEquals(
            listOf("building:colour" to "#5c3a30", "building:material" to "brick", "roof:shape" to "gabled"),
            FillIn.adds(brown, gabled),
        )
        assertEquals(listOf("roof:shape" to "flat"), FillIn.adds(null, FillIn.ROOFS.last()))
        assertEquals(emptyList<Pair<String, String>>(), FillIn.adds(null, null))
        assertEquals(listOf("brown", "red", "white", "sand", "black"), FillIn.COLOURS.map { it.id })
        assertEquals(listOf("brick", "brick", "plaster", "stone", "plaster"), FillIn.COLOURS.map { it.material })
        assertEquals(listOf("gabled", "hipped", "flat"), FillIn.ROOFS.map { it.shape })
    }

    @Test
    fun theEditKeepsWhatTheUserSaw() {
        val white = FillIn.COLOURS.first { it.id == "white" }
        assertEquals(
            TagEdit(
                OsmType.WAY, 266616341,
                mapOf("building:colour" to "#eae5da", "building:material" to "plaster"),
                mapOf("building:colour" to null, "building:material" to null),
            ),
            FillIn.edit(OsmType.WAY, 266616341, leliegracht, white, null),
        )
        // nothing answered, nothing to send
        assertNull(FillIn.edit(OsmType.WAY, 266616341, leliegracht, null, null))
    }
}

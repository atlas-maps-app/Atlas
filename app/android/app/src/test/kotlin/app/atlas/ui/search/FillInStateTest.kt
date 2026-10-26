package app.atlas.ui.search

import app.atlas.providers.OsmElement
import app.atlas.providers.OsmType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FillInStateTest {
    private val brown = FillIn.COLOURS.first { it.id == "brown" }
    private val gabled = FillIn.ROOFS.first { it.shape == "gabled" }
    private val leliegracht = mapOf(
        "building" to "commercial", "height" to "16.1", "start_date" to "1673",
        "addr:street" to "Leliegracht", "addr:housenumber" to "49",
    )

    @Test
    fun theBoardsFlowStepByStep() {
        var s = FillInState(FillIn.asks(leliegracht))
        assertEquals(FillIn.Ask.COLOUR, s.ask)
        // Next waits for an answer
        assertFalse(s.canNext)
        assertEquals(s, s.next())
        s = s.pick(brown).next()
        assertEquals(FillIn.Ask.ROOF, s.ask)
        s = s.pick(gabled).next()
        assertTrue(s.reviewing)
        assertEquals(listOf("building:colour" to "#5c3a30", "building:material" to "brick", "roof:shape" to "gabled"), s.adds)
        // change: back to the first question, the answers kept
        assertEquals(brown, s.restart().colour)
        assertEquals(0, s.restart().step)
        s = s.send()
        assertTrue(s.done && s.sent)
    }

    @Test
    fun cantTellForgetsTheAnswerAndNothingToSendIsNotSent() {
        var s = FillInState(FillIn.asks(leliegracht)).pick(brown).skip()
        assertEquals(null, s.colour)
        s = s.skip()
        assertTrue(s.reviewing)
        assertTrue(s.adds.isEmpty())
        val done = s.send()
        assertTrue(done.done)
        assertFalse(done.sent)
    }

    @Test
    fun theReviewNamesWhatStaysAGuess() {
        // the board's: after colour and roof, building:levels · roof:colour · roof:height
        assertEquals(
            listOf("building:levels", "roof:colour", "roof:height"),
            FillInState.stillGuessed(leliegracht, FillIn.adds(brown, gabled)),
        )
        assertEquals("Leliegracht 49", FillInState.address(leliegracht))
        // the review's three lines: two telling tags and a count of the rest; three or fewer all shown
        assertEquals(listOf("building" to "commercial", "height" to "16.1"), FillInState.reviewTags(leliegracht))
        assertEquals(
            listOf("building" to "commercial", "height" to "16.1", "start_date" to "1673"),
            FillInState.reviewTags(mapOf("start_date" to "1673", "height" to "16.1", "building" to "commercial")),
        )
        assertEquals(
            "way 266616341 · Leliegracht 49",
            FillInState.elementLine(OsmElement(OsmType.WAY, 266616341, 7, leliegracht)),
        )
    }
}

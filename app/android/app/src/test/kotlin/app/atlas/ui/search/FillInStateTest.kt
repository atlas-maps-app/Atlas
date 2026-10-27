package app.atlas.ui.search

import app.atlas.mapview.AskedBuilding
import app.atlas.model.OsmId
import app.atlas.providers.OsmElement
import app.atlas.providers.OsmType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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
    fun theBuildingAskedAboutAsTheBoardDrawsIt() {
        val id = OsmId(app.atlas.model.OsmType.WAY, 123L)
        val open = AskedBuilding(id, 0xA2A5A9, hatched = true)
        val answered = AskedBuilding(id, 0x453433, hatched = false)
        var s = FillInState(FillIn.asks(leliegracht))
        // the board's grey, hatched, while the colour is open (none_*)
        assertEquals(open, s.asked(id))
        // the answer's colour as the board draws it (brown_*), plain, from the pick through the end
        s = s.pick(brown)
        assertEquals(answered, s.asked(id))
        s = s.next().pick(gabled).next().send()
        assertTrue(s.done)
        assertEquals(answered, s.asked(id))
        // "Can't tell" leaves it open
        assertEquals(open, FillInState(FillIn.asks(leliegracht)).pick(brown).skip().asked(id))
        // a colour mapped already: the map draws the building as mapped
        assertNull(FillInState(FillIn.asks(leliegracht + ("building:material" to "brick"))).asked(id))
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

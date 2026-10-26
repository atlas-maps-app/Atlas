package app.atlas.services

import app.atlas.providers.EditsSent
import app.atlas.providers.OsmType
import app.atlas.providers.TagEdit
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.time.Instant

class EditQueueTest {
    @get:Rule
    val dir = TemporaryFolder()

    private val now = Instant.parse("2026-10-26T20:30:00Z")
    private val colour = TagEdit(OsmType.WAY, 266616341, mapOf("building:colour" to "#8A5A44"), mapOf("building:colour" to null))
    private val roof = TagEdit(OsmType.WAY, 266616341, mapOf("roof:shape" to "gabled"), mapOf("roof:shape" to null))
    private val other = TagEdit(OsmType.WAY, 4242, mapOf("building:colour" to "white"), mapOf("building:colour" to null))

    private fun file() = File(dir.root, "edits/queue.json")

    @Test
    fun editsSurviveARestart() {
        val q = EditQueue.open(file()) { now }
        q.add(colour, "Leliegracht 49 · colour of the front")
        q.add(roof, "Leliegracht 49 · roof")
        val again = EditQueue.open(file()) { now }
        assertEquals(q.all.value, again.all.value)
        assertEquals(listOf(1L, 2L), again.pending.map { it.id })
        assertEquals(colour, again.pending.first().edit)
        assertEquals(now, again.pending.first().at)
        // a removal survives too: its value is null, not the text "null"
        val removal = TagEdit(OsmType.NODE, 7, mapOf("opening_hours" to null), mapOf("opening_hours" to "Mo-Fr 09:00-17:00"))
        again.add(removal, "Café · hours")
        assertEquals(removal, EditQueue.open(file()) { now }.pending.last().edit)
    }

    @Test
    fun aPendingEditCanBeTakenBack() {
        val q = EditQueue.open(file()) { now }
        val a = q.add(colour, "colour")
        q.add(other, "other")
        assertTrue(q.undo(a.id))
        assertFalse(q.undo(a.id))
        assertEquals(listOf(other), EditQueue.open(file()) { now }.pending.map { it.edit })
    }

    @Test
    fun aSendKeepsWhatCameOfEachEdit() = runBlocking {
        val q = EditQueue.open(file()) { now }
        q.add(colour, "colour")
        q.add(other, "other")
        var asked: List<TagEdit> = emptyList()
        val result = q.sendPending { edits ->
            asked = edits
            EditsSent(123, applied = listOf(colour), conflicts = listOf(other))
        }
        assertEquals(listOf(colour, other), asked)
        assertEquals(123L, result!!.changeset)
        val states = EditQueue.open(file()) { now }.all.value.associate { it.edit to (it.state to it.changeset) }
        assertEquals(EditQueue.State.SENT to 123L, states[colour])
        assertEquals(EditQueue.State.CONFLICT to null, states[other])
        assertTrue(q.pending.isEmpty())
        // nothing left to send
        assertNull(q.sendPending { error("nothing should be sent") })
        q.clearDone()
        assertTrue(q.all.value.isEmpty())
    }

    @Test
    fun aFailedSendLeavesEveryEditPending() = runBlocking {
        val q = EditQueue.open(file()) { now }
        q.add(colour, "colour")
        try {
            q.sendPending { throw IOException("offline") }
        } catch (_: IOException) {
        }
        assertEquals(listOf(colour), EditQueue.open(file()) { now }.pending.map { it.edit })
    }

    @Test
    fun anUnreadableFileStartsEmpty() {
        file().parentFile!!.mkdirs()
        file().writeText("{not json")
        assertTrue(EditQueue.open(file()) { now }.all.value.isEmpty())
    }
}

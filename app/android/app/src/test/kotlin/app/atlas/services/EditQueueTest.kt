package app.atlas.services

import app.atlas.providers.EditsSent
import app.atlas.providers.HttpException
import app.atlas.providers.OsmRefusedException
import app.atlas.providers.OsmSignedOutException
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

    private var now = Instant.parse("2026-10-26T20:30:00Z")
    private val colour = TagEdit(OsmType.WAY, 266616341, mapOf("building:colour" to "#8A5A44"), mapOf("building:colour" to null))
    private val roof = TagEdit(OsmType.WAY, 266616341, mapOf("roof:shape" to "gabled"), mapOf("roof:shape" to null))
    private val other = TagEdit(OsmType.WAY, 4242, mapOf("building:colour" to "white"), mapOf("building:colour" to null))

    private fun file() = File(dir.root, "edits/queue.json")

    private fun open() = EditQueue.open(file()) { now }

    /** Leliegracht 49's place, where these edits are made. */
    private fun EditQueue.add(edit: TagEdit, label: String) = add(edit, label, 52.37557, 4.88641)

    /** What [block] threw, failing unless it is a [T]. */
    private inline fun <reified T : Throwable> fails(block: () -> Unit): T {
        try {
            block()
        } catch (e: Throwable) {
            if (e is T) return e
            throw e
        }
        throw AssertionError("expected ${T::class.simpleName}")
    }

    @Test
    fun editsSurviveARestart() {
        val q = open()
        q.add(colour, "Leliegracht 49 · colour of the front")
        q.add(roof, "Leliegracht 49 · roof")
        val again = open()
        assertEquals(q.all.value, again.all.value)
        assertEquals(listOf(1L, 2L), again.pending.map { it.id })
        assertEquals(colour, again.pending.first().edit)
        assertEquals(now, again.pending.first().at)
        assertEquals(52.37557 to 4.88641, again.pending.first().let { it.lat to it.lon })
        // a removal survives too: its value is null, not the text "null"
        val removal = TagEdit(OsmType.NODE, 7, mapOf("opening_hours" to null), mapOf("opening_hours" to "Mo-Fr 09:00-17:00"))
        again.add(removal, "Café · hours")
        assertEquals(removal, open().pending.last().edit)
    }

    @Test
    fun aPendingEditCanBeTakenBack() {
        val q = open()
        val a = q.add(colour, "colour")
        q.add(other, "other")
        assertTrue(q.undo(a.id))
        assertFalse(q.undo(a.id))
        assertEquals(listOf(other), open().pending.map { it.edit })
    }

    @Test
    fun aSendKeepsWhatCameOfEachEdit() = runBlocking {
        val q = open()
        q.add(colour, "colour")
        q.add(other, "other")
        var asked: List<TagEdit> = emptyList()
        val result = q.sendPending { batch ->
            asked = batch.map { it.edit }
            EditsSent(123, applied = listOf(colour), conflicts = listOf(other))
        }
        assertEquals(listOf(colour, other), asked)
        assertEquals(listOf(EditQueue.State.SENT, EditQueue.State.CONFLICT), result!!.map { it.state })
        val kept = open().all.value
        assertEquals(EditQueue.State.SENT to 123L, kept[0].state to kept[0].changeset)
        assertEquals(now, kept[0].sentAt)
        assertEquals(EditQueue.State.CONFLICT to null, kept[1].state to kept[1].changeset)
        assertTrue(q.pending.isEmpty())
        // the map draws as mapped only what OpenStreetMap took
        assertEquals(listOf(colour), q.mapped.map { it.edit })
        // nothing left to send
        assertNull(q.sendPending { error("nothing should be sent") })
        // a sent edit is OpenStreetMap's now; one in conflict can be taken back
        assertFalse(q.undo(kept[0].id))
        assertTrue(q.undo(kept[1].id))
        q.clear()
        assertTrue(open().all.value.isEmpty())
    }

    @Test
    fun aServerOutOfReachOrBusyLeavesEveryEditWaitingAndSaysWhy() = runBlocking {
        val q = open()
        q.add(colour, "colour")
        fails<IOException> { q.sendPending { throw IOException("offline") } }
        assertEquals(listOf(colour), open().pending.map { it.edit })
        assertEquals(EditQueue.Trouble.UNREACHABLE, open().pending.single().trouble)
        fails<HttpException> { q.sendPending { throw HttpException(503, "https://api.openstreetmap.org/api/0.6/changeset/create", "") } }
        assertEquals(EditQueue.Trouble.BUSY, q.pending.single().trouble)
        // a sign-in no longer accepted leaves them waiting as they were
        fails<OsmSignedOutException> { q.sendPending { throw OsmSignedOutException() } }
        assertEquals(EditQueue.Trouble.BUSY, q.pending.single().trouble)
        // the send that gets through clears it
        q.sendPending { EditsSent(5, listOf(colour), emptyList()) }
        assertNull(q.all.value.single().trouble)
    }

    @Test
    fun aRefusalSaysWhyAndIsSentAgainOnlyWhenTheUserAsks() = runBlocking {
        val q = open()
        val a = q.add(colour, "colour")
        fails<OsmRefusedException> { q.sendPending { throw OsmRefusedException(403, "You must agree to the contributor terms") } }
        val refused = open().all.value.single()
        assertEquals(EditQueue.State.REFUSED, refused.state)
        assertEquals("You must agree to the contributor terms", refused.reason)
        assertNull("a refused edit is not sent by itself", q.sendPending { error("sent") })
        assertTrue(q.retry(a.id))
        assertFalse(q.retry(a.id))
        assertEquals(listOf(colour), q.pending.map { it.edit })
        assertNull(q.pending.single().reason)
    }

    @Test
    fun aNewEditOfABuildingReplacesOneThatCouldNotGo() = runBlocking {
        val q = open()
        q.add(colour, "colour")
        q.sendPending { EditsSent(null, emptyList(), listOf(colour)) }
        q.add(other, "other")
        q.add(roof, "roof")
        assertEquals(listOf(other, roof), q.all.value.map { it.edit })
    }

    @Test
    fun anEditBeingSentIsNeitherTakenBackNorSentTwice() = runBlocking {
        val q = open()
        val a = q.add(colour, "colour")
        val result = q.sendPending { batch ->
            assertFalse(q.undo(a.id))
            assertNull(q.sendPending { error("sent twice") })
            q.clear()
            EditsSent(9, batch.map { it.edit }, emptyList())
        }
        assertEquals(EditQueue.State.SENT, result!!.single().state)
        assertEquals(listOf(9L), q.all.value.map { it.changeset })
        assertTrue(q.undo(q.add(other, "other").id))
    }

    @Test
    fun sentEditsAreKeptForTheMapNinetyDaysAndWaitingOnesForGood() = runBlocking {
        val q = open()
        q.add(colour, "colour")
        q.sendPending { EditsSent(7, listOf(colour), emptyList()) }
        q.add(other, "other")
        now = now.plus(EditQueue.KEEP_SENT).minusSeconds(1)
        assertEquals(listOf(colour, other), open().all.value.map { it.edit })
        now = now.plusSeconds(2)
        assertEquals(listOf(other), open().all.value.map { it.edit })
    }

    @Test
    fun anEditSaysWhereItStands() {
        val q = EditQueue.Queued(1, colour, "colour", 52.37557, 4.88641, now, EditQueue.State.PENDING)
        assertEquals(EditStatus.SIGNED_OUT, EditStatus.of(q, signedIn = false, online = true))
        assertEquals(EditStatus.OFFLINE, EditStatus.of(q, signedIn = true, online = false))
        assertEquals(EditStatus.SENDING, EditStatus.of(q, signedIn = true, online = true))
        assertEquals(EditStatus.BUSY, EditStatus.of(q.copy(trouble = EditQueue.Trouble.BUSY), signedIn = true, online = true))
        assertEquals(EditStatus.UNREACHABLE, EditStatus.of(q.copy(trouble = EditQueue.Trouble.UNREACHABLE), signedIn = true, online = true))
        assertEquals(EditStatus.MAPPED, EditStatus.of(q.copy(state = EditQueue.State.SENT), signedIn = false, online = false))
        assertEquals(EditStatus.CONFLICT, EditStatus.of(q.copy(state = EditQueue.State.CONFLICT), signedIn = true, online = true))
        assertEquals(EditStatus.REFUSED, EditStatus.of(q.copy(state = EditQueue.State.REFUSED), signedIn = true, online = true))
        assertEquals(
            setOf(EditStatus.SIGNED_OUT, EditStatus.OFFLINE, EditStatus.UNREACHABLE, EditStatus.BUSY, EditStatus.SENDING),
            EditStatus.entries.filter { it.waiting }.toSet(),
        )
    }

    @Test
    fun anUnreadableFileStartsEmpty() {
        file().parentFile!!.mkdirs()
        file().writeText("{not json")
        assertTrue(open().all.value.isEmpty())
    }
}

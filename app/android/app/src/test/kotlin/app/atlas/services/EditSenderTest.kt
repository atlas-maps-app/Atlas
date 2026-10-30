package app.atlas.services

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import app.atlas.providers.Http
import app.atlas.providers.OsmEdits
import app.atlas.providers.OsmType
import app.atlas.providers.TagEdit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.time.Instant

/**
 * The user's edits sent to OpenStreetMap's API, against a local server that answers as the API does: the request
 * sequence (read the building, open a changeset, upload, close it), what each edit came to, and nothing sent before the
 * user signed in.
 */
class EditSenderTest {
    @get:Rule
    val dir = TemporaryFolder()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val server = MockWebServer().apply { start() }

    @After
    fun stop() {
        scope.cancel()
        runCatching { server.close() }
    }

    /** Leliegracht 49 as the API returns it (the FillIn board's building), at [version]. */
    private fun leliegracht(version: Int = 7) = """{"version":"0.6","elements":[{"type":"way","id":266616341,""" +
        """"timestamp":"2024-05-01T10:00:00Z","version":$version,"changeset":1,"user":"someone","uid":1,""" +
        """"nodes":[11,12,13,14,11],"tags":{"building":"commercial","height":"16.1"}}]}"""

    private val colour = TagEdit(OsmType.WAY, 266616341, mapOf("building:colour" to "#8A5A44"), mapOf("building:colour" to null))
    private val roof = TagEdit(OsmType.WAY, 266616341, mapOf("roof:shape" to "gabled"), mapOf("roof:shape" to null))

    private fun ok(body: String = "") = MockResponse.Builder().code(200).body(body).build()

    private fun answer(code: Int, error: String? = null) =
        MockResponse.Builder().code(code).body(error.orEmpty()).apply { if (error != null) addHeader("Error", error) }.build()

    private val queue by lazy { EditQueue.open(File(dir.root, "edits/queue.json")) { Instant.parse("2026-10-30T20:00:00Z") } }

    private val osm by lazy { OsmEdits(Http(policies = emptyMap()), server.url("/").toString().trimEnd('/'), createdBy = "Atlas 0.24") }

    /** OpenStreetMap's sign-in, giving token tok-1 to Erika. */
    private class SignIn : OsmAccount.SignIn {
        override val redirectUri = OsmAccount.REDIRECT

        override fun page(challenge: String, state: String) = "https://www.openstreetmap.org/oauth2/authorize?state=$state"

        override suspend fun exchange(code: String, verifier: String) = "tok-1"

        override suspend fun userName(token: String) = "Erika"
    }

    private fun account(signedIn: Boolean): OsmAccount = runBlocking {
        val sealer = object : Sealer {
            override fun seal(plain: ByteArray) = plain.map { (it.toInt() xor 0x5A).toByte() }.toByteArray()

            override fun open(sealed: ByteArray) = seal(sealed)
        }
        val a = OsmAccount(PreferenceDataStoreFactory.create(scope = scope) { File(dir.root, "osm.preferences_pb") }, sealer, SignIn(), scope)
        if (signedIn) {
            val state = a.begin()!!.toHttpUrl().queryParameter("state")!!
            assertTrue(a.finish("${OsmAccount.REDIRECT}?code=c&state=$state"))
        }
        a
    }

    private val RecordedRequest.text: String get() = body?.utf8().orEmpty()

    private fun RecordedRequest.line() = "$method ${url.encodedPath}"

    @Test
    fun theEditsGoInOneChangesetWithWhatTheyFilledInAndAreDrawnAsMapped() = runBlocking {
        val a = account(signedIn = true)
        queue.add(colour, "Leliegracht 49 · building:colour", 52.37557, 4.88641)
        queue.add(roof, "Leliegracht 49 · roof:shape", 52.37557, 4.88641)
        server.enqueue(ok(leliegracht()))
        server.enqueue(ok("126"))
        server.enqueue(ok("""<diffResult version="0.6"><way old_id="266616341" new_id="266616341" new_version="8"/></diffResult>"""))
        server.enqueue(ok())
        val mapped = mutableListOf<EditQueue.Queued>()
        assertEquals(EditSender.Outcome.SENT, EditSender.send(queue, a, osm) { mapped += it })

        val read = server.takeRequest()
        assertEquals("GET /api/0.6/way/266616341.json", read.line())
        val open = server.takeRequest()
        assertEquals("PUT /api/0.6/changeset/create", open.line())
        assertEquals("Bearer tok-1", open.headers["Authorization"])
        assertTrue(open.text, open.text.contains("""<tag k="created_by" v="Atlas 0.24"/>"""))
        assertTrue(open.text, open.text.contains("""<tag k="comment" v="Building colour and roof shape as seen from the street"/>"""))
        val upload = server.takeRequest()
        assertEquals("POST /api/0.6/changeset/126/upload", upload.line())
        assertEquals("Bearer tok-1", upload.headers["Authorization"])
        // the building as OpenStreetMap has it now, both answers merged into it
        assertTrue(upload.text, upload.text.contains("""<way id="266616341" version="7" changeset="126">"""))
        for (tag in listOf("building" to "commercial", "height" to "16.1", "building:colour" to "#8A5A44", "roof:shape" to "gabled")) {
            assertTrue(upload.text, upload.text.contains("""<tag k="${tag.first}" v="${tag.second}"/>"""))
        }
        val close = server.takeRequest()
        assertEquals("PUT /api/0.6/changeset/126/close", close.line())
        assertEquals(4, server.requestCount)

        assertEquals(listOf(EditQueue.State.SENT to 126L, EditQueue.State.SENT to 126L), queue.all.value.map { it.state to it.changeset })
        assertEquals(listOf(colour, roof), mapped.map { it.edit })
        assertEquals(EditSender.Outcome.NOTHING, EditSender.send(queue, a, osm))
        assertEquals(4, server.requestCount)
    }

    @Test
    fun aBuildingChangedMeanwhileIsReadAgainAndSentOnce() = runBlocking {
        val a = account(signedIn = true)
        queue.add(colour, "colour", 52.37557, 4.88641)
        server.enqueue(ok(leliegracht(7)))
        server.enqueue(ok("127"))
        server.enqueue(answer(409, "Version mismatch: Provided 7, server had: 8 of Way 266616341"))
        server.enqueue(ok(leliegracht(8)))
        server.enqueue(ok("<diffResult/>"))
        server.enqueue(ok())
        assertEquals(EditSender.Outcome.SENT, EditSender.send(queue, a, osm))
        val lines = List(6) { server.takeRequest() }
        assertEquals(
            listOf(
                "GET /api/0.6/way/266616341.json", "PUT /api/0.6/changeset/create", "POST /api/0.6/changeset/127/upload",
                "GET /api/0.6/way/266616341.json", "POST /api/0.6/changeset/127/upload", "PUT /api/0.6/changeset/127/close",
            ),
            lines.map { it.line() },
        )
        assertTrue(lines[4].text.contains("""version="8""""))
        assertEquals(EditQueue.State.SENT, queue.all.value.single().state)
    }

    @Test
    fun nothingIsSentBeforeTheUserSignsIn() = runBlocking {
        val a = account(signedIn = false)
        queue.add(colour, "colour", 52.37557, 4.88641)
        assertEquals(EditSender.Outcome.SIGNED_OUT, EditSender.send(queue, a, osm))
        assertEquals(0, server.requestCount)
        assertEquals(listOf(colour), queue.pending.map { it.edit })
    }

    @Test
    fun aSignInOpenStreetMapNoLongerAcceptsSignsTheUserOut() = runBlocking {
        val a = account(signedIn = true)
        queue.add(colour, "colour", 52.37557, 4.88641)
        server.enqueue(ok(leliegracht()))
        server.enqueue(answer(401))
        assertEquals(EditSender.Outcome.SIGNED_OUT, EditSender.send(queue, a, osm))
        assertNull(a.token())
        assertEquals(2, server.requestCount)
        assertEquals(listOf(colour), queue.pending.map { it.edit })
    }

    @Test
    fun aRefusalSaysWhyAndIsNotSentAgainByItself() = runBlocking {
        val a = account(signedIn = true)
        queue.add(colour, "colour", 52.37557, 4.88641)
        server.enqueue(ok(leliegracht()))
        server.enqueue(answer(403, "You must agree to the contributor terms before editing"))
        assertEquals(EditSender.Outcome.REFUSED, EditSender.send(queue, a, osm))
        val q = queue.all.value.single()
        assertEquals(EditQueue.State.REFUSED, q.state)
        assertEquals("You must agree to the contributor terms before editing", q.reason)
        assertEquals(EditSender.Outcome.NOTHING, EditSender.send(queue, a, osm))
        assertEquals(2, server.requestCount)
    }

    @Test
    fun aBusyOrUnreachableServerIsTriedAgainLater() = runBlocking {
        val a = account(signedIn = true)
        queue.add(colour, "colour", 52.37557, 4.88641)
        server.enqueue(answer(503))
        assertEquals(EditSender.Outcome.RETRY, EditSender.send(queue, a, osm))
        assertEquals(EditQueue.Trouble.BUSY, queue.pending.single().trouble)
        val gone = server.url("/").toString().trimEnd('/')
        server.close()
        assertEquals(EditSender.Outcome.RETRY, EditSender.send(queue, a, OsmEdits(Http(policies = emptyMap()), gone)))
        assertEquals(EditQueue.Trouble.UNREACHABLE, queue.pending.single().trouble)
        assertEquals("tok-1", a.token())
    }

    @Test
    fun theCommentSaysWhatWasFilledIn() {
        assertEquals("Building colour as seen from the street", EditSender.comment(listOf(colour)))
        assertEquals("Building colour and roof shape as seen from the street", EditSender.comment(listOf(colour, roof)))
        val elsewhere = TagEdit(OsmType.WAY, 4242, mapOf("building:colour" to "white"), mapOf("building:colour" to null))
        assertEquals("Building colour of 2 buildings, as seen from the street", EditSender.comment(listOf(colour, elsewhere)))
        assertEquals("Building details as seen from the street", EditSender.comment(listOf(TagEdit(OsmType.NODE, 1, mapOf("x" to "y"), mapOf("x" to null)))))
    }
}

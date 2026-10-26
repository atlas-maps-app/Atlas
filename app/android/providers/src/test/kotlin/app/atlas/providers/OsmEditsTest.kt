package app.atlas.providers

import kotlinx.coroutines.runBlocking
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.URLDecoder
import java.util.Collections

class OsmEditsTest {
    /** Leliegracht 49 as the API returns it (the FillIn board's building). */
    private val leliegracht = """{"version":"0.6","elements":[{"type":"way","id":266616341,"timestamp":"2024-05-01T10:00:00Z",""" +
        """"version":7,"changeset":1,"user":"someone","uid":1,"nodes":[11,12,13,14,11],""" +
        """"tags":{"building":"commercial","height":"16.1","start_date":"1673"}}]}"""

    private val colour = TagEdit(OsmType.WAY, 266616341, mapOf("building:colour" to "#8A5A44"), mapOf("building:colour" to null))

    private fun form(body: String): Map<String, String> =
        body.split('&').associate { it.substringBefore('=') to URLDecoder.decode(it.substringAfter('='), "UTF-8") }

    @Test
    fun pkceIsRfc7636s() {
        // RFC 7636, appendix B
        assertEquals("E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM", OsmAuth.challenge("dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk"))
        val v = OsmAuth.verifier()
        assertEquals(43, v.length)
        assertTrue(v.all { it.isLetterOrDigit() || it == '-' || it == '_' })
    }

    @Test
    fun theSignInPageAsksForPkceAndTheScopes() {
        val url = OsmAuth(testHttp(), "client-1", "atlas://osm-signin").authorizeUrl("chal", "state-1").toHttpUrl()
        assertEquals("www.openstreetmap.org", url.host)
        assertEquals("/oauth2/authorize", url.encodedPath)
        assertEquals("code", url.queryParameter("response_type"))
        assertEquals("client-1", url.queryParameter("client_id"))
        assertEquals("atlas://osm-signin", url.queryParameter("redirect_uri"))
        assertEquals("read_prefs write_api", url.queryParameter("scope"))
        assertEquals("chal", url.queryParameter("code_challenge"))
        assertEquals("S256", url.queryParameter("code_challenge_method"))
        assertEquals("state-1", url.queryParameter("state"))
    }

    @Test
    fun theCodeBecomesATokenAndTheTokenAName() = runBlocking {
        Mock().use { m ->
            m.enqueue("""{"access_token":"tok-1","token_type":"Bearer","scope":"read_prefs write_api","created_at":1}""")
            m.enqueue("""{"version":"0.6","user":{"id":42,"display_name":"Erika"}}""")
            val auth = OsmAuth(testHttp(), "client-1", "atlas://osm-signin", web = m.base, api = m.base)
            assertEquals("tok-1", auth.exchange("code-1", "verifier-1"))
            val exchange = m.take()
            assertEquals("POST", exchange.method)
            assertEquals("/oauth2/token", exchange.url.encodedPath)
            val f = form(exchange.text)
            assertEquals("authorization_code", f["grant_type"])
            assertEquals("code-1", f["code"])
            assertEquals("verifier-1", f["code_verifier"])
            assertEquals("client-1", f["client_id"])
            assertEquals("atlas://osm-signin", f["redirect_uri"])
            assertEquals("Erika", auth.userName("tok-1"))
            assertEquals("Bearer tok-1", m.take().headers["Authorization"])
        }
    }

    @Test
    fun anEditIsReadAppliedAndSentInOneChangeset() = runBlocking {
        Mock().use { m ->
            val paths = Collections.synchronizedList(ArrayList<String>())
            val bodies = Collections.synchronizedMap(HashMap<String, String>())
            val auth = Collections.synchronizedMap(HashMap<String, String?>())
            m.dispatch { req ->
                val path = req.url.encodedPath
                paths += "${req.method} $path"
                bodies[path] = req.text
                auth[path] = req.headers["Authorization"]
                when (path) {
                    "/api/0.6/way/266616341.json" -> response(leliegracht)
                    "/api/0.6/changeset/create" -> response("123")
                    "/api/0.6/changeset/123/upload" -> response("<diffResult/>")
                    "/api/0.6/changeset/123/close" -> response("")
                    else -> response("", 404)
                }
            }
            val sent = OsmEdits(testHttp(), m.base, createdBy = "Atlas 0.1").send("tok-1", listOf(colour), "Building colour, seen from the street")
            assertEquals(123L, sent.changeset)
            assertEquals(listOf(colour), sent.applied)
            assertTrue(sent.conflicts.isEmpty())
            assertEquals(
                listOf(
                    "GET /api/0.6/way/266616341.json",
                    "PUT /api/0.6/changeset/create",
                    "POST /api/0.6/changeset/123/upload",
                    "PUT /api/0.6/changeset/123/close",
                ),
                paths,
            )
            // the reads go without the token, the writes with it
            assertNull(auth["/api/0.6/way/266616341.json"])
            for (p in listOf("/api/0.6/changeset/create", "/api/0.6/changeset/123/upload", "/api/0.6/changeset/123/close")) {
                assertEquals("Bearer tok-1", auth[p])
            }
            assertEquals(
                """<osm><changeset><tag k="created_by" v="Atlas 0.1"/><tag k="comment" v="Building colour, seen from the street"/>""" +
                    """<tag k="source" v="survey"/></changeset></osm>""",
                bodies["/api/0.6/changeset/create"],
            )
            // the way whole, at the version read, its nodes and every tag kept, the new one added
            assertEquals(
                """<osmChange version="0.6" generator="Atlas 0.1"><modify><way id="266616341" version="7" changeset="123">""" +
                    """<nd ref="11"/><nd ref="12"/><nd ref="13"/><nd ref="14"/><nd ref="11"/>""" +
                    """<tag k="building" v="commercial"/><tag k="height" v="16.1"/><tag k="start_date" v="1673"/>""" +
                    """<tag k="building:colour" v="#8A5A44"/></way></modify></osmChange>""",
                bodies["/api/0.6/changeset/123/upload"],
            )
        }
    }

    @Test
    fun anEditOfWhatChangedMeanwhileIsAConflictAndNothingIsOpened() = runBlocking {
        Mock().use { m ->
            // someone mapped a colour since the user looked
            m.enqueue(leliegracht.replace("\"start_date\":\"1673\"", "\"start_date\":\"1673\",\"building:colour\":\"white\""))
            val sent = OsmEdits(testHttp(), m.base).send("tok-1", listOf(colour), "Building colour")
            assertNull(sent.changeset)
            assertTrue(sent.applied.isEmpty())
            assertEquals(listOf(colour), sent.conflicts)
            assertEquals(1, m.count)
        }
    }

    @Test
    fun anUploadThatMeetsANewerVersionReadsAgainOnce() = runBlocking {
        Mock().use { m ->
            var reads = 0
            var uploads = 0
            m.dispatch { req ->
                when (req.url.encodedPath) {
                    "/api/0.6/way/266616341.json" -> {
                        reads++
                        // the second read finds version 8: another tag changed, not the user's
                        response(if (reads == 1) leliegracht else leliegracht.replace("\"version\":7", "\"version\":8").replace("16.1", "16.2"))
                    }
                    "/api/0.6/changeset/create" -> response("124")
                    "/api/0.6/changeset/124/upload" -> {
                        uploads++
                        if (uploads == 1) response("Version mismatch", 409) else response("<diffResult/>")
                    }
                    "/api/0.6/changeset/124/close" -> response("")
                    else -> response("", 404)
                }
            }
            val sent = OsmEdits(testHttp(), m.base).send("tok-1", listOf(colour), "Building colour")
            assertEquals(124L, sent.changeset)
            assertEquals(listOf(colour), sent.applied)
            assertEquals(2, reads)
            assertEquals(2, uploads)
        }
    }

    @Test
    fun aNodeKeepsItsPlaceAndValuesAreEscaped() {
        val node = OsmElement(OsmType.NODE, 5, 3, mapOf("name" to "Café \"De Kat\" & co"), lat = 52.3745123, lon = 4.8838)
        val edits = OsmEdits(testHttp(), "http://127.0.0.1:9")
        val edited = edits.applied(node, TagEdit(OsmType.NODE, 5, mapOf("opening_hours" to "Mo-Fr 08:00-18:00"), mapOf("opening_hours" to null)))!!
        assertEquals(
            """<osmChange version="0.6" generator="Atlas"><modify><node id="5" version="3" changeset="9" lat="52.3745123" lon="4.8838">""" +
                """<tag k="name" v="Café &quot;De Kat&quot; &amp; co"/><tag k="opening_hours" v="Mo-Fr 08:00-18:00"/></node></modify></osmChange>""",
            edits.osmChangeXml(9, listOf(edited)),
        )
        // removing a tag the user saw
        val removed = edits.applied(edited, TagEdit(OsmType.NODE, 5, mapOf("opening_hours" to null), mapOf("opening_hours" to "Mo-Fr 08:00-18:00")))!!
        assertEquals(setOf("name"), removed.tags.keys)
        // an edit of another element does not apply
        assertNull(edits.applied(node, TagEdit(OsmType.WAY, 5, mapOf("a" to "b"), mapOf("a" to null))))
    }
}

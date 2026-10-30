package app.atlas.render.data

import app.atlas.geo.BBox
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import mockwebserver3.SocketEffect
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.io.IOException
import java.time.Instant
import java.util.concurrent.TimeUnit

/**
 * The phone's windows across the public Overpass servers, each a fake server here, asked as the phone asks them
 * (MapData's post): a server that is busy (429, 502, 504), gives no answer, or answers 200 with an error page fails
 * over to the next at once and rests, so the next window does not ask it again; the one that answered last is asked
 * first, one whose data is behind last; and where every server rests, a window fails at once without asking any.
 */
class OverpassHostsTest {
    private val servers = List(3) { MockWebServer() }
    private var now = 1_000_000L
    private val pauses = ArrayList<Long>()
    private val box = BBox(4.88, 52.37, 4.89, 52.38)
    private val fresh = Instant.parse("2026-10-29T00:00:00Z")

    @Before
    fun start() = servers.forEach { it.start() }

    @After
    fun stop() = servers.forEach { it.close() }

    /** MapData's post: OkHttp, every status but 2xx an IOException; a short wait for the answer, for the tests. */
    private val okhttp = OkHttpClient.Builder().readTimeout(500, TimeUnit.MILLISECONDS).build()
    private val post = HttpPost { url, form, agent ->
        val b = FormBody.Builder()
        for ((k, v) in form) b.add(k, v)
        val res = okhttp.newCall(Request.Builder().url(url).header("User-Agent", agent).post(b.build()).build()).execute()
        if (!res.isSuccessful) {
            val msg = runCatching { res.body.string().take(200) }.getOrDefault("")
            res.close()
            throw IOException("$url answered ${res.code} $msg")
        }
        res.body.byteStream()
    }

    private fun host(i: Int) = servers[i].url("/").toString().removeSuffix("/")

    private fun client() = OverpassClient(http = post, hosts = servers.indices.map(::host), pause = { pauses += it }, clock = { now })

    private fun answer(time: String = "2026-10-30T02:00:00Z") = MockResponse.Builder().code(200).body(
        """{"version":0.6,"generator":"Overpass API","osm3s":{"timestamp_osm_base":"$time","copyright":"ODbL"},""" +
            """"elements":[{"type":"node","id":1,"lat":52.375,"lon":4.885,"tags":{"amenity":"cafe"}}]}""",
    ).build()

    private fun status(code: Int) = MockResponse.Builder().code(code)
        .body("""<html><body><p><strong style="color:#FF0000">Error</strong>: runtime error: the server is at capacity</p></body></html>""").build()

    private fun stalled() = MockResponse.Builder().onResponseStart(SocketEffect.Stall).build()

    private fun diff(time: String, body: String = "") = MockResponse.Builder().code(200).body(
        "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<osm version=\"0.6\" generator=\"Overpass API\">\n<meta osm_base=\"$time\"/>\n$body\n</osm>\n",
    ).build()

    /** Server [i] answers every request with [response]. */
    private fun always(i: Int, response: () -> MockResponse) {
        servers[i].dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest) = response()
        }
    }

    private fun asked() = servers.map { it.requestCount }

    private fun window(c: OverpassClient): File {
        val f = File.createTempFile("window", ".json")
        f.deleteOnExit()
        c.window(box, f, freshAfter = fresh)
        return f
    }

    @Test
    fun aBusyServerIsFollowedByTheNextAtOnceAndTheNextWindowAsksTheOneThatAnswered() {
        always(0) { status(502) }
        always(1) { answer() }
        always(2) { answer() }
        val c = client()
        assertTrue(window(c).readText().contains("\"timestamp_osm_base\":\"2026-10-30T02:00:00Z\""))
        assertEquals(listOf(1, 1, 0), asked())
        // the second window asks the server that answered, and never the busy one while it rests
        window(c)
        window(c)
        assertEquals(listOf(1, 3, 0), asked())
        assertEquals("no pause between servers", emptyList<Long>(), pauses)
    }

    @Test
    fun aServerThatGivesNoAnswerRestsAndIsAskedAgainOnceItsRestIsOver() {
        always(0) { stalled() }
        always(1) { answer() }
        val c = client()
        window(c)
        assertEquals(listOf(1, 1, 0), asked())
        // the server that answered fails now: the next is asked, not the one that gave no answer, which still rests
        always(1) { status(504) }
        always(2) { answer() }
        window(c)
        assertEquals(listOf(1, 2, 1), asked())
        // a minute on, the first rests no more; the one that answered last is still asked first
        now += OverpassClient.REST_MS
        always(0) { answer() }
        window(c)
        assertEquals(listOf(1, 2, 2), asked())
    }

    @Test
    fun anErrorPageThatSaysOkIsNotTakenForTheWindowsData() {
        always(0) { MockResponse.Builder().code(200).body("<html><head><title>502 Bad Gateway</title></head><body>nginx</body></html>").build() }
        always(1) { answer() }
        val c = client()
        val f = window(c)
        assertTrue(f.readText().startsWith("{\"version\":0.6"))
        window(c)
        assertEquals("the server with the error page rests", listOf(1, 2, 0), asked())
    }

    @Test
    fun everyServerFailingRestsThemAllAndTheNextWindowFailsWithoutAskingAny() {
        always(0) { status(429) }
        always(1) { status(504) }
        always(2) { stalled() }
        val c = client()
        val first = runCatching { window(c) }.exceptionOrNull()
        assertTrue("$first", first is IOException && first.message!!.contains("504"))
        assertEquals(listOf(1, 1, 1), asked())
        // every server rests: the next windows fail at once, and no server is asked
        val resting = runCatching { window(c) }.exceptionOrNull()
        assertTrue("$resting", resting is IOException && resting.message!!.contains("failed lately"))
        runCatching { c.windowDiff(box, "2026-10-22T00:00:00Z", fresh) }
        assertEquals(listOf(1, 1, 1), asked())
        // after a minute each is asked once more; failing again, each rests twice as long
        now += OverpassClient.REST_MS
        runCatching { window(c) }
        assertEquals(listOf(2, 2, 2), asked())
        now += OverpassClient.REST_MS
        runCatching { window(c) }
        assertEquals("still resting after one more minute", listOf(2, 2, 2), asked())
        now += OverpassClient.REST_MS
        always(1) { answer() }
        window(c)
        assertEquals(listOf(3, 3, 2), asked())
        assertEquals(emptyList<Long>(), pauses)
    }

    @Test
    fun aServerWhoseDataIsBehindIsAskedAfterTheOthers() {
        always(0) { answer(time = "2026-06-01T08:52:28Z") }
        always(1) { answer() }
        always(2) { answer() }
        val c = client()
        window(c)
        assertEquals(listOf(1, 1, 0), asked())
        // the one that answered goes first; when it fails, the other fresh one is asked before the one behind
        always(1) { status(502) }
        window(c)
        assertEquals(listOf(1, 2, 1), asked())
        // where only the one behind answers, its data is taken: better old data than none
        always(2) { status(502) }
        now += OverpassClient.REST_CAP_MS
        always(1) { status(502) }
        val f = window(c)
        assertEquals(Instant.parse("2026-06-01T08:52:28Z"), OverpassClient.dataDate(f.readBytes()))
    }

    @Test
    fun aRemarkOnTheChangesLeavesTheServerAskedForWholeWindows() {
        always(0) { diff("2026-10-30T02:00:00Z", "<remark> runtime error: open64: 2 No such file or directory /osm3s_attic </remark>") }
        always(1) { diff("2026-10-30T02:00:00Z", "<action type=\"create\"><node id=\"1\" lat=\"52.375\" lon=\"4.885\"/></action>") }
        val c = client()
        val bytes = c.windowDiff(box, "2026-10-22T00:00:00Z", fresh)
        assertTrue(String(bytes).contains("<action type=\"create\">"))
        assertEquals(listOf(1, 1, 0), asked())
        // the server without the history still gives whole windows when the one that answered fails
        always(0) { answer() }
        always(1) { status(504) }
        window(c)
        assertEquals(listOf(2, 2, 0), asked())
    }
}

package app.atlas.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** One record of the Overpass servers for every client: the map's windows and the app's queries see the same rests. */
class OverpassServersTest {
    private var now = 1_000_000L
    private val servers = OverpassServers(clock = { now })

    @Test
    fun aServerIsKnownByItsHostWhicheverClientNamesIt() {
        // the app's client names the interpreter, the map's the server
        assertEquals("overpass-api.de", OverpassServers.keyOf("https://overpass-api.de/api/interpreter"))
        assertEquals("overpass-api.de", OverpassServers.keyOf("https://overpass-api.de"))
        assertEquals("localhost:8080", OverpassServers.keyOf("http://localhost:8080/api/interpreter"))
        servers.failed("https://overpass-api.de", pauseMs = 60_000, capMs = 30 * 60_000)
        assertEquals(now + 60_000, servers.restUntil("https://overpass-api.de/api/interpreter"))
        assertEquals("a server on another port is another", Long.MIN_VALUE, servers.restUntil("http://localhost:8081"))
    }

    @Test
    fun failuresInARowFromEitherClientRestAServerLongerUpToTheCap() {
        val url = "https://maps.mail.ru/osm/tools/overpass/api/interpreter"
        servers.failed(url, pauseMs = 10_000, capMs = 600_000)
        assertEquals(now + 10_000, servers.restUntil(url))
        now += 10_000
        servers.failed("https://maps.mail.ru", pauseMs = 60_000, capMs = 1_800_000)
        assertEquals("the second in a row, twice as long", now + 120_000, servers.restUntil(url))
        now += 120_000
        servers.failed(url, pauseMs = 10_000, capMs = 20_000)
        assertEquals("never past the asker's cap", now + 20_000, servers.restUntil(url))
        // a shorter rest leaves a longer one standing
        servers.failed("https://maps.mail.ru", pauseMs = 60_000, capMs = 1_800_000)
        val long = servers.restUntil(url)
        servers.failed(url, pauseMs = 1, capMs = 1)
        assertEquals(long, servers.restUntil(url))
        // an answer ends the rest and the count
        servers.answered(url)
        assertEquals(Long.MIN_VALUE, servers.restUntil(url))
        servers.failed(url, pauseMs = 10_000, capMs = 600_000)
        assertEquals(now + 10_000, servers.restUntil(url))
    }

    @Test
    fun theServerThatAnsweredLastIsAskedFirstUntilItFails() {
        servers.answered("https://overpass-api.de/api/interpreter")
        assertTrue(servers.isFirst("https://overpass-api.de"))
        servers.answered("https://overpass.private.coffee", first = true)
        assertTrue(servers.isFirst("https://overpass.private.coffee/api/interpreter"))
        assertFalse(servers.isFirst("https://overpass-api.de"))
        // an answer that is not to be asked first (the map's data behind) takes that place from it
        servers.answered("https://overpass.private.coffee", first = false)
        assertFalse(servers.isFirst("https://overpass.private.coffee"))
        servers.answered("https://overpass-api.de")
        servers.failed("https://overpass-api.de/api/interpreter", pauseMs = 10_000, capMs = 600_000)
        assertFalse(servers.isFirst("https://overpass-api.de"))
    }

    @Test
    fun aServerIsBusyWhileTheLimiterSaysSo() {
        val running = HashSet<String>()
        val shared = OverpassServers(clock = { now }, inUse = { it in running })
        assertFalse(shared.busy("https://overpass-api.de"))
        running += "https://overpass-api.de"
        assertTrue(shared.busy("https://overpass-api.de"))
    }
}

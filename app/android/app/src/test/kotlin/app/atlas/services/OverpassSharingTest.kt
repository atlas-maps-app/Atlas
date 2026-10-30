package app.atlas.services

import app.atlas.providers.Overpass
import app.atlas.testing.testServices
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * The map's windows and the app's own Overpass queries share one record of the servers and one limiter (the user, 30
 * October: "are all sources reliable and respond fast?"): a server either client found down rests for both, and a query
 * under way on a server in either makes it busy for the other, which asks a free one first.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class OverpassSharingTest {
    @Test
    fun aQueryUnderWayInTheLimiterMakesItsServerBusyForBothClients() {
        val services = testServices()
        assertSame("the app's queries keep the shared record", services.overpassServers, services.overpass.servers)
        val main = Overpass.ENDPOINTS.first()
        assertFalse(services.overpassServers.busy(main))
        // a window in its turn (MapData's posts): the server is busy, under either client's name for it, and only that one
        val turn = services.http.limiter.turn("overpass-api.de")
        assertTrue(services.overpassServers.busy(main))
        assertTrue(services.overpassServers.busy("https://overpass-api.de"))
        assertFalse(services.overpassServers.busy(Overpass.ENDPOINTS[1]))
        turn.close()
        assertFalse(services.overpassServers.busy(main))
    }

    @Test
    fun theMapsWindowsAskThroughTheSameRecordAndTurns() {
        val android = File(System.getProperty("atlas.repo") ?: "../..", "app/android")
        val mapData = File(android, "app/src/main/kotlin/app/atlas/services/MapData.kt").readText()
        assertTrue(mapData.contains("OverpassClient(post.inTurns(services.http.limiter::turn), hosts = OVERPASS_HOSTS, servers = services.overpassServers)"))
    }
}

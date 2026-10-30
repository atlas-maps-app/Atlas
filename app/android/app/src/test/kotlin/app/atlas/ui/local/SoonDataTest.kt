package app.atlas.ui.local

import app.atlas.Services
import app.atlas.model.OsmId
import app.atlas.model.OsmType
import app.atlas.model.Place
import app.atlas.providers.Forecast
import app.atlas.testing.testServices
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Instant
import java.time.ZoneId
import kotlin.coroutines.EmptyCoroutineContext

/**
 * The next hours' sources asked side by side, each to its deadline (the user, 30 October: "are all sources reliable
 * and respond fast?"): one after another, a slow line kept the Local, Soon, Moments and Sky screens empty for minutes.
 * On the test's clock, so what is measured is the waiting, not the machine.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SoonDataTest {
    private val here = Services.WESTERKERK
    private val now = Instant.parse("2026-10-30T12:00:00Z")
    private val zone = ZoneId.of("Europe/Amsterdam")
    private val kept = Forecast(here, 2.0, "Europe/Amsterdam", 3600, null, emptyList(), emptyList(), emptyList(), now.minusSeconds(3600), kept = true)
    private val noordermarkt = Found(
        Place(OsmId(OsmType.NODE, 1), app.atlas.geo.LatLon(52.3760, 4.8860), mapOf("amenity" to "marketplace", "name" to "Noordermarkt")), 240.0,
    )

    @Test
    fun theSourcesAreAskedSideBySideAndEachWaitedForOnlyToItsDeadline() = runTest {
        val s = testServices()
        s.local.onlineOverride = true
        val asked = ArrayList<String>()
        val sources = SoonSources(
            // the weather services never answer: past the deadline, the forecast kept
            forecast = { online -> asked += "forecast $online"; if (online) awaitCancellation() else kept },
            // Overpass answers in ten seconds: in time
            markets = { online -> asked += "markets $online"; if (online) { delay(10_000); listOf(noordermarkt) } else emptyList() },
            // CelesTrak takes a minute: past the deadline, the passes of the orbit data kept or bundled
            outlook = { online -> asked += "outlook $online"; if (!online) IssOutlook(null, emptyList()) else { delay(60_000); error("too late") } },
        )
        val t0 = testScheduler.currentTime
        val snap = loadSoon(s, here, now, zone, sources, deadlineMs = 15_000, context = EmptyCoroutineContext)
        // the screen waited for one deadline, not for one source after another (15 s + 10 s + 15 s)
        assertEquals(15_000L, testScheduler.currentTime - t0)
        assertEquals(setOf("forecast true", "markets true", "outlook true"), asked.take(3).toSet())
        assertEquals(setOf("forecast false", "outlook false"), asked.drop(3).toSet())
        assertSame(kept, snap.forecast)
        assertEquals(false, snap.noForecast)
        assertEquals(listOf(noordermarkt), snap.markets)
        assertTrue(snap.outlook!!.passes.isEmpty())
        s.close()
    }

    @Test
    fun offlineEachSourceIsAskedOfThePhoneAloneAndAtOnce() = runTest {
        val s = testServices()
        s.local.onlineOverride = false
        val asked = ArrayList<String>()
        val sources = SoonSources(
            forecast = { online -> asked += "forecast $online"; null },
            markets = { online -> asked += "markets $online"; emptyList() },
            // a broken orbit file costs the station's lines, not the screen
            outlook = { online -> asked += "outlook $online"; throw java.io.IOException("unreadable") },
        )
        val snap = loadSoon(s, here, now, zone, sources, context = EmptyCoroutineContext)
        assertEquals(0L, testScheduler.currentTime)
        assertEquals(setOf("forecast false", "markets false", "outlook false"), asked.toSet())
        assertTrue(snap.noForecast)
        assertTrue(snap.outlook!!.noData)
        s.close()
    }
}

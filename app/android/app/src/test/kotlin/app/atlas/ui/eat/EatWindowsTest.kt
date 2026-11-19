package app.atlas.ui.eat

import app.atlas.geo.BBox
import app.atlas.geo.LatLon
import app.atlas.geo.Mercator
import app.atlas.geo.TileId
import app.atlas.providers.Http
import app.atlas.providers.Overpass
import app.atlas.services.LocalData
import app.atlas.services.PlaceOrigin
import app.atlas.services.WindowPlaces
import app.atlas.testing.testServices
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.GZIPOutputStream

/**
 * The eat screens' places where no region's store or pack holds them, at a border no pack reaches past: the circle's
 * windows west of it come from Atlas's packs beside Overpass, which is asked at once, as they cannot make it whole (the
 * app maintainer, 18 November 2026, batch 5 A1). Where Overpass fails at once, the answer holds them; where it is slow,
 * they show within the wait. On ff1d7649 neither: they were not waited for, and the failure answered before they came.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class EatWindowsTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val server = MockWebServer()
    private val asked = AtomicInteger()

    @After
    fun stop() = server.close()

    /** Leidschendam's Damplein's window; east of the next column no pack holds a window. */
    private val tile = Mercator.tileAt(LatLon(52.0830, 4.3900), WindowPlaces.LEVEL)
    private val border = tile.x + 1

    /** 150 m west of the border, 75 m south of the window's north edge: the eat circle meets two windows by two. */
    private val centre = tile.bbox.let { b -> LatLon(b.north - 0.05 * (b.north - b.south), b.west + 0.9 * (b.east - b.west)) }

    /** The cafés of the two windows west of the border the circle meets, by the names [cafeAt] gives them. */
    private val west = setOf("${tile.x}/${tile.y - 1}", "${tile.x}/${tile.y}")

    @Test
    fun whereOverpassFailsAtOnceTheAnswerHoldsTheWindowsThePacksSend() {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                asked.incrementAndGet()
                return MockResponse.Builder().code(504).body("busy").build()
            }
        }
        server.start()
        val op = Overpass(Http(), listOf(server.url("/api/interpreter").toString()))
        val repo = repo(op, waitMs = 5_000L) { Thread.sleep(300) }
        val area = runBlocking { withTimeout(30_000) { repo.area(centre).first { !it.loading } } }
        assertNotNull(area.error)
        assertEquals(PlaceOrigin.PACK, area.origin)
        assertEquals(west, area.places.map { it.name }.toSet())
        assertEquals(1, asked.get())
    }

    @Test
    fun whereOverpassIsSlowThePacksPartComesWithinTheWait() {
        val gate = CountDownLatch(1)
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                asked.incrementAndGet()
                gate.await(20, TimeUnit.SECONDS)
                return MockResponse.Builder().code(200).addHeader("Content-Type", "application/json").body("""{"version":0.6,"elements":[]}""").build()
            }
        }
        server.start()
        val op = Overpass(Http(), listOf(server.url("/api/interpreter").toString()))
        val repo = repo(op, waitMs = 3_000L) { Thread.sleep(100) }
        val started = System.nanoTime()
        val flow = repo.area(centre)
        var seen = -1L
        while (seen < 0 && System.nanoTime() - started < 8_000_000_000L) {
            if (flow.value.places.map { it.name }.toSet() == west) seen = (System.nanoTime() - started) / 1_000_000 else Thread.sleep(20)
        }
        gate.countDown()
        runBlocking { withTimeout(30_000) { flow.first { !it.loading } } }
        assertTrue("the windows' cafés did not show before Overpass answered", seen >= 0)
        assertTrue("the windows' cafés showed after $seen ms", seen < 3_500)
        assertEquals(1, asked.get())
    }

    /**
     * The eat screens' state with a phone that holds no region and keeps no window: the windows come from the packs,
     * each after [slow], none past the border; a lookup before found those past it in no pack.
     */
    private fun repo(op: Overpass, waitMs: Long, slow: () -> Unit): EatRepository {
        val dir = tmp.newFolder()
        val windows = WindowPlaces({ dir }, packed = { x, y -> slow(); if (x >= border) null else cafeAt(x, y) })
        val past = TileId(WindowPlaces.LEVEL, border + 1, tile.y).bbox.center
        runBlocking { windows.fetched(BBox.around(past, 1_000.0), past, 10_000L) }
        val local = LocalData(stores = { null }, packs = { null }, overpass = { op }, isOnline = { true }, windowPlaces = windows, countryAt = { null }, windowWaitMs = waitMs)
        return EatRepository(testServices(), localData = { local }, overpass = { op }, isOnline = { true })
    }

    /** Window ([x], [y])'s data, gzipped: one café at its middle, named "x/y". */
    private fun cafeAt(x: Int, y: Int): ByteArray {
        val c = TileId(WindowPlaces.LEVEL, x, y).bbox.center
        val text = """{"elements":[{"type":"node","id":${x * 100_000L + y},"lat":${c.lat},"lon":${c.lon},"tags":{"amenity":"cafe","name":"$x/$y"}}]}"""
        return ByteArrayOutputStream().also { o -> GZIPOutputStream(o).use { it.write(text.toByteArray()) } }.toByteArray()
    }
}

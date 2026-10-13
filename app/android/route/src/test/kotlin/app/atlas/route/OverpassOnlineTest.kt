package app.atlas.route

import app.atlas.model.Mode
import app.atlas.model.RouteRequest
import app.atlas.model.Vehicle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import java.io.IOException
import java.net.URLEncoder
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** The validation loop against the live Overpass API (only with -Datlas.online=true, and E0_N50.rd5). */
class OverpassOnlineTest {

    companion object {
        private const val OVERPASS = "https://overpass-api.de/api/interpreter"
        /** Short pieces keep each query small enough for a GET. */
        private const val PIECE_METRES = 4_000.0
    }

    private val client = OkHttpClient.Builder().readTimeout(180, TimeUnit.SECONDS).build()
    private val queries = AtomicInteger()

    /** GET while the query fits in a URL (on 13 Oct 2026 overpass-api.de answered every POST with 504), else POST. */
    private val overpass: suspend (String) -> String = { ql ->
        queries.incrementAndGet()
        withContext(Dispatchers.IO) {
            val encoded = URLEncoder.encode(ql, Charsets.UTF_8)
            val builder = Request.Builder().header("User-Agent", SegmentStore.USER_AGENT)
            val req = if (encoded.length <= 6_000) builder.url("$OVERPASS?data=$encoded").build()
            else builder.url(OVERPASS).post(FormBody.Builder().add("data", ql).build()).build()
            client.newCall(req).execute().use { r ->
                if (!r.isSuccessful) throw IOException("Overpass returned ${r.code}")
                r.body.string()
            }
        }
    }

    @Before
    fun online() {
        assumeTrue(System.getProperty("atlas.online") == "true")
        RealData.assumeAmsterdam()
        // the public server is often overloaded (HTTP 504/429): that is no failure of ours
        val probe = OverpassRestrictionSource(overpass, attempts = 4)
        val up = runCatching { runBlocking { probe.restrictionsAlong(Corridor(listOf(RealData.WESTERKERK, RealData.WESTERKERK), 5.0)) } }
        assumeTrue("Overpass is unavailable: ${up.exceptionOrNull()?.message}", up.isSuccess)
        queries.set(0)
    }

    @Test
    fun aTallHeavyTruckToHaarlem() = runBlocking {
        val router = BRouterRouter(RealData.segments, RealData.profileDir(), restrictions = OverpassRestrictionSource(overpass, pieceMetres = PIECE_METRES, attempts = 5))
        val req = RouteRequest(Mode.TRUCK, listOf(RealData.WESTERKERK, RealData.HAARLEM), vehicle = Vehicle(heightM = 4.2, weightT = 40.0))
        val t0 = System.nanoTime()
        val r = router.route(req)
        val route = (r as RouteResult.Found).best
        println("ONLINE truck: ${(System.nanoTime() - t0) / 1_000_000} ms, ${queries.get()} Overpass queries, %.0f m".format(route.distance))
        val limits = route.hazards.filter { it.tags.containsKey("osm") }
        for (h in limits) println("  ${h.kind} ${h.value} at ${h.pointIndex} ${h.tags}")
        route.hazards.firstOrNull { it.kind == HazardKind.UNCHECKED }?.let { println("  unchecked: ${it.text}") }
        assertTrue(route.hazards.none { it.kind == HazardKind.UNCHECKED })
        assertTrue(queries.get() >= 1)
        // what the route still breaks is said, never silently kept
        val broken = limits.filter { it.tags["violated"] == "yes" }
        if (broken.isNotEmpty()) println("  no way round ${broken.size} of them: the route keeps them, marked violated")
        assertTrue(broken.all { it.value != null || it.kind == HazardKind.HGV || it.kind == HazardKind.HAZMAT })
    }

    @Test
    fun canalRestrictionsForABoat() = runBlocking {
        val source = OverpassRestrictionSource(overpass, OverpassRestrictionSource.Scope.WATER, PIECE_METRES, attempts = 5)
        val router = BRouterRouter(RealData.segments, RealData.profileDir(), restrictions = source)
        val boat = Vehicle(airDraftM = 2.5, draftM = 1.2, beamM = 3.0)
        val r = WaterRouter(router).route(RouteRequest(Mode.BOAT, listOf(RealData.PRINSENGRACHT, RealData.IJ), vehicle = boat))
        val route = (r as RouteResult.Found).best
        println("ONLINE boat: %.0f m, ${queries.get()} Overpass queries".format(route.distance))
        for (h in route.hazards) println("  ${h.kind} ${h.value} at ${h.pointIndex} ${h.tags}")
        assertTrue(route.hazards.none { it.kind == HazardKind.UNCHECKED })
    }
}

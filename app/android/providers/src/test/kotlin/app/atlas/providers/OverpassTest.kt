package app.atlas.providers

import app.atlas.geo.BBox
import app.atlas.geo.LatLon
import app.atlas.model.OsmId
import app.atlas.model.OsmType
import kotlinx.coroutines.runBlocking
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File
import java.io.IOException
import java.io.StringReader
import java.net.URLDecoder
import java.time.Instant
import java.time.LocalDate

class OverpassTest {
    private val westerkerk = LatLon(52.3745, 4.8838)

    private fun parse(name: String) = OverpassParser.parse(StringReader(Fixtures.text(name)))

    @Test
    fun parsesCafesNearTheWesterkerk() {
        val r = parse("overpass_cafes.json")
        assertEquals("2026-10-13T17:26:21Z", r.meta.timestamp)
        val places = r.places()
        assertEquals(19, places.size)
        val hoek = places.first { it.name() == "De Hoek" }
        assertEquals(OsmId(OsmType.NODE, 2468154212), hoek.id)
        assertEquals(52.3723311, hoek.location.lat, 1e-9)
        assertEquals("Prinsengracht", hoek["addr:street"])
        assertTrue(places.all { it["amenity"] == "cafe" && app.atlas.geo.Geo.distance(it.location, westerkerk) < 400 })
    }

    @Test
    fun waysAndRelationsGetTheirCentre() {
        val places = parse("overpass_ways_center.json").places()
        val kerk = places.first { it.id == OsmId(OsmType.WAY, 99205257) }
        assertEquals("Westerkerk", kerk.name())
        assertTrue(app.atlas.geo.Geo.distance(kerk.location, LatLon(52.37455, 4.88399)) < 30)
        assertTrue(places.any { it.id?.type == OsmType.RELATION })
    }

    @Test
    fun waysWithGeometryForRestrictionChecks() {
        val ways = parse("overpass_geom.json").ways()
        assertEquals(59, ways.size)
        val rozengracht = ways.first { it.tags["name"] == "Rozengracht" }
        assertTrue(rozengracht.points.size >= 2)
        assertTrue(ways.all { it.tags.containsKey("maxweight") || it.tags.containsKey("maxheight") })
        val box = parse("overpass_geom.json").elements.first().box()!!
        assertTrue(box.width >= 0 && box.height >= 0)
    }

    @Test
    fun outBodyWaysAreRebuiltFromTheirNodes() {
        val json = """{"elements":[
            {"type":"way","id":1,"nodes":[10,11,12],"tags":{"maxheight":"3.2"}},
            {"type":"node","id":10,"lat":52.0,"lon":4.0},{"type":"node","id":11,"lat":52.001,"lon":4.0},{"type":"node","id":12,"lat":52.002,"lon":4.001}]}"""
        val ways = OverpassParser.parse(StringReader(json)).ways()
        assertEquals(listOf(LatLon(52.0, 4.0), LatLon(52.001, 4.0), LatLon(52.002, 4.001)), ways.single().points)
    }

    @Test
    fun newlyCreatedPlacesCarryTheirVersion() {
        val r = parse("overpass_new.json")
        val places = r.elements.mapNotNull(Trends::placeWithMeta)
        assertEquals(10, places.size)
        val ikaria = places.first { it.name() == "Ikaria Food" }
        assertEquals("1", ikaria.extra["osm:version"])
        val f = Trends.freshness(ikaria, LocalDate.of(2026, 10, 13))!!
        assertEquals(Freshness.Kind.NEWLY_MAPPED, f.kind)
        assertEquals(LocalDate.of(2026, 9, 20), f.date)
    }

    @Test
    fun newlyCreatedRunsTheNewerQuery() = runBlocking {
        Mock().use { m ->
            m.fixture("overpass_new.json")
            val now = Instant.parse("2026-10-13T12:00:00Z")
            val places = Trends.newlyCreated(Overpass(testHttp(), listOf(m.url("/i"))), OverpassQl.oneOf("amenity", listOf("restaurant", "cafe", "bar")), OverpassArea.Box(BBox(4.87, 52.36, 4.91, 52.39)), now, days = 180)
            assertEquals(10, places.size)
            assertTrue(places.all { it.extra["osm:version"] == "1" && it.extra["osm:timestamp"] != null })
            val sent = URLDecoder.decode(m.take().text.removePrefix("data="), "UTF-8")
            assertTrue(sent, sent.contains("(newer:\"2026-04-16T12:00:00Z\")(if: version()==1);"))
        }
    }

    @Test
    fun streamsTheAmsterdamExtractInFlatMemory() {
        val file = File(System.getProperty("atlas.repo"), "data/ams/raw.json")
        org.junit.Assume.assumeTrue(file.exists())
        var count = 0
        var tagged = 0
        var named = 0
        val meta = file.reader().use { r ->
            OverpassParser.stream(r) { e ->
                count++
                val p = e.toPlace()
                if (p != null) tagged++
                if (p?.name() != null) named++
            }
        }
        assertNull(meta.remark)
        assertEquals(27_663, count)
        assertTrue("tagged: $tagged", tagged in 11_000..count)
        assertTrue("named: $named", named > 900)
    }

    @Test
    fun failsOverFromABusyServerAndRemembersTheOneThatAnswered() = runBlocking {
        Mock().use { busy ->
            Mock().use { good ->
                busy.fixture("overpass_504.html", 504)
                good.fixture("overpass_cafes.json")
                good.fixture("overpass_cafes.json")
                val o = Overpass(testHttp(), listOf(busy.url("/api/interpreter"), good.url("/api/interpreter")))
                val ql = OverpassQl.places(listOf(OverpassQl.tag("amenity", "cafe")), OverpassArea.Around(westerkerk, 350.0))
                assertEquals(19, o.places(ql).size)
                assertEquals(good.url("/api/interpreter"), o.lastEndpoint)
                // The query went as a form field, in JSON, with a timeout.
                val sent = URLDecoder.decode(good.take().text.removePrefix("data="), "UTF-8")
                assertTrue(sent.startsWith("[out:json][timeout:60];"))
                assertTrue(sent.contains("nwr[\"amenity\"=\"cafe\"](around:350,52.3745,4.8838);"))
                // Next time the server that answered is asked first; the busy one is resting.
                assertEquals(19, o.places(ql).size)
                assertEquals(1, busy.count)
            }
        }
    }

    @Test
    fun anHtmlPageInsteadOfJsonCountsAsBusy() = runBlocking {
        Mock().use { a ->
            Mock().use { b ->
                a.fixture("overpass_mailru_504.html", 200)
                b.fixture("overpass_cafes.json")
                val o = Overpass(testHttp(), listOf(a.url("/i"), b.url("/i")))
                assertEquals(19, o.places("node[amenity=cafe](around:350,52.3745,4.8838); out;").size)
            }
        }
    }

    @Test
    fun rawReturnsTheAnswerAsItCame() = runBlocking {
        Mock().use { m ->
            m.fixture("overpass_cafes.json")
            val text = Overpass(testHttp(), listOf(m.url("/i"))).raw("node(1); out;")
            assertEquals(Fixtures.text("overpass_cafes.json"), text)
        }
    }

    @Test
    fun aBadQueryIsNotRetriedElsewhere() = runBlocking {
        Mock().use { a ->
            Mock().use { b ->
                a.enqueue("""<html><body><p><strong style="color:#FF0000">Error</strong>: line 1: parse error: Unknown type "nod" </p></body></html>""", 400)
                val o = Overpass(testHttp(), listOf(a.url("/i"), b.url("/i")))
                try {
                    o.query("nod[amenity]; out;")
                    fail("expected OverpassException")
                } catch (e: OverpassException) {
                    assertTrue(e.message!!.contains("Unknown type"))
                }
                assertEquals(0, b.count)
            }
        }
    }

    @Test
    fun aBadQueryOnTheRealErrorPageSaysWhatIsWrong() = runBlocking {
        // overpass-api.de's error page: its sentence starts past the first 500 characters, after the head and the ODbL line.
        val page = Fixtures.text("overpass_504.html").replace(
            Regex("""<p><strong style="color:#FF0000">Error</strong>:.*?</p>"""),
            """<p><strong style="color:#FF0000">Error</strong>: line 2: parse error: Unknown type "nod" </p>""",
        )
        assertTrue(page.indexOf("Unknown type") > 500)
        Mock().use { a ->
            a.enqueue(page, 400)
            try {
                Overpass(testHttp(), listOf(a.url("/i"))).query("nod[amenity]; out;")
                fail("expected OverpassException")
            } catch (e: OverpassException) {
                assertTrue(e.message, e.message!!.contains("line 2: parse error: Unknown type \"nod\""))
            }
        }
    }

    @Test
    fun tooManyRequestsPausesThatHostAndTriesTheNext() = runBlocking {
        Mock().use { a ->
            Mock().use { b ->
                a.enqueue("rate limited", 429)
                b.fixture("overpass_cafes.json")
                val http = testHttp()
                // Two hosts: the pause after a 429 holds back only the host that sent it.
                val other = b.url("/i").replace("localhost", "127.0.0.1")
                val o = Overpass(http, listOf(a.url("/i"), other))
                val start = System.nanoTime()
                assertEquals(19, o.places("node(1); out;").size)
                assertTrue((System.nanoTime() - start) / 1_000_000 < 10_000)
            }
        }
    }

    @Test
    fun aRuntimeErrorRemarkIsRetriedOnAnotherServer() = runBlocking {
        Mock().use { a ->
            Mock().use { b ->
                a.enqueue("""{"version":0.6,"elements":[],"remark":"runtime error: Query timed out in \"query\" at line 3 after 61 seconds."}""")
                b.fixture("overpass_cafes.json")
                val o = Overpass(testHttp(), listOf(a.url("/i"), b.url("/i")))
                assertEquals(19, o.query("node(1); out;").places().size)
            }
        }
    }

    @Test
    fun aMalformedNumberFailsOverToTheNextServer() = runBlocking {
        Mock().use { a ->
            Mock().use { b ->
                a.enqueue("""{"version":0.6,"elements":[{"type":"node","id":1,"lat":52.3.7,"lon":4.88,"tags":{"amenity":"cafe"}}]}""")
                b.fixture("overpass_cafes.json")
                val o = Overpass(testHttp(), listOf(a.url("/i"), b.url("/i")))
                assertEquals(19, o.places("node(1); out;").size)
                assertEquals(b.url("/i"), o.lastEndpoint)
            }
        }
    }

    @Test
    fun aStreamIsNotRepeatedOnceElementsWereHandedOut() = runBlocking {
        Mock().use { a ->
            Mock().use { b ->
                val cut = Fixtures.text("overpass_cafes.json").let { it.substring(0, it.length / 2) }
                a.enqueue(cut)
                b.fixture("overpass_cafes.json")
                val o = Overpass(testHttp(), listOf(a.url("/i"), b.url("/i")))
                var seen = 0
                try {
                    o.stream("node(1); out;") { seen++ }
                    fail("expected a failure")
                } catch (_: IOException) {
                }
                assertTrue(seen > 0)
                assertEquals(0, b.count)
            }
        }
    }

    @Test
    fun allServersDownIsOneClearError() = runBlocking {
        Mock().use { a ->
            a.enqueue("down", 503)
            val o = Overpass(testHttp(), listOf(a.url("/i")))
            try {
                o.query("node(1); out;")
                fail()
            } catch (e: ProviderException) {
                assertTrue(e.message!!.contains("No Overpass server could answer"))
            }
        }
    }

    @Test
    fun aServerThatKeepsFailingRestsLongerAndWhileAllRestOnlyOneIsAsked() = runBlocking {
        Mock().use { a ->
            Mock().use { b ->
                a.dispatch { response("<html><body>502: the server is at capacity</body></html>", 502) }
                b.dispatch { response("<html><body>504 Gateway Timeout</body></html>", 504) }
                var now = 0L
                val o = Overpass(testHttp(), listOf(a.url("/i"), b.url("/i")), clock = { now })
                suspend fun ask() = runCatching { o.query("node(1); out;") }.exceptionOrNull()
                fun counts() = a.count to b.count
                assertTrue(ask() is ProviderException)
                assertEquals(1 to 1, counts())
                // both rest (a 30 s after its 502, b 15 s after its 504): only the one whose rest ends first is asked
                ask()
                assertEquals(1 to 2, counts())
                // b failed twice in a row and rests 30 s: at 30 s both are asked again, and rest a minute
                now = 30_000
                ask()
                assertEquals(2 to 3, counts())
                // still resting at 60 s: one server is asked, not both
                now = 60_000
                ask()
                assertEquals(3 to 3, counts())
                // b answers at 90 s (a rests two minutes now): b's next failure rests it 15 s again, not two minutes
                now = 90_000
                b.dispatch { response(Fixtures.text("overpass_cafes.json")) }
                assertEquals(19, o.query("node(1); out;").places().size)
                b.dispatch { response("<html><body>504 Gateway Timeout</body></html>", 504) }
                ask()
                now = 105_000
                ask()
                assertEquals(3 to 6, counts())
            }
        }
    }

    @Test
    fun restrictionsAlongARouteAskForWaysWithGeometry() = runBlocking {
        Mock().use { m ->
            m.fixture("overpass_geom.json")
            val o = Overpass(testHttp(), listOf(m.url("/i")))
            val route = listOf(LatLon(52.3740, 4.8790), LatLon(52.3745, 4.8838), LatLon(52.3750, 4.8900))
            val ways = o.restrictionsAlong(route, 30.0)
            assertEquals(59, ways.size)
            val sent = URLDecoder.decode(m.take().text.removePrefix("data="), "UTF-8")
            assertTrue(sent.contains("out tags geom qt;"))
            // The middle point lies 7 m off the straight line and is simplified away; the corridor asked for must still
            // reach 30 m from every point of the route itself.
            val around = Regex("""way\["maxheight"]\(around:([0-9.]+),([^)]*)\);""").find(sent)!!
            val radius = around.groupValues[1].toDouble()
            val asked = around.groupValues[2].split(',').map { it.toDouble() }.chunked(2) { LatLon(it[0], it[1]) }
            assertEquals(2, asked.size)
            val askedLine = app.atlas.geo.Polyline(asked)
            assertTrue(sent, route.all { askedLine.snap(it)!!.offset + 30.0 <= radius })
        }
    }

    @Test
    fun qlBuildersQuoteAndShapeQueries() {
        assertEquals("\"a\\\"b\\\\c\"", OverpassQl.quote("a\"b\\c"))
        assertEquals("[\"brand:wikidata\"=\"Q1653985\"]", OverpassQl.tag("brand:wikidata", "Q1653985"))
        assertEquals("[\"cuisine\"~\"^(pizza|fish\\\\.chips)$\"]", OverpassQl.oneOf("cuisine", listOf("pizza", "fish.chips")))
        assertEquals("[out:json][timeout:25];\nnode(1); out;", OverpassQl.ensureJson("node(1); out;", 25))
        assertEquals("[out:json][timeout:90][bbox:1,2,3,4];\nout;", OverpassQl.ensureJson("[out:xml][timeout:90][bbox:1,2,3,4];\nout;"))
        assertEquals(90, OverpassQl.timeoutOf("[out:json][timeout:90];"))
        assertEquals("(52.37,4.88,52.38,4.89)", OverpassArea.Box(BBox(4.88, 52.37, 4.89, 52.38)).clause())
        val created = OverpassQl.createdSince("[\"amenity\"=\"cafe\"]", OverpassArea.Around(westerkerk, 500.0), Instant.parse("2026-07-15T10:00:00.123Z"))
        assertTrue(created.contains("(newer:\"2026-07-15T10:00:00Z\")(if: version()==1);"))
        assertTrue(created.endsWith("out meta center qt;"))
        val chargers = OverpassQl.chargers(OverpassArea.Around(westerkerk, 1000.0), listOf("type2_combo"))
        assertTrue(chargers.contains("nwr[\"amenity\"=\"charging_station\"][\"socket:type2_combo\"](around:1000,52.3745,4.8838);"))
    }

    @Test
    fun htmlErrorsAreReadable() {
        val text = Overpass.htmlError(Fixtures.text("overpass_504.html"))
        assertTrue(text.startsWith("runtime error: open64: 0 Success /osm3s_osm_base Dispatcher_Client::request_read_and_idx::timeout"))
        assertNotNull(Overpass.ENDPOINTS.map { it.toHttpUrl().host }.single { it == "overpass-api.de" })
    }
}

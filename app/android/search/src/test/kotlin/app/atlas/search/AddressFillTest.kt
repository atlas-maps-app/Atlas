package app.atlas.search

import app.atlas.geo.BBox
import app.atlas.geo.LatLon
import app.atlas.geo.TileId
import app.atlas.providers.Http
import app.atlas.providers.OpenAddresses
import app.atlas.providers.Overpass
import kotlinx.coroutines.runBlocking
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AddressFillTest {
    // four tiles in a row by Castellina in Chianti: the store has its own in A, many numbers in B, nothing in C and D
    private val a = TileId(14, 8704, 5990)
    private val b = TileId(14, 8705, 5990)
    private val c = TileId(14, 8706, 5990)
    private val d = TileId(14, 8707, 5990)

    /** From a tenth into A to nine tenths into D, across the middle of the row. */
    private val region = BBox(at(a, 0.1).lon, TestArchive.at(a, 0.0, 0.95).lat, at(d, 0.9).lon, TestArchive.at(a, 0.0, 0.05).lat)

    @Test
    fun aRegionGetsTheNumbersItLacksWhereItHasSomethingOfItsOwn() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            val ranges = ArrayList<String?>()
            serve(server, ranges)
            val http = Http(policies = emptyMap())
            val oa = OpenAddresses(http, server.url("/oa").toString())
            val import = RegionImport(Overpass(http, listOf(server.url("/api/interpreter").toString())), tileDegrees = 1.0, addresses = oa)
            val store = PlaceStore.inMemory()
            val progress = ArrayList<RegionImport.Progress>()
            val stats = import.import(region, store, "Castellina") { progress += it }
            val fill = import.fill!!
            assertNull(fill.failure)
            assertEquals(1, fill.sources)
            // A, B and C; D lies beside nothing of the store's own
            assertEquals(3, fill.tiles)
            assertEquals("B holds about as many numbers as its bytes", 1, fill.complete)
            assertEquals(2, fill.read)
            // A's but the one west of the region, and C's own two (not those of its buffer)
            assertEquals(11 + 2, fill.points)
            assertEquals(6, fill.added)
            assertEquals(6, stats.filled)
            assertEquals(42 + 6, stats.addresses)
            val everything = BBox(a.bbox.west, a.bbox.south, d.bbox.east, a.bbox.north)
            val filled = store.addresses(everything).filter { it.place.source == OpenAddresses.ID }
            assertEquals(
                listOf("Castellina in Chianti 7", "Strada Provinciale 10", "Strada Provinciale 8", "Via Nuova 5", "Via Roma 14", "Via del Campo 21"),
                filled.map { "${it.tags["addr:street"] ?: it.tags["addr:place"]} ${it.tags["addr:housenumber"]}" }.sorted(),
            )
            assertTrue(filled.all { it.place.extra[OpenAddresses.CREDIT] == "Regione Toscana" && it.place.extra[OpenAddresses.LICENCE] == "CC BY 4.0" })
            assertEquals(42, store.addresses(everything).count { it.place.source == "osm" })
            assertEquals(listOf(AddressCredit("it/52/test", "CC BY 4.0", "Regione Toscana", 6)), store.addressSources())
            val roma = store.text("via roma 14", near = at(a, 0.6), limit = 10)
            assertTrue(roma.any { it.place.source == OpenAddresses.ID && it.tags["addr:street"] == "Via Roma" && it.tags["addr:housenumber"] == "14" })
            assertEquals(1.0, progress.last().fraction, 1e-9)
            assertTrue(progress.zipWithNext().all { (x, y) -> y.fraction >= x.fraction })
            // the header, the root directory, the leaf, then A and C in one; never the whole file
            assertEquals(4, ranges.size)
            assertTrue(ranges.all { it?.startsWith("bytes=") == true })

            // again: nothing new, and the source credited once
            val second = store.writer(regionName = "Castellina").use { w -> AddressFill(oa).fill(region, w).also { w.finish() } }
            assertNull(second.failure)
            assertEquals(0, second.added)
            assertEquals(listOf(AddressCredit("it/52/test", "CC BY 4.0", "Regione Toscana", 6)), store.addressSources())
            store.close()
        }
    }

    @Test
    fun aRegionKeepsWhatItHasWhenTheSourcesCannotBeAsked() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            serve(server, ArrayList())
            val http = Http(policies = emptyMap())
            val down = OpenAddresses(http, server.url("/down").toString())
            val import = RegionImport(Overpass(http, listOf(server.url("/api/interpreter").toString())), tileDegrees = 1.0, addresses = down)
            val store = PlaceStore.inMemory()
            val stats = import.import(region, store, "Castellina")
            assertTrue(import.fill!!.failure!!.contains("503"))
            assertEquals(0, stats.filled)
            assertEquals(42, stats.addresses)
            assertTrue(store.addressSources().isEmpty())
            store.close()
        }
    }

    @Test
    fun aNumberIsThereWhenItsLettersAndDigitsAreOrItsHouseIs() {
        assertEquals("263h", Known.numberKey("263-H"))
        assertEquals(Known.numberKey("12 bis"), Known.numberKey("12bis"))
        assertEquals("263", Known.houseOf("263-H"))
        assertEquals("263", Known.houseOf("263/2"))
        assertEquals("12A", Known.houseOf("12A-3"))
        assertNull(Known.houseOf("263"))
        assertNull(Known.houseOf("12 bis"))
        assertEquals("ruedeleglise", Known.streetKey("Rue de l'Église"))
        assertTrue(Known.sameStreet(Known.streetKey("Main St"), Known.streetKey("Main Street")))
        assertFalse(Known.sameStreet(Known.streetKey("Via Roma"), Known.streetKey("Via del Campo")))
        assertFalse("too short to be the start of another", Known.sameStreet("ab", "abc"))
    }

    @Test
    fun aPointKeepsItsStreetTidiedOrIsNumberedInItsTown() {
        val p = AddressFill.parts(OpenAddresses.Point(43.47, 11.28, " 12  bis ", "VIA DEL CAMPO", null, null, "SAN DONATO", " 53011 "))!!
        assertEquals("Via del Campo", p.street)
        assertEquals(Addresses.STREET, p.kind)
        assertEquals("12 bis", p.housenumber)
        assertEquals("53011", p.postcode)
        assertEquals("a district stands in for the town", "San Donato", p.city)
        val town = AddressFill.parts(OpenAddresses.Point(43.47, 11.28, "7", null, null, "CASTELLINA IN CHIANTI", null, null))!!
        assertEquals("Castellina in Chianti" to Addresses.PLACE, town.street to town.kind)
        assertNull(AddressFill.parts(OpenAddresses.Point(43.47, 11.28, "7", null, null, null, null, null)))
        assertNull(AddressFill.parts(OpenAddresses.Point(43.47, 11.28, "1".repeat(25), "VIA LUNGA", null, null, null, null)))
    }

    private fun at(t: TileId, fx: Double): LatLon = TestArchive.at(t, fx, 0.5)

    /** The store's own: two numbers and a café in A, forty numbers along B. */
    private fun osm(): String {
        val elements = ArrayList<String>()
        var id = 1L
        fun node(t: TileId, fx: Double, tags: Map<String, String>) {
            val p = at(t, fx)
            val json = tags.entries.joinToString(",") { (k, v) -> "\"$k\":\"$v\"" }
            elements += """{"type":"node","id":${id++},"lat":${p.lat},"lon":${p.lon},"tags":{$json}}"""
        }
        fun number(street: String, n: String) = mapOf("addr:street" to street, "addr:housenumber" to n)
        node(a, 0.30, number("Via del Campo", "12"))
        node(a, 0.32, number("Via del Campo", "14"))
        node(a, 0.50, mapOf("amenity" to "cafe", "name" to "Bar Centrale", "addr:street" to "Piazza Grande", "addr:housenumber" to "3"))
        for (n in 1..40) node(b, 0.1 + (n - 1) * 0.02, number("Via Lunga", "$n"))
        return """{"version":0.6,"elements":[${elements.joinToString(",")}]}"""
    }

    private fun point(fx: Double, number: String, street: String?, unit: String? = null, fy: Double = 0.5): TestArchive.Point {
        val fields = linkedMapOf("number" to number, "city" to "CASTELLINA IN CHIANTI", "postcode" to "53011")
        street?.let { fields["street"] = it }
        unit?.let { fields["unit"] = it }
        return TestArchive.Point(fx, fy, fields)
    }

    /** The source's tiles: what the store has (one way or another), what it lacks, and what lies outside the region. */
    private fun archive(): ByteArray = TestArchive.archive(
        mapOf(
            a to TestArchive.tile(
                listOf(
                    point(0.305, "12", "VIA DEL CAMPO"), // OSM's 12, 9 m on
                    point(0.40, "14", "VIA DEL CAMPO"), // OSM's 14, 140 m on along its street
                    point(0.325, "14", "VIA ROMA", fy = 0.505), // another street's 14 within 30 m of OSM's: one house
                    point(0.60, "14", "VIA ROMA"), // 500 m on: another house
                    point(0.31, "12-3", "VIA DEL CAMPO"), // a flat of OSM's 12
                    point(0.502, "3", "PIAZZA GRANDE"), // the café's
                    point(0.70, "5", "VIA NUOVA", unit = "1"),
                    point(0.70, "5", "VIA NUOVA", unit = "2"), // its other flat: one point is enough
                    point(0.80, "7", null), // no street: numbered in its town
                    point(0.05, "99", "VIA FUORI"), // west of the region
                    point(0.85, "1".repeat(25), "VIA LUNGA"), // no house number
                    point(0.90, "21", "VIA DEL CAMPO"),
                ),
            ),
            b to TestArchive.tile((41..45).map { point(0.9 + (it - 41) * 0.02, "$it", "VIA LUNGA") }),
            c to TestArchive.tile(
                listOf(
                    point(0.50, "8", "STRADA PROVINCIALE"),
                    point(0.60, "10", "STRADA PROVINCIALE"),
                    point(1.02, "77", "VIA ESTERNA"), // D's, in C's buffer
                    point(-0.02, "46", "VIA LUNGA"), // B's, in C's buffer
                ),
            ),
            d to TestArchive.tile(listOf(point(0.5, "1", "VIA LONTANA"))),
        ),
    )

    /**
     * Overpass with the store's own, OpenAddresses' service under /oa (one licensed source, and a buildings layer to
     * pass over) with its tiles by their bytes, and a service under /down that is down.
     */
    private fun serve(server: MockWebServer, ranges: MutableList<String?>) {
        val file = archive()
        val tiles = server.url("/tiles/test.pmtiles").toString()
        val box = BBox(a.bbox.west, a.bbox.south, d.bbox.east, a.bbox.north)
        val polygon = with(box) { "[[[$west,$south],[$east,$south],[$east,$north],[$west,$north],[$west,$south]]]" }
        val data = """[{"source":"it/52/test","layer":"addresses","name":"region","job":7,"output":{"pmtiles":true}},""" +
            """{"source":"it/52/test","layer":"buildings","name":"region","job":8,"output":{"pmtiles":true}}]"""
        val job = """{"source_name":"it/52/test","count":19,"pmtiles_url":"$tiles",""" +
            """"license":{"text":"CC BY 4.0","url":"https://creativecommons.org/licenses/by/4.0/","attribution name":"Regione Toscana"},""" +
            """"bounds":{"type":"Polygon","coordinates":$polygon}}"""
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when (request.url.encodedPath) {
                "/api/interpreter" -> json(osm())
                "/oa/data" -> json(data)
                "/oa/job/7" -> json(job)
                "/tiles/test.pmtiles" -> {
                    val range = request.headers["Range"]
                    synchronized(ranges) { ranges += range }
                    bytes(file, range)
                }
                "/down/data" -> MockResponse.Builder().code(503).body("down").build()
                else -> MockResponse.Builder().code(404).body("no").build()
            }
        }
    }

    private fun json(body: String) = MockResponse.Builder().code(200).addHeader("Content-Type", "application/json").body(body).build()

    /** The bytes [range] asks for, as the tiles' storage answers; the whole file without one. */
    private fun bytes(file: ByteArray, range: String?): MockResponse {
        val m = Regex("bytes=(\\d+)-(\\d+)").matchEntire(range ?: "")
            ?: return MockResponse.Builder().code(200).body(Buffer().write(file)).build()
        val from = m.groupValues[1].toInt()
        val to = minOf(m.groupValues[2].toInt(), file.size - 1)
        return MockResponse.Builder().code(206).addHeader("Content-Range", "bytes $from-$to/${file.size}")
            .body(Buffer().write(file.copyOfRange(from, to + 1))).build()
    }
}

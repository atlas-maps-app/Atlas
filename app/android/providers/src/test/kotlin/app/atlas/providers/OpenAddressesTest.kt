package app.atlas.providers

import app.atlas.geo.BBox
import app.atlas.geo.TileId
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import mockwebserver3.MockResponse
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.IOException
import java.util.zip.GZIPInputStream

class OpenAddressesTest {
    @Test
    fun aTileIdIsItsPlaceAlongTheHilbertCurveAfterTheCoarserZooms() {
        assertEquals(0L, PmTiles.tileId(0, 0, 0))
        // the specification's own: zoom 1 goes 0/0, 0/1, 1/1, 1/0
        assertEquals(listOf(1L, 2L, 3L, 4L), listOf(PmTiles.tileId(1, 0, 0), PmTiles.tileId(1, 0, 1), PmTiles.tileId(1, 1, 1), PmTiles.tileId(1, 1, 0)))
        assertEquals(5L, PmTiles.tileId(2, 0, 0))
        // the tile of Castellina in Chianti that it/countrywide's archive holds under this id
        assertEquals(317_221_228L, PmTiles.tileId(14, 8705, 5990))
    }

    @Test
    fun aDirectoryReadsItsRunsLeavesAndRunningOffsets() {
        // three entries: tile 0 (10 bytes at 0), tiles 1 and 2 (20 bytes right after: offset 0), a leaf from tile 5
        val bytes = byteArrayOf(3, 0, 1, 4, 1, 2, 0, 10, 20, 30, 1, 0, 101)
        val dir = PmTiles.directory(bytes)
        assertEquals(listOf(0L, 1L, 5L), dir.map { it.tileId })
        assertEquals(listOf(0L, 10L, 100L), dir.map { it.offset })
        assertEquals(listOf(10, 20, 30), dir.map { it.length })
        assertEquals(1L, PmTiles.find(dir, 2)?.tileId)
        assertNull("tile 3 is in no run", PmTiles.find(dir, 3))
        assertEquals("a leaf holds the tiles from 5 on", 5L, PmTiles.find(dir, 900)?.tileId)
        try {
            PmTiles.directory(byteArrayOf(3, 0, 1))
            fail("a directory cut short")
        } catch (e: PmTilesException) {
            assertTrue(e.message!!.contains("cut short"))
        }
    }

    @Test
    fun aRealTileGivesItsAddressPointsWithTheirFields() {
        // tippecanoe's packed fields run past 127 bytes: their lengths take two bytes
        val tile = GZIPInputStream(ByteArrayInputStream(Fixtures.bytes("openaddresses_castellina.mvt.gz"))).use { it.readBytes() }
        val points = OpenAddresses.points(tile, 14, 8705, 5990)
        val box = TileId(14, 8705, 5990).bbox
        assertTrue(points.size > 400)
        assertTrue(points.all { it.lat in box.south..box.north && it.lon in box.west..box.east && it.number.isNotBlank() })
        val first = points.first()
        assertEquals("LOCALITA' SASSOCUPO", first.street)
        assertEquals("42", first.number)
        assertEquals("Castellina in Chianti", first.city)
        assertEquals(43.48361, first.lat, 1e-4)
        assertEquals(11.27676, first.lon, 1e-4)
        assertTrue(points.count { it.street?.startsWith("VIA ") == true } > 20)
    }

    @Test
    fun theSourcesAreTheLicensedRunsWithTilesWhoseBoxMeetsTheRegion() = runBlocking {
        Mock().use { m ->
            val asked = ArrayList<String>()
            m.dispatch { r ->
                val path = r.url.encodedPath
                synchronized(asked) { asked += path + (r.url.encodedQuery?.let { "?$it" } ?: "") }
                when (path) {
                    "/api/data" -> json(Fixtures.text("openaddresses_data.json"))
                    "/api/job/896384" -> json(Fixtures.text("openaddresses_job.json"))
                    // Bologna's run lies north of the region; Florence's names no licence
                    "/api/job/946963" -> json(job("it/45/bologna", """{"text":"CC-BY 4.0","url":"https://creativecommons.org/licenses/by/4.0/"}""", 11.2, 44.4, 11.45, 44.55))
                    "/api/job/946953" -> json(job("it/52/firenze", "false", 11.1, 43.7, 11.35, 43.85))
                    else -> MockResponse.Builder().code(404).body("no").build()
                }
            }
            val oa = OpenAddresses(Http(policies = emptyMap()), m.base + "/api")
            val sources = oa.sources(BBox(11.270, 43.460, 11.300, 43.480))
            assertEquals(listOf("it/countrywide"), sources.map { it.name })
            val s = sources.single()
            assertEquals(896384L, s.job)
            assertEquals("CC-BY 4.0", s.licence)
            assertEquals("Istat e dall'Agenzia delle Entrate", s.credit)
            assertEquals("https://v2.openaddresses.io/batch-prod/job/896384/source.pmtiles", s.tiles)
            assertEquals(27_405_384L, s.points)
            assertTrue(s.bounds!!.intersects(BBox(11.27, 43.46, 11.30, 43.48)))
            // a small region is asked at its four corners and its middle; a buildings layer's run is never looked at
            assertEquals(5, asked.count { it.startsWith("/api/data?point=") })
            assertTrue(asked.none { it == "/api/job/946964" })
        }
    }

    @Test
    fun aBigRegionIsAskedAcrossItAndASmallOneAtItsCornersAndMiddle() {
        assertEquals(5, OpenAddresses.probes(BBox(4.85, 52.35, 4.95, 52.40)).size)
        // the Île-de-France: about every half degree, five a side at most, and the middle
        val idf = OpenAddresses.probes(BBox(1.4451, 48.1192, 3.5604, 49.2427))
        assertEquals(5 * 4 + 1, idf.size)
        assertTrue(idf.all { it.lat in 48.1192..49.2427 && it.lon in 1.4451..3.5604 })
    }

    @Test
    fun aLicenceIsNamedOrTheSourceIsLeftOut() {
        val ban = buildJsonObject {
            put("url", "https://alliance.numerique.gouv.fr/licence-ouverte-open-licence/")
            put("text", "Licence Ouverte Etalab 2.0")
            put("attribution", true)
            put("attribution name", "Base Adresse Nationale (adresse.data.gouv.fr)")
        }
        assertEquals("Licence Ouverte Etalab 2.0" to "Base Adresse Nationale (adresse.data.gouv.fr)", OpenAddresses.licence(ban, "fr/75/statewide"))
        val byUrl = buildJsonObject { put("url", "https://creativecommons.org/publicdomain/zero/1.0/"); put("website", "https://www.kadaster.nl/bag") }
        assertEquals("https://creativecommons.org/publicdomain/zero/1.0/" to "kadaster.nl", OpenAddresses.licence(byUrl, "nl/countrywide"))
        assertEquals("CC0" to "lu/countrywide", OpenAddresses.licence(JsonPrimitive("CC0"), "lu/countrywide"))
        assertNull(OpenAddresses.licence(JsonPrimitive(false), "us/ny/city_of_new_york"))
        assertNull(OpenAddresses.licence(JsonNull, "x"))
        assertNull(OpenAddresses.licence(buildJsonObject { put("attribution", false) }, "x"))
    }

    @Test
    fun namesInCapitalsAreWrittenAsTheirSignsShowThem() {
        assertEquals("Via del Campo", OpenAddresses.tidy("VIA DEL CAMPO"))
        assertEquals("Via Carlo Pisacane", OpenAddresses.tidy("VIA  CARLO PISACANE "))
        assertEquals("Rue de l'Église", OpenAddresses.tidy("RUE DE L'ÉGLISE"))
        assertEquals("Saint-Germain-des-Prés", OpenAddresses.tidy("SAINT-GERMAIN-DES-PRÉS"))
        assertEquals("Villeneuve-sur-Lot", OpenAddresses.tidy("VILLENEUVE-SUR-LOT"))
        assertEquals("L'Aquila", OpenAddresses.tidy("L'AQUILA"))
        assertEquals("Localita' Sassocupo", OpenAddresses.tidy("LOCALITA' SASSOCUPO"))
        assertEquals("Castellina in Chianti", OpenAddresses.tidy("CASTELLINA IN CHIANTI"))
        assertEquals("Via alla Chiesa", OpenAddresses.tidy("VIA ALLA CHIESA"))
        assertEquals("Rua das Flores", OpenAddresses.tidy("RUA DAS FLORES"))
        // a name with small letters is as its source wrote it; a lone letter too
        assertEquals("Avenue Michel Guérin", OpenAddresses.tidy("Avenue Michel Guérin"))
        assertEquals("rue du Port", OpenAddresses.tidy("rue du Port"))
        assertEquals("A", OpenAddresses.tidy("A"))
    }

    @Test
    fun theRunsBoxIsReadFromItsPolygon() {
        val polygon = kotlinx.serialization.json.Json.parseToJsonElement("""{"type":"Polygon","coordinates":[[[3.36,50.75],[7.23,50.75],[7.23,53.53],[3.36,53.53],[3.36,50.75]]]}""")
        assertEquals(BBox(3.36, 50.75, 7.23, 53.53), OpenAddresses.bounds(polygon))
        assertNull(OpenAddresses.bounds(JsonNull))
    }

    @Test
    fun bytesComeOnlyAsAskedNeverAsTheWholeFile() = runBlocking {
        Mock().use { m ->
            val file = ByteArray(100) { it.toByte() }
            var mode = "ok"
            var range: String? = null
            m.dispatch { r ->
                range = r.headers["Range"]
                when (mode) {
                    "ok" -> MockResponse.Builder().code(206).addHeader("Content-Range", "bytes 10-19/100").body(Buffer().write(file.copyOfRange(10, 20))).build()
                    "whole" -> MockResponse.Builder().code(200).body(Buffer().write(file)).build()
                    else -> MockResponse.Builder().code(206).addHeader("Content-Range", "bytes 0-9/100").body(Buffer().write(file.copyOfRange(0, 10))).build()
                }
            }
            val oa = OpenAddresses(Http(policies = emptyMap()), m.base + "/api")
            val url = m.url("/tiles/source.pmtiles")
            assertEquals(file.copyOfRange(10, 20).toList(), oa.range(url, 10, 10).toList())
            assertEquals("bytes=10-19", range)
            mode = "whole"
            try {
                oa.range(url, 10, 10)
                fail("a whole file is refused")
            } catch (e: HttpException) {
                assertEquals(200, e.code)
            }
            mode = "elsewhere"
            try {
                oa.range(url, 10, 10)
                fail("other bytes are refused")
            } catch (e: IOException) {
                assertTrue(e.message!!.contains("bytes 0-9/100"))
            }
        }
    }

    @Test
    fun itIsListedWithTheOtherSourcesAndItsHosts() {
        val info = Catalogue.byId(OpenAddresses.ID)!!
        assertTrue(info.onByDefault && info.switchable)
        assertEquals(listOf("batch.openaddresses.io", "v2.openaddresses.io"), info.hosts)
    }

    private fun json(body: String) = MockResponse.Builder().code(200).addHeader("Content-Type", "application/json").body(body).build()

    private fun job(name: String, license: String, w: Double, s: Double, e: Double, n: Double) =
        """{"source_name":"$name","license":$license,"count":10,"pmtiles_url":"https://v2.openaddresses.io/batch-prod/job/1/source.pmtiles",""" +
            """"bounds":{"type":"Polygon","coordinates":[[[$w,$s],[$e,$s],[$e,$n],[$w,$n],[$w,$s]]]}}"""
}

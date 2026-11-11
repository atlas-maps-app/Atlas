package app.atlas.providers

import app.atlas.geo.LatLon
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BathingWatersTest {
    private val westerkerk = LatLon(52.3745, 4.8838)

    private fun serve(m: Mock, query: String = "eea_bathing_ams.json") = m.dispatch { req ->
        val path = req.url.encodedPath
        when {
            path.endsWith("/BathingWater") -> response(Fixtures.text("eea_services.json"))
            path.endsWith("/BathingWater_Dyna_WM_2025/MapServer/3/query") -> response(Fixtures.text(query))
            path.endsWith("/geoserver/zwr-wms/wfs") -> response(Fixtures.text("zwr_zwemplek_ams.json"))
            else -> response("""{"error":{"code":404,"message":"Service not found"}}""")
        }
    }

    private fun waters(m: Mock) = BathingWaters(testHttp(), m.url("/arcgis/rest/services").trimEnd('/'), m.url("/").trimEnd('/'))

    /** The Dutch register's advisories (Diemerpark's warning, Nieuwe Meer Noord's advice against) are set for the test. */
    @Test
    fun theEusBathingWatersWithTheDutchAdvisories() = runBlocking {
        Mock().use { m ->
            serve(m)
            val bw = waters(m)
            val got = bw.near(westerkerk, 15_000.0)
            // the EU's eleven, and the spot only the Dutch register holds
            assertEquals(12, got.size)
            val first = got.first()
            assertEquals("Marineterrein Amsterdam", first.name)
            assertEquals(WaterKind.RIVER, first.kind)
            assertEquals(WaterQuality.UNCLASSIFIED, first.quality)
            assertEquals(2025, first.season)
            val diemerpark = got.single { it.id == "NLBW92_DIEMPK" }
            assertEquals("Strand Diemerpark", diemerpark.name)
            assertEquals(WaterQuality.EXCELLENT, diemerpark.quality)
            assertEquals(SwimAdvisory.WARNING, diemerpark.advisory)
            assertNotNull(diemerpark.area)
            assertTrue(diemerpark.swimmable)
            val noord = got.single { it.id == "NLBW13_RO539" }
            assertEquals(SwimAdvisory.ADVISED_AGAINST, noord.advisory)
            assertFalse(noord.swimmable)
            val dutch = got.single { it.source == BathingSite.ZWEMWATER }
            assertEquals("Testplas", dutch.name)
            assertNull(dutch.quality)
            // nearest first
            assertEquals(got.sortedBy { app.atlas.geo.Geo.distance(westerkerk, it.location) }, got)
            // the season's service was found by listing them, and the Dutch register asked by box
            assertTrue(m.take().url.encodedPath.endsWith("/BathingWater"))
            val query = m.take().url
            assertEquals("esriGeometryPoint", query.queryParameter("geometryType"))
            assertEquals("15000", query.queryParameter("distance"))
            assertEquals("zwr-wms:zwemplek", m.take().url.queryParameter("typeName"))
            // asked again, nothing is sent
            bw.near(westerkerk, 15_000.0)
            assertEquals(3, m.count)
            // outside the Netherlands only the EU is asked
            bw.near(LatLon(48.85, 2.35), 15_000.0)
            assertEquals(4, m.count)
        }
    }

    @Test
    fun aMissingSeasonIsAnErrorNotAnEmptyAnswer() = runBlocking {
        Mock().use { m ->
            serve(m, query = "missing.json")
            m.dispatch { req ->
                if (req.url.encodedPath.endsWith("/BathingWater")) response(Fixtures.text("eea_services.json"))
                else response("""{"error":{"code":404,"message":"Service not found","details":[]}}""")
            }
            val e = runCatching { waters(m).near(LatLon(48.85, 2.35), 5_000.0) }.exceptionOrNull()
            assertTrue(e is ProviderException)
        }
    }

    @Test
    fun registerNamesReadAsNames() {
        assertEquals("Het Twiske; Doesstrand", BathingWaters.readable("HET TWISKE; DOESSTRAND"))
        assertEquals("Strand Zuidoever, Gaasperplas", BathingWaters.readable("STRAND ZUIDOEVER, GAASPERPLAS"))
        assertEquals("Nieuwe Meer Steiger", BathingWaters.readable("Nieuwe Meer Steiger"))
    }

    @Test
    fun theCatalogueListsIt() {
        assertEquals(BathingWaters.INFO, Catalogue.byId(BathingWaters.ID))
    }
}

package app.atlas.render.data

import app.atlas.geo.BBox
import app.atlas.geo.LatLon
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** A region nobody framed by hand: origin, clock, building tradition and cameras from the box and the country. */
class RegionConfigTest {
    private fun text(o: JsonObject, k: String) = (o[k] as JsonPrimitive).content

    @Test
    fun aDistrictOfRomeGetsItsCentreItsClockAndTheItalianTradition() {
        val box = BBox(12.47, 41.89, 12.49, 41.91)
        val c = RegionConfig.forBox("Rome centro", box)
        assertEquals("Europe/Rome", text(c, "tz"))
        assertEquals("IT", text(c, "country"))
        assertNotNull(c["tradition"])
        assertEquals(LatLon(41.9, 12.48).lat, RegionConfig.originOf(c)!!.lat, 1e-9)
        assertEquals(box.west, RegionConfig.bboxOf(c)!!.west, 1e-9)
        assertEquals(41.89, RegionConfig.bboxOf(c)!!.south, 1e-9)
    }

    @Test
    fun anExplicitCountryWinsOverTheGuess() {
        val c = RegionConfig.forBox("Hoek", BBox(4.0, 51.9, 4.1, 52.0), "BE")
        assertEquals("BE", text(c, "country"))
        assertEquals("Europe/Brussels", text(c, "tz"))
    }

    @Test
    fun theViewsFollowTheRegionsSize() {
        val small = RegionConfig.views(BBox(4.88, 52.37, 4.89, 52.375))
        assertNotNull(small[""])
        assertNull("a region under 900 m has no close view", small["close"])
        val big = RegionConfig.views(BBox(4.8, 52.3, 5.0, 52.45))
        val overview = big[""] as JsonObject
        assertEquals(4000, text(overview, "dist").toInt())
        val close = big["close"] as JsonObject
        assertEquals(430, text(close, "dist").toInt())
        assertEquals(3, (close["target"] as JsonArray).size)
        for (k in listOf("yaw", "pitch", "fov", "cy", "radius", "ground_r")) assertNotNull(k, overview[k])
    }

    @Test
    fun theSameKeysAsAPlacesEntryAreThere() {
        val c = RegionConfig.forBox("X", BBox(5.0, 52.0, 5.01, 52.005), "NL")
        for (k in listOf("bbox", "origin", "title", "tz", "views")) assertTrue(k, k in c)
        assertEquals(4, (c["bbox"] as JsonArray).size)
        assertEquals(2, (c["origin"] as JsonArray).size)
    }

    @Test
    fun countriesResolveByTheSmallestBoxAndOceansHaveNone() {
        assertEquals("LU", Countries.of(LatLon(49.61, 6.13)))
        assertEquals("NL", Countries.of(LatLon(52.37, 4.9)))
        assertEquals("US", Countries.of(LatLon(40.73, -74.0)))
        assertNull(Countries.of(LatLon(0.0, -30.0)))
        assertEquals("Europe/Luxembourg", Countries.timezone("LU", LatLon(49.6, 6.1)))
        assertEquals("nl_amsterdam", Countries.tradition("NL"))
    }

    @Test
    fun aRegionIdIsASlug() {
        assertEquals("rome-centro", RegionBuilder.slug("Rome, centro"))
        assertEquals("sao-paulo", RegionBuilder.slug("São Paulo"))
        assertEquals("region", RegionBuilder.slug("***"))
    }
}

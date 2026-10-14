package app.atlas.ui.search

import app.atlas.geo.LatLon
import app.atlas.model.OsmId
import app.atlas.model.Place
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import javax.xml.parsers.DocumentBuilderFactory

/** Lists written for other apps: GPX that parses, GeoJSON with every tag, plain text with links, safe file names. */
class SavedExportTest {
    private val places = listOf(
        Place(OsmId.parse("n1147560517"), LatLon(52.3739012, 4.8830034), mapOf("name" to "Café Kalkhoven", "amenity" to "cafe", "addr:street" to "Prinsengracht", "addr:housenumber" to "283"), source = "osm"),
        Place(OsmId.parse("w99205257"), LatLon(52.37452, 4.88377), mapOf("name" to "Westerkerk & <toren>", "building" to "church"), source = "osm"),
        Place(null, LatLon(52.3, 4.9), emptyMap(), source = "point"),
    )

    @Test
    fun gpxIsWellFormedWithAWaypointEach() {
        val gpx = SavedExport.gpx("Rainy \"day\"", places, Instant.parse("2026-10-13T17:30:00Z"), "en")
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(gpx.byteInputStream())
        val wpts = doc.getElementsByTagName("wpt")
        assertEquals(3, wpts.length)
        val first = wpts.item(0)
        assertEquals("52.3739012", first.attributes.getNamedItem("lat").nodeValue)
        assertTrue(gpx.contains("<name>Westerkerk &amp; &lt;toren&gt;</name>"))
        assertTrue(gpx.contains("https://www.openstreetmap.org/node/1147560517"))
        assertTrue(gpx.contains("<time>2026-10-13T17:30:00Z</time>"))
        // A point with no name is named by its coordinates.
        assertTrue(gpx.contains("52.3"))
    }

    @Test
    fun geoJsonHasEveryTagAndLonLatOrder() {
        val json = Json.parseToJsonElement(SavedExport.geoJson("Rainy day", places)).jsonObject
        assertEquals("FeatureCollection", json["type"]!!.jsonPrimitive.content)
        val features = json["features"]!!.jsonArray
        assertEquals(3, features.size)
        val f = features[0].jsonObject
        val c = f["geometry"]!!.jsonObject["coordinates"]!!.jsonArray
        assertEquals(4.8830034, c[0].jsonPrimitive.double, 1e-9)
        assertEquals(52.3739012, c[1].jsonPrimitive.double, 1e-9)
        assertEquals("283", f["properties"]!!.jsonObject["addr:housenumber"]!!.jsonPrimitive.content)
        assertEquals("n1147560517", f["id"]!!.jsonPrimitive.content)
        assertFalse(features[2].jsonObject.containsKey("id"))
    }

    @Test
    fun textHasNumberedPlacesWithLinks() {
        val t = SavedExport.text("Rainy day", places, "en")
        val lines = t.lines()
        assertEquals("Rainy day", lines[0])
        assertTrue(t.contains("1. Café Kalkhoven"))
        assertTrue(t.contains("geo:52.373901,4.883003"))
        assertTrue(t.contains("https://www.openstreetmap.org/way/99205257"))
    }

    @Test
    fun fileNamesAreSafe() {
        assertEquals("rainy-day.gpx", SavedExport.fileName("Rainy day!", "gpx"))
        assertEquals("café-crawl.geojson", SavedExport.fileName("  Café / crawl ", "geojson"))
        assertEquals("places.gpx", SavedExport.fileName("***", "gpx"))
    }
}

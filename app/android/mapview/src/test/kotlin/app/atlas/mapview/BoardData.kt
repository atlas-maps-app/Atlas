package app.atlas.mapview

import app.atlas.engine.Extras
import app.atlas.engine.TreeRow
import app.atlas.geo.LatLon
import app.atlas.model.OsmId
import app.atlas.model.OsmType
import app.atlas.model.Place
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import java.io.File
import java.time.Instant

/**
 * The Main board's view as test data: the real OSM trees in it (`testdata/trees-grachten.json`, from the canal ring's
 * Overpass extract), the board's sunny moment and the board pictures (in `cache/`, on the owner's machine only).
 */
object BoardData {
    /** The board's `sunny` moment: 1 October 2026, 16:00 in Amsterdam (the sun at azimuth 222.5°, elevation 26°). */
    val sunny: Instant = Instant.parse("2026-10-01T14:00:00Z")

    private val repo: File get() = File(System.getProperty("atlas.repo") ?: "../../..")

    /** The board picture with its UI, 824 × 1830 (2× of 412 × 915 dp). */
    val picture: File get() = File(repo, "cache/pictures/big/Main.jpg")

    /** The board's map alone, without the UI, pixel-aligned with a 412 × 915 dp screen at density 2. */
    val reference: File get() = File(repo, "cache/look-ref/map_2x.png")

    /** The real trees and tree rows of the board's view, as the app feeds them to the map from a region's place store. */
    val trees: Extras by lazy {
        val root = Json.parseToJsonElement(File(Fixtures.testdata, "trees-grachten.json").readText()).jsonObject
        fun tags(o: JsonObject) = buildMap {
            for (k in listOf("height", "diameter_crown", "leaf_type", "genus")) o[k]?.let { put(k, it.jsonPrimitive.content) }
            put("natural", "tree")
        }
        val trees = root["trees"]!!.jsonArray.map { e ->
            val o = e.jsonObject
            Place(OsmId(OsmType.NODE, o["id"]!!.jsonPrimitive.long), LatLon(o["lat"]!!.jsonPrimitive.double, o["lon"]!!.jsonPrimitive.double), tags(o))
        }
        val rows = root["rows"]!!.jsonArray.map { e ->
            val o = e.jsonObject
            val line = o["line"]!!.jsonArray.map { p -> p.jsonArray.let { LatLon(it[0].jsonPrimitive.double, it[1].jsonPrimitive.double) } }
            TreeRow(line, tags(o) + ("natural" to "tree_row"))
        }
        Extras(trees, rows)
    }
}

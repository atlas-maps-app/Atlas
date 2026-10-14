package app.atlas.places

import app.atlas.geo.LatLon
import app.atlas.model.OsmId
import app.atlas.model.OsmType
import app.atlas.model.Place
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull
import java.io.File

/** The prototype's real OSM extracts, read as places with their geometry kind. */
object Fixtures {
    val repo: File = File(System.getProperty("atlas.repo") ?: "../../..")

    /** An element of an extract: the place and how it is drawn. */
    class Element(val place: Place, val geometry: Geometry)

    private val cache = HashMap<String, List<Element>>()

    /** Every tagged element of `data/<name>/raw.json` (an Overpass result, or the errands file's lists). */
    fun elements(name: String): List<Element> = cache.getOrPut(name) {
        val file = File(repo, "data/$name/raw.json")
        val root = Json.parseToJsonElement(file.readText()) as JsonObject
        val lists = (root["elements"] as? JsonArray)?.let { listOf(it) } ?: root.values.filterIsInstance<JsonArray>()
        val seen = HashSet<String>()
        val out = ArrayList<Element>()
        for (list in lists) for (e in list) {
            val o = e as? JsonObject ?: continue
            val tags = (o["tags"] as? JsonObject)?.mapValues { (it.value as JsonPrimitive).content } ?: continue
            if (tags.isEmpty()) continue
            val type = when ((o["type"] as? JsonPrimitive)?.content) {
                "node" -> OsmType.NODE
                "way" -> OsmType.WAY
                "relation" -> OsmType.RELATION
                else -> continue
            }
            val id = (o["id"] as JsonPrimitive).longOrNull ?: continue
            if (!seen.add("$type$id")) continue
            val at = position(o) ?: continue
            val geometry = when (type) {
                OsmType.NODE -> Geometry.POINT
                OsmType.WAY -> if (closed(o)) Geometry.AREA else Geometry.LINE
                OsmType.RELATION -> if (tags["type"] == "multipolygon" || tags["type"] == "boundary") Geometry.AREA else Geometry.RELATION
            }
            out += Element(Place(OsmId(type, id), at, tags), geometry)
        }
        out
    }

    private fun num(e: JsonElement?): Double? = (e as? JsonPrimitive)?.doubleOrNull

    private fun position(o: JsonObject): LatLon? {
        num(o["lat"])?.let { lat -> num(o["lon"])?.let { return LatLon(lat, it) } }
        (o["center"] as? JsonObject)?.let { c -> return LatLon(num(c["lat"]) ?: return null, num(c["lon"]) ?: return null) }
        (o["geometry"] as? JsonArray)?.let { g ->
            if (g.isNotEmpty()) {
                var lat = 0.0
                var lon = 0.0
                for (p in g) { lat += num((p as JsonObject)["lat"]) ?: 0.0; lon += num(p["lon"]) ?: 0.0 }
                return LatLon(lat / g.size, lon / g.size)
            }
        }
        (o["bounds"] as? JsonObject)?.let { b ->
            return LatLon(((num(b["minlat"]) ?: 0.0) + (num(b["maxlat"]) ?: 0.0)) / 2, ((num(b["minlon"]) ?: 0.0) + (num(b["maxlon"]) ?: 0.0)) / 2)
        }
        return null
    }

    private fun closed(o: JsonObject): Boolean {
        val nodes = o["nodes"] as? JsonArray ?: return false
        return nodes.size >= 4 && nodes.first() == nodes.last()
    }

    /** The extracts' points of interest: elements with one of the keys a place is made of. */
    val poiKeys = listOf("amenity", "shop", "tourism", "leisure", "office", "craft", "healthcare", "emergency", "historic", "club")

    fun pois(name: String): List<Element> = elements(name).filter { e -> poiKeys.any { it in e.place.tags } }
}

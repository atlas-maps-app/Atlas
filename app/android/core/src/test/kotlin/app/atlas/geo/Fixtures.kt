package app.atlas.geo

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import java.io.File

/** The test fixtures: Earcut's JSON polygons, and the real Overpass extract around the Westerkerk. */
object Fixtures {
    val westerkerk = LatLon(52.37452, 4.88377)

    /** A resource as text. */
    fun resource(path: String): String =
        Fixtures::class.java.getResourceAsStream("/$path")!!.bufferedReader().use { it.readText() }

    fun json(path: String): JsonElement = Json.parseToJsonElement(resource(path))

    /** An Earcut fixture as rings of flat coordinates, and its dimension. */
    fun earcutRings(name: String): Pair<List<DoubleArray>, Int> {
        val rings = json("earcut/fixtures/$name.json").jsonArray
        val dim = rings[0].jsonArray[0].jsonArray.size
        return rings.map { ring ->
            val pts = ring.jsonArray
            val out = DoubleArray(pts.size * dim)
            for (i in pts.indices) {
                val p = pts[i].jsonArray
                for (d in 0 until dim) out[i * dim + d] = p[d].jsonPrimitive.double
            }
            out
        } to dim
    }

    /** An OSM element from the extract: its tags and, for ways, the geometry. */
    class Element(val type: String, val id: Long, val tags: Map<String, String>, val points: List<LatLon>)

    val ams: List<Element> by lazy { overpass(File(System.getProperty("atlas.repo"), "data/ams/raw.json")) }

    private fun overpass(file: File): List<Element> {
        val root = Json.parseToJsonElement(file.readText()).jsonObject
        return root["elements"]!!.jsonArray.map { e ->
            val o = e.jsonObject
            val type = o["type"]!!.jsonPrimitive.content
            val tags = (o["tags"] as? JsonObject)?.mapValues { it.value.jsonPrimitive.content } ?: emptyMap()
            val points = when (type) {
                "node" -> listOf(LatLon(o["lat"]!!.jsonPrimitive.double, o["lon"]!!.jsonPrimitive.double))
                "way" -> (o["geometry"] as? JsonArray)?.map {
                    val g = it.jsonObject
                    LatLon(g["lat"]!!.jsonPrimitive.double, g["lon"]!!.jsonPrimitive.double)
                } ?: emptyList()
                else -> emptyList()
            }
            Element(type, o["id"]!!.jsonPrimitive.long, tags, points)
        }
    }

    /** Lines of a CSV resource, without comments and blank lines. */
    fun csv(path: String): List<List<String>> =
        resource(path).lines().filter { it.isNotBlank() && !it.startsWith("#") }.map { it.split(',') }
}

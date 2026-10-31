package app.atlas.packer.world

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Stage P's tag tables (the core's load/Tables.hpp, as `atlas_stagep tags` prints them into mapcore/style/tags.json,
 * which the rendertiles jar carries as `/style/tags.json`): the kinds and classes the world's profile gives a feature
 * by the same tags stage P reads, so a field is the same kind on both sides of a country's edge (ENGINE.md step 5).
 */
class StagePTags(
    /** regions.py's LAND: each land cover kind and its draw order, in its order. */
    val land: Map<String, Int>,
    /** The leisure values that are land cover of their own kind (regions.py's `land_kind`). */
    val landLeisure: Set<String>,
    /** amsterdam.py's road classes and their widths in metres, in its order. */
    val roads: Map<String, Double>,
    /** The highway classes the regions draw over the ground (`S['big']`). */
    val bigRoads: Set<String>,
    /** The railways the regions draw (`S['rail']`). */
    val rail: Set<String>,
    /** The aeroways the regions draw (`S['aero']`). */
    val aero: Set<String>,
    /** The waterway values mapped as water areas (amsterdam.py's water, beside natural=water). */
    val waterWays: Set<String>,
    /** The tunnel values that leave a big road or a railway out (regions.py's `underground`, beside a layer below 0). */
    val tunnels: Set<String>,
) {
    /** These tables with land cover kind [kind] of draw order [order] beside stage P's (the globe's glaciers). */
    fun withLand(kind: String, order: Int): StagePTags =
        StagePTags(LinkedHashMap(land).apply { put(kind, order) }, landLeisure, roads, bigRoads, rail, aero, waterWays, tunnels)

    companion object {
        /** The tables as the packer's jar carries them. */
        val STAGE_P: StagePTags by lazy {
            val text = StagePTags::class.java.getResourceAsStream("/style/tags.json")?.use { String(it.readBytes(), Charsets.UTF_8) }
                ?: throw IllegalStateException("the packer carries no /style/tags.json (mapcore/style/tags.json)")
            parse(text)
        }

        fun parse(text: String): StagePTags {
            val o = Json.parseToJsonElement(text).jsonObject
            fun names(key: String): Set<String> = LinkedHashSet(array(o, key).map { it.jsonPrimitive.content })
            val land = LinkedHashMap<String, Int>()
            for (e in array(o, "land")) {
                val pair = e.jsonArray
                land[pair[0].jsonPrimitive.content] = pair[1].jsonPrimitive.int
            }
            val roads = LinkedHashMap<String, Double>()
            for (e in array(o, "roads")) {
                val pair = e.jsonArray
                roads[pair[0].jsonPrimitive.content] = pair[1].jsonPrimitive.double
            }
            return StagePTags(land, names("land_leisure"), roads, names("big_roads"), names("rail"), names("aero"), names("water_ways"), names("tunnels"))
        }

        private fun array(o: JsonObject, key: String): JsonArray = (o[key] ?: throw IllegalArgumentException("tags.json has no $key")).jsonArray
    }
}

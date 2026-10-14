package app.atlas.render.data

import app.atlas.geo.BBox
import app.atlas.geo.LatLon
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/**
 * The small, readable head of a `.atlas` region pack: what the region is and where its data came from. [json] is the
 * whole manifest as stored; the accessors read the fields the app uses.
 *
 * `config` is the region's entry of `data/regions/places.py` as JSON (title, tz, tradition, moments, park, zoo, and the
 * `views` cameras), so the engine frames the region exactly as the renders did. `header` holds the extract's own
 * top-level fields (`version`, `generator`, `osm3s`, any remark).
 */
class PackManifest(val json: JsonObject) {
    private fun str(key: String): String? = (json[key] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content
    private fun num(o: JsonObject, key: String): Double = (o[key] as? JsonPrimitive)?.content?.toDouble() ?: throw PackFormatException("the manifest lacks $key")

    val version: Int get() = (json["version"] as? JsonPrimitive)?.content?.toIntOrNull() ?: throw PackFormatException("the manifest has no version")
    val id: String get() = str("id") ?: throw PackFormatException("the manifest has no id")
    val name: String get() = str("name") ?: id
    val timezone: String? get() = str("tz")
    val tradition: String? get() = str("tradition")

    /** The OSM data's own date, `osm3s.timestamp_osm_base` of the extract. */
    val dataTime: String? get() = str("data_time")
    val licence: String get() = str("licence") ?: LICENCE
    val attribution: String get() = str("attribution") ?: ATTRIBUTION

    val bbox: BBox
        get() = (json["bbox"] as? JsonObject)?.let { BBox(num(it, "west"), num(it, "south"), num(it, "east"), num(it, "north")) }
            ?: throw PackFormatException("the manifest has no bbox")

    val origin: LatLon
        get() = (json["origin"] as? JsonObject)?.let { LatLon(num(it, "lat"), num(it, "lon")) } ?: bbox.center

    val config: JsonObject get() = json["config"] as? JsonObject ?: JsonObject(emptyMap())
    val header: JsonObject get() = json["header"] as? JsonObject ?: JsonObject(emptyMap())
    val sources: List<String> get() = (json["sources"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.content }.orEmpty()

    /** The views of the region's cameras (`config.views`), keyed '' for the overview. */
    val views: JsonObject? get() = config["views"] as? JsonObject

    val elementCount: Int get() = ((json["counts"] as? JsonObject)?.get("elements") as? JsonPrimitive)?.content?.toIntOrNull() ?: 0

    /** Pretty JSON for people (`packer info`). */
    fun pretty(): String = Json { prettyPrint = true }.encodeToString(JsonElement.serializer(), json)

    companion object {
        const val FORMAT = "atlas-pack"
        const val VERSION = 1
        const val LICENCE = "ODbL-1.0"
        const val ATTRIBUTION = "Map data © OpenStreetMap contributors, under the Open Database Licence (ODbL 1.0): https://www.openstreetmap.org/copyright"

        fun parse(text: String): PackManifest {
            val o = Json.parseToJsonElement(text) as? JsonObject ?: throw PackFormatException("the manifest is not a JSON object")
            if ((o["format"] as? JsonPrimitive)?.content != FORMAT) throw PackFormatException("not an Atlas region pack")
            return PackManifest(o)
        }

        /** A manifest from its parts; [extra] fields (counts, digits, chunk size) are added by the writer. */
        fun build(
            id: String,
            name: String,
            bbox: BBox,
            origin: LatLon,
            tz: String?,
            tradition: String?,
            dataTime: String?,
            config: JsonObject,
            header: JsonObject,
            sources: List<String>,
            extra: JsonObject = JsonObject(emptyMap()),
        ): PackManifest = PackManifest(buildJsonObject {
            put("format", FORMAT)
            put("version", VERSION)
            put("id", id)
            put("name", name)
            put("bbox", buildJsonObject {
                put("south", bbox.south)
                put("west", bbox.west)
                put("north", bbox.north)
                put("east", bbox.east)
            })
            put("origin", buildJsonObject {
                put("lat", origin.lat)
                put("lon", origin.lon)
            })
            if (tz != null) put("tz", tz)
            if (tradition != null) put("tradition", tradition)
            if (dataTime != null) put("data_time", dataTime)
            put("licence", LICENCE)
            put("attribution", ATTRIBUTION)
            putJsonArray("sources") { for (s in sources) add(JsonPrimitive(s)) }
            put("config", config)
            put("header", header)
            for ((k, v) in extra) put(k, v)
        })
    }
}

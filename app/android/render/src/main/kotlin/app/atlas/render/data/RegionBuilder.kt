package app.atlas.render.data

import app.atlas.geo.BBox
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** Makes a [PackSpec] from a region's configuration and its elements. Runs on the JVM and on the phone. */
object RegionBuilder {
    /** A pack spec whose name, box, origin, time zone and tradition come from [config] (a `places.py` entry). */
    fun spec(
        id: String,
        config: JsonObject,
        elements: ElementSource,
        sources: List<String> = emptyList(),
        dems: Map<String, Dem> = emptyMap(),
        aux: Map<String, JsonElement> = emptyMap(),
    ): PackSpec {
        val bbox = RegionConfig.bboxOf(config) ?: throw IllegalArgumentException("region $id: the configuration has no bbox")
        val origin = RegionConfig.originOf(config) ?: bbox.center
        fun str(key: String) = (config[key] as? JsonPrimitive)?.content
        return PackSpec(id, str("title") ?: id, bbox, origin, str("tz"), str("tradition"), config, elements, sources, dems, aux)
    }

    /** A region nobody framed by hand, fetched from Overpass: the default configuration for [bbox] and [country]. */
    fun fromOverpass(client: OverpassClient, id: String, name: String, bbox: BBox, country: String? = null): PackSpec {
        val result = client.fetch(bbox)
        return spec(id, RegionConfig.forBox(name, bbox, country), result.source(), listOf("Overpass API, the query of fetch/regions.py", "OpenStreetMap contributors (ODbL 1.0)"))
    }

    /** On the phone (or anywhere): fetches [bbox] from Overpass and writes the region's pack to [file]. */
    fun buildFromOverpass(client: OverpassClient, id: String, name: String, bbox: BBox, file: java.io.File, country: String? = null): PackStats =
        PackWriter.write(fromOverpass(client, id, name, bbox, country), file)

    /** A region id for a name: lower-case letters, digits and dashes. */
    fun slug(name: String): String {
        val folded = java.text.Normalizer.normalize(name, java.text.Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "")
        return folded.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-').ifEmpty { "region" }
    }
}

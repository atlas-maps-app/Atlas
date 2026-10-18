package app.atlas.services

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import java.io.IOException
import java.io.InputStream

/**
 * The render packs the APK carries (`tiles/bundled.json`, written by `packer render-tiles --bundled`): region id to the
 * render pack's SHA-256, which [app.atlas.render.live.PackLibrary] takes as `bundledRenders`, so a published render pack
 * equal to a bundled one is never downloaded.
 */
internal object BundledRenders {
    /** The index's asset path. */
    const val ASSET = "tiles/bundled.json"

    /** The index [open] gives, or an empty map when there is none or it cannot be read. */
    fun read(open: () -> InputStream): Map<String, String> = try {
        parse(open().use { it.readBytes().decodeToString() })
    } catch (_: IOException) {
        emptyMap() // no bundled render tiles in this build, or the asset cannot be read here
    } catch (_: IllegalArgumentException) {
        emptyMap() // not the index's JSON (SerializationException is one)
    }

    /** Each pack of the index [text] with its id and the SHA-256 of its own file; a pack without either is left out. */
    fun parse(text: String): Map<String, String> {
        val packs = Json.parseToJsonElement(text).jsonObject["packs"]?.jsonArray ?: return emptyMap()
        val out = LinkedHashMap<String, String>()
        for (p in packs) {
            val pack = p as? JsonObject ?: continue
            val id = (pack["id"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: continue
            val sha = (pack["sha256"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: continue
            out[id] = sha.lowercase()
        }
        return out
    }
}

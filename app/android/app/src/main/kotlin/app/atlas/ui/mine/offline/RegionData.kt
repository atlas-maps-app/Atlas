package app.atlas.ui.mine.offline

import app.atlas.geo.BBox
import app.atlas.services.RegionLayer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import java.io.File
import java.io.IOException

/** How a region's map layer is kept: as Atlas's own region pack (`.atlas`), or not at all. */
enum class MapKind(val id: String) { ATLAS("atlas"), NONE("none");

    companion object {
        fun of(id: String?): MapKind = entries.firstOrNull { it.id == id } ?: NONE
    }
}

/** One layer of a region as it is on the phone: how it got there, its size, its data's date and the pack it came from. */
data class LayerRecord(
    val kind: String,
    val bytes: Long,
    /** When it was fetched (epoch millis). */
    val at: Long,
    /** The SHA-256 of the pack file it came from, to tell when the pack has a newer one. */
    val sha: String? = null,
    /** The OpenStreetMap time of its data, when known. */
    val dataTime: String? = null,
)

/**
 * What the phone keeps about one region, in `regions/atlas/<id>.json`: its name and box, when it was made, and each layer
 * it holds (the map, the places, the routing tiles, the voices) with its size. Files are named from the [id].
 */
data class RegionMeta(
    val id: String,
    val name: String,
    val bbox: BBox,
    val createdAt: Long,
    val updatedAt: Long,
    /** The pack the region was taken from, when one exists for it. */
    val packId: String? = null,
    val layers: Map<RegionLayer, LayerRecord> = emptyMap(),
    /** BRouter tiles the region's routing needs (shared between regions). */
    val segments: List<String> = emptyList(),
    val voices: List<String> = emptyList(),
    /** The layers wanted, so an interrupted download knows what is left. */
    val wanted: Set<RegionLayer> = emptySet(),
) {
    fun toJson(): String = buildJsonObject {
        put("id", id)
        put("name", name)
        putJsonArray("bbox") { add(JsonPrimitive(bbox.west)); add(JsonPrimitive(bbox.south)); add(JsonPrimitive(bbox.east)); add(JsonPrimitive(bbox.north)) }
        put("created", createdAt)
        put("updated", updatedAt)
        packId?.let { put("pack", it) }
        put("layers", buildJsonObject {
            for ((l, r) in layers) put(l.name.lowercase(), buildJsonObject {
                put("kind", r.kind)
                put("bytes", r.bytes)
                put("at", r.at)
                r.sha?.let { put("sha", it) }
                r.dataTime?.let { put("data", it) }
            })
        })
        put("segments", buildJsonArray { segments.forEach { add(JsonPrimitive(it)) } })
        put("voices", buildJsonArray { voices.forEach { add(JsonPrimitive(it)) } })
        put("wanted", buildJsonArray { wanted.forEach { add(JsonPrimitive(it.name.lowercase())) } })
    }.toString()

    companion object {
        fun fromJson(text: String): RegionMeta? = runCatching {
            val o = Json.parseToJsonElement(text).jsonObject
            val b = o["bbox"]!!.jsonArray.map { it.jsonPrimitive.doubleOrNull!! }
            val layers = LinkedHashMap<RegionLayer, LayerRecord>()
            for ((k, v) in o["layers"]?.jsonObject.orEmpty()) {
                val layer = RegionLayer.entries.firstOrNull { it.name.equals(k, true) } ?: continue
                val r = v.jsonObject
                layers[layer] = LayerRecord(
                    r["kind"]?.jsonPrimitive?.contentOrNull ?: "", r["bytes"]?.jsonPrimitive?.longOrNull ?: 0L, r["at"]?.jsonPrimitive?.longOrNull ?: 0L,
                    r["sha"]?.jsonPrimitive?.contentOrNull, r["data"]?.jsonPrimitive?.contentOrNull,
                )
            }
            fun strings(key: String) = (o[key] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }.orEmpty()
            RegionMeta(
                id = o["id"]!!.jsonPrimitive.content,
                name = o["name"]!!.jsonPrimitive.content,
                bbox = BBox(b[0], b[1], b[2], b[3]),
                createdAt = o["created"]?.jsonPrimitive?.longOrNull ?: 0L,
                updatedAt = o["updated"]?.jsonPrimitive?.longOrNull ?: 0L,
                packId = (o["pack"] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.contentOrNull,
                layers = layers,
                segments = strings("segments"),
                voices = strings("voices"),
                wanted = strings("wanted").mapNotNull { w -> RegionLayer.entries.firstOrNull { it.name.equals(w, true) } }.toSet(),
            )
        }.getOrNull()
    }
}

/** Where a region's files are: the atlas pack and metadata under `regions/atlas`, the place store under `places`. */
class RegionFiles(val regions: File, val places: File, val segments: File) {
    val atlasDir = File(regions, "atlas")

    fun meta(id: String) = File(atlasDir, "$id.json")
    fun atlas(id: String) = File(atlasDir, "$id.atlas")
    fun places(id: String) = File(places, "$id.db")

    /** Every region's metadata, by name. */
    fun list(): List<RegionMeta> = atlasDir.listFiles { f -> f.name.endsWith(".json") }.orEmpty()
        .mapNotNull { f -> runCatching { RegionMeta.fromJson(f.readText()) }.getOrNull() }
        .sortedBy { it.name.lowercase() }

    fun get(id: String): RegionMeta? = meta(id).takeIf { it.isFile }?.let { runCatching { RegionMeta.fromJson(it.readText()) }.getOrNull() }

    /** Changes the record of [id] as one step (read, change, write), so two writers do not lose each other's change; null when it is gone. */
    @Synchronized
    fun edit(id: String, change: (RegionMeta) -> RegionMeta): RegionMeta? {
        val m = get(id) ?: return null
        return change(m).also { save(it) }
    }

    /** Writes [m] atomically. */
    @Synchronized
    fun save(m: RegionMeta) {
        atlasDir.mkdirs()
        val tmp = File(atlasDir, "${m.id}.json.tmp")
        tmp.writeText(m.toJson())
        if (!tmp.renameTo(meta(m.id))) {
            meta(m.id).delete()
            if (!tmp.renameTo(meta(m.id))) throw IOException("cannot write ${meta(m.id)}")
        }
    }

    /** Removes the files of [id] that only it uses (the atlas pack, the place store); returns bytes freed. */
    @Synchronized
    fun deleteOwn(id: String): Long {
        var freed = 0L
        for (f in listOf(atlas(id), File(atlas(id).path + ".download"), File(atlas(id).path + ".download.id"), places(id), File(places(id).path + ".download"),
            File(places(id).path + ".download.id"), File(places(id).path + "-wal"), File(places(id).path + "-shm"), meta(id))) {
            if (f.isFile) { freed += f.length(); f.delete() }
        }
        return freed
    }
}

/** What a download of a region is doing now: the layer at work and how far the whole has got (0..1, by bytes expected). */
data class RegionProgress(val layer: RegionLayer?, val fraction: Float, val waiting: Boolean = false)

/** What an update would fetch: its size when known and the layers behind it. */
data class UpdateInfo(val bytes: Long?, val layers: Set<RegionLayer>)

/** What a region screen shows of one region: its record, its layers' sizes, progress, error and update. */
data class RegionDetail(
    val meta: RegionMeta,
    val sizes: Map<RegionLayer, Long>,
    val complete: Boolean,
    val progress: RegionProgress? = null,
    val error: String? = null,
    val update: UpdateInfo? = null,
) {
    val id: String get() = meta.id
    val name: String get() = meta.name
    val bytes: Long get() = sizes.values.sum()
    val hasAtlas: Boolean get() = meta.layers[RegionLayer.MAP]?.kind == MapKind.ATLAS.id
}

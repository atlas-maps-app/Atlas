package app.atlas.packer

import app.atlas.geo.BBox
import app.atlas.tiles.FileRangeSource
import app.atlas.tiles.PmHeader
import app.atlas.tiles.PmTiles
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import java.io.File
import java.time.Instant

/**
 * `packs.json`, the list of packs the app offers: per region its id, name, country, box, the OSM time its data is
 * current to, the zoom range of its tiles, and its files (kind, URL, bytes, SHA-256). A build job writes one
 * `<id>.pack.json` per region it built; the publishing job merges those with the previous list.
 */
object Manifest {
    /** Bumped when a reader would misread the file. */
    const val FORMAT = 1

    const val ATTRIBUTION = "Map data © OpenStreetMap contributors, under the Open Database Licence (ODbL 1.0). " +
        "Extracts by Geofabrik. Tiles in the OpenMapTiles schema (© OpenMapTiles, CC-BY 4.0), built with Planetiler."

    /** A region the workflow builds, from `packs/regions.json`. */
    data class Region(val id: String, val name: String, val country: String?, val url: String?, val bbox: BBox?, val clip: Boolean)

    private val pretty = Json { prettyPrint = true }

    fun pretty(e: JsonElement): String = pretty.encodeToString(JsonElement.serializer(), e)

    /** A coordinate with seven decimals, as OSM keeps them. */
    fun round(v: Double): Double = Math.round(v * 1e7) / 1e7

    fun corners(b: BBox): List<Double> = listOf(round(b.west), round(b.south), round(b.east), round(b.north))

    /** Reads `packs/regions.json`: `{"regions": [{"id", "name", "country", "url", "bbox": [w, s, e, n], "clip"}]}`. */
    fun readRegions(file: File): List<Region> {
        val root = Json.parseToJsonElement(file.readText()).jsonObject
        return root["regions"]!!.jsonArray.map { e ->
            val o = e.jsonObject
            val id = o.str("id") ?: error("a region without an id in ${file.name}")
            require(ID.matches(id)) { "region id '$id' must be lower-case letters, digits and dashes" }
            Region(id, o.str("name") ?: id, o.str("country"), o.str("url"), o.box("bbox"), o["clip"]?.jsonPrimitive?.booleanOrNull ?: false)
        }.also { list ->
            val dup = list.groupBy { it.id }.filterValues { it.size > 1 }.keys
            require(dup.isEmpty()) { "regions listed twice: $dup" }
        }
    }

    /**
     * Writes `<id>.pack.json` for every region with files in [dir] (tiles `<id>.pmtiles`, places `<id>.places.db.gz`
     * with its `<id>.places.json` sidecar), their URLs under [urlBase], and returns the merged list: the [previous]
     * list, then any `<id>.pack.json` already in [dir], then the fresh entries, each replacing the one before. With
     * [regions], only listed regions stay, in their order.
     */
    fun build(
        dir: File,
        urlBase: String,
        regions: List<Region>? = null,
        previous: JsonObject? = null,
        now: Instant = Instant.now(),
        splitBytes: Long = SPLIT_BYTES,
    ): JsonObject {
        require(urlBase.startsWith("https://") || urlBase.startsWith("http://")) { "the release URL base must be http(s): $urlBase" }
        val base = urlBase.trimEnd('/')
        val byId = regions?.associateBy { it.id }
        val entries = LinkedHashMap<String, JsonObject>()
        previous?.get("packs")?.jsonArray?.forEach { e -> e.jsonObject.str("id")?.let { entries[it] = e.jsonObject } }
        val names = dir.listFiles()?.map { it.name }.orEmpty().sorted()
        for (n in names) if (n.endsWith(PACK_SUFFIX)) {
            val o = Json.parseToJsonElement(File(dir, n).readText()).jsonObject
            entries[o.str("id") ?: n.removeSuffix(PACK_SUFFIX)] = o
        }
        val fresh = names.mapNotNull { n ->
            when {
                n.endsWith(TILES_SUFFIX) -> n.removeSuffix(TILES_SUFFIX)
                n.endsWith(PLACES_GZ_SUFFIX) -> n.removeSuffix(PLACES_GZ_SUFFIX)
                n.endsWith(PLACES_SUFFIX) -> n.removeSuffix(PLACES_SUFFIX)
                else -> null
            }
        }.distinct()
        for (id in fresh) {
            val entry = entry(dir, id, base, byId?.get(id), entries[id], splitBytes)
            File(dir, id + PACK_SUFFIX).writeText(pretty(entry) + "\n")
            entries[id] = entry
        }
        val ordered = if (regions != null) regions.mapNotNull { entries[it.id] } else entries.keys.sorted().map { entries.getValue(it) }
        return buildJsonObject {
            put("format", FORMAT)
            put("generated", now.toString())
            put("attribution", ATTRIBUTION)
            put("packs", JsonArray(ordered))
        }
    }

    /**
     * One region's entry from its files in [dir]; [region] and [before] (its earlier entry) fill in what the files do
     * not say, and a kind of file no longer here whole (already cut into parts) keeps its earlier description.
     */
    fun entry(dir: File, id: String, base: String, region: Region?, before: JsonObject? = null, splitBytes: Long = SPLIT_BYTES): JsonObject {
        val tiles = File(dir, id + TILES_SUFFIX).takeIf { it.isFile }
        val places = File(dir, id + PLACES_GZ_SUFFIX).takeIf { it.isFile } ?: File(dir, id + PLACES_SUFFIX).takeIf { it.isFile }
        val earlier = before?.get("files")?.let { it as? JsonArray }?.mapNotNull { it as? JsonObject }.orEmpty()
        fun earlier(kind: String) = earlier.firstOrNull { it.str("kind") == kind }
        require(tiles != null || places != null || earlier.isNotEmpty()) { "no files for region $id in $dir" }
        val sidecar = File(dir, "$id.places.json").takeIf { it.isFile }?.let { Json.parseToJsonElement(it.readText()).jsonObject }
        val header = tiles?.let { readHeader(it) }
        val metadata = tiles?.let { runCatching { readMetadata(it) }.getOrDefault(emptyMap()) }.orEmpty()
        val bbox = region?.bbox ?: sidecar?.box("bbox") ?: header?.bounds ?: before?.box("bbox")
        val osmTime = sidecar?.str("osm_timestamp")
            ?: metadata.entries.firstOrNull { it.key.endsWith("osmosisreplicationtime") }?.value?.toString()
            ?: before?.str("osm_timestamp")
        val tilesEntry = if (tiles != null) buildJsonObject {
            put("kind", "tiles")
            file(tiles, base, splitBytes)
            put("format", "pmtiles")
            metadata["version"]?.let { put("schema", "openmaptiles $it") }
        } else earlier("tiles")
        val placesEntry = if (places != null) buildJsonObject {
            put("kind", "places")
            val gz = places.name.endsWith(".gz")
            file(places, base, splitBytes)
            put("format", "sqlite")
            if (gz) put("compression", "gzip")
            sidecar?.long("raw_bytes")?.let { put("raw_bytes", it) }
            sidecar?.get("schema")?.jsonPrimitive?.intOrNull?.let { put("schema", it) }
            sidecar?.long("places")?.let { put("places", it) }
            sidecar?.long("addresses")?.let { put("addresses", it) }
            sidecar?.long("trees")?.let { put("trees", it) }
        } else earlier("places")
        return buildJsonObject {
            put("id", id)
            put("name", region?.name ?: before?.str("name") ?: id)
            (region?.country ?: before?.str("country"))?.let { put("country", it) }
            bbox?.let { b -> putJsonArray("bbox") { for (v in corners(b)) add(v) } }
            osmTime?.let { put("osm_timestamp", it) }
            val minZoom = header?.minZoom ?: before?.get("minzoom")?.jsonPrimitive?.intOrNull
            val maxZoom = header?.maxZoom ?: before?.get("maxzoom")?.jsonPrimitive?.intOrNull
            if (minZoom != null && maxZoom != null) {
                put("minzoom", minZoom)
                put("maxzoom", maxZoom)
            }
            (releaseOf(base) ?: before?.str("release"))?.let { put("release", it) }
            put("files", JsonArray(listOfNotNull(tilesEntry, placesEntry)))
        }
    }

    /**
     * A file's name, size and SHA-256, and its URL; a file over [splitBytes] is cut into parts (see [PackFiles.split])
     * listed in order under "parts", each with its own URL, size and SHA-256, and the whole file's checksum kept.
     */
    private fun JsonObjectBuilder.file(f: File, base: String, splitBytes: Long) {
        put("name", f.name)
        put("bytes", f.length())
        put("sha256", PackFiles.sha256(f))
        if (f.length() <= splitBytes) {
            put("url", "$base/${f.name}")
            return
        }
        val parts = PackFiles.split(f, splitBytes)
        putJsonArray("parts") {
            for (p in parts) addJsonObject {
                put("name", p.name)
                put("url", "$base/${p.name}")
                put("bytes", p.length())
                put("sha256", PackFiles.sha256(p))
            }
        }
    }

    /** The release tag in a `…/releases/download/<tag>` URL. */
    fun releaseOf(base: String): String? = Regex("/releases/download/([^/]+)$").find(base)?.groupValues?.get(1)

    private fun readHeader(file: File): PmHeader {
        val head = ByteArray(127)
        file.inputStream().use { input ->
            var n = 0
            while (n < head.size) {
                val k = input.read(head, n, head.size - n)
                if (k < 0) break
                n += k
            }
            require(n == head.size) { "${file.name} is too short for a PMTiles header" }
        }
        return PmHeader.parse(head)
    }

    private fun readMetadata(file: File): Map<String, Any?> = runBlocking {
        PmTiles.open(FileRangeSource(file)).use { it.metadata }
    }

    private fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull
    private fun JsonObject.long(key: String): Long? = (this[key] as? JsonPrimitive)?.longOrNull

    private fun JsonObject.box(key: String): BBox? {
        val a = (this[key] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.doubleOrNull } ?: return null
        return if (a.size == 4) BBox(a[0], a[1], a[2], a[3]) else null
    }

    /** GitHub release assets must stay under 2 GiB: bigger files go up in parts of this size. */
    const val SPLIT_BYTES = 2_000_000_000L

    const val TILES_SUFFIX = ".pmtiles"
    const val PLACES_GZ_SUFFIX = ".places.db.gz"
    const val PLACES_SUFFIX = ".places.db"
    const val PACK_SUFFIX = ".pack.json"
    private val ID = Regex("[a-z0-9][a-z0-9-]*")
}

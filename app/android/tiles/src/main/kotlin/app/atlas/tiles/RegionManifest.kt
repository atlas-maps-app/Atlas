package app.atlas.tiles

import app.atlas.geo.BBox
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.onEach
import java.io.File
import java.text.Normalizer
import java.time.Instant

/** What Atlas remembers about an offline region: its name, area, zooms, size and dates. Stored as a small JSON file. */
data class RegionManifest(
    /** A file-safe id, also the pack's file name. */
    val id: String,
    val name: String,
    val bbox: BBox,
    val minZoom: Int,
    val maxZoom: Int,
    val tileCount: Long,
    val bytes: Long,
    /** Epoch milliseconds. */
    val createdAt: Long,
    val updatedAt: Long,
    /** The data release the tiles belong to. */
    val version: String? = null,
    /** False while a download is unfinished (it can be resumed). */
    val complete: Boolean = false,
) {
    fun toJson(): String = Json.write(
        linkedMapOf(
            "id" to id,
            "name" to name,
            "bbox" to listOf(bbox.west, bbox.south, bbox.east, bbox.north),
            "minzoom" to minZoom,
            "maxzoom" to maxZoom,
            "tiles" to tileCount,
            "bytes" to bytes,
            "created" to Instant.ofEpochMilli(createdAt).toString(),
            "updated" to Instant.ofEpochMilli(updatedAt).toString(),
            "version" to version,
            "complete" to complete,
        ),
    )

    companion object {
        fun fromJson(text: String): RegionManifest {
            val m = Json.parseObject(text)
            val b = m.list("bbox")?.mapNotNull { (it as? Number)?.toDouble() }?.takeIf { it.size == 4 }
                ?: throw TileFormatException("region manifest without a bbox")
            fun time(key: String): Long = m.str(key)?.let { runCatching { Instant.parse(it).toEpochMilli() }.getOrNull() } ?: 0L
            return RegionManifest(
                id = m.str("id") ?: throw TileFormatException("region manifest without an id"),
                name = m.str("name") ?: "",
                bbox = BBox(b[0], b[1], b[2], b[3]),
                minZoom = m.int("minzoom") ?: 0,
                maxZoom = m.int("maxzoom") ?: 14,
                tileCount = m.long("tiles") ?: 0,
                bytes = m.long("bytes") ?: 0,
                createdAt = time("created"),
                updatedAt = time("updated"),
                version = m.str("version"),
                complete = m["complete"] as? Boolean ?: false,
            )
        }

        /**
         * A file-safe id from a region's name: lower-case ASCII letters, digits and hyphens, accents dropped ("Zürich" is
         * "zurich"). When that loses part of the name (another script, or a name cut to length), a short hash of the
         * name is added, so "東京" and "大阪" do not share one pack.
         */
        fun idFor(name: String): String {
            val ascii = Normalizer.normalize(name, Normalizer.Form.NFKD).replace(MARKS, "")
            val slug = ascii.lowercase().replace(NON_ID, "-").trim('-')
            val lossy = slug.length > MAX_ID || ascii.any { it.isLetterOrDigit() && !(it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9') }
            if (!lossy && slug.isNotEmpty()) return slug
            val hash = String.format(java.util.Locale.ROOT, "%08x", name.hashCode())
            return (slug.take(MAX_ID - 9).trim('-').ifEmpty { "region" }) + "-" + hash
        }

        private const val MAX_ID = 48
        private val MARKS = Regex("\\p{M}+")
        private val NON_ID = Regex("[^a-z0-9]+")
    }
}

/** The offline regions on the device: a folder of `<id>.mbtiles` packs, each with its `<id>.json` manifest. */
class RegionLibrary(val dir: File, private val clock: () -> Long = System::currentTimeMillis) {
    init {
        dir.mkdirs()
    }

    fun packFile(id: String) = File(dir, "$id.mbtiles")
    fun manifestFile(id: String) = File(dir, "$id.json")

    /** Every region with a readable manifest, by name. */
    fun list(): List<RegionManifest> =
        dir.listFiles { f -> f.name.endsWith(".json") }.orEmpty()
            .mapNotNull { runCatching { RegionManifest.fromJson(it.readText()) }.getOrNull() }
            .sortedBy { it.name.lowercase() }

    fun get(id: String): RegionManifest? =
        manifestFile(id).takeIf { it.isFile }?.let { runCatching { RegionManifest.fromJson(it.readText()) }.getOrNull() }

    /** Writes [m] (atomically: a temporary file renamed over the old one). */
    fun save(m: RegionManifest) {
        val tmp = File(dir, "${m.id}.json.tmp")
        tmp.writeText(m.toJson())
        if (!tmp.renameTo(manifestFile(m.id))) {
            manifestFile(m.id).delete()
            if (!tmp.renameTo(manifestFile(m.id))) throw java.io.IOException("cannot write ${manifestFile(m.id)}")
        }
    }

    /** Removes a region's pack and manifest. */
    fun delete(id: String): Boolean {
        val base = packFile(id).path
        listOf("", "-wal", "-shm", "-journal").forEach { File(base + it).delete() }
        return manifestFile(id).delete()
    }

    /** Opens every complete region's pack, for a [TileStack]. */
    fun openPacks(): List<MbTiles> = list().filter { it.complete && packFile(it.id).isFile }.mapNotNull {
        runCatching { MbTiles(packFile(it.id)) }.getOrNull()
    }

    /**
     * Downloads (or resumes, or with [update] refreshes) region [name] of [plan] with [downloader], keeping its manifest
     * current as tiles arrive.
     */
    fun download(downloader: RegionDownloader, plan: RegionPlan, name: String, update: Boolean = false): Flow<DownloadProgress> {
        val id = RegionManifest.idFor(name)
        val pack = packFile(id)
        val tiles = if (update) downloader.update(plan, pack, name) else downloader.download(plan, pack, name)
        // Nothing is written until the flow is collected; each collection reads the manifest afresh.
        return flow {
            val existing = get(id)
            val started = clock()
            val createdAt = existing?.createdAt ?: started
            // An update refreshes a pack that already works: it stays on the map while it runs, and if it stops half-way.
            val keptComplete = update && existing?.complete == true
            save(
                RegionManifest(
                    id, name, plan.bbox, plan.minZoom, plan.maxZoom, plan.tileCount, existing?.bytes ?: 0,
                    createdAt, started, existing?.version, complete = keptComplete,
                ),
            )
            emitAll(
                tiles.onEach { p ->
                    if (p.finished) {
                        val version = runCatching { MbTiles(pack).use { it.metadata("atlas.version") } }.getOrNull()
                        save(
                            RegionManifest(
                                id, name, plan.bbox, plan.minZoom, plan.maxZoom, plan.tileCount, pack.length(),
                                createdAt, clock(), version, complete = keptComplete || p.failed == 0L,
                            ),
                        )
                    }
                },
            )
        }
    }
}

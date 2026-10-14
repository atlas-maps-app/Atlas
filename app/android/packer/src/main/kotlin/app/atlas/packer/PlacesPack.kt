package app.atlas.packer

import app.atlas.search.PlaceStore
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import java.io.File

/** What `packer places` wrote: the store (gzipped, or plain), its sidecar of facts for the manifest, and the counts. */
data class PlacesPack(val file: File, val sidecar: File, val rawBytes: Long, val stats: PackStats, val summary: StoreSummary)

/** Builds a region's place pack: the store from the extract, sealed with the extract's facts, gzipped. */
object PlacesPackBuilder {
    /** Tells pack files from different builders apart. */
    const val VERSION = "atlas-packer/1"

    /**
     * Builds the store at [out] from [pbf]. With [gzip] it then writes `<out>.gz` and, unless [keepDb], removes
     * [out]. Either way it writes the sidecar [sidecarFor] ([out]) that `packer manifest` reads.
     */
    fun build(
        pbf: File,
        out: File,
        options: PackOptions = PackOptions(),
        name: String? = null,
        gzip: Boolean = true,
        keepDb: Boolean = false,
        vacuum: Boolean = true,
        log: (String) -> Unit = {},
    ): PlacesPack {
        out.absoluteFile.parentFile?.mkdirs()
        PackFiles.deleteDatabase(out)
        val stats = PlaceStore.open(out).use { store -> PlacePacker(options).pack(pbf, store, name, log) }
        val h = stats.header
        val meta = LinkedHashMap<String, String>()
        h.replicationTime?.let { meta["osm_timestamp"] = it.toString() }
        h.replicationSequence?.let { meta["osm_sequence"] = it.toString() }
        h.writingProgram?.let { meta["osm_writer"] = it }
        meta["osm_source"] = pbf.name
        (options.clip ?: h.bbox)?.let { b -> meta["bbox"] = listOf(b.west, b.south, b.east, b.north).joinToString(",") }
        meta["packer"] = VERSION
        val summary = PackFiles.sealStore(out, meta, vacuum, log)
        check(summary.places == stats.places.toLong()) { "the sealed store holds ${summary.places} places, the import wrote ${stats.places}" }
        val rawBytes = out.length()
        log("sealed: ${rawBytes shr 20} MiB")
        val file = if (gzip) {
            val gz = File(out.path + ".gz")
            PackFiles.gzip(out, gz)
            log("gzipped: ${gz.length() shr 20} MiB")
            if (!keepDb) PackFiles.deleteDatabase(out)
            gz
        } else {
            out
        }
        val sidecar = sidecarFor(out)
        sidecar.writeText(Manifest.pretty(sidecarJson(file, rawBytes, stats, summary, options)) + "\n")
        return PlacesPack(file, sidecar, rawBytes, stats, summary)
    }

    /** `x.places.db` → `x.places.json`: the facts `packer manifest` needs about a store it may only see gzipped. */
    fun sidecarFor(db: File): File = File(db.absoluteFile.parentFile, db.name.removeSuffix(".gz").removeSuffix(".db") + ".json")

    private fun sidecarJson(file: File, rawBytes: Long, s: PackStats, summary: StoreSummary, options: PackOptions): JsonObject = buildJsonObject {
        put("file", file.name)
        s.header.replicationTime?.let { put("osm_timestamp", it.toString()) }
        s.header.replicationSequence?.let { put("osm_sequence", it) }
        (options.clip ?: s.header.bbox)?.let { b -> putJsonArray("bbox") { for (v in Manifest.corners(b)) add(v) } }
        put("raw_bytes", rawBytes)
        summary.schema?.let { put("schema", it.toIntOrNull() ?: 0) }
        putJsonArray("langs") { for (l in options.langs) add(l) }
        put("places", summary.places)
        put("trees", summary.trees)
        put("nodes", s.nodes)
        put("ways", s.ways)
        put("relations", s.relations)
        put("unlocated", s.unlocated)
        put("clipped", s.clipped)
        put("missing_nodes", s.missingNodes)
        put("seconds", s.millis / 1000.0)
        put("packer", VERSION)
    }
}

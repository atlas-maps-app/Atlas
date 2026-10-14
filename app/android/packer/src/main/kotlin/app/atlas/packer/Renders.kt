package app.atlas.packer

import app.atlas.geo.BBox
import app.atlas.render.data.DirectoryRegion
import app.atlas.render.data.PackSpec
import app.atlas.render.data.PackStats
import app.atlas.render.data.PackWriter
import app.atlas.render.data.RegionBuilder
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.File
import java.io.OutputStream
import java.util.zip.GZIPOutputStream

/**
 * The renders' own data as `.atlas` packs, so the app shows exactly what the Python renderer drew: every
 * `data/regions/<name>/` that has a `raw.json` (with its `places.py` entry, `dem*.json`, `wx.json`, `sea.json`, and a
 * park's ride profiles) and Amsterdam's `data/ams/raw.json`.
 */
object Renders {
    class Result(val id: String, val file: File, val stats: PackStats, val jsonBytes: Long, val gzipBytes: Long)

    /** The `places.py` table as JSON, by running the file with python3 (it is Python: tuples, comments). */
    fun places(repo: File): JsonObject {
        val script = "import json,runpy,sys; print(json.dumps(runpy.run_path(sys.argv[1])['PLACES']))"
        val p = ProcessBuilder("python3", "-c", script, File(repo, "data/regions/places.py").path).redirectErrorStream(false).start()
        val out = p.inputStream.readBytes().toString(Charsets.UTF_8)
        val err = p.errorStream.readBytes().toString(Charsets.UTF_8)
        if (p.waitFor() != 0) error("python3 could not read data/regions/places.py: $err")
        return Json.parseToJsonElement(out) as JsonObject
    }

    /** Amsterdam, the living map's Westerkerk: the one place with no `places.py` entry (amsterdam.py holds its origin). */
    private val amsterdam = Json.parseToJsonElement(
        """{"bbox":[52.371,4.878,52.378,4.891],"origin":[52.3746,4.884],"title":"Amsterdam, the Westerkerk","tz":"Europe/Amsterdam","tradition":"nl_amsterdam"}""",
    ) as JsonObject

    fun build(repo: File, out: File, only: Set<String>? = null, log: (String) -> Unit = {}): List<Result> {
        out.mkdirs()
        val places = places(repo)
        val results = ArrayList<Result>()
        val dir = File(repo, "data/regions")
        val names = dir.listFiles { f -> File(f, "raw.json").isFile }.orEmpty().map { it.name }.sorted()
        for (name in names) {
            if (only != null && name !in only) continue
            val entry = places[name] as? JsonObject
            if (entry == null) {
                log("$name: no entry in places.py, skipped")
                continue
            }
            val region = DirectoryRegion(File(dir, name), name, entry)
            val aux = LinkedHashMap<String, JsonElement>()
            for (a in region.auxNames) region.aux(a)?.let { aux[a] = it }
            // a theme park's ride profiles, which regions.py reads from data/park/<park>/pf
            val park = (entry["park"] as? JsonPrimitive)?.content
            val profiles = park?.let { File(repo, "data/park/$it/pf").listFiles { f -> f.name.endsWith(".json") }.orEmpty().sortedBy { f -> f.name } }.orEmpty()
            for (f in profiles) aux["park/pf/${f.nameWithoutExtension}"] = Json.parseToJsonElement(f.readText())
            val dems = LinkedHashMap<String, app.atlas.render.data.Dem>()
            for (d in region.demNames) region.dem(d)?.let { dems[d] = it }
            val files = listOf(File(dir, "$name/raw.json")) + region.demNames.map { File(dir, "$name/$it.json") } + region.auxNames.map { File(dir, "$name/$it.json") } + profiles
            results += pack(name, region.config, region.elements, listOf("data/regions/$name/raw.json: Overpass API, fetch/regions.py"), dems, aux, files, File(out, "$name.atlas"), log)
        }
        if (only == null || "amsterdam" in only) {
            val raw = File(repo, "data/ams/raw.json")
            if (raw.isFile) {
                val wx = File(repo, "data/wx/ams.json")
                val obs = File(repo, "data/wx/ams_observed.json")
                val aux = LinkedHashMap<String, File>()
                if (wx.isFile) aux["wx"] = wx
                if (obs.isFile) aux["wx_observed"] = obs
                val region = DirectoryRegion(File(repo, "data/ams"), "amsterdam", amsterdam, raw, emptyMap(), aux)
                val docs = LinkedHashMap<String, JsonElement>()
                for (a in region.auxNames) region.aux(a)?.let { docs[a] = it }
                results += pack("amsterdam", region.config, region.elements, listOf("data/ams/raw.json: Overpass API"), emptyMap(), docs, listOf(raw) + aux.values, File(out, "amsterdam.atlas"), log)
            }
        }
        return results
    }

    private fun pack(
        id: String,
        config: JsonObject,
        elements: app.atlas.render.data.ElementSource,
        sources: List<String>,
        dems: Map<String, app.atlas.render.data.Dem>,
        aux: Map<String, JsonElement>,
        inputs: List<File>,
        file: File,
        log: (String) -> Unit,
    ): Result {
        val spec: PackSpec = RegionBuilder.spec(id, config, elements, sources + "OpenStreetMap contributors (ODbL 1.0)", dems, aux)
        val stats = PackWriter.write(spec, file)
        val json = inputs.sumOf { it.length() }
        val gz = inputs.sumOf { gzipSize(it) }
        log("%-14s %7d elements %2d chunks  json %6.1f MB  gzip %5.1f MB  pack %5.2f MB (%4.1f%% of json, %4.1f%% of gzip)  %d ms".format(
            id, stats.elements, stats.chunks, json / 1e6, gz / 1e6, stats.bytes / 1e6, 100.0 * stats.bytes / json, 100.0 * stats.bytes / gz, stats.millis,
        ))
        return Result(id, file, stats, json, gz)
    }

    private class Counter : OutputStream() {
        var n = 0L
        override fun write(b: Int) { n++ }
        override fun write(b: ByteArray, off: Int, len: Int) { n += len }
    }

    private fun gzipSize(f: File): Long {
        val c = Counter()
        GZIPOutputStream(c).use { g -> f.inputStream().use { it.copyTo(g, 1 shl 16) } }
        return c.n
    }

    /** The box of a `places.py` entry, for reports. */
    fun boxOf(entry: JsonObject): BBox? = app.atlas.render.data.RegionConfig.bboxOf(entry)
}

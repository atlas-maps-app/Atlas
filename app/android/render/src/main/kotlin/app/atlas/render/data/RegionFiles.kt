package app.atlas.render.data

import kotlinx.serialization.json.JsonPrimitive
import java.io.BufferedWriter
import java.io.File
import java.io.OutputStreamWriter

/**
 * A region's data written back as the files the engine reads (`render/regions.py` reads `data/regions/<name>/`):
 * `raw.json` (the extract, element by element as Overpass wrote it), `region.json` (the configuration), `wx.json`,
 * `sea.json`, `dem.json` / `dem5.json`, and a park's ride profiles under `<park root>/<park>/pf/`. The pack is lossless,
 * so the engine reads exactly what it read from the renders' own folders.
 *
 * A region without a weather record (one built on the phone from Overpass) gets [fallbackWx]: the engine's fixed moments
 * need hours on 30 September and 1 October 2026 to exist; the live moment brings its own forecast.
 */
object RegionFiles {
    /** What [unpack] wrote: the region's folder and the park folder (or null). */
    class Unpacked(val dir: File, val parkDir: File?, val bytes: Long, val millis: Long)

    /** The marker file that says a folder is complete, holding the pack's identity. */
    private const val DONE = ".unpacked"

    /**
     * Writes [region] into `[root]/<id>/` (and its park profiles into `[parkRoot]/<park>/pf/`), unless the folder already
     * holds [stamp] (the pack's identity: its size and date, or a hash). Writes go to a temporary folder first, so a
     * folder is never half written.
     */
    fun unpack(region: RegionData, root: File, parkRoot: File, stamp: String, fallbackWx: (() -> String)? = null): Unpacked {
        val t0 = System.nanoTime()
        val dir = File(root, region.id)
        val done = File(dir, DONE)
        val park = (region.config["park"] as? JsonPrimitive)?.content
        val parkDir = park?.let { File(File(parkRoot, it), "pf") }
        if (done.isFile && done.readText() == stamp) return Unpacked(dir, parkDir, sizeOf(dir), (System.nanoTime() - t0) / 1_000_000)
        root.mkdirs()
        val tmp = File(root, ".${region.id}.tmp")
        tmp.deleteRecursively()
        tmp.mkdirs()
        writeRaw(region, File(tmp, "raw.json"))
        File(tmp, "region.json").writeText(region.config.toString())
        val auxNames = (region as? PackReader)?.auxNames ?: (region as? DirectoryRegion)?.auxNames ?: listOf("wx", "sea")
        var hasWx = false
        for (name in auxNames) {
            val doc = region.aux(name) ?: continue
            when {
                name == "wx" || name == "sea" -> {
                    File(tmp, "$name.json").writeText(doc.toString())
                    if (name == "wx") hasWx = true
                }
                name.startsWith("park/pf/") && parkDir != null -> {
                    parkDir.mkdirs()
                    File(parkDir, name.removePrefix("park/pf/") + ".json").writeText(doc.toString())
                }
            }
        }
        if (!hasWx && fallbackWx != null) File(tmp, "wx.json").writeText(fallbackWx())
        val demNames = (region as? PackReader)?.demNames ?: (region as? DirectoryRegion)?.demNames ?: listOf("dem", "dem5")
        for (name in demNames) region.dem(name)?.let { writeDem(it, File(tmp, "$name.json")) }
        File(tmp, DONE).writeText(stamp)
        dir.deleteRecursively()
        if (!tmp.renameTo(dir)) {
            tmp.copyRecursively(dir, overwrite = true)
            tmp.deleteRecursively()
        }
        return Unpacked(dir, parkDir, sizeOf(dir), (System.nanoTime() - t0) / 1_000_000)
    }

    /** The extract as Overpass JSON: its header fields, then `elements`, one element per line. */
    fun writeRaw(region: RegionData, file: File) {
        val src = region.elements
        BufferedWriter(OutputStreamWriter(file.outputStream(), Charsets.UTF_8), 1 shl 16).use { w ->
            w.write("{")
            for ((k, v) in src.header) {
                w.write(jsonString(k))
                w.write(":")
                w.write(v.toString())
                w.write(",")
            }
            w.write("\"elements\":[\n")
            val sb = StringBuilder(4096)
            var first = true
            for (e in src.elements()) {
                sb.setLength(0)
                if (!first) sb.append(",\n")
                first = false
                JsonText.element(sb, e)
                w.append(sb)
            }
            w.write("\n]}\n")
        }
    }

    /** A height grid as `dem.json` holds it: its fields, then `z` rows (null where the survey has no ground). */
    fun writeDem(dem: Dem, file: File) {
        BufferedWriter(OutputStreamWriter(file.outputStream(), Charsets.UTF_8), 1 shl 16).use { w ->
            w.write("{")
            for ((k, v) in dem.meta) {
                w.write(jsonString(k))
                w.write(":")
                w.write(v.toString())
                w.write(",")
            }
            w.write("\"z\":[")
            val sb = StringBuilder(16 * dem.width)
            for (r in 0 until dem.height) {
                sb.setLength(0)
                if (r > 0) sb.append(',')
                sb.append('[')
                for (c in 0 until dem.width) {
                    if (c > 0) sb.append(',')
                    val v = dem.at(r, c)
                    if (v.isNaN()) sb.append("null") else JsonText.number(sb, v)
                }
                sb.append(']')
                w.append(sb)
            }
            w.write("]}")
        }
    }

    private fun jsonString(s: String): String = StringBuilder().also { JsonText.string(it, s) }.toString()

    private fun sizeOf(dir: File): Long = dir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
}

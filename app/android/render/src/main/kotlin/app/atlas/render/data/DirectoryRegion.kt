package app.atlas.render.data

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import java.io.File

/**
 * A region as the Python renderer reads it from `data/regions/<name>/`: `raw.json`, `region.json`, `dem.json` and
 * `dem5.json`, `wx.json`, `sea.json`. [places] is the region's `places.py` entry as JSON (views included); its fields
 * win over `region.json`'s. [rawFile], [demFiles] and [auxFiles] override where the data is, for a region that lives
 * elsewhere (Amsterdam's extract is `data/ams/raw.json`, its weather `data/wx/ams.json`).
 */
class DirectoryRegion(
    val dir: File,
    override val id: String = dir.name,
    places: JsonObject? = null,
    private val rawFile: File = File(dir, "raw.json"),
    private val demFiles: Map<String, File> = listOf("dem", "dem5").associateWith { File(dir, "$it.json") }.filterValues { it.isFile },
    private val auxFiles: Map<String, File> = listOf("wx", "sea").associateWith { File(dir, "$it.json") }.filterValues { it.isFile },
) : RegionData {
    override val config: JsonObject = run {
        val m = LinkedHashMap<String, JsonElement>()
        val own = File(dir, "region.json")
        if (own.isFile) (Json.parseToJsonElement(own.readText()) as JsonObject).forEach { (k, v) -> m[k] = v }
        places?.forEach { (k, v) -> m[k] = v }
        JsonObject(m)
    }

    override val elements: ElementSource = OverpassJsonFile(rawFile)

    val demNames: List<String> get() = demFiles.keys.toList()
    val auxNames: List<String> get() = auxFiles.keys.toList()

    override fun dem(name: String): Dem? = demFiles[name]?.let { Dem.read(it) }

    override fun aux(name: String): JsonElement? = auxFiles[name]?.let { Json.parseToJsonElement(it.readText()) }
}

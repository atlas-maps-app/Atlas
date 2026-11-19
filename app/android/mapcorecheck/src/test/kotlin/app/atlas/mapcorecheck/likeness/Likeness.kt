package app.atlas.mapcorecheck.likeness

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import java.io.File

/**
 * The engine's references' harness (ENGINE.md, Checks): where the reference files are (`likeness/engine.json`,
 * `world.json`, `world-refs.json`, `world-keep.json` and the globe check's board), where a run's report goes, and the
 * platform the core's host build draws on; each frame is held by the SHA-256 of its pixels on its platform.
 */
object Likeness {
    val repo: File get() = File(System.getProperty("atlas.repo") ?: "../..").canonicalFile

    /** The reference files. */
    val cases: File get() = File(repo, "app/android/mapcorecheck/src/test/resources/likeness")

    /** Where the report goes. */
    val report: File get() = System.getProperty("atlas.likeness.report")?.let { File(it) } ?: File(repo, "cache/likeness/report")

    /**
     * The platform the core draws on: `-Datlas.likeness.platform` (CI: lavapipe-x86_64, lavapipe-arm64), else
     * this machine's lavapipe.
     */
    val platform: String get() = System.getProperty("atlas.likeness.platform")?.takeIf { it.isNotBlank() } ?: when {
        System.getProperty("os.name").lowercase().contains("mac") -> "lavapipe-macos"
        System.getProperty("os.arch") in setOf("aarch64", "arm64") -> "lavapipe-arm64"
        else -> "lavapipe-x86_64"
    }

    fun json(file: File): JsonObject = Json.parseToJsonElement(file.readText()).jsonObject

    /** [v] as the reference files are written: an object or array on one line where it is short and flat. */
    fun format(v: JsonElement, ind: Int = 0): String {
        val pad = "  ".repeat(ind)
        return when (v) {
            is JsonObject -> {
                if (v.isEmpty()) "{}"
                else if (v.values.none { it is JsonObject || it is JsonArray } && v.toString().length < 110) compact(v)
                else "{\n" + v.entries.joinToString(",\n") { (k, x) -> "$pad  ${JsonPrimitive(k)}: ${format(x, ind + 1)}" } + "\n$pad}"
            }
            is JsonArray -> if (v.none { it is JsonObject || it is JsonArray }) compact(v) else "[\n" + v.joinToString(",\n") { "$pad  ${format(it, ind + 1)}" } + "\n$pad]"
            else -> v.toString()
        }
    }

    /** One line, with Python's `json.dumps` spacing (", " and ": "). */
    private fun compact(v: JsonElement): String = when (v) {
        is JsonObject -> "{" + v.entries.joinToString(", ") { (k, x) -> "${JsonPrimitive(k)}: ${compact(x)}" } + "}"
        is JsonArray -> "[" + v.joinToString(", ") { compact(it) } + "]"
        else -> v.toString()
    }
}

/** JSON helpers. */
fun JsonObject.str(k: String): String = (this[k] as? JsonPrimitive)?.content ?: throw IllegalArgumentException("missing '$k'")
fun JsonObject.strOrNull(k: String): String? = (this[k] as? JsonPrimitive)?.takeIf { it.isString }?.content
fun JsonObject.int(k: String): Int = (this[k] as JsonPrimitive).content.toInt()
fun JsonObject.dbl(k: String): Double = (this[k] as JsonPrimitive).doubleOrNull ?: throw IllegalArgumentException("'$k' is not a number")

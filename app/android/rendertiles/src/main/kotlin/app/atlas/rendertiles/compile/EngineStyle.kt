package app.atlas.rendertiles.compile

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * The engine's style file (`app/android/mapcore/style/engine.jsonc`, in this module's resources at [RESOURCE]): the
 * colours and widths the core draws with, as tables [Style] writes into every pack's `style` section (ENGINE.md, design
 * point 3). JSON with comments; its tables in the file's order, each a typed value as the section carries it: an
 * object a map, an array a list, a number without a fraction or an exponent an Int (a Long past an Int's range), any
 * other number a Double, as Python's json reads them.
 */
object EngineStyle {
    const val RESOURCE = "/style/engine.jsonc"

    /** The file's tables. */
    val tables: Map<String, Any?> by lazy {
        val text = (EngineStyle::class.java.getResourceAsStream(RESOURCE) ?: throw java.io.FileNotFoundException(RESOURCE)).use { it.readBytes().toString(Charsets.UTF_8) }
        parse(text)
    }

    @OptIn(ExperimentalSerializationApi::class)
    private val json = Json { allowComments = true }

    /** The tables of a style file's [text]: a JSON object (with comments) of tables. */
    fun parse(text: String): Map<String, Any?> {
        val root = json.parseToJsonElement(text) as? JsonObject ?: throw IllegalArgumentException("a style file is an object of tables")
        @Suppress("UNCHECKED_CAST")
        return plain(root) as Map<String, Any?>
    }

    private fun plain(e: kotlinx.serialization.json.JsonElement): Any? = when (e) {
        is JsonNull -> null
        is JsonObject -> LinkedHashMap<String, Any?>().also { m -> for ((k, v) in e) m[k] = plain(v) }
        is JsonArray -> e.map { plain(it) }
        is JsonPrimitive -> when {
            e.isString -> e.content
            e.content == "true" -> true
            e.content == "false" -> false
            e.content.any { it == '.' || it == 'e' || it == 'E' } -> e.content.toDouble()
            else -> e.content.toLong().let { if (it in Int.MIN_VALUE..Int.MAX_VALUE) it.toInt() else it }
        }
    }
}

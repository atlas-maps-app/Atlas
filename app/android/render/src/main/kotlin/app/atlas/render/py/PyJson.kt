package app.atlas.render.py

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.File
import java.math.BigInteger

/** `json.load`: JSON as the values Python's json module makes (int or float, str, bool, None, list, ordered dict). */
object PyJson {
    private val json = Json { isLenient = false }

    /** `json.load(open(file))`. */
    fun load(file: File): Any? = loads(file.readText(Charsets.UTF_8))

    /** `json.loads(text)`. */
    fun loads(text: String): Any? = py(json.parseToJsonElement(text))

    /** One JSON value as the Python one: ints stay Int (or Long, or BigInteger), numbers with '.', 'e' or 'E' are floats. */
    fun py(e: JsonElement): Any? = when (e) {
        is JsonNull -> null
        is JsonPrimitive -> when {
            e.isString -> e.content
            e.content == "true" -> true
            e.content == "false" -> false
            e.content.any { it == '.' || it == 'e' || it == 'E' } -> e.content.toDouble()
            else -> e.content.toLongOrNull()?.let { if (it in Int.MIN_VALUE..Int.MAX_VALUE) it.toInt() else it } ?: BigInteger(e.content)
        }
        is JsonArray -> e.mapTo(ArrayList(e.size)) { py(it) }
        is JsonObject -> LinkedHashMap<String, Any?>(e.size * 2).also { m -> for ((k, v) in e) m[k] = py(v) }
    }
}

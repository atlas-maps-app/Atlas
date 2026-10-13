package app.atlas.places

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** The bundled data under `atlas/places/`, read from the classpath on first use. */
internal object Resources {
    private const val ROOT = "atlas/places/"

    fun text(name: String): String? {
        val stream = Resources::class.java.classLoader?.getResourceAsStream(ROOT + name) ?: return null
        return stream.use { it.readBytes().toString(Charsets.UTF_8) }
    }

    fun json(name: String): JsonElement =
        Json.parseToJsonElement(text(name) ?: error("missing resource $ROOT$name"))

    fun jsonOrNull(name: String): JsonElement? = text(name)?.let { Json.parseToJsonElement(it) }
}

internal val JsonElement.str: String? get() = (this as? JsonPrimitive)?.takeIf { it !is JsonNull && it.isString }?.content
internal val JsonElement.num: Double? get() = (this as? JsonPrimitive)?.takeIf { it !is JsonNull && !it.isString }?.content?.toDoubleOrNull()
internal val JsonElement.obj: JsonObject? get() = this as? JsonObject
internal val JsonElement.arr: JsonArray? get() = this as? JsonArray

internal fun JsonElement?.stringMap(): Map<String, String> =
    (this as? JsonObject)?.entries?.associate { (k, v) -> k to (v.str ?: "") } ?: emptyMap()

internal fun JsonElement?.stringList(): List<String> = (this as? JsonArray)?.mapNotNull { it.str } ?: emptyList()

/** Parses "#RRGGBB" or "RRGGBB" into an opaque ARGB colour. */
internal fun parseColour(hex: String?): Int? {
    val h = hex?.removePrefix("#") ?: return null
    if (h.length != 6) return null
    return h.toLongOrNull(16)?.let { (0xFF000000L or it).toInt() }
}

/** The languages Atlas bundles, and how other language tags fall back onto them. */
internal object Langs {
    val bundled = listOf("en", "nl", "de", "fr", "es", "it", "pt", "pl", "sv", "da", "nb", "fi", "cs", "ja", "zh-CN", "ru", "ar", "tr")

    /** "nl-BE" -> "nl", "pt-BR" -> "pt", "zh-Hans" -> "zh-CN", "no" -> "nb"; anything unknown -> "en". */
    fun resolve(lang: String?): String {
        if (lang.isNullOrBlank()) return "en"
        val l = lang.replace('_', '-')
        if (l in bundled) return l
        val base = l.substringBefore('-').lowercase()
        return when (base) {
            "zh" -> "zh-CN"
            "no", "nn", "nb" -> "nb"
            else -> if (base in bundled) base else "en"
        }
    }
}

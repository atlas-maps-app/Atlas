package app.atlas.search

import app.atlas.places.Categories
import app.atlas.places.Category
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import java.security.MessageDigest

/**
 * The category rules a store files its places under: [categoryOf] gives a place its category from its tags, at import,
 * and the store records the rules' [fingerprint] in its `filed` metadata. A store opened under other rules than it
 * records is filed anew ([StoreRefile]): the rules changed with this version of Atlas.
 */
internal class Filing(val fingerprint: String, private val categories: (Map<String, String>) -> Category?) {
    /** The category of a place with [tags]: the first leaf whose rule they pass, none for what is not a place to go. */
    fun categoryOf(tags: Map<String, String>): Category? = categories(tags)

    companion object {
        /** This Atlas's rules: `categories.json`, as [Categories] reads it. */
        val current: Filing by lazy { Filing(fingerprint(bundled()), Categories::of) }

        private const val RULES = "atlas/places/categories.json"

        private fun bundled(): String = Filing::class.java.classLoader?.getResourceAsStream(RULES)?.use { it.readBytes().toString(Charsets.UTF_8) }
            ?: error("missing resource $RULES")

        /**
         * The fingerprint of what [json] (a `categories.json`) files a place under: its `notAPlace`, then each leaf's id,
         * rule, symbol and names in rule order (the first rule a place's tags pass files it there, the store keeps the
         * leaf's symbol where the place's preset has none, and its typo vocabulary holds the names of the leaves it has
         * places in). Colours, groups and families are read as they are shown, not stored, so a change to them files
         * nothing anew. 16 hex digits of a SHA-256.
         */
        fun fingerprint(json: String): String {
            val root = Json.parseToJsonElement(json).jsonObject
            val text = StringBuilder(text(root["notAPlace"]))
            for (e in root["categories"]?.jsonArray.orEmpty()) {
                val o = e as? JsonObject ?: continue
                if ("group" in o || "shelf" in o) continue
                text.append('\n').append(text(o["id"])).append('\t').append(text(o["rule"])).append('\t').append(text(o["symbol"]))
                    .append('\t').append(o["names"]?.toString() ?: "")
            }
            val hash = MessageDigest.getInstance("SHA-256").digest(text.toString().toByteArray(Charsets.UTF_8))
            return hash.take(8).joinToString("") { String.format(java.util.Locale.ROOT, "%02x", it) }
        }

        private fun text(e: JsonElement?): String = (e as? JsonPrimitive)?.takeIf { it.isString }?.content ?: ""
    }
}

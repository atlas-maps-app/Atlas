package app.atlas.providers

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull

/** The JSON settings every provider uses: unknown keys are ignored, so new fields upstream never break Atlas. */
internal val ProviderJson = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
    coerceInputValues = true
    isLenient = true
}

// Small, forgiving accessors over JSON trees: providers disagree on whether numbers arrive as numbers or strings.

internal fun JsonElement?.asObject(): JsonObject? = this as? JsonObject
internal fun JsonElement?.asArray(): JsonArray? = this as? JsonArray

internal fun JsonElement?.asString(): String? = (this as? JsonPrimitive)?.takeIf { it !is JsonNull }?.contentOrNull

internal fun JsonElement?.asDouble(): Double? = (this as? JsonPrimitive)?.takeIf { it !is JsonNull }?.let { it.doubleOrNull ?: it.contentOrNull?.trim()?.toDoubleOrNull() }

internal fun JsonElement?.asLong(): Long? = (this as? JsonPrimitive)?.takeIf { it !is JsonNull }?.let { it.longOrNull ?: it.doubleOrNull?.toLong() }

internal fun JsonElement?.asBoolean(): Boolean? = (this as? JsonPrimitive)?.takeIf { it !is JsonNull }?.booleanOrNull

internal fun JsonObject.str(key: String): String? = this[key].asString()?.takeIf { it.isNotEmpty() }
internal fun JsonObject.dbl(key: String): Double? = this[key].asDouble()
internal fun JsonObject.int(key: String): Int? = this[key].asLong()?.toInt()
internal fun JsonObject.long(key: String): Long? = this[key].asLong()
internal fun JsonObject.bool(key: String): Boolean? = this[key].asBoolean()
internal fun JsonObject.obj(key: String): JsonObject? = this[key].asObject()
internal fun JsonObject.arr(key: String): JsonArray = this[key].asArray() ?: JsonArray(emptyList())

/** Formats a number for a tag value: whole numbers without ".0", others with up to three decimals. */
internal fun tagNumber(v: Double): String =
    if (v == Math.rint(v) && kotlin.math.abs(v) < 1e15) v.toLong().toString()
    else String.format(java.util.Locale.ROOT, "%.3f", v).trimEnd('0').trimEnd('.')

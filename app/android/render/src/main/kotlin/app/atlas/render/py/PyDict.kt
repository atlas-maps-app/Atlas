package app.atlas.render.py

/** A Python dict with str keys: insertion-ordered, values of any Python-like type. */
typealias Dict = MutableMap<String, Any?>

/** `dict(k=v, …)`, keeping the order given: a [CompactDict] of exactly that size. */
fun dictOf(vararg pairs: Pair<String, Any?>): Dict {
    val d = CompactDict<String, Any?>(pairs.size)
    for ((k, v) in pairs) d[k] = v
    return d
}

/** `{}`: an empty dict, with room for [capacity] keys before it grows. */
fun emptyDict(capacity: Int = 0): Dict = CompactDict(capacity)

/** `dict(d)`: a shallow, ordered copy. */
fun Map<String, Any?>.copyDict(): Dict = CompactDict(this)

/** Python truthiness: None, False, 0, 0.0, "" and empty containers are false. */
fun truthy(v: Any?): Boolean = when (v) {
    null -> false
    is Boolean -> v
    is Int -> v != 0
    is Long -> v != 0L
    is Double -> v != 0.0
    is Float -> v != 0f
    is Number -> v.toDouble() != 0.0
    is String -> v.isNotEmpty()
    is Collection<*> -> v.isNotEmpty()
    is Map<*, *> -> v.isNotEmpty()
    is Array<*> -> v.isNotEmpty()
    is DoubleArray -> v.isNotEmpty()
    is IntArray -> v.isNotEmpty()
    else -> true
}

/** `d[k]` as a float. */
fun Map<String, Any?>.num(k: String): Double =
    (this[k] as? Number ?: throw NoSuchElementException("missing number '$k'")).toDouble()

/** `d.get(k, default)` as a float. */
fun Map<String, Any?>.num(k: String, default: Double): Double = (this[k] as? Number)?.toDouble() ?: default

/** `d.get(k)` as a float, or null. */
fun Map<String, Any?>.numOrNull(k: String): Double? = (this[k] as? Number)?.toDouble()

/** `d[k]` as an int. */
fun Map<String, Any?>.int(k: String): Int = (this[k] as? Number ?: throw NoSuchElementException("missing int '$k'")).toInt()

/** `d[k]` as a str. */
fun Map<String, Any?>.str(k: String): String = this[k] as? String ?: throw NoSuchElementException("missing str '$k'")

/** `d.get(k, default)` as a str. */
fun Map<String, Any?>.str(k: String, default: String): String = this[k] as? String ?: default

/** `d.get(k)` as a str, or null. */
fun Map<String, Any?>.strOrNull(k: String): String? = this[k] as? String

/** The truthiness of `d.get(k)`. */
fun Map<String, Any?>.flag(k: String): Boolean = truthy(this[k])

/** `d.get(k, [])` as a list. */
@Suppress("UNCHECKED_CAST")
fun Map<String, Any?>.list(k: String): List<Any?> = this[k] as? List<Any?> ?: emptyList()

/** `d.get(k, {})` as a dict. */
@Suppress("UNCHECKED_CAST")
fun Map<String, Any?>.dict(k: String): Map<String, Any?> = this[k] as? Map<String, Any?> ?: emptyMap()

/** A Python number (int or float) as a Kotlin Double. */
fun pyNum(v: Any?): Double = (v as? Number ?: throw IllegalArgumentException("not a number: $v")).toDouble()

/** A Python list of str. */
@Suppress("UNCHECKED_CAST")
fun pyStrList(v: Any?): List<String> = v as List<String>

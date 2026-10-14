package app.atlas.render.data

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.File

/**
 * An elevation grid as `dem.json` holds it: [meta] is every field but `z` (`crs`, `cell`, `W`, `H`, and the corners
 * `s w n e` of a lat/lon grid or `x0 x1 y0 y1` of a Dutch RD grid, `source`), [z] the heights in metres, [height] rows
 * of [width] values, north row first, NaN where the survey has no ground (`null` in the JSON).
 */
class Dem(val meta: JsonObject, val width: Int, val height: Int, val z: DoubleArray) {
    init {
        require(z.size == width * height) { "a ${width}x$height grid needs ${width * height} values, not ${z.size}" }
    }

    /** `d['crs']`, 'rd' when absent, as terrain.py reads it. */
    val crs: String get() = (meta["crs"] as? JsonPrimitive)?.content ?: "rd"

    fun number(key: String): Double? = (meta[key] as? JsonPrimitive)?.content?.toDoubleOrNull()

    /** `z[row][col]`; NaN for a cell without ground. */
    fun at(row: Int, col: Int): Double = z[row * width + col]

    override fun equals(other: Any?): Boolean = other is Dem && meta == other.meta && width == other.width && height == other.height && z.contentEquals(other.z)

    override fun hashCode(): Int = meta.hashCode() * 31 + z.contentHashCode()

    companion object {
        /** Reads a `dem.json` / `dem5.json` file. */
        fun read(file: File): Dem = file.inputStream().buffered(1 shl 16).use { read(JsonPull(java.io.InputStreamReader(it, Charsets.UTF_8))) }

        fun read(p: JsonPull): Dem {
            val meta = LinkedHashMap<String, JsonElement>()
            var rows = ArrayList<DoubleArray>()
            p.beginObject()
            while (p.hasNext()) {
                val name = p.nextName()
                if (name != "z") {
                    meta[name] = p.nextJson()
                    continue
                }
                p.beginArray()
                while (p.hasNext()) {
                    var row = DoubleArray(256)
                    var n = 0
                    p.beginArray()
                    while (p.hasNext()) {
                        if (n == row.size) row = row.copyOf(n * 2)
                        if (p.peek() == JsonToken.NULL) {
                            p.nextNull()
                            row[n++] = Double.NaN
                        } else row[n++] = p.nextDouble()
                    }
                    p.endArray()
                    rows += row.copyOf(n)
                }
                p.endArray()
            }
            p.endObject()
            val h = rows.size
            val w = rows.firstOrNull()?.size ?: 0
            if (rows.any { it.size != w }) throw PackFormatException("a height grid with rows of different lengths")
            val z = DoubleArray(w * h)
            for ((i, r) in rows.withIndex()) System.arraycopy(r, 0, z, i * w, w)
            return Dem(JsonObject(meta), w, h, z)
        }
    }
}

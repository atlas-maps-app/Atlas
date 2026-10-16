package app.atlas.rendertiles

import app.atlas.rendertiles.model.Feature
import app.atlas.rendertiles.model.Geom
import app.atlas.rendertiles.model.GridPoint
import app.atlas.rendertiles.model.RenderTile
import java.security.MessageDigest

/**
 * A canonical text of a tile's records (plan-vector L0 (b)): what the core's reader must give back, line by line, so
 * the two can be compared by text or by hash. One line per cell that holds features, then one per feature in the tile's
 * order: its layer, cell and fields sorted by name, each `name=value` with every number exact (a double as its bits in
 * hex), a ring as its vertices `lat,lon` (and `/keep` codes), strings in JSON quoting.
 */
object CanonicalDump {
    fun of(t: RenderTile): String = buildString {
        append("tile ").append(t.level).append('/').append(t.x).append('/').append(t.y).append(" n=").append(t.cellsPerSide).append('\n')
        for ((i, c) in t.cells.withIndex()) {
            if (c.total == 0) continue
            append("cell ").append(i).append(" box=").append(c.box!!.joinToString(",")).append(" top=").append(c.maxHeightCm)
            for ((layer, k) in c.counts) if (k > 0) append(' ').append(layer.label).append('=').append(k)
            append('\n')
        }
        for ((layer, fs) in t.layers) for (f in fs) feature(this, f)
    }

    /** The SHA-256 (hex) of [of]'s UTF-8 text. */
    fun hash(t: RenderTile): String = MessageDigest.getInstance("SHA-256").digest(of(t).toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it.toInt() and 0xFF) }

    private fun feature(sb: StringBuilder, f: Feature) {
        sb.append(f.layer.label).append(" @").append(f.cell)
        for (k in f.fields.keys.sorted()) {
            sb.append(' ').append(k).append('=')
            value(sb, f.fields[k])
        }
        sb.append('\n')
    }

    private fun value(sb: StringBuilder, v: Any?) {
        when (v) {
            null -> sb.append("null")
            is Boolean -> sb.append(v)
            is Int, is Long -> sb.append(v)
            is Double -> sb.append("d").append(java.lang.Long.toHexString(v.toRawBits()))
            is String -> string(sb, v)
            is GridPoint -> sb.append('(').append(v.lat).append(',').append(v.lon).append(')')
            is Geom -> {
                sb.append('[')
                for (i in 0 until v.size) {
                    if (i > 0) sb.append(';')
                    sb.append(v.lat[i]).append(',').append(v.lon[i])
                    if (v.keep != null) sb.append('/').append(v.keep[i].toInt())
                }
                sb.append(']')
            }
            is List<*> -> {
                sb.append('<')
                for ((i, e) in v.withIndex()) {
                    if (i > 0) sb.append(',')
                    value(sb, e)
                }
                sb.append('>')
            }
            is Map<*, *> -> {
                sb.append('{')
                for ((i, e) in v.entries.withIndex()) {
                    if (i > 0) sb.append(',')
                    string(sb, e.key as String)
                    sb.append(':')
                    value(sb, e.value)
                }
                sb.append('}')
            }
            else -> throw IllegalArgumentException("no canonical text for ${v::class.qualifiedName}")
        }
    }

    private fun string(sb: StringBuilder, s: String) {
        sb.append('"')
        for (c in s) when {
            c == '"' -> sb.append("\\\"")
            c == '\\' -> sb.append("\\\\")
            c < ' ' -> sb.append("\\u%04x".format(c.code))
            else -> sb.append(c)
        }
        sb.append('"')
    }
}

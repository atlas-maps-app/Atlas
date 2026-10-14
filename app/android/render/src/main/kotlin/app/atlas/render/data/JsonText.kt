package app.atlas.render.data

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonUnquotedLiteral
import java.math.BigDecimal

/** Number literals that keep their written text. */
object JsonRaw {
    @OptIn(ExperimentalSerializationApi::class)
    fun number(text: String): JsonElement = JsonUnquotedLiteral(text)
}

/** Writes elements as Overpass JSON, the way Python's `json.dumps` with compact separators would. */
object JsonText {
    /** [fixed]: write every coordinate as the integer count of 10^-[fixed] degrees (a canonical form to compare against other readers). */
    fun element(out: StringBuilder, e: OsmElement, fixed: Int = 0) {
        out.append("{\"type\":\"").append(e.type.json).append("\",\"id\":").append(e.id)
        if (e.type == ElementType.NODE) {
            if (!e.lat.isNaN()) {
                out.append(",\"lat\":")
                num(out, fixed, e.lat)
                out.append(",\"lon\":")
                num(out, fixed, e.lon)
            }
        } else {
            e.bounds?.let { b ->
                out.append(",\"bounds\":{\"minlat\":")
                num(out, fixed, b.minLat)
                out.append(",\"minlon\":")
                num(out, fixed, b.minLon)
                out.append(",\"maxlat\":")
                num(out, fixed, b.maxLat)
                out.append(",\"maxlon\":")
                num(out, fixed, b.maxLon)
                out.append('}')
            }
            e.nodes?.let { ns ->
                out.append(",\"nodes\":[")
                for (i in ns.indices) {
                    if (i > 0) out.append(',')
                    out.append(ns[i])
                }
                out.append(']')
            }
            e.geometry?.let {
                out.append(",\"geometry\":")
                points(out, it, fixed)
            }
            e.members?.let { ms ->
                out.append(",\"members\":[")
                for ((i, m) in ms.withIndex()) {
                    if (i > 0) out.append(',')
                    out.append("{\"type\":\"").append(m.type.json).append("\",\"ref\":").append(m.ref).append(",\"role\":")
                    string(out, m.role)
                    if (m.hasPosition) {
                        out.append(",\"lat\":")
                        num(out, fixed, m.lat)
                        out.append(",\"lon\":")
                        num(out, fixed, m.lon)
                    }
                    m.geometry?.let {
                        out.append(",\"geometry\":")
                        points(out, it, fixed)
                    }
                    out.append('}')
                }
                out.append(']')
            }
        }
        e.tags?.let { t ->
            out.append(",\"tags\":{")
            var first = true
            for ((k, v) in t) {
                if (!first) out.append(',')
                first = false
                string(out, k)
                out.append(':')
                string(out, v)
            }
            out.append('}')
        }
        out.append('}')
    }

    private fun points(out: StringBuilder, p: Points, fixed: Int) {
        out.append('[')
        for (i in 0 until p.size) {
            if (i > 0) out.append(',')
            if (p.isNull(i)) out.append("null")
            else {
                out.append("{\"lat\":")
                num(out, fixed, p.lat[i])
                out.append(",\"lon\":")
                num(out, fixed, p.lon[i])
                out.append('}')
            }
        }
        out.append(']')
    }

    private fun num(out: StringBuilder, fixed: Int, v: Double) {
        if (fixed == 0) number(out, v) else out.append(Math.round(v * Math.pow(10.0, fixed.toDouble())))
    }

    /** A double as Python's `repr` writes it for ordinary magnitudes: the shortest digits that read back the same. */
    fun number(out: StringBuilder, v: Double) {
        val s = v.toString()
        if (s.indexOf('E') < 0) out.append(s) else out.append(BigDecimal(s).toPlainString().let { if (it.contains('.')) it else "$it.0" })
    }

    fun string(out: StringBuilder, s: String) {
        out.append('"')
        for (ch in s) {
            when {
                ch == '"' -> out.append("\\\"")
                ch == '\\' -> out.append("\\\\")
                ch == '\n' -> out.append("\\n")
                ch == '\r' -> out.append("\\r")
                ch == '\t' -> out.append("\\t")
                ch == '\b' -> out.append("\\b")
                ch == '\u000C' -> out.append("\\f")
                ch < ' ' || ch.code > 126 -> out.append("\\u").append(String.format("%04x", ch.code))
                else -> out.append(ch)
            }
        }
        out.append('"')
    }
}

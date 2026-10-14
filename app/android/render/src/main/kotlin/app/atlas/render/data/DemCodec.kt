package app.atlas.render.data

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

/**
 * A height grid in the pack: heights as integers at the finest decimal place they need (survey heights have one or two),
 * each coded against the last height before it, null cells as a zero token; or as raw doubles when no decimal scale
 * fits. The caller deflates the section.
 */
internal object DemCodec {
    private const val SCALED = 0
    private const val RAW = 1

    fun encode(dem: Dem, sink: ByteSink) {
        var p = -1
        for (digits in 0..6) {
            val f = POW10[digits]
            var ok = true
            for (v in dem.z) {
                if (v.isNaN()) continue
                val r = Math.round(v * f)
                if (r / f != v || Math.abs(v * f) > 4e15 || (v == 0.0 && 1.0 / v < 0)) {
                    ok = false
                    break
                }
            }
            if (ok) {
                p = digits
                break
            }
        }
        sink.byte(if (p >= 0) SCALED else RAW)
        sink.string(dem.meta.toString())
        sink.varInt(dem.width)
        sink.varInt(dem.height)
        if (p < 0) {
            for (v in dem.z) sink.f64(v)
            return
        }
        sink.byte(p)
        val f = POW10[p]
        var last = 0L
        for (v in dem.z) {
            if (v.isNaN()) {
                sink.byte(0)
                continue
            }
            val r = Math.round(v * f)
            sink.varLong(zig(r - last) + 1)
            last = r
        }
    }

    fun decode(src: ByteSource): Dem {
        val mode = src.byte()
        val meta = Json.parseToJsonElement(src.string()) as JsonObject
        val w = src.varInt()
        val h = src.varInt()
        val z = DoubleArray(w * h)
        if (mode == RAW) {
            for (i in z.indices) z[i] = src.f64()
            return Dem(meta, w, h, z)
        }
        val f = POW10[src.byte()]
        var last = 0L
        for (i in z.indices) {
            val t = src.varLong()
            if (t == 0L) {
                z[i] = Double.NaN
                continue
            }
            last += unzig(t - 1)
            z[i] = last / f
        }
        return Dem(meta, w, h, z)
    }

    private fun zig(v: Long): Long = (v shl 1) xor (v shr 63)
    private fun unzig(v: Long): Long = (v ushr 1) xor -(v and 1)
    private val POW10 = doubleArrayOf(1.0, 10.0, 100.0, 1e3, 1e4, 1e5, 1e6)
}

package app.atlas.engine

import app.atlas.tiles.Mvt
import app.atlas.tiles.VectorTile
import java.io.ByteArrayOutputStream

/** A small Mapbox Vector Tile writer for hand-made test tiles: layers of points, lines and polygons with properties. */
class MiniMvt {
    private val tile = ByteArrayOutputStream()

    /** Adds layer [name] built by [block]. */
    fun layer(name: String, extent: Int = 4096, block: Layer.() -> Unit): MiniMvt {
        val l = Layer().apply(block)
        val w = ByteArrayOutputStream()
        w.uint(15, 2); w.bytes(1, name.toByteArray())
        for (f in l.features) w.bytes(2, f)
        for (k in l.keys) w.bytes(3, k.toByteArray())
        for (v in l.values) w.bytes(4, value(v))
        w.uint(5, extent.toLong())
        tile.bytes(3, w.toByteArray())
        return this
    }

    fun build(): VectorTile = Mvt.decode(tile.toByteArray())

    class Layer {
        internal val keys = ArrayList<String>()
        internal val values = ArrayList<Any>()
        internal val features = ArrayList<ByteArray>()

        /** A polygon from rings of x, y pairs in tile units (outer clockwise on screen, holes the other way). */
        fun polygon(vararg rings: IntArray, props: Map<String, Any> = emptyMap(), id: Long? = null) = feature(3, paths(true, rings), props, id)

        fun line(vararg lines: IntArray, props: Map<String, Any> = emptyMap(), id: Long? = null) = feature(2, paths(false, lines), props, id)

        fun point(x: Int, y: Int, props: Map<String, Any> = emptyMap(), id: Long? = null) =
            feature(1, intArrayOf(cmd(1, 1), zz(x), zz(y)), props, id)

        private fun feature(type: Int, geometry: IntArray, props: Map<String, Any>, id: Long?) {
            val tags = ArrayList<Int>()
            for ((k, v) in props) {
                var ki = keys.indexOf(k)
                if (ki < 0) { keys += k; ki = keys.size - 1 }
                var vi = values.indexOf(v)
                if (vi < 0) { values += v; vi = values.size - 1 }
                tags += ki; tags += vi
            }
            val w = ByteArrayOutputStream()
            if (id != null) w.uint(1, id)
            if (tags.isNotEmpty()) w.packed(2, tags.toIntArray())
            w.uint(3, type.toLong())
            w.packed(4, geometry)
            features += w.toByteArray()
        }

        private fun paths(close: Boolean, paths: Array<out IntArray>): IntArray {
            val out = ArrayList<Int>()
            var cx = 0; var cy = 0
            for (p in paths) {
                out += cmd(1, 1); out += zz(p[0] - cx); out += zz(p[1] - cy); cx = p[0]; cy = p[1]
                out += cmd(2, p.size / 2 - 1)
                for (i in 2 until p.size step 2) { out += zz(p[i] - cx); out += zz(p[i + 1] - cy); cx = p[i]; cy = p[i + 1] }
                if (close) out += cmd(7, 1)
            }
            return out.toIntArray()
        }

        private fun cmd(id: Int, count: Int) = (count shl 3) or id
        private fun zz(v: Int) = (v shl 1) xor (v shr 31)
    }

    private fun value(v: Any): ByteArray = ByteArrayOutputStream().also { w ->
        when (v) {
            is String -> w.bytes(1, v.toByteArray())
            is Double -> { w.tag(3, 1); for (i in 0 until 8) w.write((java.lang.Double.doubleToLongBits(v) shr (8 * i)).toInt() and 0xFF) }
            is Int -> w.uint(4, v.toLong())
            is Long -> w.uint(5, v)
            is Boolean -> w.uint(7, if (v) 1 else 0)
            else -> error("unsupported value $v")
        }
    }.toByteArray()

    private companion object {
        fun ByteArrayOutputStream.varint(v: Long) {
            var x = v
            while (x and 0x7fL.inv() != 0L) { write(((x and 0x7f) or 0x80).toInt()); x = x ushr 7 }
            write(x.toInt())
        }

        fun ByteArrayOutputStream.tag(field: Int, wire: Int) = varint(((field shl 3) or wire).toLong())
        fun ByteArrayOutputStream.uint(field: Int, v: Long) { tag(field, 0); varint(v) }
        fun ByteArrayOutputStream.bytes(field: Int, b: ByteArray) { tag(field, 2); varint(b.size.toLong()); write(b) }
        fun ByteArrayOutputStream.packed(field: Int, values: IntArray) {
            val p = ByteArrayOutputStream()
            for (v in values) p.varint(v.toLong() and 0xffffffffL)
            bytes(field, p.toByteArray())
        }
    }
}
